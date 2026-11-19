import SwiftUI

struct HomeView: View {
    @Environment(\.colorScheme) private var scheme
    @AppStorage("home-template") private var template = "classic"
    @AppStorage("home-rotation") private var rotation = "daily"
    private var resolved: String {
        guard template == "auto" else { return template }
        let day = Calendar.current.dateComponents([.year, .month, .day], from: Date())
        let utcDay = Calendar(identifier: .gregorian).date(from: day) ?? Date()
        let offset = TimeZone.current.secondsFromGMT(for: utcDay)
        let slot = rotation == "hourly" ? Int(Date().timeIntervalSince1970 / 3600) : Int((utcDay.timeIntervalSince1970 + Double(offset)) / 86400)
        return ["classic", "gallery", "radio"][((slot % 3) + 3) % 3]
    }
    var body: some View {
        GeometryReader { proxy in
            let width = max(0, proxy.size.width - 32)
            ScrollView {
                VStack(spacing: 10) {
                    if resolved != "classic" {
                        Text(resolved == "gallery" ? "一首歌，一段故事。" : "此刻，听点喜欢的。").font(.system(size: 13)).foregroundStyle(Palette.muted).frame(maxWidth: .infinity, alignment: .leading).padding(.bottom, 8)
                        entry(books: false, width: width)
                        entry(books: true, width: width).padding(.top, 4)
                    } else if scheme == .dark {
                        HStack(spacing: 10) { entry(books: false, width: (width - 10) / 2); entry(books: true, width: (width - 10) / 2) }
                    } else { entry(books: false, width: width); entry(books: true, width: width) }
                    NavigationLink { DownloadsView() } label: { AndroidRow(title: "本机下载", icon: "arrow.down.circle") }.padding(.top, 4)
                }.padding(.horizontal, 16).padding(.top, 4).padding(.bottom, 22)
            }
        }.androidPage("音浪", category: "", root: true) {
            NavigationLink { SettingsView() } label: { Image("android-ic_settings_cupertino").renderingMode(.template).resizable().scaledToFit().frame(width: 28, height: 28).foregroundStyle(Palette.ink).frame(width: 48, height: 48) }.accessibilityLabel("总设置")
        }.buttonStyle(.plain)
    }
    private func entry(books: Bool, width: CGFloat) -> some View {
        NavigationLink { if books { BooksView() } else { MusicView() } } label: { HomeEntry(books: books, template: resolved, width: width) }.buttonStyle(.plain)
    }
}

struct HomeEntry: View {
    @Environment(\.colorScheme) private var scheme
    let books: Bool
    let template: String
    let width: CGFloat
    private var dark: Bool { scheme == .dark }
    private var gallery: Bool { template == "gallery" }
    private var height: CGFloat { template != "classic" ? max(200, min(232, width * 0.58)) : dark ? max(164, min(238, width + 22)) : max(154, min(214, width * 0.46)) }
    private var colors: [Color] {
        let values: [UInt32]
        if gallery { values = books ? (dark ? [0x3b2f2b,0x312a29] : [0xf2e8dc,0xeee0cd]) : (dark ? [0x243c34,0x24332f] : [0xe9eee7,0xdce7db]) }
        else { values = books ? (dark ? [0x663951,0xa15a4b] : [0x854256,0xbc7057]) : (dark ? [0x173d53,0x216966] : [0x204966,0x2b7c77]) }
        return values.map { Color(rgb: $0) }
    }
    private var ink: Color { gallery ? Color(rgb: books ? (dark ? 0xf5e8dd : 0x513a2e) : (dark ? 0xeef2eb : 0x243e34)) : .white }
    var body: some View {
        ZStack(alignment: template == "classic" && dark ? .bottomLeading : .leading) {
            if template == "classic" {
                Image("home_\(books ? "books" : "music")_\(dark ? "dark" : "light")").resizable().scaledToFill().frame(width: width, height: height).clipped()
                LinearGradient(colors: dark ? [.clear, .black.opacity(0.83)] : [Color(rgb: 0x110c08).opacity(0.58), Color(rgb: 0x110c08).opacity(0.31), .clear], startPoint: dark ? .top : books ? .trailing : .leading, endPoint: dark ? .bottom : books ? .leading : .trailing)
                VStack(alignment: .leading, spacing: dark ? 4 : 5) { Text(books ? "有声书" : "音乐").font(.system(size: dark ? 22 : 28, weight: .bold)); Text(books ? "书架 · 发现 · 搜索" : "歌单 · 榜单 · 音乐库").font(.system(size: 11)) }.foregroundStyle(.white).shadow(color: .black.opacity(0.6), radius: 3, y: 1).padding(dark ? 17 : 16).frame(width: dark ? width : width / 2, alignment: .leading).frame(maxWidth: .infinity, alignment: dark || !books ? .leading : .trailing)
            } else {
                LinearGradient(colors: colors, startPoint: .topLeading, endPoint: .bottomTrailing)
                Image("android-home-\(template)-\(books ? "books" : "music")-\(dark ? "dark" : "light")").resizable().scaledToFit().frame(width: width * (gallery ? 0.46 : 0.55), height: height).frame(maxWidth: .infinity, alignment: .trailing)
                VStack(alignment: .leading, spacing: 0) {
                    Text(gallery ? (books ? "02 / 故事时光" : "01 / 旋律日常") : (books ? "STORY CHANNEL" : "MUSIC CHANNEL")).font(.system(size: 10, weight: .bold)).tracking(0.8).opacity(0.7)
                    Text(books ? "有声书" : "音乐").font(.system(size: gallery ? 29 : 32, weight: .bold)).padding(.top, 10)
                    Text(books ? "书架 · 发现 · 搜索" : "歌单 · 榜单 · 音乐库").font(.system(size: 12)).opacity(0.7).padding(.top, 8)
                    Text(books ? "走进故事" : "听见好音乐").font(.system(size: 12, weight: .bold)).padding(.top, 22)
                }.foregroundStyle(ink).padding(.leading, 22).frame(width: width * 0.62, alignment: .leading)
            }
        }.frame(width: width, height: height).clipShape(RoundedRectangle(cornerRadius: template == "classic" ? (dark ? 19 : 20) : 24)).accessibilityElement(children: .combine)
    }
}

struct MusicView: View {
    @EnvironmentObject private var store: AppStore
    @State private var chartsCover: URL?
    @State private var listsCover: URL?
    private let columns = [GridItem(.flexible(), spacing: 8), GridItem(.flexible(), spacing: 8)]
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 8) {
                NavigationLink { CatalogView(mode: "search") } label: { SearchEntry(title: "搜索在线音乐或资料库") }
                SectionTitle(title: "在线音乐")
                LazyVGrid(columns: columns, spacing: 8) {
                    NavigationLink { CatalogView(mode: "charts") } label: { ModuleCard(title: "在线排行榜", subtitle: "五个平台的榜单", icon: "chart.bar", color: Color(rgb: 0x843c42), image: chartsCover) }
                    NavigationLink { CatalogView(mode: "lists") } label: { ModuleCard(title: "在线歌单", subtitle: "按来源和分类浏览", icon: "square.stack", color: Color(rgb: 0x77501d), image: listsCover) }
                    NavigationLink { SavedView(title: "关注的歌单", type: "followed") } label: { ModuleCard(title: "关注的歌单", subtitle: "", icon: "bookmark", color: Color(rgb: 0x245b5b), image: store.followed.first.flatMap { store.artwork($0) }) }
                    NavigationLink { SavedView(title: "我喜欢的音乐", type: "liked") } label: { ModuleCard(title: "我喜欢的音乐", subtitle: "", icon: "heart", color: Color(rgb: 0x62466d), image: store.liked.first.flatMap { store.artwork($0) }) }
                }
                SectionTitle(title: "个人音乐库")
                LazyVGrid(columns: columns, spacing: 8) {
                    NavigationLink { LibraryListView(kind: "playlists", title: "本地歌单") } label: { ModuleCard(title: "本地歌单", subtitle: "Navidrome 歌单", icon: "music.note.list", color: Color(rgb: 0x2d4e76)) }
                    NavigationLink { LibraryView() } label: { ModuleCard(title: "音乐库", subtitle: "浏览 Navidrome", icon: "opticaldisc", color: Color(rgb: 0x405665)) }
                }
                let recent: [MediaItem] = Disk.read("recent-music.json", fallback: [])
                if let item = recent.first, item.kind == "song" { SectionTitle(title: "继续听歌"); RecentSong(item: item) }
                NavigationLink { PluginSettingsView(books: false) } label: { AndroidRow(title: "音乐设置", subtitle: "LX 音源与优先音质", icon: "slider.horizontal.3") }.padding(.top, 6)
            }.padding(.horizontal, 16).padding(.top, 4).padding(.bottom, 22)
        }.androidPage("发现音乐", category: "音乐").buttonStyle(.plain).task {
            if listsCover == nil, let rows = try? await store.catalog("lists", ["source": "wy", "sort": "@hot", "page": 1]).rows("list"), let first = rows.first { listsCover = store.artwork(MediaItem(first)) }
            if chartsCover == nil, let row = try? await store.catalog("charts", ["source": "wy"]).rows("list").first,
               let cover = try? await store.catalog("chart-cover", ["source": "wy", "id": MediaItem(row, kind: "chart").catalogID]) { chartsCover = cover.rows("list").first.flatMap { URL(string: $0.string("img")) } }
        }
    }
}
struct SearchEntry: View {
    var title: String
    var body: some View { HStack(spacing: 6) { YLIcon(systemName: "magnifyingglass", size: 18).foregroundStyle(Palette.accent).frame(width: 29); Text(title).font(.system(size: 14)).foregroundStyle(Palette.muted); Spacer(); YLIcon(systemName: "chevron.right", size: 16).foregroundStyle(Palette.muted) }.padding(.horizontal, 12).frame(minHeight: 48).androidSurface(radius: 24) }
}
struct RecentSong: View {
    @EnvironmentObject var player: Player
    var item: MediaItem
    var body: some View { SongRow(item: item) { player.play([item]) } }
}
struct SavedView: View {
    @EnvironmentObject var store: AppStore
    @EnvironmentObject var player: Player
    var title: String
    var type: String
    private var items: [MediaItem] { type == "liked" ? store.liked : type == "followed" ? store.followed : store.bookshelf }
    var body: some View {
        AndroidList {
            if items.isEmpty { ErrorPanel(text: "还没有收藏内容") }
            if type == "liked" && !items.isEmpty { Button("播放全部", systemImage: "play.fill") { player.play(items) }.buttonStyle(AndroidButtonStyle(primary: true)) }
            ForEach(items) { item in
                if type == "liked" { SongRow(item: item, remove: { store.liked.removeAll { $0.id == item.id } }) { player.play(items, at: items.firstIndex(of: item) ?? 0) } }
                else if type == "followed" { NavigationLink { CatalogDetailView(item: item, chart: item.kind == "chart") } label: { MediaRow(item: item) }.contextMenu { Button("取消关注", role: .destructive) { store.toggleFollow(item) } } }
                else { NavigationLink { BookDetailView(book: item) } label: { MediaRow(item: item) }.contextMenu { Button("移出书架", role: .destructive) { store.toggleShelf(item) } } }
            }
        }.androidPage(title, category: type == "bookshelf" ? "有声书" : "音乐")
    }
}
struct DownloadsView: View {
    @EnvironmentObject var store: AppStore
    @EnvironmentObject var player: Player
    var body: some View {
        AndroidList {
            if store.downloads.isEmpty { ErrorPanel(text: "还没有本机下载。歌曲菜单可保存音频。") }
            ForEach(store.downloads) { download in
                HStack { SongRow(item: download.item) { player.play(store.downloads.map(\.item), at: store.downloads.firstIndex(where: { $0.id == download.id }) ?? 0) }
                    Button(role: .destructive) { store.deleteDownload(download.id) } label: { YLIcon(systemName: "trash").frame(width: 44, height: 44) }.accessibilityLabel("删除下载") }
            }
        }.androidPage("本机下载")
    }
}
