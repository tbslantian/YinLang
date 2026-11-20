import XCTest
import Network
import UIKit
@testable import YinLang

final class CoreTests: XCTestCase {
    func testLibraryTrackSortMatchesAndroidFieldsDirectionAndMissingDates() {
        let a = MediaItem(["id":"a", "title":"Alpha", "artist":"Z", "album":"One", "created":"2020-01-01", "duration":120],source:"library")
        let b = MediaItem(["id":"b", "title":"Beta", "artist":"A", "album":"Two", "created":"2021-01-01", "playlistAddedAt":"2025-02-01T10:00:00.000Z", "duration":60],source:"library")
        let unknown = MediaItem(["id":"c", "title":"Gamma", "artist":"A", "album":"Two", "duration":180],source:"library")
        let rows = [unknown, a, b]
        XCTAssertEqual(LibraryTrackSort.original.apply(rows, descending:true).map(\.key), ["c","a","b"])
        XCTAssertEqual(LibraryTrackSort.added.apply(rows, descending:true).map(\.key), ["b","a","c"])
        XCTAssertEqual(LibraryTrackSort.added.apply(rows, descending:false).map(\.key), ["a","b","c"])
        XCTAssertEqual(LibraryTrackSort.name.apply(rows, descending:false).map(\.key), ["a","b","c"])
        XCTAssertEqual(LibraryTrackSort.artist.apply(rows, descending:false).map(\.key), ["b","c","a"])
        XCTAssertEqual(LibraryTrackSort.album.apply(rows, descending:true).map(\.key), ["c","b","a"])
        XCTAssertEqual(LibraryTrackSort.duration.apply(rows, descending:false).map(\.key), ["b","a","c"])
        XCTAssertNotNil(LibraryTrackSort.playlistDate(b))
    }
    func testLibraryPlaylistOwnershipMatchesAndroid() {
        let nav = Navidrome(base: "https://example.com", user: "Alice", password: "")
        XCTAssertTrue(nav.ownsPlaylist(["id":"mine", "owner":"alice"]))
        XCTAssertTrue(nav.ownsPlaylist(["id":"legacy"]))
        XCTAssertFalse(nav.ownsPlaylist(["id":"shared", "owner":"Bob", "public":true]))
    }
    @MainActor func testArtworkCachePersistsDeduplicatesAndIgnoresNavidromeSalt() async throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: folder) }
        let renderer = UIGraphicsImageRenderer(size: CGSize(width: 8, height: 8))
        let png = renderer.pngData { context in UIColor.red.setFill(); context.fill(CGRect(x: 0, y: 0, width: 8, height: 8)) }
        ArtworkFixtureProtocol.reset(png)
        let config = URLSessionConfiguration.ephemeral; config.protocolClasses = [ArtworkFixtureProtocol.self]
        let session = URLSession(configuration: config); defer { session.invalidateAndCancel() }
        let url = URL(string: "https://images.example.com/rest/getCoverArt.view?u=alice&id=album&size=400&t=one&s=first")!
        let rotated = URL(string: "https://images.example.com/rest/getCoverArt.view?u=alice&id=album&size=400&t=two&s=second")!
        let cache = ArtworkCache(directory: folder, session: session)
        async let first = cache.image(url)
        async let concurrent = cache.image(rotated)
        let pair = await (first, concurrent)
        XCTAssertNotNil(pair.0); XCTAssertNotNil(pair.1); XCTAssertEqual(ArtworkFixtureProtocol.calls, 1)
        XCTAssertNotNil(cache.memoryImage(rotated))
        let relaunched = ArtworkCache(directory: folder, session: session)
        let disk = await relaunched.image(rotated)
        XCTAssertNotNil(disk); XCTAssertEqual(ArtworkFixtureProtocol.calls, 1, "重进页面或重启必须读取持久缓存")
        let other = URL(string: "https://images.example.com/rest/getCoverArt.view?u=bob&id=album&size=400&t=two&s=second")!
        XCTAssertNotEqual(ArtworkCache.key(url), ArtworkCache.key(other))
        let otherImage = await relaunched.image(other)
        XCTAssertNotNil(otherImage); XCTAssertEqual(ArtworkFixtureProtocol.calls, 2)
        let online = URL(string: "https://images.example.com/playlist.jpg")!
        let onlineImage = await cache.image(online)
        XCTAssertNotNil(onlineImage)
        let onlineRelaunch = await ArtworkCache(directory: folder, session: session).image(online)
        XCTAssertNotNil(onlineRelaunch); XCTAssertEqual(ArtworkFixtureProtocol.calls, 3, "在线封面同样持久缓存")
    }
    func testNavidromeCoverURLStaysStableAndSeparatesCredentials() throws {
        let base = "https://" + UUID().uuidString + ".example.com"
        let account = Navidrome(base: base, user: "first", password: "secret")
        let cover = account.cover("album", size: 400)
        for _ in 0..<20 { XCTAssertEqual(Navidrome(base: base, user: "first", password: "secret").cover("album", size: 400), cover) }
        XCTAssertNotEqual(Navidrome(base: base, user: "second", password: "secret").cover("album"), cover)
        XCTAssertNotEqual(Navidrome(base: base, user: "first", password: "changed").cover("album"), cover)
        XCTAssertNotEqual(account.cover("other"), cover)
        XCTAssertNotEqual(account.cover("album", size: 800), cover)
        XCTAssertEqual(account.cover(""), "")
    }
    func testNextTrackPreparationDefaultsAndSeventyPercentBoundary() throws {
        let name = "next-track-" + UUID().uuidString
        let defaults = try XCTUnwrap(UserDefaults(suiteName: name))
        defer { defaults.removePersistentDomain(forName: name) }
        let initial = NextTrackSettings(defaults: defaults)
        XCTAssertTrue(initial.preparse); XCTAssertFalse(initial.precache)
        XCTAssertFalse(initial.shouldStart(elapsed: 69.99, duration: 100))
        XCTAssertTrue(initial.shouldStart(elapsed: 70, duration: 100))
        XCTAssertFalse(initial.shouldStart(elapsed: 70, duration: 0))
        XCTAssertFalse(initial.shouldStart(elapsed: .infinity, duration: 100))
        defaults.set(false, forKey: NextTrackSettings.parseKey)
        XCTAssertFalse(NextTrackSettings(defaults: defaults).enabled)
        defaults.set(true, forKey: NextTrackSettings.cacheKey)
        XCTAssertTrue(NextTrackSettings(defaults: defaults).enabled)
        XCTAssertTrue(NextTrackSettings(defaults: defaults).shouldStart(elapsed: 70, duration: 100))
        XCTAssertEqual(CacheDownloadSettings.threads(defaults: defaults), 3)
        defaults.set(99, forKey: CacheDownloadSettings.key)
        XCTAssertEqual(CacheDownloadSettings.threads(defaults: defaults), 5)
        defaults.set(0, forKey: CacheDownloadSettings.key)
        XCTAssertEqual(CacheDownloadSettings.threads(defaults: defaults), 1)
    }
    func testAudioParametersKeepUnitsAndActualValues() {
        XCTAssertEqual(AudioStreamInfo(format: "MP3", bitsPerSecond: 320000, sampleRate: 44100).text, "MP3 · 320 kbps · 44.1 kHz")
        XCTAssertEqual(AudioStreamInfo(format: "FLAC", bitsPerSecond: 1411200, sampleRate: 96000).text, "FLAC · 96 kHz")
        XCTAssertEqual(AudioStreamInfo().text, "格式未知 · -- kbps · -- kHz")
        XCTAssertEqual(AudioStreamInfo(bitsPerSecond: .nan, sampleRate: .infinity).text, "格式未知 · -- kbps · -- kHz")
    }
    func testBookSourceSelectionRestoresLastSourceAndFallsBackWhenUnavailable() {
        let first: JSONObject = ["id":"one", "_plugin":"plugin-a"]
        let last: JSONObject = ["id":"two", "_plugin":"plugin-a"]
        let duplicate: JSONObject = ["id":"two", "_plugin":"plugin-b"]
        let all = [first, last, duplicate]
        XCTAssertEqual(BookSourceSelection.restore(current: "", saved: "", sources: all), BookSourceSelection.key(first))
        XCTAssertEqual(BookSourceSelection.restore(current: "", saved: BookSourceSelection.key(last), sources: all), BookSourceSelection.key(last))
        XCTAssertEqual(BookSourceSelection.restore(current: "", saved: BookSourceSelection.key(duplicate), sources: all), BookSourceSelection.key(duplicate))
        XCTAssertEqual(BookSourceSelection.restore(current: "", saved: BookSourceSelection.key(last), sources: [first]), BookSourceSelection.key(first))
        XCTAssertEqual(BookSourceSelection.restore(current: BookSourceSelection.key(last), saved: BookSourceSelection.key(first), sources: all), BookSourceSelection.key(last))
        XCTAssertEqual(BookSourceSelection.restore(current: "", saved: BookSourceSelection.key(last), sources: []), "")
    }
    func testCatalogColumnsKeepPhoneCardsReadableAndAdaptTabletWindows() {
        XCTAssertEqual(CatalogLayout.columns(preferred: 0, width: 390, tablet: false), 2)
        XCTAssertEqual(CatalogLayout.columns(preferred: 4, width: 390, tablet: false), 2)
        XCTAssertEqual(CatalogLayout.columns(preferred: 4, width: 844, tablet: false), 2)
        XCTAssertEqual(CatalogLayout.columns(preferred: 0, width: 1024, tablet: true), 4)
        XCTAssertEqual(CatalogLayout.columns(preferred: 0, width: 1440, tablet: true), 4)
        XCTAssertEqual(CatalogLayout.columns(preferred: 4, width: 500, tablet: true), 3)
        XCTAssertEqual(CatalogLayout.columns(preferred: 0, width: 500, tablet: true), 2)
        XCTAssertEqual(CatalogLayout.columns(preferred: 4, width: 280, tablet: true), 1)
        XCTAssertEqual(CatalogLayout.columns(preferred: 1, width: 1024, tablet: true), 1)
        for tablet in [false, true] {
            for width in [CGFloat(320), 390, 500, 768, 1024, 1440] {
                let count = CatalogLayout.columns(preferred: 4, width: width, tablet: tablet)
                let cardWidth = (width - 32 - CGFloat(count - 1) * 8) / CGFloat(count)
                XCTAssertGreaterThanOrEqual(cardWidth, 140)
            }
        }
    }
    @MainActor func testJavaScriptDispatchIgnoresPromiseReturnAndKeepsMessageResults() async throws {
        let runtime = ScriptRuntime(scope: "dispatch-regression"); defer { runtime.close() }
        try await runtime.start("""
        const dispatch = SoundShelfRuntime.execute;
        SoundShelfRuntime.execute = (...args) => {
          setTimeout(() => dispatch(...args), 30);
          return Promise.resolve(() => 'not bridgeable');
        };
        """)
        let value = try await runtime.call("Promise.resolve({url:'https://example.com/audio.mp3', headers:{Referer:'https://example.com'}})") as? JSONObject
        XCTAssertEqual(value?.string("url"), "https://example.com/audio.mp3")
        XCTAssertEqual(value?.object("headers").string("Referer"), "https://example.com")
        do { _ = try await runtime.call("Promise.reject(Error('expected plugin error'))"); XCTFail("Expected plugin failure") }
        catch { XCTAssertTrue(error.localizedDescription.contains("expected plugin error")) }
        let next = try await runtime.call("Promise.resolve(42)") as? Int
        XCTAssertEqual(next, 42)
    }
    @MainActor func testBundledFivePlatformBoardsUseAPIIdentifiers() async throws {
        let url = try XCTUnwrap(Bundle.main.url(forResource: "catalog.bundle", withExtension: "js", subdirectory: "Runtime"))
        let runtime = ScriptRuntime(scope: "chart-regression"); defer { runtime.close() }
        try await runtime.start(String(contentsOf: url, encoding: .utf8))
        for source in ["wy", "tx", "kg", "kw", "mg"] {
            let result = try await runtime.call("SoundShelfCatalog('charts',{source:\(quote(source))})") as? JSONObject ?? [:]
            let rows = result.rows("list")
            XCTAssertFalse(rows.isEmpty, source)
            for row in rows {
                let item = MediaItem(row, kind: "chart")
                XCTAssertEqual(item.catalogID, row.string("bangid"), source)
                XCTAssertFalse(item.catalogID.hasPrefix(source + "__"), source)
            }
        }
    }
    func testChartAPIIdentifiersPreserveSavedIdentity() throws {
        for (source, number) in [("wy", "19723756"), ("tx", "4"), ("kg", "8888"), ("kw", "93"), ("mg", "27553319")] {
            let chart = MediaItem(["id": source + "__" + number, "bangid": number, "source": source], kind: "chart")
            XCTAssertEqual(chart.catalogID, number)
            XCTAssertEqual(chart.key, source + "__" + number)
            let restored = try JSONDecoder().decode(MediaItem.self, from: JSONEncoder().encode(chart))
            XCTAssertEqual(restored.catalogID, number)
            XCTAssertEqual(restored.id, chart.id)
            XCTAssertEqual(MediaItem(["id": source + "__" + number, "source": source], kind: "chart").catalogID, number)
        }
    }
    func testPlaylistShareLinksAndFragments() throws {
        let cases = [
            ("wy", "https://music.163.com/#/playlist?id=123", "123"),
            ("tx", "分享歌单 https://y.qq.com/n/ryqq/playlist/456", "https://y.qq.com/n/ryqq/playlist/456"),
            ("kg", "https://www.kugou.com/yy/special/single/789.html", "https://www.kugou.com/yy/special/single/789.html"),
            ("kw", "https://www.kuwo.cn/playlist_detail/321", "https://www.kuwo.cn/playlist_detail/321"),
            ("mg", "https://music.migu.cn/v3/music/playlist/654", "https://music.migu.cn/v3/music/playlist/654")
        ]
        for (source, input, expected) in cases {
            let item = try PlaylistLink.item(input, platform: "all")
            XCTAssertEqual(item.source, source); XCTAssertEqual(item.key, expected)
            XCTAssertEqual(try PlaylistLink.item(input, platform: "all"), item)
        }
        XCTAssertEqual(try PlaylistLink.item("https://c6.y.qq.com/base/fcgi-bin/u?__=abc", platform: "tx").key, "https://c6.y.qq.com/base/fcgi-bin/u?__=abc")
        XCTAssertEqual(try PlaylistLink.item("https://y.qq.com/a?disstid=42", platform: "tx").key, "42")
        XCTAssertEqual(try PlaylistLink.item("123", platform: "wy").key, "123")
        XCTAssertThrowsError(try PlaylistLink.item("123", platform: "all"))
        XCTAssertThrowsError(try PlaylistLink.item("", platform: "wy"))
    }
    func testResumeBookRebuildsWholeQueueIncludingLegacyProgress() throws {
        let book = MediaItem(["bookUrl": "https://example.com/book/1", "name": "书籍", "_plugin": "plugin"], source: "fixture", kind: "book")
        let detail: JSONObject = ["episodes": (1...3).map { ["url": "https://example.com/chapter/\($0)", "name": "第\($0)章"] }]
        let episodes = BookPlayback.episodes(detail, book: book)
        let saved = episodes[1]
        let recovered = try BookPlayback.book(for: saved, shelf: [])
        XCTAssertEqual(recovered.key, book.key)
        XCTAssertEqual(recovered.raw.string("_plugin"), "plugin")
        let index = try BookPlayback.resumeIndex(saved, episodes: episodes)
        XCTAssertEqual(index, 1)
        XCTAssertEqual(PlayMode.sequence.nextIndex(current: index, count: episodes.count, manual: false), 2)
        var legacy = saved.raw; legacy.removeValue(forKey: "_bookURL")
        XCTAssertEqual(try BookPlayback.book(for: MediaItem(legacy, source: "fixture", kind: "episode"), shelf: []).key, book.key)
        XCTAssertThrowsError(try BookPlayback.resumeIndex(saved, episodes: [episodes[0]]))
    }
    func testMusicInfoSupportsRootAndMetaPlugins() {
        let item = MediaItem(["songmid": "mid", "songId": 42, "name": "歌曲", "singer": "歌手", "albumName": "专辑", "types": [["type": "320k"]], "_types": ["320k": ["size": 123]], "strMediaMid": "media"], source: "tx")
        let info = item.onlineMusicInfo
        XCTAssertEqual(info.object("meta").string("id"), "42")
        XCTAssertEqual(info.object("meta").string("songId"), "mid")
        XCTAssertEqual(info.object("meta").rows("qualitys").first?.string("type"), "320k")
        XCTAssertEqual(info.object("meta").string("strMediaMid"), "media")
        let nested = MediaItem(["name": "歌曲", "meta": ["songId": "nested", "albumName": "专辑", "hash": "hash", "qualitys": [["type": "flac"]]]], source: "kg").onlineMusicInfo
        XCTAssertEqual(nested.string("songmid"), "nested"); XCTAssertEqual(nested.string("hash"), "hash")
        XCTAssertEqual(nested.rows("types").first?.string("type"), "flac")
    }
    @MainActor func testRedownloadReplacesOldSameFormatAndBypassesLocalFiles() async throws {
        let fresh = Data("ID3new-selected-quality-audio".utf8)
        let listener = try NWListener(using: .tcp, on: .any)
        listener.newConnectionHandler = { connection in
            connection.start(queue: .global())
            connection.receive(minimumIncompleteLength: 1, maximumLength: 16384) { _, _, _, _ in
                let header = "HTTP/1.1 200 OK\r\nContent-Type: audio/mpeg\r\nContent-Length: \(fresh.count)\r\nConnection: close\r\n\r\n"
                connection.send(content: Data(header.utf8) + fresh, isComplete: true, completion: .contentProcessed { _ in connection.cancel() })
            }
        }
        let port: UInt16 = try await withCheckedThrowingContinuation { continuation in
            listener.stateUpdateHandler = { state in
                if case .ready = state { listener.stateUpdateHandler = nil; continuation.resume(returning: listener.port!.rawValue) }
                if case .failed(let error) = state { listener.stateUpdateHandler = nil; continuation.resume(throwing: error) }
            }; listener.start(queue: .global())
        }
        defer { listener.cancel() }
        let store = AppStore(), defaults = UserDefaults.standard
        let oldPlugins = store.plugins, oldDownloads = store.downloads, oldQuality = defaults.object(forKey: "quality")
        let item = MediaItem(["id": UUID().uuidString, "name": "下载回归", "singer": "测试"], source: "wy")
        let oldFile = UUID().uuidString + ".mp3", oldURL = Disk.documents.appendingPathComponent(oldFile)
        let cache = Disk.directory(Disk.cache.appendingPathComponent("music")).appendingPathComponent(Disk.hash(store.cacheKey(item, quality: "320k")))
        try Data("ID3old-low-quality".utf8).write(to: oldURL)
        try Data("ID3old-cache".utf8).write(to: cache)
        defer {
            for download in store.downloads where download.item.id == item.id { try? FileManager.default.removeItem(at: Disk.documents.appendingPathComponent(download.filename)) }
            try? FileManager.default.removeItem(at: oldURL); try? FileManager.default.removeItem(at: cache)
            for plugin in store.plugins where !oldPlugins.contains(where: { $0.id == plugin.id }) { store.invalidate(plugin.id) }
            store.plugins = oldPlugins; store.downloads = oldDownloads
            if let oldQuality { defaults.set(oldQuality, forKey: "quality") } else { defaults.removeObject(forKey: "quality") }
        }
        defaults.set("320k", forKey: "quality")
        store.plugins = []; store.downloads = [.init(item: item, filename: oldFile)]
        let script = "lx.on('request', ({info}) => { if (!info.musicInfo.meta || info.type !== '320k') throw Error('missing compatibility fields'); return 'http://127.0.0.1:\(port)/audio'; }); lx.send('inited',{sources:{wy:{qualitys:['320k']}}});"
        try await store.install(Data(script.utf8), name: "fixture", address: "", books: false)
        await store.download(item)
        let downloaded = try XCTUnwrap(store.downloads.first)
        XCTAssertNotEqual(downloaded.filename, oldFile)
        XCTAssertEqual(try Data(contentsOf: Disk.documents.appendingPathComponent(downloaded.filename)), fresh)
        XCTAssertFalse(FileManager.default.fileExists(atPath: oldURL.path))
    }
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
