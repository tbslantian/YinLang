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
    @AppStorage private var grid: Bool
    @AppStorage private var preferredColumns: Int
    @State private var showColumns = false
    @State private var librarySearch = false
    @State private var generation = UUID()
    @State private var link = ""
    @State private var showLink = false
    @State private var linkedPlaylist: MediaItem?
    var title: String { mode == "search" ? "搜索音乐" : mode == "charts" ? "在线排行榜" : "在线歌单" }
    init(mode: String, library: Bool = false) {
        self.mode = mode
        _platform = State(initialValue: mode == "charts" ? "wy" : "all")
        _librarySearch = State(initialValue: library)
        _grid = AppStorage(wrappedValue: true, "catalog-\(mode)-grid")
        let device = UIDevice.current.userInterfaceIdiom == .pad ? "tablet" : "phone"
        _preferredColumns = AppStorage(wrappedValue: 0, "catalog-\(mode)-columns-\(device)")
    }
    var body: some View {
        GeometryReader { proxy in
            let count = CatalogLayout.columns(preferred: preferredColumns, width: proxy.size.width, tablet: UIDevice.current.userInterfaceIdiom == .pad)
            let columns = Array(repeating: GridItem(.flexible(), spacing: 8), count: count)
            let cardWidth = max(0, (proxy.size.width - 32 - CGFloat(count - 1) * 8) / CGFloat(count))
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    if mode == "search", store.nav != nil { AndroidChips(names: [("online", "在线音乐"), ("library", "音乐库")], selection: Binding(get: { librarySearch ? "library" : "online" }, set: { librarySearch = $0 == "library"; Task { await reload() } })) }
                    AndroidSearch(text: $query, hint: mode == "charts" ? "搜索榜名" : mode == "search" ? "歌名、歌手、专辑" : "搜索歌单") { if mode != "charts" { Task { await reload() } } }
                    if !librarySearch { AndroidChips(names: Platform.allCases.filter { mode != "charts" || $0.rawValue != "all" }.map { ($0.rawValue, $0.title) }, selection: Binding(get: { platform }, set: { value in
                        guard value != platform else { return }
                        platform = value; tags = []; tag = ""; generation = UUID()
                    })) }
                    if mode == "lists" {
                        HStack(spacing: 16) {
                            AndroidChoice(title: "分类", selection: Binding(get: { tag }, set: { tag = $0; Task { await reload() } }), options: [("", "全部分类")] + tags.map { ($0.string("id"), $0.string("name")) })
                            AndroidChoice(title: "排序", selection: $sort, options: [("@hot", "最热"), ("@new", "最新")]).onChange(of: sort) { _, _ in Task { await reload() } }
                        }.font(.system(size: 13, weight: .bold)).foregroundStyle(Palette.accent).frame(minHeight: 44)
                    }
                    if mode == "lists" || mode == "charts" { layoutControls(width: proxy.size.width) }
                    if !error.isEmpty { ErrorPanel(text: error) { Task { await reload(refresh: true) } } }
                    if rows.isEmpty && !loading && error.isEmpty { ErrorPanel(text: mode == "search" ? "输入歌名或歌手，开始寻找喜欢的声音" : "暂无内容") }
                    if mode == "search" { LazyVStack(spacing: 6) { ForEach(rows) { item in SongRow(item: item) { player.play(rows, at: rows.firstIndex(of: item) ?? 0) } } } }
                    else if grid {
                        LazyVGrid(columns: columns, spacing: 8) {
                            ForEach(filtered) { item in NavigationLink { CatalogDetailView(item: item, chart: mode == "charts") } label: { CatalogCard(item: item, chart: mode == "charts", height: max(134, min(178, cardWidth * 0.8))) }.accessibilityIdentifier("catalog-card-" + item.id) }
                        }.accessibilityIdentifier("catalog-grid")
                    } else { LazyVStack(spacing: 8) { ForEach(filtered) { item in NavigationLink { CatalogDetailView(item: item, chart: mode == "charts") } label: { MediaRow(item: item) } } }.accessibilityIdentifier("catalog-list") }
                    if loading { ProgressView().frame(maxWidth: .infinity).padding() }
                    if more && !loading { Button("加载更多") { Task { await load(page: page + 1) } }.buttonStyle(AndroidButtonStyle()).onAppear { Task { await load(page: page + 1) } } }
                }.padding(.horizontal, 16).padding(.top, 4).padding(.bottom, 22)
            }
        }.androidPage(title, category: "在线音乐") { Button { Task { await reload(refresh: true) } } label: { YLIcon(systemName: "arrow.clockwise").frame(width: 44, height: 44) }.accessibilityLabel("刷新列表") }
            .buttonStyle(.plain).refreshable { await reload(refresh: true) }
            .navigationDestination(item: $linkedPlaylist) { item in CatalogDetailView(item: item, chart: false) }
            .task(id: platform) { await reload(); await loadTags() }
            .alert("打开歌单", isPresented: $showLink) { TextField("歌单 ID 或链接", text: $link); Button("打开") { openLink() }; Button("取消", role: .cancel) {} } message: { Text("先选择对应平台，再填写歌单 ID 或分享链接") }
    }
    private func layoutControls(width: CGFloat) -> some View {
        let tablet = UIDevice.current.userInterfaceIdiom == .pad
        let count = CatalogLayout.columns(preferred: preferredColumns, width: width, tablet: tablet)
        let choices = [0] + Array(1...CatalogLayout.maximumColumns(width: width, tablet: tablet))
        let titles = choices.map { $0 == 0 ? "自动（当前 \(CatalogLayout.columns(preferred: 0, width: width, tablet: tablet)) 列）" : "\($0) 列" }
        return HStack(spacing: 8) {
            Text(grid ? "卡片显示" : "列表显示").font(.system(size: 13, weight: .semibold)).foregroundStyle(Palette.muted)
            Spacer(minLength: 0)
            Button { grid.toggle() } label: {
                YLIcon(systemName: grid ? "list.bullet" : "square.grid.2x2", size: 20)
                    .frame(width: 44, height: 44).contentShape(Rectangle())
            }.accessibilityLabel("切换网格列表").accessibilityValue(grid ? "卡片显示" : "列表显示").accessibilityIdentifier("catalog-style-toggle")
            Button { showColumns = true } label: {
                HStack(spacing: 4) { Text("\(count) 列").font(.system(size: 13, weight: .semibold)); YLIcon(systemName: "chevron.down", size: 12) }
                    .padding(.horizontal, 8).frame(minWidth: 64, minHeight: 44).contentShape(Rectangle())
            }.disabled(!grid).opacity(grid ? 1 : 0.4).accessibilityLabel("卡片列数")
                .accessibilityValue(preferredColumns == 0 ? "自动，当前 \(count) 列" : "\(count) 列").accessibilityIdentifier("catalog-columns")
                .sheet(isPresented: $showColumns) {
                    PlayerChoice(title: "卡片列数", rows: titles, selected: titles[choices.firstIndex(of: preferredColumns) ?? 0]) { value in
                        if let index = titles.firstIndex(of: value) { preferredColumns = choices[index] }
                    }
                }
            if mode == "lists" {
                Button { showLink = true } label: { YLIcon(systemName: "link", size: 20).frame(width: 44, height: 44).contentShape(Rectangle()) }.accessibilityLabel("按链接打开")
            }
        }.foregroundStyle(Palette.accent).buttonStyle(.plain).frame(minHeight: 44).zIndex(1).accessibilityIdentifier("catalog-layout-controls")
    }
    var filtered: [MediaItem] { mode == "charts" && !query.isEmpty ? rows.filter { $0.title.localizedCaseInsensitiveContains(query) } : rows }
    private func reload(refresh: Bool = false) async { generation = UUID(); loading = false; rows = []; more = false; page = 1; await load(page: 1, refresh: refresh) }
    private func load(page requested: Int, refresh: Bool = false) async {
        guard !Task.isCancelled, !loading, !(mode == "search" && query.trimmingCharacters(in: .whitespaces).isEmpty) else { return }
        loading = true; error = ""; let token = generation
        do {
            let items: [MediaItem]; let hasMore: Bool
            if librarySearch, let nav = store.nav {
                let result = try await nav.search(query, offset: (requested - 1) * 50); items = result.map { store.libraryItem($0) }; hasMore = result.count == 50
            } else {
                let result = try await store.catalog(mode, ["source": platform, "query": query, "sort": sort, "tag": tag, "page": requested], refresh: refresh)
                items = result.rows("list").map { MediaItem($0, kind: mode == "charts" ? "chart" : mode == "lists" ? "playlist" : "song") }; hasMore = result["more"] as? Bool ?? false
            }
            guard token == generation, !Task.isCancelled else { return }
            let existing = Set(rows.map(\.id)); rows += items.filter { !existing.contains($0.id) }; page = requested; more = hasMore && !items.isEmpty
        } catch { if token == generation, !Task.isCancelled { self.error = error.localizedDescription } }
        if token == generation { loading = false }
    }
    private func loadTags() async {
        let selected = platform
        guard !Task.isCancelled else { return }
        guard mode == "lists", selected != "all" else { tags = []; return }
        let result = try? await store.catalog("tags", ["source": selected])
        guard platform == selected, !Task.isCancelled else { return }
        func flatten(_ value: Any) -> [JSONObject] {
            if let list = value as? [Any] { return list.flatMap(flatten) }
            guard let row = value as? JSONObject else { return [] }
            let children = ["list", "tags", "children", "data"].compactMap { row[$0] }.flatMap(flatten)
            return children.isEmpty && !row.string("id").isEmpty && !row.string("name").isEmpty ? [row] : children
        }
        tags = result.map { flatten($0) } ?? []
    }
    private func openLink() {
        do { linkedPlaylist = try PlaylistLink.item(link, platform: platform) }
        catch { store.message = error.localizedDescription }
    }
}

enum CatalogLayout {
    static func maximumColumns(width: CGFloat, tablet: Bool) -> Int {
        // Keep cards at least 140 points wide. Phones retain at most two
        // columns even in landscape; iPad split windows clamp independently.
        max(1, min(tablet ? 4 : 2, Int((max(0, width - 32) + 8) / 148)))
    }
    static func columns(preferred: Int, width: CGFloat, tablet: Bool) -> Int {
        let automatic = tablet && width >= 600 ? 4 : 2
        return max(1, min(preferred > 0 ? preferred : automatic, maximumColumns(width: width, tablet: tablet)))
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
            if more && !loading { Button("加载更多") { Task { await load(next: true) } }.buttonStyle(AndroidButtonStyle()).accessibilityIdentifier("load-more") }
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
