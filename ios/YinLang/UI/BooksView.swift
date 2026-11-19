import SwiftUI
import WebKit

struct BooksView: View {
    @EnvironmentObject var store: AppStore
    @EnvironmentObject var player: Player
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                NavigationLink { BookBrowseView(search: true) } label: { Label("搜索书名或作者", systemImage: "magnifyingglass").foregroundStyle(.secondary).frame(maxWidth: .infinity, alignment: .leading).padding(16).background(.background, in: RoundedRectangle(cornerRadius: 15)) }
                HStack(spacing: 12) {
                    NavigationLink { BookBrowseView(search: false) } label: { ModuleCard(title: "发现", subtitle: "总有一个故事，值得听见", icon: "book.pages", color: Palette.accent).frame(height: 235) }
                    VStack(spacing: 10) {
                        NavigationLink { SavedView(title: "书架", type: "bookshelf") } label: { small("书架", icon: "books.vertical") }
                        NavigationLink { PluginSettingsView(books: true) } label: { small("插件", icon: "puzzlepiece.extension") }
                        NavigationLink { BookSettingsView() } label: { small("听书设置", icon: "slider.horizontal.3") }
                    }
                }
                if let current = store.nowBook { SectionTitle(title: "继续收听"); SongRow(item: current) { player.resumeBook(current) }.padding(12).background(.background, in: RoundedRectangle(cornerRadius: 15)) }
                SectionTitle(title: "收藏的有声书")
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 130))], spacing: 18) {
                    ForEach(store.bookshelf) { book in NavigationLink { BookDetailView(book: book) } label: { VStack(alignment: .leading, spacing: 8) { Artwork(item: book, size: 130); Text(book.title).font(.subheadline).foregroundStyle(.primary).lineLimit(2) }.frame(maxWidth: .infinity, alignment: .leading) } }
                }
                if store.bookshelf.isEmpty { Text("把喜欢的故事加入书架，随时接着听。").font(.subheadline).foregroundStyle(.secondary) }
            }.padding(16).frame(maxWidth: 1000).frame(maxWidth: .infinity)
        }.background(Palette.paper).navigationTitle("有声书").task { await store.installBundledBooks() }
    }
    private func small(_ title: String, icon: String) -> some View { Label(title, systemImage: icon).font(.subheadline.weight(.medium)).frame(maxWidth: .infinity, alignment: .leading).padding(17).frame(height: 70).background(.background, in: RoundedRectangle(cornerRadius: 16)).foregroundStyle(.primary) }
}

struct BookBrowseView: View {
    @EnvironmentObject var store: AppStore
    var search: Bool
    @State private var sources: [JSONObject] = []
    @State private var sourceID = ""
    @State private var menus: [JSONObject] = []
    @State private var category = ""
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
    var body: some View {
        ScrollView {
            VStack(spacing: 15) {
                HStack {
                    Picker("来源", selection: $sourceID) { ForEach(Array(sources.enumerated()), id: \.offset) { _, row in Text(row.string("group") + " · " + row.string("name")).tag(row.string("id")) } }.onChange(of: sourceID) { _, _ in Task { await switchSource() } }
                    Spacer(); Button { grid.toggle() } label: { Image(systemName: grid ? "list.bullet" : "square.grid.2x2") }.accessibilityLabel("切换网格列表")
                }
                if !search && !menus.isEmpty {
                    ScrollView(.horizontal, showsIndicators: false) { HStack { ForEach(Array(menus.enumerated()), id: \.offset) { _, menu in Button(menu.string("title", "name")) { category = menu.string("url"); Task { await reload() } }.buttonStyle(.bordered).tint(category == menu.string("url") ? Palette.accent : .secondary) } } }
                }
                if !error.isEmpty { ErrorPanel(text: error) { Task { await reload() } } }
                if grid {
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 130))], spacing: 20) { ForEach(rows) { book in NavigationLink { BookDetailView(book: book) } label: { VStack(alignment: .leading, spacing: 7) { Artwork(item: book, size: 130); Text(book.title).font(.subheadline).foregroundStyle(.primary).lineLimit(2); Text(book.subtitle).font(.caption).foregroundStyle(.secondary).lineLimit(1) }.frame(maxWidth: .infinity, alignment: .leading) } } }
                } else {
                    LazyVStack { ForEach(rows) { book in NavigationLink { BookDetailView(book: book) } label: { HStack(spacing: 14) { Artwork(item: book, size: 65); VStack(alignment: .leading, spacing: 7) { Text(book.title).font(.headline).foregroundStyle(.primary); Text(book.subtitle).font(.caption).foregroundStyle(.secondary) }; Spacer() }.padding(.vertical, 6) } } }
                }
                if loading { ProgressView().padding() }
                if more && !loading { Button("加载更多") { Task { await load(next: true) } }.padding().onAppear { Task { await load(next: true) } } }
                if rows.isEmpty && !loading && error.isEmpty { ErrorPanel(text: search ? "输入书名或作者开始搜索" : "该分类暂无内容") }
            }.padding(16).frame(maxWidth: 1100).frame(maxWidth: .infinity)
        }.background(Palette.paper).navigationTitle(search ? "搜索有声书" : "发现有声书")
            .searchable(text: $query, prompt: "书名或作者").onSubmit(of: .search) { Task { await reload() } }
            .refreshable { await reload() }
            .task {
                do { sources = try await store.bookSources().filter { $0[search ? "search" : "discover"] as? Bool == true }; sourceID = sources.first?.string("id") ?? "" }
                catch { self.error = error.localizedDescription }
            }
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
                menus = flatten(result); category = menus.first?.string("url") ?? ""
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
        List {
            HStack(alignment: .top, spacing: 18) { Artwork(item: book, size: 100); VStack(alignment: .leading, spacing: 10) { Text(book.title).font(.headline); Text(book.subtitle).font(.caption).foregroundStyle(.secondary); Button(store.bookshelf.contains(where: { $0.id == book.id }) ? "移出书架" : "加入书架", systemImage: "bookmark") { store.toggleShelf(book) } } }.listRowBackground(Color.clear)
            if !description.isEmpty { Text(description).font(.subheadline).foregroundStyle(.secondary) }
            if !episodes.isEmpty { Button("从第一章播放", systemImage: "play.fill") { player.play(displayed) } }
            ForEach(displayed) { item in SongRow(item: item) { player.play(displayed, at: displayed.firstIndex(of: item) ?? 0) } }
            if loading { ProgressView() }; if !error.isEmpty { ErrorPanel(text: error) { Task { await load() } } }
        }.navigationTitle("章节").navigationBarTitleDisplayMode(.inline).searchable(text: $query, prompt: "搜索章节")
            .toolbar { Button { reverse.toggle() } label: { Image(systemName: "arrow.up.arrow.down") }.accessibilityLabel("章节排序") }
            .task { if episodes.isEmpty { await load() } }.refreshable { await load() }
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
    var body: some View { Form { Section("缓存") { Stepper("听书缓存：\(cache) MB", value: $cache, in: 0...8192, step: 128); Button("清除听书缓存") { Task { await MediaCache.shared.clear(books: true) } } }; Section { NavigationLink("管理听书插件") { PluginSettingsView(books: true) }; Text("章节进度自动保存，播放倍速按书籍分别记忆。").font(.caption).foregroundStyle(.secondary) } }.navigationTitle("听书设置") }
}

struct LoginWebView: UIViewRepresentable {
    var url: URL
    func makeUIView(context: Context) -> WKWebView { let web = WKWebView(); web.load(URLRequest(url: url)); return web }
    func updateUIView(_ uiView: WKWebView, context: Context) {}
}
