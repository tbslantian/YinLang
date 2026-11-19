import XCTest
import Network
@testable import YinLang

final class CoreTests: XCTestCase {
    func testPlaybackBoundaries() {
        XCTAssertNil(PlayMode.sequence.nextIndex(current: 2, count: 3, manual: false))
        XCTAssertEqual(PlayMode.sequence.nextIndex(current: 2, count: 3, manual: true), 0)
        XCTAssertEqual(PlayMode.loop.nextIndex(current: 2, count: 3, manual: false), 0)
        XCTAssertEqual(PlayMode.single.nextIndex(current: 1, count: 3, manual: false), 1)
        XCTAssertEqual(PlayMode.single.nextIndex(current: 1, count: 3, manual: true), 2)
        for _ in 0..<50 { XCTAssertNotEqual(PlayMode.shuffle.nextIndex(current: 1, count: 3, manual: false), 1) }
        XCTAssertNil(PlayMode.loop.nextIndex(current: 0, count: 0, manual: true))
    }
    func testLyricsMultipleTimestampsAndFractions() {
        let rows = LyricLine.parse("[00:10.50][01:02.123]重复歌词\n[00:01.00]开头\n[ar:歌手]")
        XCTAssertEqual(rows.map(\.time), [1, 10.5, 62.123])
        XCTAssertEqual(rows[1].text, "重复歌词")
    }
    func testPlaylistPermissionFallback() {
        let nav = Navidrome(base: "https://example.com", user: "Alice", password: "secret")
        XCTAssertFalse(nav.canEdit([:]))
        XCTAssertFalse(nav.canEdit(["owner":"Other"]))
        XCTAssertTrue(nav.canEdit(["owner":"alice"]))
        XCTAssertFalse(nav.canEdit(["owner":"Alice", "readonly":true]))
        XCTAssertTrue(nav.canEdit(["owner":"Other", "readonly":false]))
    }
    func testCrossPlatformMatchingRejectsDifferentRecordings() {
        let wanted = MediaItem(["id":"a", "title":"晴天", "artist":"周杰伦", "duration":269])
        XCTAssertTrue(SongMatch.same(wanted, MediaItem(["id":"b", "title":"晴天", "artist":"周杰伦 / 合作者", "interval":"04:29"])))
        XCTAssertFalse(SongMatch.same(wanted, MediaItem(["id":"c", "title":"晴天 (Live)", "artist":"周杰伦", "duration":269])))
        XCTAssertFalse(SongMatch.same(wanted, MediaItem(["id":"d", "title":"晴天", "artist":"其他歌手", "duration":269])))
        XCTAssertFalse(SongMatch.same(wanted, MediaItem(["id":"e", "title":"晴天", "artist":"周杰伦", "duration":40])))
    }
    func testNavidromeAuthenticationEscaping() throws {
        let nav = Navidrome(base: "https://example.com/music/rest", user: "a+b&x", password: "secret")
        let parts = try XCTUnwrap(URLComponents(url: nav.endpoint("stream", [.init(name: "id", value: "song & 1")]), resolvingAgainstBaseURL: false))
        XCTAssertEqual(parts.path, "/music/rest/stream.view")
        XCTAssertEqual(parts.queryItems?.first { $0.name == "u" }?.value, "a+b&x")
        XCTAssertEqual(parts.queryItems?.first { $0.name == "id" }?.value, "song & 1")
        XCTAssertFalse(parts.url!.absoluteString.contains("secret"))
        XCTAssertEqual(parts.queryItems?.first { $0.name == "t" }?.value?.count, 32)
        XCTAssertThrowsError(try Navidrome(base: "file:///etc/passwd", user: "x", password: "").endpoint("ping"))
    }
    func testCompressionAndAESRoundTrip() throws {
        let data = Data(String(repeating: "音浪 music ", count: 200).utf8)
        XCTAssertEqual(try PluginArchive.decompress(PluginArchive.compress(data)), data)
        let key = Data("0123456789abcdef".utf8), iv = Data(repeating: 1, count: 16)
        for mode in ["ECB", "CBC", "CTR", "CFB", "OFB"] {
            let encoded = try NativeCrypto.aes(data, key: key, iv: iv, mode: mode)
            XCTAssertEqual(try NativeCrypto.aes(encoded, key: key, iv: iv, mode: mode, decrypt: true), data)
        }
    }
    func testBundledZIP() throws {
        let url = try XCTUnwrap(Bundle.main.url(forResource: "Sources-v5.7", withExtension: "zip", subdirectory: "Runtime"))
        let plugin = try PluginArchive.read(Data(contentsOf: url), name: "test", address: "", books: true)
        XCTAssertEqual(plugin.version, "5.7"); XCTAssertTrue(plugin.script.contains("registerSource"))
        XCTAssertThrowsError(try PluginArchive.read(Data("invalid".utf8), name: "test", address: "", books: true))
    }
    @MainActor func testWKRuntimeAndCryptoBridge() async throws {
        let runtime = ScriptRuntime(scope: "test"); defer { runtime.close() }
        try await runtime.start("lx.on('request', () => 'https://example.com/audio.mp3'); lx.send('inited',{sources:{wy:{qualitys:['320k']}}});")
        let result = try await runtime.call("lxResolve('wy','320k',{name:'Test'})") as? JSONObject
        XCTAssertEqual(result?.string("url"), "https://example.com/audio.mp3")
        let digest = try await runtime.call("Native.digest('MD5','hello')") as? String
        XCTAssertEqual(digest, "5d41402abc4b2a76b9719d911017c592")
        let book = try await runtime.call("(()=>{registerSource({id:'test',name:'Test',categoryMenus:()=>[{title:'Radio',tabs:[{title:'Live',url:'radio:live'}]}]});return bookSources()})()") as? [JSONObject]
        XCTAssertEqual(book?.first?.string("id"), "test")
    }
    @MainActor func testSynchronousBookAndAsynchronousMusicHTTPTransport() async throws {
        let listener = try NWListener(using: .tcp, on: .any)
        listener.newConnectionHandler = { connection in
            connection.start(queue: .global())
            connection.receive(minimumIncompleteLength: 1, maximumLength: 16384) { _, _, _, _ in
                let body = "{\"ok\":true,\"title\":\"音浪\"}"
                let response = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: \(body.utf8.count)\r\nConnection: close\r\n\r\n" + body
                connection.send(content: Data(response.utf8), isComplete: true, completion: .contentProcessed { _ in connection.cancel() })
            }
        }
        let port: UInt16 = try await withCheckedThrowingContinuation { continuation in
            listener.stateUpdateHandler = { state in
                if case .ready = state { listener.stateUpdateHandler = nil; continuation.resume(returning: listener.port!.rawValue) }
                if case .failed(let error) = state { listener.stateUpdateHandler = nil; continuation.resume(throwing: error) }
            }; listener.start(queue: .global())
        }
        defer { listener.cancel() }
        let runtime = ScriptRuntime(scope: "network-test"); defer { runtime.close() }
        try await runtime.start("")
        let address = "http://127.0.0.1:\(port)/test"
        let sync = try await runtime.call("JSON.parse(Native.network(\(quote(address)), '{}'))") as? JSONObject
        XCTAssertEqual(sync?["status"] as? Int, 200)
        XCTAssertTrue(sync?.string("body").contains("音浪") == true)
        let async = try await runtime.call("catalogTransport(\(quote(address)),{method:'GET'})") as? JSONObject
        XCTAssertEqual(async?["status"] as? Int, 200)
        XCTAssertTrue(async?.string("body").contains("音浪") == true)
    }
}
