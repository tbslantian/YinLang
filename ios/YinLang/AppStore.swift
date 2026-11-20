import Foundation
import SwiftUI
import UniformTypeIdentifiers
import AVFoundation

@MainActor
final class AppStore: ObservableObject {
    @Published var plugins: [Plugin] = (Disk.read("plugins.json", fallback: []) as [Plugin]).map(PluginArchive.restoreMusicMetadata) { didSet { Disk.save(plugins, "plugins.json"); resolutionRevision += 1 } }
    private(set) var resolutionRevision = 0
    @Published var liked: [MediaItem] = Disk.read("liked.json", fallback: []) { didSet { Disk.save(liked, "liked.json") } }
    @Published var followed: [MediaItem] = Disk.read("followed.json", fallback: []) { didSet { Disk.save(followed, "followed.json") } }
    @Published var bookshelf: [MediaItem] = Disk.read("bookshelf.json", fallback: []) { didSet { Disk.save(bookshelf, "bookshelf.json") } }
    @Published var downloads: [DownloadedItem] = Disk.read("downloads.json", fallback: []) { didSet { Disk.save(downloads, "downloads.json") } }
    @Published var message: String?
    @Published var activity = ""
    @Published var connectionRevision = 0
    @Published private(set) var libraryPlaylistRevision = 0
    @Published var downloading: Set<String> = []
    @Published private var serverStars: [String: Bool] = Disk.read("server-stars.json", fallback: [:])
    @Published var nowBook: MediaItem? = Disk.read("last-book.json", fallback: Optional<MediaItem>.none)
    private var runtimes: [String: ScriptRuntime] = [:]
    private var starts: [String: Task<ScriptRuntime, Error>] = [:]
    private var coverRequests: [String: Task<JSONObject, Error>] = [:]
    private var chapterDownloadQueue: [MediaItem] = []
    private var chapterDownloadTask: Task<Void, Never>?
    private let defaults = UserDefaults.standard
    init() { Disk.save(plugins, "plugins.json") }
    var nav: Navidrome? {
        let base = defaults.string(forKey: "nav-url") ?? "", user = defaults.string(forKey: "nav-user") ?? ""
        guard !base.isEmpty, !user.isEmpty else { return nil }
        return Navidrome(base: base, user: user, password: Keychain.read("nav-password:" + Disk.hash(base + "|" + user)))
    }
    func connect(base: String, user: String, password: String) async throws {
        let candidate = Navidrome(base: base, user: user, password: password); _ = try await candidate.get("ping")
        try Keychain.write("nav-password:" + Disk.hash(base + "|" + user), password)
        defaults.set(base, forKey: "nav-url"); defaults.set(user, forKey: "nav-user"); connectionRevision += 1
    }
    func disconnect() { defaults.removeObject(forKey: "nav-url"); defaults.removeObject(forKey: "nav-user"); connectionRevision += 1 }
    func libraryItem(_ row: JSONObject, kind: String = "song") -> MediaItem { var row = row; row["_account"] = nav?.identity ?? ""; return MediaItem(row, source: "library", kind: kind) }
    func libraryPlaylistsChanged() { libraryPlaylistRevision += 1 }
    func changePlaylistCover(_ item: MediaItem, jpeg: Data?) async throws -> MediaItem {
        guard let nav, item.source == "library", item.kind == "playlist", item.raw.string("_account") == nav.identity else { throw AppFailure.message("音乐库账号已变更，请重新打开歌单") }
        let refreshed = try await nav.changePlaylistCover(item.key, jpeg: jpeg)
        guard self.nav?.identity == nav.identity else { throw AppFailure.message("封面已提交到原服务器，当前音乐库账号已变更") }
        let revision = UUID().uuidString
        for id in Set([item.cover, refreshed.string("coverArt"), "pl-" + item.key]) where !id.isEmpty {
            defaults.set(revision, forKey: "nav-cover-revision:" + nav.identity + ":" + id)
        }
        libraryPlaylistRevision += 1
        return libraryItem(refreshed, kind: "playlist")
    }
    func artwork(_ item: MediaItem) -> URL? {
        if item.source == "library" && item.raw.string("_account") != nav?.identity { return nil }
        return URL(string: item.source == "library" ? nav?.cover(item.cover) ?? "" : item.cover.replacingOccurrences(of: "{size}", with: "400"))
    }
    func isLiked(_ item: MediaItem) -> Bool {
        if item.source == "library" { return serverStars[item.id] ?? !(item.raw.string("starred").isEmpty) }
        return liked.contains { $0.id == item.id }
    }
    func toggleLike(_ item: MediaItem) {
        if item.source == "library", let nav {
            guard item.raw.string("_account") == nav.identity else { message = "音乐库账号已变更"; return }
            let enabled = !isLiked(item)
            Task { do { _ = try await nav.get(enabled ? "star" : "unstar", [.init(name: "id", value: item.key)]); serverStars[item.id] = enabled; Disk.save(serverStars, "server-stars.json") } catch { message = error.localizedDescription } }; return
        }
        if let index = liked.firstIndex(where: { $0.id == item.id }) { liked.remove(at: index) } else { liked.insert(item, at: 0) }
    }
    func toggleShelf(_ item: MediaItem) {
        if let index = bookshelf.firstIndex(where: { $0.id == item.id }) { bookshelf.remove(at: index) } else { bookshelf.insert(item, at: 0) }
    }
    func toggleFollow(_ item: MediaItem) {
        if let index = followed.firstIndex(where: { $0.id == item.id }) { followed.remove(at: index) } else { followed.insert(item, at: 0) }
    }
    private func runtime(_ key: String, scripts: String) async throws -> ScriptRuntime {
        if let runtime = runtimes[key] { return runtime }
        if let start = starts[key] { return try await start.value }
        let task = Task { @MainActor in
            let runtime = ScriptRuntime(scope: key)
            runtime.update = { [weak self] log, url in self?.message = "音源更新：\(log)\n\(url)" }
            do { try await runtime.start(scripts); return runtime } catch { runtime.close(); throw error }
        }
        starts[key] = task
        do { let runtime = try await task.value; runtimes[key] = runtime; starts.removeValue(forKey: key); return runtime }
        catch { starts.removeValue(forKey: key); throw error }
    }
    func invalidate(_ id: String) { runtimes.removeValue(forKey: id)?.close(); starts.removeValue(forKey: id)?.cancel() }
    func catalog(_ operation: String, _ args: JSONObject, refresh: Bool = false) async throws -> JSONObject {
        if operation == "chart-cover", !ChartArtworkSettings.topCoverEnabled { return ["list": [JSONObject]()] }
        guard !refresh, operation == "chart-cover" || operation == "chart-covers" else {
            return try await loadCatalog(operation, args, refresh: refresh)
        }
        let key = operation + jsonText(args)
        if let request = coverRequests[key] { return try await request.value }
        let request = Task { try await self.loadCatalog(operation, args, refresh: false) }
        coverRequests[key] = request
        defer { coverRequests.removeValue(forKey: key) }
        return try await request.value
    }
    private func loadCatalog(_ operation: String, _ args: JSONObject, refresh: Bool) async throws -> JSONObject {
#if DEBUG
        if UITestFixtures.active { return UITestFixtures.catalog(operation, args: args, refresh: refresh) }
#endif
        let artworkLookup = operation == "chart-cover" || operation == "chart-covers"
        // A new namespace bypasses the old cached empty Migu artwork responses.
        // Keep first-song artwork separate from platform board artwork, also
        // bypassing older Migu responses that stored a board image as top art.
        let prefix = operation == "chart-cover" ? "chart-top-v3-" : artworkLookup ? "artwork-v2-" : operation == "charts" && args.string("source") == "mg" ? "charts-v2-" : "catalog-"
        let key = prefix + Disk.hash(operation + jsonText(args)) + ".json"
        let file = Disk.directory(Disk.cache.appendingPathComponent("catalog")).appendingPathComponent(key)
        if !refresh, let info = try? file.resourceValues(forKeys: [.contentModificationDateKey]), Date().timeIntervalSince(info.contentModificationDate ?? .distantPast) < 10800,
           let bytes = try? Data(contentsOf: file), let cached = try? JSONSerialization.jsonObject(with: bytes) as? JSONObject,
           !artworkLookup || !cached.rows("list").isEmpty { return cached }
        guard let url = Bundle.main.url(forResource: "catalog.bundle", withExtension: "js", subdirectory: "Runtime") else { throw AppFailure.message("音乐目录资源缺失") }
        let scripts = try String(contentsOf: url, encoding: .utf8)
        // A forced refresh also resets adapter memory (for example a cached QQ
        // chart period). Keep other pages' in-flight catalog requests intact.
        let fresh = refresh ? ScriptRuntime(scope: "catalog-refresh-" + UUID().uuidString) : nil
        defer { fresh?.close() }
        let runtime: ScriptRuntime
        if let fresh { try await fresh.start(scripts); runtime = fresh }
        else { runtime = try await self.runtime("catalog", scripts: scripts) }
        do {
            guard let result = try await runtime.call("SoundShelfCatalog(\(quote(operation)),\(jsonText(args)))") as? JSONObject else { throw AppFailure.message("音乐平台未返回有效内容") }
            if !artworkLookup || !result.rows("list").isEmpty { try JSONSerialization.data(withJSONObject: result).write(to: file, options: .atomic) }
            return result
        } catch {
            if !refresh, let bytes = try? Data(contentsOf: file), let cached = try? JSONSerialization.jsonObject(with: bytes) as? JSONObject,
               !artworkLookup || !cached.rows("list").isEmpty { return cached }; throw error
        }
    }
    func install(_ data: Data, name: String, address: String, books: Bool) async throws {
        var plugin = try PluginArchive.read(data, name: name, address: address, books: books)
        let runtime = try await runtime(plugin.id, scripts: plugin.runtimeScript)
        do {
            if books {
                guard let sources = try await runtime.call("bookSources()") as? [JSONObject], !sources.isEmpty else { throw AppFailure.message("插件没有注册听书源") }
            } else { _ = try await runtime.call("lxInstallation()", timeout: 20) }
        } catch { invalidate(plugin.id); throw error }
        if let index = plugins.firstIndex(where: { ((!address.isEmpty && $0.address == address) || $0.name == plugin.name) && $0.books == plugin.books }) {
            let previous = plugins[index]; invalidate(previous.id); invalidate(plugin.id)
            plugin.id = previous.id; plugin.enabled = previous.enabled; plugins[index] = plugin
        } else { plugins.append(plugin) }
    }
    func updateSources() async {
        let installed = plugins.filter { $0.enabled && !$0.address.isEmpty }
        var failures: [String] = []
        for plugin in installed {
            do { try await installFromURL(plugin.address, books: plugin.books) }
            catch { failures.append(plugin.name + "：" + error.localizedDescription) }
        }
        defaults.set(Date(), forKey: "source-update-date")
        if !failures.isEmpty { message = failures.joined(separator: "\n") }
    }
    func refreshFollowed() async {
        for item in followed { _ = try? await catalog(item.kind == "chart" ? "chart" : "list", ["source": item.source, "id": item.catalogID, "page": 1], refresh: true) }
        if let nav { _ = try? await nav.get("getPlaylists") }
        defaults.set(Date(), forKey: "playlist-update-date")
    }
    func refreshIfDue() async {
#if DEBUG
        if UITestFixtures.active { return }
#endif
        let now = Date()
        if defaults.bool(forKey: "auto-source-updates"), now.timeIntervalSince(defaults.object(forKey: "source-update-date") as? Date ?? .distantPast) >= 86400 { await updateSources() }
        let interval = defaults.object(forKey: "playlist-update-hours") as? Int ?? 6
        if interval >= 0, now.timeIntervalSince(defaults.object(forKey: "playlist-update-date") as? Date ?? .distantPast) >= Double(interval * 3600) { await refreshFollowed() }
    }
    func installFromURL(_ address: String, books: Bool) async throws {
        guard let url = URL(string: address), ["https", "http"].contains(url.scheme ?? "") else { throw AppFailure.message("请填写 HTTP/HTTPS 插件直链") }
        let (bytes, response) = try await URLSession.shared.data(from: url)
        guard (response as? HTTPURLResponse)?.statusCode == 200 else { throw AppFailure.message("插件下载失败") }
        try await install(bytes, name: url.lastPathComponent, address: address, books: books)
    }
    func installBundledBooks() async {
#if DEBUG
        if UITestFixtures.active { return }
#endif
        guard !defaults.bool(forKey: "bundled-books-installed") else { return }
        guard let url = Bundle.main.url(forResource: "Sources-v5.7", withExtension: "zip", subdirectory: "Runtime") else { return }
        do {
            try await install(Data(contentsOf: url), name: "ShunSources", address: "https://github.com/whykang/ssts--Android/releases/download/v5.7/Sources-v5.7.zip", books: true)
            defaults.set(true, forKey: "bundled-books-installed")
        } catch { message = error.localizedDescription }
    }
    func bookSources() async throws -> [JSONObject] {
#if DEBUG
        if UITestFixtures.active { return UITestFixtures.bookSources }
#endif
        await installBundledBooks()
        var sources: [JSONObject] = []
        for plugin in plugins where plugin.books && plugin.enabled {
            let runtime = try await runtime(plugin.id, scripts: plugin.runtimeScript)
            let rows = try await runtime.call("bookSources()") as? [JSONObject] ?? []
            sources += rows.filter { !$0.string("name").contains("网易云音乐") }.map { row in var row = row; row["_plugin"] = plugin.id; return row }
        }
        return sources
    }
    func bookCall(_ source: String, pluginID: String, action: String, args: [Any]) async throws -> Any {
#if DEBUG
        if UITestFixtures.active { return UITestFixtures.bookCall(action) }
#endif
        guard let plugin = plugins.first(where: { $0.id == pluginID && $0.enabled }) else { throw AppFailure.message("听书插件已停用或移除") }
        let runtime = try await runtime(plugin.id, scripts: plugin.runtimeScript)
        return try await runtime.call("bookCall(\(quote(source)),\(quote(action)),\(jsonText(args)))")
    }
    func cacheKey(_ item: MediaItem, quality: String? = nil) -> String {
        if item.source == "library", let nav {
            let settings = nav.playbackSettings()
            return item.id + ":\(nav.identity):\(settings.bitrate):\(settings.format)"
        }
        return item.id + (item.kind == "episode" ? "" : ":\(quality ?? defaults.string(forKey: "quality") ?? "320k")")
    }
    var resolutionConfiguration: String {
        jsonText([String(resolutionRevision), String(connectionRevision), nav.map { $0.playbackSettings().bitrate } ?? ""] + ["quality", "nav-url", "nav-user", "nav-bitrate", "nav-bitrate-wifi", "nav-bitrate-mobile", "nav-format"].map { defaults.string(forKey: $0) ?? "" })
    }
    func cacheResolved(_ item: MediaItem, url: URL, headers: [String: String], source: String) async -> URL? {
        let actualQuality = item.kind == "song" && item.source != "library" ? source.components(separatedBy: " · ").last : nil
        let limit = defaults.object(forKey: item.kind == "episode" ? "book-cache" : "music-cache") as? Int ?? 512
        return await MediaCache.shared.store(cacheKey(item, quality: actualQuality), url: url, headers: headers, books: item.kind == "episode", limitMB: limit)
    }
    func resolve(_ item: MediaItem, fallback: Bool = true, useLocal: Bool = true, quality requestedQuality: String? = nil, progress: ((ResolutionNotice) -> Void)? = nil) async throws -> (URL, [String: String], String) {
        try Task.checkCancellation()
        if item.source == "library", item.raw.string("_account") != nav?.identity { throw AppFailure.message("音乐库账号已变更，请从当前音乐库选择歌曲") }
        if useLocal, let download = downloads.first(where: { $0.item.id == item.id }), FileManager.default.fileExists(atPath: Disk.documents.appendingPathComponent(download.filename).path) { return (Disk.documents.appendingPathComponent(download.filename), [:], "本机下载") }
        if useLocal, let cached = await MediaCache.shared.cached(cacheKey(item, quality: requestedQuality), books: item.kind == "episode") { return (cached, [:], "离线缓存") }
        if useLocal && fallback && item.kind == "song" && item.source != "library" {
            for quality in ["master", "atmos_plus", "atmos", "hires", "flac24bit", "flac", "320k", "192k", "128k"] {
                if let cached = await MediaCache.shared.cached(cacheKey(item, quality: quality), books: false) { return (cached, [:], "离线缓存 · " + quality) }
            }
        }
        if item.source == "library" {
            guard let nav else { throw AppFailure.message("请先连接音乐库") }; return (try nav.stream(item), [:], "Navidrome")
        }
        if item.kind == "episode" {
            let result = try await bookCall(item.source, pluginID: item.raw.string("_plugin"), action: "audio", args: [item.key]) as? JSONObject ?? [:]
            guard let url = URL(string: result.string("url")) else { throw AppFailure.message("插件没有返回音频地址") }; return (url, result["headers"] as? [String: String] ?? [:], "听书插件")
        }
        let quality = requestedQuality ?? defaults.string(forKey: "quality") ?? "320k"
        let ladder = ["master", "atmos_plus", "atmos", "hires", "flac24bit", "flac", "320k", "192k", "128k"]
        let qualities = fallback ? Array(ladder.dropFirst(ladder.firstIndex(of: quality) ?? 6)) : [quality]
        let sources = plugins.filter { !$0.books && $0.enabled }
        guard !sources.isEmpty else { throw AppFailure.message("请在音乐设置导入 LX JavaScript 音源") }
        var lastError = "音源解析失败"
        var capabilities: [String: JSONObject] = [:]
        func platforms(_ plugin: Plugin) async throws -> JSONObject {
            if let saved = capabilities[plugin.id] { return saved }
            try Task.checkCancellation()
            let runtime = try await runtime(plugin.id, scripts: plugin.runtimeScript)
            let value = try await runtime.call("lxPlatforms()", timeout: 20) as? JSONObject ?? [:]
            try Task.checkCancellation()
            capabilities[plugin.id] = value
            return value
        }
        var matches: [String: [MediaItem]] = [item.source: [item]]
        // Search other supported platforms lazily after the original recording fails.
        let alternatives = fallback ? Platform.allCases.map(\.rawValue).filter { $0 != "all" && $0 != item.source } : []
        for quality in qualities {
            for platform in [item.source] + alternatives {
                try Task.checkCancellation()
                if matches[platform] == nil {
                    var supported = false
                    for plugin in sources {
                        do { if try await platforms(plugin)[platform] != nil { supported = true; break } }
                        catch { try Task.checkCancellation(); lastError = error.localizedDescription; capabilities[plugin.id] = [:] }
                    }
                    guard supported else { matches[platform] = []; continue }
                    do {
                        progress?(ResolutionNotice(phase: .resolving, detail: "\(Platform(rawValue: platform)?.title ?? platform) · 正在查找同名歌曲"))
                        let result = try await catalog("search", ["source": platform, "query": item.title + " " + item.subtitle, "page": 1])
                        matches[platform] = result.rows("list").map { MediaItem($0) }.filter { SongMatch.same(item, $0) }.sorted { abs($0.duration - item.duration) < abs($1.duration - item.duration) }
                    } catch { try Task.checkCancellation(); matches[platform] = [] }
                }
                for candidate in matches[platform] ?? [] {
                    for plugin in sources {
                        try Task.checkCancellation()
                        let attempt = "\(plugin.name) · \(Platform(rawValue: platform)?.title ?? platform) · \(quality)"
                        do {
                            // Load sources only as needed. A verified winner returns
                            // immediately, before later scripts are initialized.
                            guard let advertised = try await platforms(plugin)[platform] as? JSONObject else { continue }
                            let supported = advertised["qualitys"] as? [String] ?? []
                            guard supported.isEmpty || supported.contains(quality) else { continue }
                            activity = attempt
                            progress?(ResolutionNotice(phase: .resolving, detail: attempt))
                            let runtime = try await runtime(plugin.id, scripts: plugin.runtimeScript)
                            let result = try await runtime.call("lxResolve(\(quote(platform)),\(quote(quality)),\(jsonText(candidate.onlineMusicInfo)))", timeout: 35) as? JSONObject ?? [:]
                            try Task.checkCancellation()
                            guard let url = URL(string: result.string("url")), ["http", "https"].contains(url.scheme ?? "") else { throw AppFailure.message("音源没有返回有效音频地址") }
                            let headers = result["headers"] as? [String: String] ?? [:]
                            progress?(ResolutionNotice(phase: .validating, detail: attempt))
                            try await AudioProbe(quality: quality).verify(url, headers: headers); try Task.checkCancellation()
                            progress?(ResolutionNotice(phase: .success, detail: attempt))
                            return (url, headers, attempt)
                        } catch {
                            try Task.checkCancellation()
                            lastError = error.localizedDescription
                            progress?(ResolutionNotice(phase: .failure, detail: attempt + " · " + lastError))
                        }
                    }
                }
            }
        }
        throw AppFailure.message(lastError)
    }
    func lyrics(_ item: MediaItem) async -> String {
        if item.source == "library" { return await nav?.lyrics(item) ?? "" }
        let embedded = item.raw.string("lrc")
        if !embedded.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && !embedded.hasPrefix("http") { return embedded }
        return (try? await catalog("lyric", ["source": item.source, "music": item.onlineMusicInfo]).string("lyric")) ?? ""
    }
    func hasDownload(_ item: MediaItem) -> Bool {
        downloads.contains { $0.id == item.id && FileManager.default.fileExists(atPath: Disk.documents.appendingPathComponent($0.filename).path) }
    }
    func downloadEpisodes(_ items: [MediaItem]) {
        var seen = Set<String>()
        let pending = items.filter { $0.kind == "episode" && seen.insert($0.id).inserted && !downloading.contains($0.id) && !hasDownload($0) }
        guard !pending.isEmpty else { message = "所选章节已下载或正在下载"; return }
        chapterDownloadQueue += pending
        downloading.formUnion(pending.map(\.id))
        message = "已加入下载 \(pending.count) 个章节"
        guard chapterDownloadTask == nil else { return }
        // Resolve and save one chapter at a time. A large selection must not
        // open hundreds of plugin/audio requests or replace the playing queue.
        chapterDownloadTask = Task {
            var completed = 0, failures = 0, firstFailure = ""
            while !chapterDownloadQueue.isEmpty {
                let item = chapterDownloadQueue.removeFirst()
                do { try await saveDownload(item); completed += 1 }
                catch { failures += 1; if firstFailure.isEmpty { firstFailure = item.title + "：" + error.localizedDescription } }
                downloading.remove(item.id)
            }
            chapterDownloadTask = nil
            message = failures == 0 ? "已保存 \(completed) 个章节到本机" : "已保存 \(completed) 个章节，\(failures) 个下载失败\n" + firstFailure
        }
    }
    func download(_ item: MediaItem) async {
        if item.kind == "episode" { downloadEpisodes([item]); return }
        guard item.kind == "song", !downloading.contains(item.id) else { return }
        downloading.insert(item.id); defer { downloading.remove(item.id) }
        do { try await saveDownload(item); message = "已保存到本机" }
        catch { message = error.localizedDescription }
    }
    private func saveDownload(_ item: MediaItem) async throws {
        let quality = defaults.string(forKey: "quality") ?? "320k"
        let resolved: (URL, [String: String], String)
        if item.source == "library", item.kind != "episode" {
            guard let nav, item.raw.string("_account") == nav.identity else { throw AppFailure.message("音乐库账号已变更，请从当前音乐库选择歌曲") }
            resolved = (try nav.stream(item, original: true), [:], "Navidrome")
        } else { resolved = try await resolve(item, fallback: false, useLocal: false, quality: quality) }
        guard resolved.0.isFileURL || ["http", "https"].contains(resolved.0.scheme?.lowercased() ?? "") else { throw AppFailure.message("下载地址无效") }
        guard resolved.0.pathExtension.lowercased() != "m3u8" else { throw AppFailure.message("该音频为流媒体，此书源暂不支持本机下载") }
        var request = URLRequest(url: resolved.0); request.allHTTPHeaderFields = resolved.1
        request.setValue("identity", forHTTPHeaderField: "Accept-Encoding")
        request.timeoutInterval = 90
        let temp: URL
        var temporaryFile: URL?
        defer { if let temporaryFile { try? FileManager.default.removeItem(at: temporaryFile) } }
        if resolved.0.isFileURL { temp = resolved.0 }
        else {
            let (file, response) = try await URLSession.shared.download(for: request)
            temporaryFile = file
            guard let http = response as? HTTPURLResponse, http.statusCode == 200,
                  !(http.mimeType ?? "").contains("text"), !(http.mimeType ?? "").contains("json"),
                  !(http.mimeType ?? "").contains("mpegurl") else { throw AppFailure.message("下载返回了无效音频") }
            let length = Int64((try file.resourceValues(forKeys: [.fileSizeKey])).fileSize ?? 0)
            guard length > 0, response.expectedContentLength <= 0 || length == response.expectedContentLength else { throw AppFailure.message("音频下载不完整，请重试") }
            temp = file
        }
        let handle = try FileHandle(forReadingFrom: temp); let header = try handle.read(upToCount: 32) ?? Data(); try handle.close()
        let ext: String
        if header.starts(with: Data("fLaC".utf8)) { ext = "flac" }
        else if header.starts(with: Data("OggS".utf8)) { ext = "ogg" }
        else if header.starts(with: Data("RIFF".utf8)) { ext = "wav" }
        else if header.count >= 8 && String(decoding: header[4..<8], as: UTF8.self) == "ftyp" { ext = "m4a" }
        else if header.count >= 2 && header[0] == 255 && header[1] & 246 == 240 { ext = "aac" }
        else if header.starts(with: Data("ID3".utf8)) || (header.count >= 2 && header[0] == 255 && header[1] & 224 == 224) { ext = "mp3" }
        else { throw AppFailure.message(item.kind == "episode" ? "此书源的音频格式暂不支持本机下载" : "下载格式暂不支持；HLS 请使用服务器下载") }
        // Write the replacement before retiring the previous download, including same-format upgrades.
        let filename = Disk.hash(item.id) + "-" + UUID().uuidString + "." + ext
        let target = Disk.documents.appendingPathComponent(filename)
        try FileManager.default.copyItem(at: temp, to: target)
        do {
            let asset = AVURLAsset(url: target)
            let playable = try await asset.load(.isPlayable)
            let tracks = try await asset.loadTracks(withMediaType: .audio)
            guard playable, !tracks.isEmpty else { throw AppFailure.message("下载音频无法播放，请重试") }
        } catch { try? FileManager.default.removeItem(at: target); throw error }
        let previous = downloads.filter { $0.item.id == item.id }
        downloads.removeAll { $0.item.id == item.id }; downloads.append(.init(item: item, filename: filename))
        for old in previous { try? FileManager.default.removeItem(at: Disk.documents.appendingPathComponent(old.filename)) }
    }
    func deleteDownload(_ id: String) { if let item = downloads.first(where: { $0.id == id }) { try? FileManager.default.removeItem(at: Disk.documents.appendingPathComponent(item.filename)); downloads.removeAll { $0.id == id } } }
    func server(_ path: String, method: String = "GET", body: JSONObject? = nil) async throws -> JSONObject {
#if DEBUG
        if UITestFixtures.active { return ["jobs": []] }
#endif
        guard let url = URL(string: (defaults.string(forKey: "server-url") ?? "").trimmingCharacters(in: CharacterSet(charactersIn: "/")) + path), ["https", "http"].contains(url.scheme ?? "") else { throw AppFailure.message("请先配置下载服务器") }
        var request = URLRequest(url: url); request.httpMethod = method; request.setValue("Bearer " + Keychain.read("server-token"), forHTTPHeaderField: "Authorization")
        if let body { request.httpBody = try JSONSerialization.data(withJSONObject: body); request.setValue("application/json", forHTTPHeaderField: "Content-Type") }
        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else { throw AppFailure.message("下载服务器请求失败") }
        return (try JSONSerialization.jsonObject(with: data)) as? JSONObject ?? [:]
    }
    func push(_ item: MediaItem) async {
        do {
            let (url, headers, _) = try await resolve(item, fallback: false, useLocal: false)
            guard !url.isFileURL else { throw AppFailure.message("当前文件只有本机地址，请从在线列表选择歌曲") }
            _ = try await server("/v1/jobs", method: "POST", body: ["kind": "music", "url": url.absoluteString, "headers": headers, "title": item.title, "artist": item.subtitle, "quality": defaults.string(forKey: "quality") ?? "320k", "album": item.raw.string("albumName", "album")])
            message = "已推送到服务器"
        } catch { message = error.localizedDescription }
    }
}
