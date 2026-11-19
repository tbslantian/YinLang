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
    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                if mode == "search", store.nav != nil { Toggle("搜索音乐库", isOn: $librarySearch).onChange(of: librarySearch) { _, _ in Task { await reload() } } }
                if !librarySearch {
                    Picker("平台", selection: $platform) { ForEach(Platform.allCases) { Text($0.title).tag($0.rawValue) } }.pickerStyle(.segmented).onChange(of: platform) { _, _ in Task { await reload(); await loadTags() } }
                }
                if mode == "lists" {
                    HStack {
                        Picker("排序", selection: $sort) { Text("热门").tag("@hot"); Text("最新").tag("@new") }.onChange(of: sort) { _, _ in Task { await reload() } }
                        Menu {
                            Button("全部分类") { tag = ""; Task { await reload() } }
                            ForEach(Array(tags.enumerated()), id: \.offset) { _, row in Button(row.string("name")) { tag = row.string("id"); Task { await reload() } } }
                        } label: { Label(tag.isEmpty ? "分类" : "已选分类", systemImage: "line.3.horizontal.decrease") }
                        Spacer()
                        Button { grid.toggle() } label: { Image(systemName: grid ? "list.bullet" : "square.grid.2x2") }.accessibilityLabel("切换网格列表")
                        Button { showLink = true } label: { Image(systemName: "link") }.accessibilityLabel("按链接打开")
                    }.font(.subheadline)
                }
                if !error.isEmpty { ErrorPanel(text: error) { Task { await reload(refresh: true) } } }
                if rows.isEmpty && !loading && error.isEmpty { ErrorPanel(text: mode == "search" ? "输入歌名或歌手，开始寻找喜欢的声音" : "暂无内容") }
                if mode == "search" {
                    LazyVStack { ForEach(rows) { item in SongRow(item: item) { player.play(rows, at: rows.firstIndex(of: item) ?? 0) } } }
                } else if grid {
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 145), spacing: 16)], spacing: 20) {
                        ForEach(filtered) { item in NavigationLink { CatalogDetailView(item: item, chart: mode == "charts") } label: { VStack(alignment: .leading, spacing: 9) { Artwork(item: item, size: 145); Text(item.title).font(.subheadline.weight(.medium)).lineLimit(2).foregroundStyle(.primary); Text(Platform(rawValue: item.source)?.title ?? "").font(.caption).foregroundStyle(.secondary) }.frame(maxWidth: .infinity, alignment: .leading) } }
                    }
                } else {
                    LazyVStack { ForEach(filtered) { item in NavigationLink { CatalogDetailView(item: item, chart: mode == "charts") } label: { HStack { Artwork(item: item); Text(item.title).foregroundStyle(.primary); Spacer(); Image(systemName: "chevron.right").foregroundStyle(.secondary) }.padding(.vertical, 6) } } }
                }
                if loading { ProgressView().padding() }
                if more && !loading { Button("加载更多") { Task { await load(page: page + 1) } }.padding().onAppear { Task { await load(page: page + 1) } } }
            }.padding(16).frame(maxWidth: 1100).frame(maxWidth: .infinity)
        }.background(Palette.paper).navigationTitle(title)
            .searchable(text: $query, prompt: mode == "charts" ? "搜索榜名" : "关键词")
            .onSubmit(of: .search) { if mode != "charts" { Task { await reload() } } }
            .refreshable { await reload(refresh: true) }
            .navigationDestination(item: $linkedPlaylist) { item in CatalogDetailView(item: item, chart: false) }
            .task { if rows.isEmpty && mode != "search" { await reload(); await loadTags() } }
            .alert("打开歌单", isPresented: $showLink) {
                TextField("歌单 ID 或链接", text: $link)
                Button("打开") { openLink() }; Button("取消", role: .cancel) {}
            } message: { Text("先选择对应平台，再填写歌单 ID 或分享链接") }
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
        List {
            HStack(spacing: 18) { Artwork(item: item, size: 100); VStack(alignment: .leading, spacing: 12) { Text(item.title).font(.title3.bold()); Text(Platform(rawValue: item.source)?.title ?? "").font(.caption).foregroundStyle(.secondary); Button(store.followed.contains(where: { $0.id == item.id }) ? "取消关注" : "关注歌单", systemImage: "bookmark") { store.toggleFollow(item) } } }.listRowBackground(Color.clear)
            if !rows.isEmpty { Button("播放全部（\(filtered.count)）", systemImage: "play.fill") { player.play(filtered) } }
            ForEach(filtered) { song in SongRow(item: song) { player.play(filtered, at: filtered.firstIndex(of: song) ?? 0) } }
            if loading { ProgressView().frame(maxWidth: .infinity) }
            if !error.isEmpty { ErrorPanel(text: error) { Task { await load(refresh: true) } } }
            if more && !loading { Button("加载更多") { Task { await load(next: true) } } }
        }.navigationTitle(item.title).navigationBarTitleDisplayMode(.inline).searchable(text: $query, prompt: "在歌单中找歌")
            .toolbar { Button { Task { await load(refresh: true) } } label: { Image(systemName: "arrow.clockwise") }.accessibilityLabel("刷新列表") }
            .task { if rows.isEmpty { await load() } }.refreshable { await load(refresh: true) }
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
