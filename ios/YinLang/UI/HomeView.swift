import SwiftUI

struct HomeView: View {
    @Environment(\.colorScheme) private var colorScheme
    @EnvironmentObject var store: AppStore
    @EnvironmentObject var player: Player
    @AppStorage("home-template") private var template = "classic"
    @AppStorage("home-rotation") private var rotation = "daily"
    private var resolved: String {
        if template != "auto" { return template }
        let slot = rotation == "hourly" ? Int(Date().timeIntervalSince1970 / 3600) : Calendar.current.ordinality(of: .day, in: .era, for: Date()) ?? 0
        return ["classic", "gallery", "radio"][slot % 3]
    }
    var body: some View {
        GeometryReader { proxy in
            ScrollView {
                VStack(spacing: 12) {
                    if resolved != "classic" { Text(resolved == "gallery" ? "一首歌，一段故事。" : "此刻，听点喜欢的。").font(.subheadline).foregroundStyle(.secondary).frame(maxWidth: .infinity, alignment: .leading).padding(.bottom, 8) }
                    if colorScheme == .dark && resolved == "classic" {
                        HStack(spacing: 12) {
                            NavigationLink { MusicView() } label: { homeCard(books: false, dark: true, width: (min(1000, proxy.size.width) - 44) / 2) }
                            NavigationLink { BooksView() } label: { homeCard(books: true, dark: true, width: (min(1000, proxy.size.width) - 44) / 2) }
                        }
                    } else {
                        NavigationLink { MusicView() } label: { homeCard(books: false, dark: false, width: min(1000, proxy.size.width) - 32) }
                        NavigationLink { BooksView() } label: { homeCard(books: true, dark: false, width: min(1000, proxy.size.width) - 32) }
                    }
                    NavigationLink { DownloadsView() } label: {
                        HStack { Text("本机下载").foregroundStyle(.primary); Spacer(); Image(systemName: "chevron.right").font(.caption).foregroundStyle(.secondary) }.padding(18).background(.background, in: RoundedRectangle(cornerRadius: 16))
                    }.padding(.top, 4)
                }.padding(16).frame(maxWidth: 1000).frame(maxWidth: .infinity)
            }.background(Palette.paper)
        }.navigationTitle("音浪").toolbar {
            ToolbarItem(placement: .topBarTrailing) { NavigationLink { SettingsView() } label: { Image(systemName: "gearshape").font(.system(size: 22, weight: .regular)) }.accessibilityLabel("总设置") }
        }
    }
    @ViewBuilder private func homeCard(books: Bool, dark: Bool, width: CGFloat) -> some View {
        let height = dark ? min(300, max(190, width + 22)) : min(290, max(154, width * 0.46))
        ZStack(alignment: dark ? .bottomLeading : books ? .trailing : .leading) {
            if resolved == "classic" {
                Image("home_\(books ? "books" : "music")_\(dark ? "dark" : "light")").resizable().scaledToFill().frame(width: width, height: height).clipped()
                LinearGradient(colors: [.black.opacity(0.48), .clear], startPoint: books ? .trailing : .leading, endPoint: books ? .leading : .trailing)
            } else {
                LinearGradient(colors: resolved == "gallery" ? (books ? [Color(red: 0.86, green: 0.74, blue: 0.62), Color(red: 0.68, green: 0.5, blue: 0.37)] : [.mint.opacity(0.4), Palette.accent]) : (books ? [.purple, .orange.opacity(0.6)] : [.blue.opacity(0.8), Palette.accent]), startPoint: .topLeading, endPoint: .bottomTrailing)
                Image(systemName: books ? "book.pages" : "opticaldisc").font(.system(size: 130, weight: .ultraLight)).foregroundStyle(.white.opacity(0.3)).frame(maxWidth: .infinity, alignment: .trailing).padding(25)
            }
            VStack(alignment: .leading, spacing: 9) {
                if resolved != "classic" { Text(books ? "02 / 故事时光" : "01 / 旋律日常").font(.caption2).tracking(2) }
                Text(books ? "有声书" : "音乐").font(.system(size: dark ? 25 : 30, weight: .bold))
                Text(books ? "书架 · 发现 · 搜索" : "歌单 · 榜单 · 音乐库").font(.system(size: 12))
            }.foregroundStyle(.white).shadow(color: .black.opacity(0.3), radius: 3, y: 1).padding(dark ? 18 : 22)
        }.frame(width: width, height: height).clipShape(RoundedRectangle(cornerRadius: 20)).accessibilityElement(children: .combine)
    }
}

struct MusicView: View {
    @EnvironmentObject private var store: AppStore
    @State private var chartsCover: URL?
    @State private var listsCover: URL?
    private let columns = [GridItem(.adaptive(minimum: 150), spacing: 12)]
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 15) {
                NavigationLink { CatalogView(mode: "search") } label: { Label("搜索在线音乐或资料库", systemImage: "magnifyingglass").foregroundStyle(.secondary).frame(maxWidth: .infinity, alignment: .leading).padding(16).background(.background, in: RoundedRectangle(cornerRadius: 14)) }
                SectionTitle(title: "在线音乐")
                LazyVGrid(columns: columns, spacing: 12) {
                    NavigationLink { CatalogView(mode: "charts") } label: { ModuleCard(title: "在线排行榜", subtitle: "五个平台的榜单", icon: "chart.bar", color: .pink.opacity(0.7), image: chartsCover) }
                    NavigationLink { CatalogView(mode: "lists") } label: { ModuleCard(title: "在线歌单", subtitle: "按来源和分类浏览", icon: "square.stack", color: .orange.opacity(0.8), image: listsCover) }
                    NavigationLink { SavedView(title: "关注的歌单", type: "followed") } label: { ModuleCard(title: "关注的歌单", subtitle: "收藏喜欢的发现", icon: "bookmark", color: Palette.accent) }
                    NavigationLink { SavedView(title: "我喜欢的音乐", type: "liked") } label: { ModuleCard(title: "我喜欢的音乐", subtitle: "值得反复聆听", icon: "heart", color: .purple.opacity(0.7)) }
                }
                SectionTitle(title: "个人音乐库")
                LazyVGrid(columns: columns, spacing: 12) {
                    NavigationLink { LibraryListView(kind: "playlists", title: "本地歌单") } label: { ModuleCard(title: "本地歌单", subtitle: "Navidrome 歌单", icon: "music.note.list", color: .blue.opacity(0.7)) }
                    NavigationLink { LibraryView() } label: { ModuleCard(title: "音乐库", subtitle: "浏览 Navidrome", icon: "opticaldisc", color: .gray) }
                }
                let recent: [MediaItem] = Disk.read("recent-music.json", fallback: [])
                if let item = recent.first, item.kind == "song" { SectionTitle(title: "继续播放"); RecentSong(item: item) }
                NavigationLink { PluginSettingsView(books: false) } label: { Label("音乐设置", systemImage: "slider.horizontal.3").frame(maxWidth: .infinity, alignment: .leading).padding(18).background(.background, in: RoundedRectangle(cornerRadius: 16)) }
            }.padding(16).frame(maxWidth: 1000).frame(maxWidth: .infinity)
        }.background(Palette.paper).navigationTitle("发现音乐").task {
            if listsCover == nil, let rows = try? await store.catalog("lists", ["source": "wy", "sort": "@hot", "page": 1]).rows("list"), let first = rows.first { listsCover = store.artwork(MediaItem(first)) }
            if chartsCover == nil, let row = try? await store.catalog("charts", ["source": "wy"]).rows("list").first,
               let cover = try? await store.catalog("chart-cover", ["source": "wy", "id": MediaItem(row, kind: "chart").catalogID]) { chartsCover = cover.rows("list").first.flatMap { URL(string: $0.string("img")) } }
        }
    }
}

struct RecentSong: View {
    @EnvironmentObject var player: Player
    var item: MediaItem
    var body: some View { SongRow(item: item) { player.play([item]) }.padding(12).background(.background, in: RoundedRectangle(cornerRadius: 16)) }
}

struct SavedView: View {
    @EnvironmentObject var store: AppStore
    @EnvironmentObject var player: Player
    var title: String
    var type: String
    private var items: [MediaItem] { type == "liked" ? store.liked : type == "followed" ? store.followed : store.bookshelf }
    var body: some View {
        List {
            if items.isEmpty { ErrorPanel(text: "还没有收藏内容") }
            if type == "liked" && !items.isEmpty { Button("播放全部", systemImage: "play.fill") { player.play(items) } }
            ForEach(items) { item in
                if type == "liked" { SongRow(item: item) { player.play(items, at: items.firstIndex(of: item) ?? 0) } }
                else if type == "followed" { NavigationLink { CatalogDetailView(item: item, chart: item.kind == "chart") } label: { HStack { Artwork(item: item); Text(item.title) } } }
                else { NavigationLink { BookDetailView(book: item) } label: { HStack { Artwork(item: item); Text(item.title) } } }
            }.onDelete { indices in for index in indices.sorted(by: >) { if type == "liked" { store.liked.remove(at: index) } else if type == "followed" { store.followed.remove(at: index) } else { store.bookshelf.remove(at: index) } } }
        }.navigationTitle(title)
    }
}

struct DownloadsView: View {
    @EnvironmentObject var store: AppStore
    @EnvironmentObject var player: Player
    var body: some View {
        List {
            if store.downloads.isEmpty { ErrorPanel(text: "还没有本机下载。歌曲菜单可保存音频。") }
            ForEach(store.downloads) { download in
                HStack { SongRow(item: download.item) { player.play(store.downloads.map(\.item), at: store.downloads.firstIndex(where: { $0.id == download.id }) ?? 0) }
                    Button(role: .destructive) { store.deleteDownload(download.id) } label: { Image(systemName: "trash") }.buttonStyle(.borderless).accessibilityLabel("删除下载")
                }
            }.onDelete { indices in let ids = indices.map { store.downloads[$0].id }; ids.forEach(store.deleteDownload) }
        }.navigationTitle("本机下载")
    }
}
