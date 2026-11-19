import SwiftUI

// Screenshot data is compiled only into Debug test hosts. Release builds contain
// neither these routes nor fixture service responses.
#if DEBUG
enum UITestFixtures {
    static var active: Bool { ProcessInfo.processInfo.arguments.contains("-ui-fixtures") }
    static var screen: String { let args = ProcessInfo.processInfo.arguments; guard let index = args.firstIndex(of: "-ui-screen"), args.indices.contains(index + 1) else { return "home" }; return args[index + 1] }
    static var songs: [JSONObject] { (1...8).map { index in ["id":"song-\(index)","name":"风继续吹 · \(index)","title":"风继续吹 · \(index)","singer":"张国荣","artist":"张国荣","artistId":"artist-1","artists":[["id":"artist-1","name":"张国荣"]],"album":"经典音乐精选","albumId":"album-1","source":"wy","img":"fixture:home_music_light","coverArt":"album-1","suffix":"flac","bitRate":960,"samplingRate":96000,"bitDepth":24,"duration":245,"interval":"4:05","types":[["type":"flac"]],"lrc":"[00:00.00]让晚风轻轻吹过\n[00:12.00]记忆里的那首歌\n[00:24.00]风继续吹\n[00:36.00]不忍远离\n[00:48.00]心里亦有泪\n[01:00.00]不愿流泪望着你"] as JSONObject } }
    static var albums: [JSONObject] { (1...8).map { ["id":"album-\($0)","name":["经典音乐精选","似水流年","漫步人生路","音乐与故事"][($0-1)%4],"title":"经典音乐精选","artist":"张国荣","coverArt":"album-\($0)","img":"fixture:home_music_dark","songCount":12] as JSONObject } }
    static var playlists: [JSONObject] { (1...4).map { ["id":"playlist-\($0)","name":["华语经典","随心听","旅行音乐","夜色温柔"][$0-1],"source":"wy","img":"fixture:home_music_light","owner":"fixture","songCount":24,"comment":"那些值得反复聆听的旋律","public":false] as JSONObject } }
    static var books: [JSONObject] { (1...8).map { ["bookUrl":"book-\($0)","name":["三体","平凡的世界","长安十二时辰","听见故事"][($0-1)%4],"author":"有声书精选","artist":"有声书精选","coverUrl":"fixture:home_books_light","_plugin":"fixture-book","_source":"fixture-source"] as JSONObject } }
    static var book: MediaItem { MediaItem(books[0], source:"fixture-source", kind:"book") }
    static var source: JSONObject { ["id":"fixture-source","name":"有声书精选","group":"听书","search":true,"discover":true,"_plugin":"fixture-book","config":[["key":"example","label":"显示名称","type":"text","default":"音浪"]]] }
    static var bookSources: [JSONObject] {
        guard ProcessInfo.processInfo.arguments.contains("-ui-multiple-book-sources") else { return [source] }
        var second = source; second["id"] = "fixture-source-two"; second["name"] = "备用听书源"
        return [source, second]
    }
    @MainActor static func prepare(_ store: AppStore, _ player: Player) {
        guard active else { return }
        if ProcessInfo.processInfo.arguments.contains("-ui-reset-next-settings") {
            UserDefaults.standard.removeObject(forKey: NextTrackSettings.parseKey); UserDefaults.standard.removeObject(forKey: NextTrackSettings.cacheKey)
        }
        if ProcessInfo.processInfo.arguments.contains("-ui-reset-library-order") {
            UserDefaults.standard.removeObject(forKey: "playlist-order:" + Navidrome(base: "https://fixture.yinlang.invalid", user: "fixture", password: "").identity)
        }
        UserDefaults.standard.set("https://fixture.yinlang.invalid", forKey:"nav-url"); UserDefaults.standard.set("fixture", forKey:"nav-user"); UserDefaults.standard.set("私人音乐库", forKey:"nav-alias")
        // Root onAppear can run after a child .task. Notify account-bound
        // pages after installing the fixture account so they reload it.
        store.connectionRevision += 1
        store.plugins = [Plugin(id:"fixture-lx",name:"测试音源",version:"1.2.3",script:"/**\n * @name 测试音源\n */",address:"",books:false),Plugin(id:"fixture-book",name:"随身听书公开源合集",version:"5.7",script:"",address:"",books:true)]
        store.liked = songs.prefix(4).map { MediaItem($0, source:"wy") }; store.followed = playlists.map { MediaItem($0, kind:"playlist") }; store.bookshelf = books.prefix(4).map { MediaItem($0, source:"fixture-source", kind:"book") }; store.message = nil
        if ["player","lyrics","queue","song-menu","player-menu"].contains(screen) || ProcessInfo.processInfo.arguments.contains("-ui-mini-player") { player.queue = store.liked; player.duration = 245; player.elapsed = 62; player.buffered = 160; player.status = "FLAC · 24 bit · 96 kHz"; player.lyricText = songs[0].string("lrc"); player.lyrics = LyricLine.parse(player.lyricText) }
        if player.current != nil { player.audioInfo = AudioStreamInfo(format: "FLAC", bitsPerSecond: 960000, sampleRate: 96000) }
        if ProcessInfo.processInfo.arguments.contains("-ui-mini-loading") { player.loading = true; player.publishResolution(ResolutionNotice(phase: .resolving, detail: "测试音源 · 网易云 · 320k")) }
    }
    static func catalog(_ operation: String, args: JSONObject = [:]) -> JSONObject {
        if operation == "list", ProcessInfo.processInfo.arguments.contains("-ui-bottom-audit") {
            let page = args["page"] as? Int ?? 1
            let items = songs.map { row -> JSONObject in
                var item = row; item["id"] = "page-\(page)-" + row.string("id")
                if page > 1 { item["name"] = "第\(page)页 · " + row.string("name"); item["title"] = item["name"] }
                return item
            }
            return ["list":items,"more":page == 1]
        }
        switch operation {
        case "charts": return ["list":[["id":"wy__19723756","bangid":"19723756","name":"飙升榜","source":"wy","img":"fixture:home_music_light"],["id":"wy__3778678","bangid":"3778678","name":"热歌榜","source":"wy","img":"fixture:home_music_dark"]]]
        case "lists": return ["list":playlists,"more":false]
        case "chart-cover": return ["list":[["img":"fixture:home_music_light"]]]
        case "tags": return ["list":[["id":"all","name":"华语"],["id":"classic","name":"经典"]]]
        default: return ["list":songs,"more":false]
        }
    }
    static func nav(_ command: String) -> JSONObject {
        switch command {
        case "getScanStatus": return ["scanStatus":["count":8,"scanning":false]]
        case "getPlaylists":
            let rows = ProcessInfo.processInfo.arguments.contains("-ui-many-playlists") ? (1...24).map { index -> JSONObject in var row = playlists[(index - 1) % 4]; row["id"] = "playlist-\(index)"; return row } : playlists
            return ["playlists":["playlist":rows]]
        case "getPlaylist": var row = playlists[0]; row["entry"] = songs; return ["playlist":row]
        case "getRandomSongs": return ["randomSongs":["song":songs]]
        case "search3": return ["searchResult3":["song":songs]]
        case "getAlbumList2": return ["albumList2":["album":albums]]
        case "getAlbum": return ["album":["song":songs]]
        case "getArtist": return ["artist":["album":albums]]
        case "getArtists": return ["artists":["index":[["artist":[["id":"artist-1","name":"张国荣"]]]]]]
        case "getStarred2": return ["starred2":["song":songs]]
        case "getSong": return ["song":songs[0]]
        default: return ["status":"ok"]
        }
    }
    static func bookCall(_ action: String) -> Any {
        switch action {
        case "categoryMenus": return [["title":"听书","tabs":[["title":"热门推荐","url":"category-hot"],["title":"文学名著","url":"category-books"]]],["title":"广播","tabs":[["title":"广播电台","url":"radio"]]]]
        case "categoryPage", "search": return ["books":books,"totalPage":1]
        case "bookDetail": return ["name":"三体","description":"在声音里，遇见另一个世界。继续上次的故事，或从第一章开始聆听。","episodes":(1...12).map { ["url":"chapter-\($0)","name":"第\($0)章 · 故事开始"] }]
        default: return [:] as JSONObject
        }
    }
}
struct UITestScreen: View {
    @EnvironmentObject var store: AppStore
    @EnvironmentObject var player: Player
    @ViewBuilder var body: some View {
        switch UITestFixtures.screen {
        case "music": MusicView()
        case "charts": CatalogView(mode:"charts")
        case "playlists": CatalogView(mode:"lists")
        case "playlist-detail": CatalogDetailView(item:MediaItem(UITestFixtures.playlists[0],kind:"playlist"),chart:false)
        case "liked": SavedView(title:"我喜欢的音乐",type:"liked")
        case "library": LibraryView()
        case "discovery": LibraryDiscoveryView()
        case "songs": LibraryListView(kind:"songs",title:"歌曲")
        case "song-details": SongDetailsView(item:store.libraryItem(UITestFixtures.songs[0]))
        case "connection": ConnectionView()
        case "books": BooksView()
        case "book-discovery": BookBrowseView(search:false)
        case "book-detail": BookDetailView(book:UITestFixtures.book)
        case "settings": SettingsView()
        case "personalization": PersonalizationView()
        case "quality": LibraryAudioQualityView()
        case "cache": CacheSettingsView()
        case "plugins": PluginSettingsView(books:false)
        case "book-plugins": PluginSettingsView(books:true)
        case "source-settings": BookSourceSettings(source:UITestFixtures.source)
        case "downloads": DownloadsView()
        case "server": ServerSettingsView()
        case "about": AboutView()
        case "effects": EffectsView()
        case "player", "lyrics": Color.clear.onAppear { player.showPlayer = true }
        case "queue": QueueView()
        case "song-menu": SongActionsView(item:store.libraryItem(UITestFixtures.songs[0]))
        case "player-menu": PlayerMenuView()
        default: HomeView()
        }
    }
}
#endif
