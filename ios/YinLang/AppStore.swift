import Foundation
import SwiftUI
import UniformTypeIdentifiers

@MainActor
final class AppStore: ObservableObject {
    @Published var plugins: [Plugin] = Disk.read("plugins.json", fallback: []) { didSet { Disk.save(plugins, "plugins.json") } }
    @Published var liked: [MediaItem] = Disk.read("liked.json", fallback: []) { didSet { Disk.save(liked, "liked.json") } }
    @Published var followed: [MediaItem] = Disk.read("followed.json", fallback: []) { didSet { Disk.save(followed, "followed.json") } }
    @Published var bookshelf: [MediaItem] = Disk.read("bookshelf.json", fallback: []) { didSet { Disk.save(bookshelf, "bookshelf.json") } }
    @Published var downloads: [DownloadedItem] = Disk.read("downloads.json", fallback: []) { didSet { Disk.save(downloads, "downloads.json") } }
    @Published var message: String?
    @Published var activity = ""
    @Published var connectionRevision = 0
    @Published var downloading: Set<String> = []
    @Published var nowBook: MediaItem? = Disk.read("last-book.json", fallback: Optional<MediaItem>.none)
    private var runtimes: [String: ScriptRuntime] = [:]
    private var starts: [String: Task<ScriptRuntime, Error>] = [:]
    private let defaults = UserDefaults.standard
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
    func artwork(_ item: MediaItem) -> URL? { URL(string: item.source == "library" ? nav?.cover(item.cover) ?? "" : item.cover.replacingOccurrences(of: "{size}", with: "400")) }
    func toggleLike(_ item: MediaItem) {
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
        let key = "catalog-" + Disk.hash(operation + jsonText(args)) + ".json"
        let file = Disk.directory(Disk.cache.appendingPathComponent("catalog")).appendingPathComponent(key)
        if !refresh, let info = try? file.resourceValues(forKeys: [.contentModificationDateKey]), Date().timeIntervalSince(info.contentModificationDate ?? .distantPast) < 10800,
           let bytes = try? Data(contentsOf: file), let cached = try? JSONSerialization.jsonObject(with: bytes) as? JSONObject { return cached }
        guard let url = Bundle.main.url(forResource: "catalog.bundle", withExtension: "js", subdirectory: "Runtime") else { throw AppFailure.message("音乐目录资源缺失") }
        let runtime = try await runtime("catalog", scripts: String(contentsOf: url, encoding: .utf8))
        do {
            guard let result = try await runtime.call("SoundShelfCatalog(\(quote(operation)),\(jsonText(args)))") as? JSONObject else { throw AppFailure.message("音乐平台未返回有效内容") }
            try JSONSerialization.data(withJSONObject: result).write(to: file, options: .atomic); return result
        } catch {
            if !refresh, let bytes = try? Data(contentsOf: file), let cached = try? JSONSerialization.jsonObject(with: bytes) as? JSONObject { return cached }; throw error
        }
    }
    func install(_ data: Data, name: String, address: String, books: Bool) async throws {
        var plugin = try PluginArchive.read(data, name: name, address: address, books: books)
        let runtime = try await runtime(plugin.id, scripts: plugin.script)
        do {
            if books {
                guard let sources = try await runtime.call("bookSources()") as? [JSONObject], !sources.isEmpty else { throw AppFailure.message("插件没有注册听书源") }
            } else { _ = try await runtime.call("lxInstallation()", timeout: 20) }
        } catch { invalidate(plugin.id); throw error }
        if let index = plugins.firstIndex(where: { $0.name == plugin.name && $0.books == plugin.books }) {
            let previous = plugins[index]; invalidate(previous.id); invalidate(plugin.id)
            plugin.id = previous.id; plugin.enabled = previous.enabled; plugins[index] = plugin
        } else { plugins.append(plugin) }
    }
    func installFromURL(_ address: String, books: Bool) async throws {
        guard let url = URL(string: address), ["https", "http"].contains(url.scheme ?? "") else { throw AppFailure.message("请填写 HTTP/HTTPS 插件直链") }
        let (bytes, response) = try await URLSession.shared.data(from: url)
        guard (response as? HTTPURLResponse)?.statusCode == 200 else { throw AppFailure.message("插件下载失败") }
        try await install(bytes, name: url.lastPathComponent, address: address, books: books)
    }
    func installBundledBooks() async {
        guard !defaults.bool(forKey: "bundled-books-installed") else { return }
        guard let url = Bundle.main.url(forResource: "Sources-v5.7", withExtension: "zip", subdirectory: "Runtime") else { return }
        do {
            try await install(Data(contentsOf: url), name: "ShunSources", address: "https://github.com/whykang/ssts--Android/releases/download/v5.7/Sources-v5.7.zip", books: true)
            defaults.set(true, forKey: "bundled-books-installed")
        } catch { message = error.localizedDescription }
    }
    func bookSources() async throws -> [JSONObject] {
        await installBundledBooks()
        var sources: [JSONObject] = []
        for plugin in plugins where plugin.books && plugin.enabled {
            let runtime = try await runtime(plugin.id, scripts: plugin.script)
            let rows = try await runtime.call("bookSources()") as? [JSONObject] ?? []
            sources += rows.filter { !$0.string("name").contains("网易云音乐") }.map { row in var row = row; row["_plugin"] = plugin.id; return row }
        }
        return sources
    }
    func bookCall(_ source: String, pluginID: String, action: String, args: [Any]) async throws -> Any {
        guard let plugin = plugins.first(where: { $0.id == pluginID && $0.enabled }) else { throw AppFailure.message("听书插件已停用或移除") }
        let runtime = try await runtime(plugin.id, scripts: plugin.script)
        return try await runtime.call("bookCall(\(quote(source)),\(quote(action)),\(jsonText(args)))")
    }
    func cacheKey(_ item: MediaItem) -> String {
        item.id + (item.source == "library" ? ":\(nav?.identity ?? ""):\(defaults.string(forKey: "nav-bitrate") ?? "0"):\(defaults.string(forKey: "nav-format") ?? "mp3")" : ":\(defaults.string(forKey: "quality") ?? "320k")")
    }
    func resolve(_ item: MediaItem, fallback: Bool = true) async throws -> (URL, [String: String], String) {
        if let download = downloads.first(where: { $0.item.id == item.id }), FileManager.default.fileExists(atPath: Disk.documents.appendingPathComponent(download.filename).path) { return (Disk.documents.appendingPathComponent(download.filename), [:], "本机下载") }
        if let cached = await MediaCache.shared.cached(cacheKey(item), books: item.kind == "episode") { return (cached, [:], "离线缓存") }
        if item.source == "library" {
            guard let nav else { throw AppFailure.message("请先连接音乐库") }; return (try nav.stream(item), [:], "Navidrome")
        }
        if item.kind == "episode" {
            let result = try await bookCall(item.source, pluginID: item.raw.string("_plugin"), action: "audio", args: [item.key]) as? JSONObject ?? [:]
            guard let url = URL(string: result.string("url")) else { throw AppFailure.message("插件没有返回音频地址") }; return (url, result["headers"] as? [String: String] ?? [:], "听书插件")
        }
        let quality = defaults.string(forKey: "quality") ?? "320k"
        let ladder = ["master", "atmos_plus", "atmos", "hires", "flac24bit", "flac", "320k", "192k", "128k"]
        let qualities = fallback ? Array(ladder.dropFirst(ladder.firstIndex(of: quality) ?? 6)) : [quality]
        let sources = plugins.filter { !$0.books && $0.enabled }
        guard !sources.isEmpty else { throw AppFailure.message("请在音乐设置导入 LX JavaScript 音源") }
        var lastError = "音源解析失败"
        for quality in qualities {
            for plugin in sources {
                do {
                    activity = "\(plugin.name) · \(quality)"
                    let runtime = try await runtime(plugin.id, scripts: plugin.script)
                    let result = try await runtime.call("lxResolve(\(quote(item.source)),\(quote(quality)),\(jsonText(item.raw)))", timeout: 35) as? JSONObject ?? [:]
                    guard let url = URL(string: result.string("url")), ["http", "https"].contains(url.scheme ?? "") else { continue }
                    return (url, result["headers"] as? [String: String] ?? [:], "\(plugin.name) · \(quality)")
                } catch { lastError = error.localizedDescription }
            }
        }
        throw AppFailure.message(lastError)
    }
    func lyrics(_ item: MediaItem) async -> String {
        if item.source == "library" { return await nav?.lyrics(item) ?? "" }
        return (try? await catalog("lyric", ["source": item.source, "music": item.raw]).string("lyric")) ?? ""
    }
    func download(_ item: MediaItem) async {
        guard item.kind == "song", !downloading.contains(item.id) else { return }; downloading.insert(item.id); defer { downloading.remove(item.id) }
        do {
            let resolved: (URL, [String: String], String)
            if item.source == "library", let nav { resolved = (try nav.stream(item, original: true), [:], "Navidrome") }
            else { resolved = try await resolve(item, fallback: false) }
            var request = URLRequest(url: resolved.0); request.allHTTPHeaderFields = resolved.1
            let temp: URL
            if resolved.0.isFileURL { temp = resolved.0 }
            else {
                let (file, response) = try await URLSession.shared.download(for: request)
                guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode), !(http.mimeType ?? "").contains("text"), !(http.mimeType ?? "").contains("json") else { throw AppFailure.message("下载返回了无效音频") }; temp = file
            }
            let handle = try FileHandle(forReadingFrom: temp); let header = try handle.read(upToCount: 32) ?? Data(); try handle.close()
            let ext: String
            if header.starts(with: Data("fLaC".utf8)) { ext = "flac" }
            else if header.starts(with: Data("OggS".utf8)) { ext = "ogg" }
            else if header.starts(with: Data("RIFF".utf8)) { ext = "wav" }
            else if header.count >= 8 && String(decoding: header[4..<8], as: UTF8.self) == "ftyp" { ext = "m4a" }
            else if header.starts(with: Data("ID3".utf8)) || (header.count >= 2 && header[0] == 255 && header[1] & 224 == 224) { ext = "mp3" }
            else { throw AppFailure.message("下载格式暂不支持；HLS 请使用服务器下载") }
            let filename = Disk.hash(item.id) + "." + ext; let target = Disk.documents.appendingPathComponent(filename)
            if !FileManager.default.fileExists(atPath: target.path) { try FileManager.default.copyItem(at: temp, to: target) }
            downloads.removeAll { $0.item.id == item.id }; downloads.append(.init(item: item, filename: filename)); message = "已保存到本机"
        } catch { message = error.localizedDescription }
    }
    func deleteDownload(_ id: String) { if let item = downloads.first(where: { $0.id == id }) { try? FileManager.default.removeItem(at: Disk.documents.appendingPathComponent(item.filename)); downloads.removeAll { $0.id == id } } }
    func server(_ path: String, method: String = "GET", body: JSONObject? = nil) async throws -> JSONObject {
        guard let url = URL(string: (defaults.string(forKey: "server-url") ?? "").trimmingCharacters(in: CharacterSet(charactersIn: "/")) + path), ["https", "http"].contains(url.scheme ?? "") else { throw AppFailure.message("请先配置下载服务器") }
        var request = URLRequest(url: url); request.httpMethod = method; request.setValue("Bearer " + Keychain.read("server-token"), forHTTPHeaderField: "Authorization")
        if let body { request.httpBody = try JSONSerialization.data(withJSONObject: body); request.setValue("application/json", forHTTPHeaderField: "Content-Type") }
        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else { throw AppFailure.message("下载服务器请求失败") }
        return (try JSONSerialization.jsonObject(with: data)) as? JSONObject ?? [:]
    }
    func push(_ item: MediaItem) async {
        do {
            let (url, headers, quality) = try await resolve(item, fallback: false)
            guard !url.isFileURL else { throw AppFailure.message("当前文件只有本机地址，请从在线列表选择歌曲") }
            _ = try await server("/v1/jobs", method: "POST", body: ["kind": "music", "url": url.absoluteString, "headers": headers, "title": item.title, "artist": item.subtitle, "quality": quality])
            message = "已推送到服务器"
        } catch { message = error.localizedDescription }
    }
}
