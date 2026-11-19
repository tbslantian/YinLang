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
    private var connections: [NWConnection] = []
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
        let audio = target.hasPrefix("/audio.wav") || target.hasPrefix("/rest/stream.view")
        var body = audio ? wave : Data("{\"subsonic-response\":{\"status\":\"ok\",\"version\":\"1.16.1\"}}".utf8)
        var code = "200 OK", extra = ""
        if audio, let range = lines.first(where: { $0.lowercased().hasPrefix("range: bytes=") }) {
            let parts = range.split(separator: "=", maxSplits: 1).last!.split(separator: "-", omittingEmptySubsequences: false)
            let start = Int(parts.first ?? "") ?? 0
            let end = min(wave.count - 1, parts.count > 1 ? Int(parts[1]) ?? wave.count - 1 : wave.count - 1)
            if start >= 0 && start <= end {
                body = wave.subdata(in: start..<(end + 1)); code = "206 Partial Content"
                extra = "Content-Range: bytes \(start)-\(end)/\(wave.count)\r\n"
            } else { body = Data(); code = "416 Range Not Satisfiable"; extra = "Content-Range: bytes */\(wave.count)\r\n" }
        }
        let header = "HTTP/1.1 \(code)\r\nContent-Type: \(audio ? "audio/wav" : "application/json")\r\nContent-Length: \(body.count)\r\nAccept-Ranges: bytes\r\n\(extra)Connection: close\r\n\r\n"
        let head = request.hasPrefix("HEAD ")
        connection.send(content: Data(header.utf8) + (head ? Data() : body), isComplete: true, completion: .contentProcessed { _ in connection.cancel() })
    }
}

final class PlaybackTests: XCTestCase {
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

    @MainActor private func exercisePlayback(library: Bool) async throws {
        let server = try PlaybackServer(); try await server.start(); defer { server.stop() }
        let store = AppStore(), player = Player(), defaults = UserDefaults.standard
        let oldPlugins = store.plugins
        let keys = ["nav-url", "nav-user", "nav-bitrate", "nav-format", "quality", "music-cache"]
        let previous = Dictionary(uniqueKeysWithValues: keys.map { ($0, defaults.object(forKey: $0)) })
        let recent = try? Data(contentsOf: Disk.support.appendingPathComponent("recent-music.json"))
        let effects = AudioEffects.shared.config
        defer {
            player.pause()
            for plugin in store.plugins where !oldPlugins.contains(where: { $0.id == plugin.id }) { store.invalidate(plugin.id) }
            store.plugins = oldPlugins
            for key in keys { if let value = previous[key] ?? nil { defaults.set(value, forKey: key) } else { defaults.removeObject(forKey: key) } }
            if let recent { try? recent.write(to: Disk.support.appendingPathComponent("recent-music.json")) }
            else { try? FileManager.default.removeItem(at: Disk.support.appendingPathComponent("recent-music.json")) }
            AudioEffects.shared.config = effects
        }
        defaults.removeObject(forKey: "nav-url"); defaults.removeObject(forKey: "nav-user")
        defaults.set("flac", forKey: "quality"); defaults.set(0, forKey: "music-cache")
        AudioEffects.shared.config.enabled = false
        store.plugins = []; player.attach(store)
        let item: MediaItem
        if library {
            defaults.set("0", forKey: "nav-bitrate")
            try await store.connect(base: server.base, user: "fixture", password: "fixture")
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
            XCTAssertEqual(store.plugins.first?.name, "Playback Fixture")
            item = MediaItem(["id": UUID().uuidString, "name": "LX PCM", "singer": "fixture", "lrc": "[00:00.00]Fixture"], source: "wy")
        }
        player.play([item])
        for _ in 0..<200 {
            if player.elapsed >= 0.8 || store.message != nil { break }
            try await Task.sleep(nanoseconds: 100_000_000)
        }
        XCTAssertNil(store.message, store.message ?? "")
        XCTAssertFalse(player.loading, player.status)
        XCTAssertGreaterThan(player.elapsed, 0.5, "AVPlayer clock did not advance: " + player.status)
        XCTAssertGreaterThan(player.duration, 1)
        XCTAssertEqual(AVAudioSession.sharedInstance().category, .playback)
        XCTAssertEqual(AVAudioSession.sharedInstance().routeSharingPolicy, .longFormAudio)
        XCTAssertFalse(AVAudioSession.sharedInstance().categoryOptions.contains(.allowAirPlay))
        player.pause(); XCTAssertFalse(player.playing)
        player.resume(); XCTAssertTrue(player.playing)
    }
}
