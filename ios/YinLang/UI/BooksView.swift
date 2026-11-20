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
                    NavigationLink { BookBrowseView(search: false) } label: { DiscoveryHero(books: true) }.frame(width: (proxy.size.width - 10) * 1.3 / 2.3, height: proxy.size.height).accessibilityIdentifier("books-discovery")
                    VStack(spacing: 8) {
                        NavigationLink { SavedView(title: "书架", type: "bookshelf") } label: { LibraryShortcut(title: "书架", icon: "books.vertical") }.accessibilityIdentifier("books-shelf-shortcut")
                        NavigationLink { PluginSettingsView(books: true) } label: { LibraryShortcut(title: "插件", icon: "puzzlepiece.extension") }.accessibilityIdentifier("books-plugins-shortcut")
                        NavigationLink { BookSettingsView() } label: { LibraryShortcut(title: "听书设置", icon: "gearshape") }.accessibilityIdentifier("books-settings-shortcut")
                    }
                }.frame(width: proxy.size.width, height: proxy.size.height)
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
    @AppStorage("book-discovery-last-source") private var lastDiscoverySource = ""
    @AppStorage("book-search-last-source") private var lastSearchSource = ""
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
    private var source: JSONObject { sources.first { BookSourceSelection.key($0) == sourceID } ?? [:] }
    private var tabs: [JSONObject] { guard let index = Int(menuIndex), menus.indices.contains(index) else { return [] }; return menus[index].rows("tabs") }
    var body: some View {
        AndroidForm {
            if search { AndroidSearch(text: $query, hint: "书名或作者") { Task { await reload() } } }
            HStack {
                AndroidChoice(title: "来源", selection: Binding(get: { sourceID }, set: { sourceID = $0; generation = UUID() }), options: sources.map { (BookSourceSelection.key($0), $0.string("group") + " · " + $0.string("name")) }).accessibilityValue(source.string("name")).accessibilityIdentifier("book-source-picker")
                Spacer()
                Button { grid.toggle() } label: { YLIcon(systemName: grid ? "list.bullet" : "square.grid.2x2").frame(width: 44, height: 44) }.buttonStyle(.plain).accessibilityLabel("切换网格列表")
            }
            if !search && !menus.isEmpty {
                if menus.contains(where: { !$0.rows("tabs").isEmpty }) {
                    AndroidChips(names: menus.enumerated().map { (String($0.offset), $0.element.string("title", "name")) }, selection: Binding(get: { menuIndex }, set: { menuIndex = $0; changeCategory(tabs.first?.string("url") ?? "") }))
                    AndroidChips(names: tabs.map { ($0.string("url"), $0.string("title", "name")) }, selection: Binding(get: { category }, set: changeCategory))
                } else { AndroidChips(names: menus.map { ($0.string("url"), $0.string("title", "name")) }, selection: Binding(get: { category }, set: changeCategory)) }
            }
            if !error.isEmpty { ErrorPanel(text: error) { Task { await reload() } } }
            if grid { BookGrid(rows: rows) }
            else { LazyVStack(spacing: 9) { ForEach(rows) { book in NavigationLink { BookDetailView(book: book) } label: { MediaRow(item: book) }.buttonStyle(.plain) } } }
            if loading { ProgressView().frame(maxWidth: .infinity).padding() }
            if more && !loading { Button("加载更多") { Task { await load(next: true) } }.onAppear { Task { await load(next: true) } } }
            if rows.isEmpty && !loading && error.isEmpty { ErrorPanel(text: search ? "输入书名或作者开始搜索" : "该分类暂无内容") }
        }.androidPage(search ? "搜索有声书" : "发现", category: "有声书") { Button { Task { await reload() } } label: { YLIcon(systemName: "arrow.clockwise").frame(width: 44, height: 44) }.accessibilityLabel("刷新列表") }
            .refreshable { await reload() }
            .task {
                do {
                    let available = try await store.bookSources().filter { $0[search ? "search" : "discover"] as? Bool == true }
                    guard !Task.isCancelled else { return }
                    sources = available
                    sourceID = BookSourceSelection.restore(current: sourceID, saved: search ? lastSearchSource : lastDiscoverySource, sources: available)
                } catch { if !Task.isCancelled { self.error = error.localizedDescription } }
            }
            .task(id: sourceID) {
                guard !Task.isCancelled else { return }
                guard !sourceID.isEmpty else { generation = UUID(); rows = []; menus = []; category = ""; more = false; loading = false; return }
                if search { lastSearchSource = sourceID } else { lastDiscoverySource = sourceID }
                await switchSource()
            }
    }
    private func changeCategory(_ value: String) { category = value; generation = UUID(); Task { await reload() } }
    private func switchSource() async {
        let token = UUID(), selected = sourceID, activeSource = source
        generation = token; loading = false; rows = []; menus = []; category = ""; nextPage = ""; seenPages = []; more = false; error = ""
        guard !sourceID.isEmpty else { return }
        if !search {
            do {
                let result = try await store.bookCall(activeSource.string("id"), pluginID: activeSource.string("_plugin"), action: "categoryMenus", args: [])
                guard !Task.isCancelled, generation == token, sourceID == selected else { return }
                func flatten(_ value: Any) -> [JSONObject] {
                    if let rows = value as? [Any] { return rows.flatMap(flatten) }
                    guard let row = value as? JSONObject else { return [] }
                    let nested = ["menus", "tabs", "children", "items", "list"].compactMap { row[$0] }.flatMap(flatten)
                    return !row.string("url").isEmpty ? [row] + nested : nested
                }
                let original = result as? [JSONObject] ?? []
                menus = original.contains(where: { !$0.rows("tabs").isEmpty }) ? original : flatten(result)
                menuIndex = "0"; category = tabs.first?.string("url") ?? menus.first?.string("url") ?? ""
            } catch { guard !Task.isCancelled, generation == token, sourceID == selected else { return }; self.error = error.localizedDescription }
        }
        guard !Task.isCancelled, generation == token, sourceID == selected else { return }
        await reload()
    }
    private func reload() async { guard !Task.isCancelled else { return }; generation = UUID(); loading = false; rows = []; page = 1; nextPage = ""; seenPages = []; more = false; await load() }
    private func load(next: Bool = false) async {
        guard !Task.isCancelled, !loading, !sourceID.isEmpty, !(search && query.isEmpty), search || !category.isEmpty else { return }
        let token = generation; let selected = sourceID; let pluginID = source.string("_plugin"), rawID = source.string("id")
        loading = true; error = ""
        do {
            let cursor = next ? nextPage : category
            if !search && seenPages.contains(cursor) { more = false; loading = false; return }
            let requested = next ? page + 1 : 1
            let result = try await store.bookCall(rawID, pluginID: pluginID, action: search ? "search" : "categoryPage", args: search ? [query, requested] : [cursor]) as? JSONObject ?? [:]
            guard token == generation, sourceID == selected, !Task.isCancelled else { return }
            let items = result.rows("books").map { row in var row = row; row["_plugin"] = pluginID; return MediaItem(row, source: rawID, kind: "book") }
            let ids = Set(rows.map(\.id)); let fresh = items.filter { !ids.contains($0.id) }; rows += fresh; page = requested
            if !search { seenPages.insert(cursor); nextPage = result.string("nextPageUrl", "nextUrl") }
            more = !fresh.isEmpty && (search ? (result["totalPage"] as? Int ?? 1) > requested : !nextPage.isEmpty && !seenPages.contains(nextPage))
        } catch { if token == generation, sourceID == selected, !Task.isCancelled { self.error = error.localizedDescription } }
        if token == generation { loading = false }
    }
}

enum BookSourceSelection {
    static func key(_ source: JSONObject) -> String { jsonText([source.string("_plugin"), source.string("id")]) }
    static func restore(current: String, saved: String, sources: [JSONObject]) -> String {
        let keys = sources.map(key)
        if keys.contains(current) { return current }
        if keys.contains(saved) { return saved }
        return keys.first ?? ""
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
    @State private var showDownloads = false
    var displayed: [MediaItem] { let list = query.isEmpty ? episodes : episodes.filter { $0.title.localizedCaseInsensitiveContains(query) }; return reverse ? Array(list.reversed()) : list }
    var body: some View {
        AndroidList {
            RemoteImage(url: store.artwork(book)) { $0.resizable().scaledToFill() } placeholder: { Palette.soft.overlay { Text(book.title).font(.system(size: 18, weight: .bold)).padding(12) } }.frame(width: 110, height: 144).clipped().clipShape(RoundedRectangle(cornerRadius: 10)).padding(.vertical, 6)
            Button("收藏 / 取消收藏") { store.toggleShelf(book) }.buttonStyle(AndroidButtonStyle())
            if !description.isEmpty { Text(description).font(.system(size: 14)).foregroundStyle(Palette.muted).padding(.vertical, 8) }
            HStack(spacing: 12) { if !episodes.isEmpty { Button("从第一章播放") { player.play(displayed) }.buttonStyle(AndroidButtonStyle(primary: true)); Button("继续收听") { let saved = store.nowBook; if let saved, saved.raw.string("_book") == book.id { player.resumeBook(saved) } else { player.play(episodes) } }.buttonStyle(AndroidButtonStyle()) }; Spacer(); Button { reverse.toggle() } label: { YLIcon(systemName: "arrow.up.arrow.down").frame(width: 44, height: 44) }.accessibilityLabel("章节排序") }
            if !episodes.isEmpty {
                Button { showDownloads = true } label: { Label("下载章节", systemImage: "arrow.down.circle") }.buttonStyle(AndroidButtonStyle()).accessibilityIdentifier("book-download-chapters")
            }
            AndroidSearch(text: $query, hint: "搜索章节")
            ForEach(Array(displayed.enumerated()), id: \.element.id) { index, item in
                HStack(spacing: 0) {
                    Button { player.play(displayed, at: index) } label: {
                        HStack { Text("\(index + 1)  " + item.title).font(.system(size: 15, weight: .bold)).foregroundStyle(Palette.ink).padding(.vertical, 10); Spacer(); YLIcon(systemName: "play.fill", size: 17).foregroundStyle(Palette.accent).frame(width: 28, height: 36) }.contentShape(Rectangle())
                    }.buttonStyle(.plain).accessibilityIdentifier("book-chapter-\(index)")
                    ChapterDownloadButton(item: item)
                }.padding(.leading, 12).padding(.trailing, 6).padding(.vertical, 8).androidSurface()
            }
            if loading { ProgressView().frame(maxWidth: .infinity) }; if !error.isEmpty { ErrorPanel(text: error) { Task { await load() } } }
        }.androidPage(book.title, category: "有声书 / 章节").task { if episodes.isEmpty { await load() } }.refreshable { await load() }
            .sheet(isPresented: $showDownloads) { ChapterDownloadPicker(book: book, episodes: displayed).environmentObject(store) }
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

private struct ChapterDownloadButton: View {
    @EnvironmentObject private var store: AppStore
    let item: MediaItem
    private var busy: Bool { store.downloading.contains(item.id) }
    private var saved: Bool { store.hasDownload(item) }
    var body: some View {
        Button { store.downloadEpisodes([item]) } label: {
            Group {
                if busy { ProgressView().tint(Palette.accent) }
                else { YLIcon(systemName: saved ? "checkmark.circle.fill" : "arrow.down.circle", size: 21).foregroundStyle(Palette.accent) }
            }.frame(width: 44, height: 44).contentShape(Rectangle())
        }.buttonStyle(.plain).disabled(busy || saved)
            .accessibilityLabel(busy ? "章节下载中" : saved ? "章节已下载" : "下载 " + item.title)
            .accessibilityIdentifier("book-chapter-download-" + item.key)
    }
}

private struct ChapterDownloadPicker: View {
    @EnvironmentObject private var store: AppStore
    @Environment(\.dismiss) private var dismiss
    let book: MediaItem
    let episodes: [MediaItem]
    @State private var selected: Set<String> = []
    @State private var query = ""
    private var displayed: [MediaItem] { query.isEmpty ? episodes : episodes.filter { $0.title.localizedCaseInsensitiveContains(query) } }
    private func available(_ item: MediaItem) -> Bool { !store.downloading.contains(item.id) && !store.hasDownload(item) }
    private var chosen: [MediaItem] { episodes.filter { selected.contains($0.id) && available($0) } }
    var body: some View {
        NavigationStack {
            AndroidList {
                Text(book.title).font(.system(size: 18, weight: .bold)).foregroundStyle(Palette.ink)
                Text("下载的章节保存在本机下载，可离线收听。").font(.system(size: 13)).foregroundStyle(Palette.muted)
                AndroidSearch(text: $query, hint: "搜索要下载的章节")
                HStack(spacing: 12) {
                    Button("全选当前列表") { selected.formUnion(displayed.filter(available).map(\.id)) }
                    Button("清空选择") { selected = [] }
                }.buttonStyle(AndroidButtonStyle())
                ForEach(displayed) { item in
                    Button {
                        if selected.contains(item.id) { selected.remove(item.id) } else { selected.insert(item.id) }
                    } label: {
                        HStack(spacing: 10) {
                            YLIcon(systemName: selected.contains(item.id) ? "checkmark.circle.fill" : "circle", size: 21).foregroundStyle(Palette.accent)
                            Text(item.title).font(.system(size: 15)).foregroundStyle(Palette.ink).frame(maxWidth: .infinity, alignment: .leading)
                            if store.downloading.contains(item.id) { Text("下载中").font(.caption).foregroundStyle(Palette.muted) }
                            else if store.hasDownload(item) { Text("已下载").font(.caption).foregroundStyle(Palette.muted) }
                        }.padding(12).androidSurface().contentShape(Rectangle())
                    }.buttonStyle(.plain).disabled(!available(item))
                }
            }.androidPage("下载章节", category: "有声书") { Button("完成") { dismiss() } }
                .safeAreaInset(edge: .bottom) {
                    Button("下载所选（\(chosen.count) 章）") {
                        store.downloadEpisodes(chosen); dismiss()
                    }.buttonStyle(AndroidButtonStyle(primary: true)).disabled(chosen.isEmpty)
                        .padding(.horizontal, 16).padding(.vertical, 12).frame(maxWidth: .infinity).background(Palette.background)
                }
        }.presentationDetents([.large]).presentationCornerRadius(30).presentationDragIndicator(.visible)
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
