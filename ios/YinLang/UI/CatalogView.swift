import SwiftUI

struct CatalogView: View {
    @EnvironmentObject var store: AppStore
    @EnvironmentObject var player: Player
    var mode: String
    @State private var platform = "all"
    @State private var query = ""
    @State private var sort = "@hot"
    @State private var tag = ""
    @State private var tags: [JSONObject] = []
    @State private var rows: [MediaItem] = []
    @State private var error = ""
    @State private var loading = false
    @State private var more = false
    @State private var page = 1
    @State private var grid = true
    @State private var librarySearch = false
    @State private var generation = UUID()
    @State private var link = ""
    @State private var showLink = false
    @State private var linkedPlaylist: MediaItem?
    var title: String { mode == "search" ? "搜索音乐" : mode == "charts" ? "在线排行榜" : "在线歌单" }
    init(mode: String, library: Bool = false) { self.mode = mode; _platform = State(initialValue: mode == "charts" ? "wy" : "all"); _librarySearch = State(initialValue: library) }
    var body: some View {
        GeometryReader { proxy in
            let count = max(1, min(4, Int((proxy.size.width - 32) / 175)))
            let columns = Array(repeating: GridItem(.flexible(), spacing: 8), count: max(proxy.size.width < 297 ? 1 : 2, count))
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    if mode == "search", store.nav != nil { AndroidChips(names: [("online", "在线音乐"), ("library", "音乐库")], selection: Binding(get: { librarySearch ? "library" : "online" }, set: { librarySearch = $0 == "library"; Task { await reload() } })) }
                    AndroidSearch(text: $query, hint: mode == "charts" ? "搜索榜名" : mode == "search" ? "歌名、歌手、专辑" : "搜索歌单") { if mode != "charts" { Task { await reload() } } }
                    if !librarySearch { AndroidChips(names: Platform.allCases.filter { mode != "charts" || $0.rawValue != "all" }.map { ($0.rawValue, $0.title) }, selection: $platform).onChange(of: platform) { _, _ in Task { await reload(); await loadTags() } } }
                    if mode == "lists" {
                        HStack(spacing: 14) {
                            AndroidChoice(title: "分类", selection: $tag, options: [("", "全部分类")] + tags.map { ($0.string("id"), $0.string("name")) }).onChange(of: tag) { _, _ in Task { await reload() } }
                            AndroidChoice(title: "排序", selection: $sort, options: [("@hot", "最热"), ("@new", "最新")]).onChange(of: sort) { _, _ in Task { await reload() } }
                            Spacer()
                            Button { grid.toggle() } label: { YLIcon(systemName: grid ? "list.bullet" : "square.grid.2x2") }.accessibilityLabel("切换网格列表")
                            Button { showLink = true } label: { YLIcon(systemName: "link") }.accessibilityLabel("按链接打开")
                        }.font(.system(size: 13, weight: .bold)).foregroundStyle(Palette.accent).frame(minHeight: 44)
                    }
                    if !error.isEmpty { ErrorPanel(text: error) { Task { await reload(refresh: true) } } }
                    if rows.isEmpty && !loading && error.isEmpty { ErrorPanel(text: mode == "search" ? "输入歌名或歌手，开始寻找喜欢的声音" : "暂无内容") }
                    if mode == "search" { LazyVStack(spacing: 6) { ForEach(rows) { item in SongRow(item: item) { player.play(rows, at: rows.firstIndex(of: item) ?? 0) } } } }
                    else if grid {
                        LazyVGrid(columns: columns, spacing: 8) {
                            ForEach(filtered) { item in NavigationLink { CatalogDetailView(item: item, chart: mode == "charts") } label: { CatalogCard(item: item, chart: mode == "charts", height: max(134, min(178, ((proxy.size.width - 40) / CGFloat(columns.count)) * 0.8))) } }
                        }
                    } else { LazyVStack(spacing: 8) { ForEach(filtered) { item in NavigationLink { CatalogDetailView(item: item, chart: mode == "charts") } label: { MediaRow(item: item) } } } }
                    if loading { ProgressView().frame(maxWidth: .infinity).padding() }
                    if more && !loading { Button("加载更多") { Task { await load(page: page + 1) } }.buttonStyle(AndroidButtonStyle()).onAppear { Task { await load(page: page + 1) } } }
                }.padding(.horizontal, 16).padding(.top, 4).padding(.bottom, 22)
            }
        }.androidPage(title, category: "在线音乐") { Button { Task { await reload(refresh: true) } } label: { YLIcon(systemName: "arrow.clockwise").frame(width: 44, height: 44) }.accessibilityLabel("刷新列表") }
            .buttonStyle(.plain).refreshable { await reload(refresh: true) }
            .navigationDestination(item: $linkedPlaylist) { item in CatalogDetailView(item: item, chart: false) }
            .task { if rows.isEmpty && mode != "search" { await reload(); await loadTags() } }
            .alert("打开歌单", isPresented: $showLink) { TextField("歌单 ID 或链接", text: $link); Button("打开") { openLink() }; Button("取消", role: .cancel) {} } message: { Text("先选择对应平台，再填写歌单 ID 或分享链接") }
    }
    var filtered: [MediaItem] { mode == "charts" && !query.isEmpty ? rows.filter { $0.title.localizedCaseInsensitiveContains(query) } : rows }
    private func reload(refresh: Bool = false) async { generation = UUID(); loading = false; rows = []; more = false; page = 1; await load(page: 1, refresh: refresh) }
    private func load(page requested: Int, refresh: Bool = false) async {
        guard !loading, !(mode == "search" && query.trimmingCharacters(in: .whitespaces).isEmpty) else { return }
        loading = true; error = ""; let token = generation
        do {
            let items: [MediaItem]; let hasMore: Bool
            if librarySearch, let nav = store.nav {
                let result = try await nav.search(query, offset: (requested - 1) * 50); items = result.map { store.libraryItem($0) }; hasMore = result.count == 50
            } else {
                let result = try await store.catalog(mode, ["source": platform, "query": query, "sort": sort, "tag": tag, "page": requested], refresh: refresh)
                items = result.rows("list").map { MediaItem($0, kind: mode == "charts" ? "chart" : mode == "lists" ? "playlist" : "song") }; hasMore = result["more"] as? Bool ?? false
            }
            guard token == generation else { return }
            let existing = Set(rows.map(\.id)); rows += items.filter { !existing.contains($0.id) }; page = requested; more = hasMore && !items.isEmpty
        } catch { if token == generation { self.error = error.localizedDescription } }
        if token == generation { loading = false }
    }
    private func loadTags() async {
        guard mode == "lists", platform != "all", let result = try? await store.catalog("tags", ["source": platform]) else { tags = []; return }
        func flatten(_ value: Any) -> [JSONObject] {
            if let list = value as? [Any] { return list.flatMap(flatten) }
            guard let row = value as? JSONObject else { return [] }
            let children = ["list", "tags", "children", "data"].compactMap { row[$0] }.flatMap(flatten)
            return children.isEmpty && !row.string("id").isEmpty && !row.string("name").isEmpty ? [row] : children
        }
        tags = flatten(result)
    }
    private func openLink() {
        do { linkedPlaylist = try PlaylistLink.item(link, platform: platform) }
        catch { store.message = error.localizedDescription }
    }
}

struct CatalogDetailView: View {
    @EnvironmentObject var store: AppStore
    @EnvironmentObject var player: Player
    var item: MediaItem
    var chart: Bool
    @State private var rows: [MediaItem] = []
    @State private var loading = false
    @State private var error = ""
    @State private var page = 1
    @State private var more = false
    @State private var query = ""
    var filtered: [MediaItem] { query.isEmpty ? rows : rows.filter { ($0.title + $0.subtitle).localizedCaseInsensitiveContains(query) } }
    var body: some View {
        AndroidList {
            if !chart { Button(store.followed.contains(where: { $0.id == item.id }) ? "取消关注" : "关注歌单") { store.toggleFollow(item) }.buttonStyle(AndroidButtonStyle()) }
            AndroidSearch(text: $query, hint: "在当前列表中查找歌曲")
            HStack(spacing: 9) { Button { player.play(filtered) } label: { Text("播放全部").frame(maxWidth: .infinity) }.buttonStyle(AndroidButtonStyle(primary: true)).disabled(rows.isEmpty); Button { Task { await load(refresh: true) } } label: { YLIcon(systemName: "arrow.clockwise").frame(width: 44, height: 44).foregroundStyle(Palette.accent) }.accessibilityLabel("刷新列表") }
            ForEach(Array(filtered.enumerated()), id: \.offset) { position, song in SongRow(item: song, rank: chart ? position + 1 : nil) { player.play(filtered, at: position) } }
            if loading { ProgressView().frame(maxWidth: .infinity) }
            if !error.isEmpty { ErrorPanel(text: error) { Task { await load(refresh: true) } } }
            if more && !loading { Button("加载更多") { Task { await load(next: true) } }.buttonStyle(AndroidButtonStyle()) }
        }.androidPage(item.title, category: Platform(rawValue: item.source)?.title ?? "在线音乐").task { if rows.isEmpty { await load() } }.refreshable { await load(refresh: true) }
    }
    private func load(next: Bool = false, refresh: Bool = false) async {
        guard !loading else { return }; loading = true; error = ""; defer { loading = false }
        do {
            let requested = next ? page + 1 : 1
            let result = try await store.catalog(chart ? "chart" : "list", ["source": item.source, "id": item.catalogID, "page": requested], refresh: refresh)
            let items = result.rows("list").map { MediaItem($0) }
            if !next { rows = [] }; let ids = Set(rows.map(\.id)); rows += items.filter { !ids.contains($0.id) }; page = requested; more = result["more"] as? Bool ?? false
        } catch { self.error = error.localizedDescription }
    }
}
