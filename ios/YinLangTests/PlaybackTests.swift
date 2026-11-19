import XCTest
import Network
import AVFoundation
@testable import YinLang

// Serve real PCM audio through HTTP, including the Range requests AVPlayer uses.
// No third-party accounts or external music URLs are required for this regression.
private final class PlaybackServer: @unchecked Sendable {
    private let listener: NWListener
    private let queue = DispatchQueue(label: "YinLang.PlaybackFixture")
    private let wave: Data
    var audioData: Data { wave }
    private var connections: [NWConnection] = []
    private var requests: [(target: String, ranged: Bool)] = []
    private var activeRanges = 0
    private var peakRanges = 0
    var maximumConcurrentRanges: Int { queue.sync { peakRanges } }
    func rangeRequests(containing value: String) -> Int { queue.sync { requests.filter { $0.target.contains(value) && $0.ranged }.count } }
    func fullRequests(containing value: String) -> Int { queue.sync { requests.filter { $0.target.contains(value) && !$0.ranged }.count } }
    private(set) var port: UInt16 = 0
    var base: String { "http://127.0.0.1:\(port)" }
    init() throws {
        listener = try NWListener(using: .tcp, on: .any)
        var data = Data()
        func text(_ value: String) { data.append(Data(value.utf8)) }
        func number<T: FixedWidthInteger>(_ value: T) { var value = value.littleEndian; withUnsafeBytes(of: &value) { data.append(contentsOf: $0) } }
        let samples = 44100 * 8
        text("RIFF"); number(UInt32(36 + samples * 2)); text("WAVEfmt ")
        number(UInt32(16)); number(UInt16(1)); number(UInt16(1)); number(UInt32(44100))
        number(UInt32(88200)); number(UInt16(2)); number(UInt16(16)); text("data"); number(UInt32(samples * 2))
        for index in 0..<samples { number(Int16(sin(Double(index) * 2 * .pi * 440 / 44100) * 1200)) }
        wave = data
    }
    func start() async throws {
        listener.newConnectionHandler = { [weak self] connection in
            guard let self else { return }; self.connections.append(connection)
            connection.start(queue: self.queue); self.receive(connection, data: Data())
        }
        port = try await withCheckedThrowingContinuation { continuation in
            listener.stateUpdateHandler = { [weak self] state in
                guard let self else { return }
                if case .ready = state { self.listener.stateUpdateHandler = nil; continuation.resume(returning: self.listener.port!.rawValue) }
                if case .failed(let error) = state { self.listener.stateUpdateHandler = nil; continuation.resume(throwing: error) }
            }
            listener.start(queue: queue)
        }
    }
    func stop() { queue.sync { listener.cancel(); connections.forEach { $0.cancel() }; connections = [] } }
    private func receive(_ connection: NWConnection, data: Data) {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 16384) { [weak self] bytes, _, complete, error in
            guard let self else { return }
            let request = data + (bytes ?? Data())
            guard request.range(of: Data("\r\n\r\n".utf8)) != nil else {
                if complete || error != nil || request.count > 32768 { connection.cancel() }
                else { self.receive(connection, data: request) }; return
            }
            self.respond(connection, request: String(decoding: request, as: UTF8.self))
        }
    }
    private func respond(_ connection: NWConnection, request: String) {
        let lines = request.components(separatedBy: "\r\n")
        let target = lines.first?.split(separator: " ").dropFirst().first.map(String.init) ?? "/"
        requests.append((target, lines.contains { $0.lowercased().hasPrefix("range:") }))
        if target == "/stall" { return }
        let audio = target.hasPrefix("/audio.wav") || target.hasPrefix("/rest/stream.view")
        var body = audio ? wave : Data("{\"subsonic-response\":{\"status\":\"ok\",\"version\":\"1.16.1\"}}".utf8)
        var code = "200 OK", extra = ""
        if audio, !target.contains("ignore-range"), let range = lines.first(where: { $0.lowercased().hasPrefix("range: bytes=") }) {
            let parts = range.split(separator: "=", maxSplits: 1).last!.split(separator: "-", omittingEmptySubsequences: false)
            let start = Int(parts.first ?? "") ?? 0
            let end = min(wave.count - 1, parts.count > 1 ? Int(parts[1]) ?? wave.count - 1 : wave.count - 1)
            if start >= 0 && start <= end {
                body = wave.subdata(in: start..<(end + 1)); code = "206 Partial Content"
                extra = "Content-Range: bytes \(start)-\(end)/\(wave.count)\r\n"
            } else { body = Data(); code = "416 Range Not Satisfiable"; extra = "Content-Range: bytes */\(wave.count)\r\n" }
        }
        if target.contains("bad-range"), code.hasPrefix("206") { extra = "Content-Range: bytes 0-0/\(wave.count)\r\n" }
        let header = "HTTP/1.1 \(code)\r\nContent-Type: \(audio ? "audio/wav" : "application/json")\r\nContent-Length: \(body.count)\r\nAccept-Ranges: bytes\r\n\(extra)Connection: close\r\n\r\n"
        let head = request.hasPrefix("HEAD ")
        let reply = Data(header.utf8) + (head ? Data() : body)
        let delayed = target.contains("parallel-audit") && code.hasPrefix("206")
        if delayed { activeRanges += 1; peakRanges = max(peakRanges, activeRanges) }
        let send: @Sendable () -> Void = { [weak self] in
            connection.send(content: reply, isComplete: true, completion: .contentProcessed { _ in
                if delayed { self?.activeRanges -= 1 }; connection.cancel()
            })
        }
        if delayed { queue.asyncAfter(deadline: .now() + .milliseconds(150), execute: send) } else { send() }
    }
}

final class PlaybackTests: XCTestCase {
    func testCacheDownloadUsesThreeWorkersAndSharesLimitAcrossFiles() async throws { try await exerciseCacheWorkers(threads: 3) }
    func testCacheDownloadSupportsFiveWorkers() async throws { try await exerciseCacheWorkers(threads: 5) }
    private func exerciseCacheWorkers(threads: Int) async throws {
        let server = try PlaybackServer(); try await server.start(); defer { server.stop() }
        let defaults = UserDefaults.standard, previous = defaults.object(forKey: CacheDownloadSettings.key)
        defer { if let previous { defaults.set(previous, forKey: CacheDownloadSettings.key) } else { defaults.removeObject(forKey: CacheDownloadSettings.key) } }
        defaults.set(threads, forKey: CacheDownloadSettings.key)
        try await withThrowingTaskGroup(of: Void.self) { group in
            for index in 0..<2 {
                group.addTask {
                    let (file, response) = try await CacheDownloader.download(URL(string: server.base + "/audio.wav?parallel-audit=\(index)")!, headers: [:], maximumBytes: 16_777_216)
                    defer { try? FileManager.default.removeItem(at: file) }
                    XCTAssertEqual(response.statusCode, 200)
                    let bytes = try Data(contentsOf: file)
                    XCTAssertEqual(bytes, server.audioData, "分段拼接必须逐字节保持原始音频")
                }
            }
            try await group.waitForAll()
        }
        XCTAssertEqual(server.rangeRequests(containing: "parallel-audit"), (threads + 1) * 2)
        XCTAssertEqual(server.fullRequests(containing: "parallel-audit"), 0)
        XCTAssertGreaterThanOrEqual(server.maximumConcurrentRanges, 2)
        XCTAssertLessThanOrEqual(server.maximumConcurrentRanges, threads)
    }
    func testCacheDownloadFallsBackWhenRangeIgnoredOrMalformed() async throws {
        let server = try PlaybackServer(); try await server.start(); defer { server.stop() }
        for query in ["ignore-range", "bad-range"] {
            let (file, response) = try await CacheDownloader.download(URL(string: server.base + "/audio.wav?" + query)!, headers: [:], maximumBytes: 16_777_216)
            defer { try? FileManager.default.removeItem(at: file) }
            XCTAssertEqual(response.statusCode, 200)
            XCTAssertEqual(try Data(contentsOf: file).count, 44 + 44100 * 8 * 2)
        }
        XCTAssertEqual(server.rangeRequests(containing: "ignore-range"), 1)
        XCTAssertEqual(server.fullRequests(containing: "ignore-range"), 0, "忽略 Range 的完整响应应直接复用")
        XCTAssertEqual(server.fullRequests(containing: "bad-range"), 1)
    }
    @MainActor func testPreparseStartsAtSeventyPercentAndNextReusesVerifiedAddress() async throws { try await exercisePreparation(precache: false) }
    @MainActor func testPrecacheWorksIndependentlyAndNextPlaysOffline() async throws { try await exercisePreparation(precache: true) }
    @MainActor private func exercisePreparation(precache: Bool) async throws {
        let server = try PlaybackServer(); try await server.start(); defer { server.stop() }
        let store = AppStore(), player = Player(), defaults = UserDefaults.standard
        let previousPlugins = store.plugins
        let keys = [NextTrackSettings.parseKey, NextTrackSettings.cacheKey, "quality", "music-cache"]
        let previous = Dictionary(uniqueKeysWithValues: keys.map { ($0, defaults.object(forKey: $0)) })
        let scope = UUID().uuidString
        let first = MediaItem(["id": scope + "-first", "name": "Current"], source: "wy")
        let next = MediaItem(["id": scope + "-next", "name": "Next", "lrc": "[00:00]Next"], source: "wy")
        let file = Disk.directory(Disk.cache.appendingPathComponent("music")).appendingPathComponent(Disk.hash(store.cacheKey(next, quality: "flac")))
        defer {
            while !player.queue.isEmpty { player.remove(0) }
            store.invalidate(scope); store.plugins = previousPlugins
            defaults.removeObject(forKey: scope + ":calls")
            for key in keys { if let value = previous[key] ?? nil { defaults.set(value, forKey: key) } else { defaults.removeObject(forKey: key) } }
            try? FileManager.default.removeItem(at: file)
        }
        defaults.set(!precache, forKey: NextTrackSettings.parseKey); defaults.set(precache, forKey: NextTrackSettings.cacheKey)
        defaults.set("flac", forKey: "quality"); defaults.set(16, forKey: "music-cache")
        let script = "let calls = 0; lx.on('request', () => { Native.put('calls', String(++calls)); return '\(server.base)/audio.wav?next=1'; }); lx.send('inited',{sources:{wy:{qualitys:['flac']}}});"
        store.plugins = [Plugin(id: scope, name: "Next fixture", version: "1", script: script, address: "", books: false)]
        player.attach(store); player.queue = [first, next]; player.index = 0; player.playing = true; player.duration = 100; player.elapsed = 69.99
        player.prepareNextIfNeeded(); try await Task.sleep(nanoseconds: 300_000_000)
        XCTAssertNil(defaults.string(forKey: scope + ":calls"))
        player.elapsed = 70; player.prepareNextIfNeeded()
        for _ in 0..<100 {
            if player.resolutionNotice?.phase == .success || player.resolutionNotice?.phase == .cached { break }
            try await Task.sleep(nanoseconds: 100_000_000)
        }
        XCTAssertEqual(defaults.string(forKey: scope + ":calls"), "1")
        if precache {
            for _ in 0..<100 {
                if player.resolutionNotice?.phase == .cached { break }
                try await Task.sleep(nanoseconds: 100_000_000)
            }
            XCTAssertEqual(player.resolutionNotice?.phase, .cached)
            XCTAssertTrue(FileManager.default.fileExists(atPath: file.path))
            XCTAssertGreaterThanOrEqual(server.rangeRequests(containing: "next=1"), 4)
            server.stop()
        } else { XCTAssertEqual(server.fullRequests(containing: "next=1"), 0, "预解析不能下载整首音频") }
        player.next()
        for _ in 0..<150 {
            if player.elapsed > 0.5 || store.message != nil { break }
            try await Task.sleep(nanoseconds: 100_000_000)
        }
        XCTAssertEqual(player.current?.id, next.id); XCTAssertNil(store.message, player.status)
        XCTAssertGreaterThan(player.elapsed, 0.5, player.status)
        XCTAssertEqual(defaults.string(forKey: scope + ":calls"), "1", "切歌必须复用已验证结果")
        if precache { XCTAssertTrue(player.status.contains("离线缓存"), player.status) }
    }
    @MainActor func testCurrentAudioCachesNotifiesAndReplaysWithoutNetwork() async throws { try await exercisePlayback(library: false, cacheEnabled: true) }
    @MainActor func testAudioVerificationCancellationStopsWaitingForNetworkTimeout() async throws {
        let server = try PlaybackServer(); try await server.start(); defer { server.stop() }
        let task = Task { try await AudioProbe(quality: "flac").verify(URL(string: server.base + "/stall")!, headers: [:]) }
        try await Task.sleep(nanoseconds: 200_000_000)
        let cancelledAt = Date(); task.cancel()
        do { try await task.value; XCTFail("Cancelled probe must not succeed") }
        catch { XCTAssertTrue(error is CancellationError) }
        XCTAssertLessThan(Date().timeIntervalSince(cancelledAt), 2)
    }
    @MainActor func testVerifiedSourceStopsBeforeLaterSourceIsInitialized() async throws { try await exerciseSourceOrder(failFirst: false) }
    @MainActor func testFailedSourceFallsThroughAndStopsAtFirstVerifiedSource() async throws { try await exerciseSourceOrder(failFirst: true) }
    @MainActor private func exerciseSourceOrder(failFirst: Bool) async throws {
        let server = try PlaybackServer(); try await server.start(); defer { server.stop() }
        let store = AppStore(), old = store.plugins
        let prefix = UUID().uuidString
        let firstID = prefix + "-first", winnerID = prefix + "-winner", laterID = prefix + "-later"
        let ids = [firstID, winnerID, laterID]
        func script(_ url: String) -> String {
            "Native.put('started','yes'); lx.on('request', () => { Native.put('called','yes'); return '\(url)'; }); lx.send('inited',{sources:{wy:{qualitys:['flac']}}});"
        }
        store.plugins = [
            Plugin(id: firstID, name: "First", version: "1", script: script(server.base + (failFirst ? "/invalid" : "/audio.wav")), address: "", books: false),
            Plugin(id: winnerID, name: "Winner", version: "1", script: script(server.base + "/audio.wav"), address: "", books: false),
            Plugin(id: laterID, name: "Later", version: "1", script: script(server.base + "/audio.wav"), address: "", books: false)
        ]
        defer {
            for id in ids { store.invalidate(id); for key in ["started", "called"] { UserDefaults.standard.removeObject(forKey: id + ":" + key) } }
            store.plugins = old
        }
        var updates: [ResolutionNotice] = []
        let item = MediaItem(["id": prefix, "name": "Source order"], source: "wy")
        let result = try await store.resolve(item, fallback: false, useLocal: false, quality: "flac") { updates.append($0) }
        XCTAssertEqual(result.0.absoluteString, server.base + "/audio.wav")
        XCTAssertEqual(UserDefaults.standard.string(forKey: firstID + ":called"), "yes")
        XCTAssertEqual(UserDefaults.standard.string(forKey: winnerID + ":called"), failFirst ? "yes" : nil)
        XCTAssertNil(UserDefaults.standard.string(forKey: laterID + ":started"))
        if !failFirst { XCTAssertNil(UserDefaults.standard.string(forKey: winnerID + ":started")) }
        XCTAssertEqual(updates.filter { $0.phase == .success }.count, 1)
        XCTAssertEqual(updates.filter { $0.phase == .failure }.count, failFirst ? 1 : 0)
        XCTAssertEqual(updates.last?.phase, .success)
    }
    @MainActor func testResolutionSuccessNoticeExpiresAndCannotHideNewerAttempt() async throws {
        let player = Player()
        player.publishResolution(ResolutionNotice(phase: .success, detail: "First"))
        try await Task.sleep(nanoseconds: 1_000_000_000)
        XCTAssertEqual(player.resolutionNotice?.phase, .success)
        player.publishResolution(ResolutionNotice(phase: .resolving, detail: "Next"))
        try await Task.sleep(nanoseconds: 1_200_000_000)
        XCTAssertEqual(player.resolutionNotice?.detail, "Next")
        player.publishResolution(ResolutionNotice(phase: .success, detail: "Next"))
        try await Task.sleep(nanoseconds: 2_300_000_000)
        XCTAssertNil(player.resolutionNotice)
    }
    @MainActor func testRemovingQueueEntryBeforeCurrentKeepsCurrentSongAndProgress() {
        let player = Player()
        let songs = (1...4).map { MediaItem(["id": "queue-test-\($0)", "name": "Song \($0)"], source: "wy") }
        player.queue = songs; player.index = 2; player.elapsed = 42; player.duration = 180; player.playing = true
        player.remove(0)
        XCTAssertEqual(player.queue.count, 3); XCTAssertEqual(player.index, 1)
        XCTAssertEqual(player.current?.id, songs[2].id); XCTAssertEqual(player.elapsed, 42); XCTAssertTrue(player.playing)
        player.remove(2)
        XCTAssertEqual(player.queue.count, 2); XCTAssertEqual(player.current?.id, songs[2].id); XCTAssertEqual(player.elapsed, 42)
    }
    @MainActor func testRemovingOnlyQueueEntryClearsPlaybackState() {
        let player = Player()
        let item = MediaItem(["id": UUID().uuidString, "name": "Queue cleanup"], source: "test", kind: "test")
        defer { UserDefaults.standard.removeObject(forKey: "progress:" + item.id) }
        player.queue = [item]; player.elapsed = 42; player.duration = 180; player.buffered = 90; player.playing = true; player.loading = true
        player.lyricText = "[00:00]Test"; player.lyrics = LyricLine.parse(player.lyricText)
        player.remove(0)
        XCTAssertTrue(player.queue.isEmpty); XCTAssertNil(player.current); XCTAssertEqual(player.index, 0)
        XCTAssertFalse(player.playing); XCTAssertFalse(player.loading); XCTAssertEqual(player.elapsed, 0); XCTAssertEqual(player.duration, 0); XCTAssertEqual(player.buffered, 0)
        XCTAssertTrue(player.lyrics.isEmpty); XCTAssertTrue(player.lyricText.isEmpty)
    }
    func testMusicSourceMetadataUsesHeaderAndPreservesImportIdentity() throws {
        let script = "\u{FEFF}/**\n * @name 我的测试音源\n * @version 2.3.4\n * @author 作者\n */\nlx.on('request', () => 'https://example.com/a');"
        let plugin = try PluginArchive.read(Data(script.utf8), name: "lxmusic.js", address: "https://example.com/source", books: false)
        XCTAssertEqual(plugin.name, "我的测试音源"); XCTAssertEqual(plugin.version, "2.3.4")
        XCTAssertTrue(plugin.runtimeScript.contains("currentScriptInfo"))
        var legacy = plugin; legacy.name = "lxmusic"; legacy.version = ""; legacy.enabled = false
        let restored = PluginArchive.restoreMusicMetadata(legacy)
        XCTAssertEqual(restored.name, plugin.name); XCTAssertEqual(restored.version, plugin.version)
        XCTAssertEqual(restored.id, legacy.id); XCTAssertEqual(restored.address, legacy.address); XCTAssertFalse(restored.enabled)
        let plain = try PluginArchive.read(Data("lx.on('request', () => 'test');".utf8), name: "fallback.js", address: "", books: false)
        XCTAssertEqual(plain.name, "fallback.js")
    }
    @MainActor func testOnlineLXAudioActuallyAdvancesPlayback() async throws { try await exercisePlayback(library: false) }
    @MainActor func testNavidromeHTTPAudioActuallyAdvancesPlayback() async throws { try await exercisePlayback(library: true) }
    @MainActor func testBookThenOnlineMusicActuallyAdvancesPlayback() async throws { try await exercisePlayback(library: false, switchFromBook: true) }
    @MainActor func testBookThenNavidromeMusicActuallyAdvancesPlayback() async throws { try await exercisePlayback(library: true, switchFromBook: true) }
    @MainActor func testEmptyCacheFallsBackToOnlinePlayback() async throws { try await exercisePlayback(library: false, cachedFile: Data()) }
    @MainActor func testCorruptCacheRetriesOnlineWithoutShowingAnError() async throws {
        try await exercisePlayback(library: false, cachedFile: Data("RIFF".utf8) + Data(repeating: 0, count: 128))
    }
    @MainActor func testCorruptNavidromeCacheRetriesOnline() async throws {
        try await exercisePlayback(library: true, cachedFile: Data("RIFF".utf8) + Data(repeating: 0, count: 128))
    }

    @MainActor private func exercisePlayback(library: Bool, cachedFile: Data? = nil, switchFromBook: Bool = false, cacheEnabled: Bool = false) async throws {
        let server = try PlaybackServer(); try await server.start(); defer { server.stop() }
        let store = AppStore(), player = Player(), defaults = UserDefaults.standard
        let oldPlugins = store.plugins
        let oldDownloads = store.downloads
        let oldBook = store.nowBook
        var seededCache: URL?
        var bookProgressKey: String?
        var bookSpeedKey: String?
        let keys = ["nav-url", "nav-user", "nav-bitrate", "nav-bitrate-wifi", "nav-bitrate-mobile", "nav-format", "quality", "music-cache", "book-cache"]
        let previous = Dictionary(uniqueKeysWithValues: keys.map { ($0, defaults.object(forKey: $0)) })
        let recent = try? Data(contentsOf: Disk.support.appendingPathComponent("recent-music.json"))
        let lastBook = try? Data(contentsOf: Disk.support.appendingPathComponent("last-book.json"))
        let effects = AudioEffects.shared.config
        defer {
            player.pause()
            if let seededCache { try? FileManager.default.removeItem(at: seededCache) }
            for plugin in store.plugins where !oldPlugins.contains(where: { $0.id == plugin.id }) { store.invalidate(plugin.id) }
            store.plugins = oldPlugins
            store.downloads = oldDownloads
            store.nowBook = oldBook
            if let bookProgressKey { defaults.removeObject(forKey: bookProgressKey) }
            if let bookSpeedKey { defaults.removeObject(forKey: bookSpeedKey) }
            if let lastBook { try? lastBook.write(to: Disk.support.appendingPathComponent("last-book.json")) }
            else { try? FileManager.default.removeItem(at: Disk.support.appendingPathComponent("last-book.json")) }
            for key in keys { if let value = previous[key] ?? nil { defaults.set(value, forKey: key) } else { defaults.removeObject(forKey: key) } }
            if let recent { try? recent.write(to: Disk.support.appendingPathComponent("recent-music.json")) }
            else if FileManager.default.fileExists(atPath: Disk.support.appendingPathComponent("recent-music.json").path) {
                try? FileManager.default.removeItem(at: Disk.support.appendingPathComponent("recent-music.json"))
            }
            AudioEffects.shared.config = effects
        }
        defaults.removeObject(forKey: "nav-url"); defaults.removeObject(forKey: "nav-user")
        defaults.set("flac", forKey: "quality"); defaults.set(cacheEnabled ? 16 : 0, forKey: "music-cache")
        AudioEffects.shared.config.enabled = false
        store.plugins = []; store.downloads = []; store.message = nil; player.attach(store)
        if switchFromBook {
            defaults.set(0, forKey: "book-cache")
            let id = UUID().uuidString
            bookSpeedKey = "speed:" + id
            let script = "registerSource({id:'book-fixture', name:'Book fixture', audio: () => ({url:'\(server.base)/audio.wav', headers:{'User-Agent':'Fixture',Cookie:'fixture=1'}})});"
            store.plugins.append(Plugin(id: id, name: "Book fixture", version: "1", script: script, address: "", books: true))
            let episode = MediaItem(["id": UUID().uuidString, "url": "chapter-1", "name": "Book PCM", "_plugin": id, "_book": id], source: "book-fixture", kind: "episode")
            bookProgressKey = "progress:" + episode.id
            player.play([episode])
            for _ in 0..<200 {
                if player.elapsed >= 0.8 || store.message != nil { break }
                try await Task.sleep(nanoseconds: 100_000_000)
            }
            XCTAssertNil(store.message, player.status); XCTAssertGreaterThan(player.elapsed, 0.5, player.status)
            player.speed = 1.5
        }
        let item: MediaItem
        if library {
            defaults.set("0", forKey: "nav-bitrate"); defaults.set("0", forKey: "nav-bitrate-wifi"); defaults.set("0", forKey: "nav-bitrate-mobile")
            // Unsigned simulator hosts cannot reliably write Keychain credentials.
            // Configure the fixture account directly, then use the production
            // Navidrome ping, account identity and stream resolution paths.
            defaults.set(server.base, forKey: "nav-url"); defaults.set("fixture", forKey: "nav-user")
            let nav = try XCTUnwrap(store.nav)
            _ = try await nav.get("ping")
            item = store.libraryItem(["id": UUID().uuidString, "title": "Navidrome PCM", "artist": "fixture"])
        } else {
            let script = """
            /**
             * @name Playback Fixture
             * @version 1.2.3
             */
            if (lx.currentScriptInfo.name !== 'Playback Fixture' || lx.currentScriptInfo.version !== '1.2.3') throw Error('Missing script metadata');
            lx.on('request', () => '\(server.base)/audio.wav');
            lx.send('inited',{sources:{wy:{qualitys:['flac']}}});
            """
            try await store.install(Data(script.utf8), name: "lx.js", address: "", books: false)
            XCTAssertEqual(store.plugins.first(where: { !$0.books })?.name, "Playback Fixture")
            item = MediaItem(["id": UUID().uuidString, "name": "LX PCM", "singer": "fixture", "lrc": "[00:00.00]Fixture"], source: "wy")
        }
        if let cachedFile {
            let url = Disk.directory(Disk.cache.appendingPathComponent("music")).appendingPathComponent(Disk.hash(store.cacheKey(item)))
            try cachedFile.write(to: url); seededCache = url
        }
        player.play([item])
        for _ in 0..<200 {
            if player.elapsed >= 0.8 || store.message != nil { break }
            try await Task.sleep(nanoseconds: 100_000_000)
        }
        XCTAssertNil(store.message, store.message ?? "")
        XCTAssertFalse(player.loading, player.status)
        XCTAssertGreaterThan(player.elapsed, 0.5, "AVPlayer clock did not advance: " + player.status)
        for _ in 0..<30 where player.audioInfo.sampleRate == 0 { try await Task.sleep(nanoseconds: 100_000_000) }
        XCTAssertEqual(player.audioInfo.format, "PCM")
        XCTAssertEqual(player.audioInfo.sampleRate, 44100, accuracy: 1)
        XCTAssertGreaterThan(player.audioInfo.bitsPerSecond, 0)
        XCTAssertGreaterThan(player.duration, 1)
        if cacheEnabled {
            let file = Disk.directory(Disk.cache.appendingPathComponent("music")).appendingPathComponent(Disk.hash(store.cacheKey(item)))
            seededCache = file
            for _ in 0..<100 {
                if player.resolutionNotice?.phase == .cached { break }
                try await Task.sleep(nanoseconds: 100_000_000)
            }
            XCTAssertEqual(player.resolutionNotice?.phase, .cached)
            XCTAssertTrue(FileManager.default.fileExists(atPath: file.path))
            server.stop(); player.play([item])
            for _ in 0..<150 {
                if player.elapsed > 0.5 || store.message != nil { break }
                try await Task.sleep(nanoseconds: 100_000_000)
            }
            XCTAssertNil(store.message, player.status); XCTAssertGreaterThan(player.elapsed, 0.5, player.status)
            XCTAssertEqual(player.status, "离线缓存")
        }
        if switchFromBook { XCTAssertEqual(player.current?.id, item.id); XCTAssertEqual(player.speed, 1) }
        if let seededCache, let cachedFile { XCTAssertEqual(try Data(contentsOf: seededCache), cachedFile, "Playback recovery must preserve the existing cache") }
        XCTAssertEqual(AVAudioSession.sharedInstance().category, .playback)
        XCTAssertEqual(AVAudioSession.sharedInstance().routeSharingPolicy, .longFormAudio)
        XCTAssertFalse(AVAudioSession.sharedInstance().categoryOptions.contains(.allowAirPlay))
        player.pause(); XCTAssertFalse(player.playing)
        player.resume(); XCTAssertTrue(player.playing)
    }
}
