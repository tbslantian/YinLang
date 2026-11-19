import SwiftUI
import WebKit

struct BooksView: View {
    @EnvironmentObject var store: AppStore
    @EnvironmentObject var player: Player
    var body: some View {
        AndroidForm {
            NavigationLink { BookBrowseView(search: true) } label: { SearchEntry(title: "搜索有声书") }.buttonStyle(.plain)
            GeometryReader { proxy in
                HStack(spacing: 10) {
                    NavigationLink { BookBrowseView(search: false) } label: { DiscoveryHero(books: true) }.frame(width: (proxy.size.width - 10) * 1.3 / 2.3)
                    VStack(spacing: 8) {
                        NavigationLink { SavedView(title: "书架", type: "bookshelf") } label: { LibraryShortcut(title: "书架", icon: "books.vertical") }
                        NavigationLink { PluginSettingsView(books: true) } label: { LibraryShortcut(title: "插件", icon: "puzzlepiece.extension") }
                        NavigationLink { BookSettingsView() } label: { LibraryShortcut(title: "听书设置", icon: "gearshape") }
                    }
                }
            }.frame(height: 184).buttonStyle(.plain)
            if let current = store.nowBook { SectionTitle(title: "继续收听"); SongRow(item: current) { player.resumeBook(current) } }
            SectionTitle(title: "我的收藏")
            BookGrid(rows: Array(store.bookshelf.prefix(6)))
            if store.bookshelf.isEmpty { Text("在书籍详情里加入书架，喜欢的故事会出现在这里。").font(.system(size: 14)).foregroundStyle(Palette.muted) }
        }.androidPage("听书", category: "有声书").task { await store.installBundledBooks() }
    }
}

struct BookBrowseView: View {
    @EnvironmentObject var store: AppStore
    var search: Bool
    @State private var sources: [JSONObject] = []
    @State private var sourceID = ""
    @State private var menus: [JSONObject] = []
    @State private var category = ""
    @State private var menuIndex = "0"
    @State private var nextPage = ""
    @State private var seenPages: Set<String> = []
    @State private var query = ""
    @State private var rows: [MediaItem] = []
    @State private var loading = false
    @State private var error = ""
    @State private var grid = true
    @State private var page = 1
    @State private var more = false
    @State private var generation = UUID()
    private var source: JSONObject { sources.first { $0.string("id") == sourceID } ?? [:] }
    private var tabs: [JSONObject] { guard let index = Int(menuIndex), menus.indices.contains(index) else { return [] }; return menus[index].rows("tabs") }
    var body: some View {
        AndroidForm {
            if search { AndroidSearch(text: $query, hint: "书名或作者") { Task { await reload() } } }
            HStack {
                AndroidChoice(title: "来源", selection: $sourceID, options: sources.map { ($0.string("id"), $0.string("group") + " · " + $0.string("name")) }).onChange(of: sourceID) { _, _ in Task { await switchSource() } }
                Spacer()
                Button { grid.toggle() } label: { YLIcon(systemName: grid ? "list.bullet" : "square.grid.2x2").frame(width: 44, height: 44) }.buttonStyle(.plain).accessibilityLabel("切换网格列表")
            }
            if !search && !menus.isEmpty {
                if menus.contains(where: { !$0.rows("tabs").isEmpty }) {
                    AndroidChips(names: menus.enumerated().map { (String($0.offset), $0.element.string("title", "name")) }, selection: $menuIndex).onChange(of: menuIndex) { _, _ in category = tabs.first?.string("url") ?? "" }
                    AndroidChips(names: tabs.map { ($0.string("url"), $0.string("title", "name")) }, selection: $category).onChange(of: category) { _, _ in Task { await reload() } }
                } else { AndroidChips(names: menus.map { ($0.string("url"), $0.string("title", "name")) }, selection: $category).onChange(of: category) { _, _ in Task { await reload() } } }
            }
            if !error.isEmpty { ErrorPanel(text: error) { Task { await reload() } } }
            if grid { BookGrid(rows: rows) }
            else { LazyVStack(spacing: 9) { ForEach(rows) { book in NavigationLink { BookDetailView(book: book) } label: { MediaRow(item: book) }.buttonStyle(.plain) } } }
            if loading { ProgressView().frame(maxWidth: .infinity).padding() }
            if more && !loading { Button("加载更多") { Task { await load(next: true) } }.onAppear { Task { await load(next: true) } } }
            if rows.isEmpty && !loading && error.isEmpty { ErrorPanel(text: search ? "输入书名或作者开始搜索" : "该分类暂无内容") }
        }.androidPage(search ? "搜索有声书" : "发现", category: "有声书") { Button { Task { await reload() } } label: { YLIcon(systemName: "arrow.clockwise").frame(width: 44, height: 44) }.accessibilityLabel("刷新列表") }
            .refreshable { await reload() }
            .task { do { sources = try await store.bookSources().filter { $0[search ? "search" : "discover"] as? Bool == true }; sourceID = sources.first?.string("id") ?? ""; await switchSource() } catch { self.error = error.localizedDescription } }
    }
    private func switchSource() async {
        generation = UUID(); loading = false; rows = []; menus = []; category = ""; nextPage = ""; seenPages = []; error = ""
        guard !sourceID.isEmpty else { return }
        if !search {
            do {
                let result = try await store.bookCall(sourceID, pluginID: source.string("_plugin"), action: "categoryMenus", args: [])
                func flatten(_ value: Any) -> [JSONObject] {
                    if let rows = value as? [Any] { return rows.flatMap(flatten) }
                    guard let row = value as? JSONObject else { return [] }
                    let nested = ["menus", "tabs", "children", "items", "list"].compactMap { row[$0] }.flatMap(flatten)
                    return !row.string("url").isEmpty ? [row] + nested : nested
                }
                let original = result as? [JSONObject] ?? []
                menus = original.contains(where: { !$0.rows("tabs").isEmpty }) ? original : flatten(result)
                menuIndex = "0"; category = tabs.first?.string("url") ?? menus.first?.string("url") ?? ""
            } catch { self.error = error.localizedDescription }
        }
        await reload()
    }
    private func reload() async { generation = UUID(); loading = false; rows = []; page = 1; nextPage = ""; seenPages = []; more = false; await load() }
    private func load(next: Bool = false) async {
        guard !loading, !sourceID.isEmpty, !(search && query.isEmpty), search || !category.isEmpty else { return }
        let token = generation; let selected = sourceID; let pluginID = source.string("_plugin")
        loading = true; error = ""
        do {
            let cursor = next ? nextPage : category
            if !search && seenPages.contains(cursor) { more = false; loading = false; return }
            let requested = next ? page + 1 : 1
            let result = try await store.bookCall(selected, pluginID: pluginID, action: search ? "search" : "categoryPage", args: search ? [query, requested] : [cursor]) as? JSONObject ?? [:]
            guard token == generation else { return }
            let items = result.rows("books").map { row in var row = row; row["_plugin"] = pluginID; return MediaItem(row, source: selected, kind: "book") }
            let ids = Set(rows.map(\.id)); let fresh = items.filter { !ids.contains($0.id) }; rows += fresh; page = requested
            if !search { seenPages.insert(cursor); nextPage = result.string("nextPageUrl", "nextUrl") }
            more = !fresh.isEmpty && (search ? (result["totalPage"] as? Int ?? 1) > requested : !nextPage.isEmpty && !seenPages.contains(nextPage))
        } catch { if token == generation { self.error = error.localizedDescription } }
        if token == generation { loading = false }
    }
}

struct BookDetailView: View {
    @EnvironmentObject var store: AppStore
    @EnvironmentObject var player: Player
    var book: MediaItem
    @State private var episodes: [MediaItem] = []
    @State private var description = ""
    @State private var error = ""
    @State private var loading = false
    @State private var query = ""
    @State private var reverse = false
    var displayed: [MediaItem] { let list = query.isEmpty ? episodes : episodes.filter { $0.title.localizedCaseInsensitiveContains(query) }; return reverse ? Array(list.reversed()) : list }
    var body: some View {
        AndroidList {
            RemoteImage(url: store.artwork(book)) { $0.resizable().scaledToFill() } placeholder: { Palette.soft.overlay { Text(book.title).font(.system(size: 18, weight: .bold)).padding(12) } }.frame(width: 110, height: 144).clipped().clipShape(RoundedRectangle(cornerRadius: 10)).padding(.vertical, 6)
            Button("收藏 / 取消收藏") { store.toggleShelf(book) }.buttonStyle(AndroidButtonStyle())
            if !description.isEmpty { Text(description).font(.system(size: 14)).foregroundStyle(Palette.muted).padding(.vertical, 8) }
            HStack(spacing: 12) { if !episodes.isEmpty { Button("从第一章播放") { player.play(displayed) }.buttonStyle(AndroidButtonStyle(primary: true)); Button("继续收听") { let saved = store.nowBook; if let saved, saved.raw.string("_book") == book.id { player.resumeBook(saved) } else { player.play(episodes) } }.buttonStyle(AndroidButtonStyle()) }; Spacer(); Button { reverse.toggle() } label: { YLIcon(systemName: "arrow.up.arrow.down").frame(width: 44, height: 44) }.accessibilityLabel("章节排序") }
            AndroidSearch(text: $query, hint: "搜索章节")
            ForEach(Array(displayed.enumerated()), id: \.offset) { index, item in Button { player.play(displayed, at: index) } label: { HStack { Text("\(index + 1)  " + item.title).font(.system(size: 15, weight: .bold)).foregroundStyle(Palette.ink).padding(.vertical, 10); Spacer(); YLIcon(systemName: "play.fill", size: 17).foregroundStyle(Palette.accent).frame(width: 28, height: 36) }.padding(.leading, 12).padding(.trailing, 10).padding(.vertical, 8).androidSurface() }.buttonStyle(.plain) }
            if loading { ProgressView().frame(maxWidth: .infinity) }; if !error.isEmpty { ErrorPanel(text: error) { Task { await load() } } }
        }.androidPage(book.title, category: "有声书 / 章节").task { if episodes.isEmpty { await load() } }.refreshable { await load() }
    }
    private func load() async {
        guard !loading else { return }; loading = true; error = ""; defer { loading = false }
        do {
            let detail = try await store.bookCall(book.source, pluginID: book.raw.string("_plugin"), action: "bookDetail", args: [book.key, true, true]) as? JSONObject ?? [:]
            description = detail.string("description", "intro")
            episodes = BookPlayback.episodes(detail, book: book)
        } catch { self.error = error.localizedDescription }
    }
}

struct BookSettingsView: View {
    @AppStorage("book-cache") private var cache = 512
    var body: some View { AndroidForm { AndroidSection("缓存") { AndroidChoice(title: "听书缓存", selection: Binding(get: { String(cache) }, set: { cache = Int($0) ?? 512 }), options: [0,128,256,512,1024,2048,4096,8192].map { (String($0), $0 == 0 ? "关闭" : "\($0) MB") }); Button("清除听书缓存") { Task { await MediaCache.shared.clear(books: true) } } }; AndroidSection { NavigationLink("管理听书插件") { PluginSettingsView(books: true) }; Text("章节进度自动保存，播放倍速按书籍分别记忆。").font(.caption).foregroundStyle(Palette.muted) } }.androidPage("听书设置") }
}

struct LoginWebView: UIViewRepresentable {
    var url: URL
    func makeUIView(context: Context) -> WKWebView { let web = WKWebView(); web.load(URLRequest(url: url)); return web }
    func updateUIView(_ uiView: WKWebView, context: Context) {}
}
