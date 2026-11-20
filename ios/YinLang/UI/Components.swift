import SwiftUI

struct Artwork: View {
    @EnvironmentObject var store: AppStore
    var item: MediaItem
    var size: CGFloat = 44
    @ViewBuilder var body: some View {
        if item.kind == "chart" { ChartCoverArtwork(item: item) { image in bitmap(image) } }
        else { bitmap(store.artwork(item)) }
    }
    private func bitmap(_ url: URL?) -> some View {
        RemoteImage(url: url) { image in image.resizable().scaledToFill().frame(width: size, height: size).clipped() } placeholder: { ZStack { Color(rgb: item.kind == "book" || item.kind == "episode" ? 0xf1e2cc : 0x173c33); Image(item.kind == "book" || item.kind == "episode" ? "android-hero_books" : "yinlang_neon").resizable().scaledToFit().padding(size * 0.1) } }.frame(width: size, height: size).clipShape(RoundedRectangle(cornerRadius: size > 100 ? 19 : 9)).allowsHitTesting(false).accessibilityHidden(true)
    }
}
// Board metadata is only the fallback. A valid board image must never suppress
// the optional first-song artwork lookup in cards, list rows or followed charts.
struct ChartCoverArtwork<Content: View>: View {
    @EnvironmentObject private var store: AppStore
    @AppStorage(ChartArtworkSettings.topCoverKey) private var topChartCover = true
    let item: MediaItem
    let chart: Bool
    let content: (URL?) -> Content
    @State private var cover: URL?
    init(item: MediaItem, chart: Bool = true, @ViewBuilder content: @escaping (URL?) -> Content) {
        self.item = item; self.chart = chart; self.content = content
    }
    private var fetchTop: Bool { chart && topChartCover }
    var body: some View {
        content((fetchTop ? cover : nil) ?? store.artwork(item))
            .task(id: item.id + "|" + item.cover + "|" + String(fetchTop)) {
                cover = nil
                guard fetchTop, let result = try? await store.catalog("chart-cover", ["source": item.source, "id": item.catalogID]),
                      !Task.isCancelled, ChartArtworkSettings.topCoverEnabled,
                      let address = result.rows("list").first?.string("img"),
                      let url = URL(string: address), ["https", "http", "fixture"].contains(url.scheme?.lowercased() ?? "") else { return }
                // Keep a usable platform cover if the first-song image URL is
                // returned but the actual image cannot be loaded.
#if DEBUG
                if url.scheme == "fixture" { cover = url; return }
#endif
                guard await ArtworkCache.shared.image(url) != nil, !Task.isCancelled,
                      ChartArtworkSettings.topCoverEnabled else { return }
                cover = url
            }
    }
}
struct MediaRow: View {
    var item: MediaItem
    var body: some View { HStack(spacing: 12) { Artwork(item: item); VStack(alignment: .leading, spacing: 5) { Text(item.title).font(.system(size: 15, weight: .bold)).foregroundStyle(Palette.ink).lineLimit(2); if !item.subtitle.isEmpty { Text(item.subtitle).font(.system(size: 13)).foregroundStyle(Palette.muted).lineLimit(2) } }.frame(maxWidth: .infinity, alignment: .leading); YLIcon(systemName: "chevron.right", size: 15).foregroundStyle(Palette.muted) }.padding(11).frame(maxWidth: .infinity, alignment: .leading).androidSurface().contentShape(Rectangle()) }
}
struct SongRow: View {
    @EnvironmentObject var store: AppStore
    @EnvironmentObject var player: Player
    let item: MediaItem
    var rank: Int? = nil
    var remove: (() -> Void)? = nil
    var play: () -> Void
    @State private var actions = false
    private var quality: String {
        let raw = item.raw
        if item.source == "library" { let rate = raw.string("bitRate"); return (raw.string("suffix").uppercased() + (rate.isEmpty ? "" : " " + rate + "K")).trimmingCharacters(in: .whitespaces) }
        let types = raw.rows("types").map { $0.string("type") } + raw.object("_types").keys.map { $0 }
        return ["master", "hires", "flac24bit", "flac", "320k", "192k", "128k"].first { types.contains($0) } ?? ""
    }
    private var detail: String {
        var value = item.subtitle
        let extra = item.source == "library" ? item.raw.string("album") : Platform(rawValue: item.source)?.title ?? ""
        if !extra.isEmpty { value += " · " + extra }
        let duration = item.duration > 0 ? clockText(item.duration) : item.raw.string("interval")
        if !duration.isEmpty { value += " · " + duration }
        return value
    }
    var body: some View {
        HStack(spacing: 6) {
            if let rank { Text("\(rank)").font(.system(size: 12, weight: .bold)).foregroundStyle(rank <= 3 ? Palette.accent : Palette.muted).frame(width: 30).padding(.vertical, 5).background(rank <= 3 ? Palette.tint : Palette.soft, in: RoundedRectangle(cornerRadius: 8)).accessibilityIdentifier("song-rank-\(rank)") }
            Button(action: play) {
                HStack(spacing: 10) {
                    if !item.cover.isEmpty { Artwork(item: item, size: 44) }
                    VStack(alignment: .leading, spacing: 5) {
                        Text(item.title).font(.system(size: 15, weight: .bold)).foregroundStyle(player.current?.id == item.id ? Palette.accent : Palette.ink).lineLimit(1)
                        Text(detail).font(.system(size: 12)).foregroundStyle(Palette.muted).lineLimit(2)
                        if !quality.isEmpty { Text(quality).font(.system(size: 10, weight: .bold)).foregroundStyle(Palette.accent).padding(.horizontal, 6).padding(.vertical, 3).background(Palette.tint, in: RoundedRectangle(cornerRadius: 7)) }
                    }.padding(.vertical, 3).frame(maxWidth: .infinity, alignment: .leading)
                }.contentShape(Rectangle())
            }.buttonStyle(.plain)
            Button { if item.source == "library" { store.toggleLike(item) } else { play() } } label: { YLIcon(systemName: item.source == "library" ? (store.isLiked(item) ? "heart.fill" : "heart") : "play.fill", size: 19).frame(width: 44, height: 48).foregroundStyle(Palette.accent) }.buttonStyle(.plain).accessibilityLabel(item.source == "library" ? "服务器收藏" : "播放 " + item.title)
            Button { actions = true } label: { if store.downloading.contains(item.id) { ProgressView().frame(width: 44) } else { YLIcon(systemName: "ellipsis", size: 19).frame(width: 44, height: 48).foregroundStyle(Palette.accent) } }.buttonStyle(.plain).accessibilityLabel("歌曲操作")
        }.padding(.horizontal, 10).padding(.vertical, 11).androidSurface()
            .sheet(isPresented: $actions) { SongActionsView(item: item, remove: remove).environmentObject(store).environmentObject(player) }
    }
}
struct SongActionsView: View {
    @EnvironmentObject var store: AppStore
    @EnvironmentObject var player: Player
    @Environment(\.dismiss) private var dismiss
    var item: MediaItem
    var remove: (() -> Void)? = nil
    var body: some View {
        NavigationStack {
            AndroidForm {
                Text(item.title).font(.system(size: 21, weight: .bold))
                Button { player.insertNext(item); dismiss() } label: { AndroidRow(title: "下一首播放", icon: "list.bullet") }.buttonStyle(.plain)
                Button { store.toggleLike(item); dismiss() } label: { AndroidRow(title: store.isLiked(item) ? "取消喜欢" : "喜欢", icon: "heart") }.buttonStyle(.plain)
                if item.kind == "episode" {
                    Button { store.downloadEpisodes([item]); dismiss() } label: { AndroidRow(title: store.downloading.contains(item.id) ? "章节下载中" : store.hasDownload(item) ? "章节已下载" : "下载当前章节", icon: "arrow.down.circle") }.buttonStyle(.plain).disabled(store.downloading.contains(item.id) || store.hasDownload(item))
                }
                if item.kind == "song" {
                    Button { Task { await store.download(item) }; dismiss() } label: { AndroidRow(title: "下载到本机", icon: "arrow.down.circle") }.buttonStyle(.plain)
                    if item.source != "library" { Button { Task { await store.push(item) }; dismiss() } label: { AndroidRow(title: "推送到服务器", icon: "server.rack") }.buttonStyle(.plain) }
                    if item.source == "library" {
                        NavigationLink { AddToPlaylistView(item: item) } label: { AndroidRow(title: "加入歌单", icon: "music.note.list") }.buttonStyle(.plain)
                        NavigationLink { SongDetailsView(item: item) } label: { AndroidRow(title: "查看歌曲详情", icon: "info.circle") }.buttonStyle(.plain)
                        if !item.raw.string("albumId").isEmpty { NavigationLink { LibraryListView(kind: "album", identifier: item.raw.string("albumId"), title: item.raw.string("album")) } label: { AndroidRow(title: "关联专辑", icon: "opticaldisc") }.buttonStyle(.plain) }
                        ForEach(Array(item.raw.rows("artists").enumerated()), id: \.offset) { _, artist in NavigationLink { LibraryListView(kind: "artist", identifier: artist.string("id"), title: artist.string("name")) } label: { AndroidRow(title: artist.string("name"), subtitle: "关联艺术家", icon: "person") }.buttonStyle(.plain) }
                        if item.raw.rows("artists").isEmpty && !item.raw.string("artistId").isEmpty { NavigationLink { LibraryListView(kind: "artist", identifier: item.raw.string("artistId"), title: item.subtitle) } label: { AndroidRow(title: "关联艺术家", subtitle: item.subtitle, icon: "person") }.buttonStyle(.plain) }
                    }
                }
                if let remove { Button("移出列表", role: .destructive) { remove(); dismiss() } }
            }.androidPage("歌曲操作")
        }.presentationDetents([.medium, .large]).presentationDragIndicator(.visible).presentationCornerRadius(30)
    }
}
struct ErrorPanel: View {
    let text: String
    var retry: (() -> Void)?
    var body: some View { VStack(alignment: .leading, spacing: 15) { Text(text).font(.system(size: 14)).foregroundStyle(Palette.muted); if let retry { Button("重试", action: retry).buttonStyle(AndroidButtonStyle()) } }.frame(maxWidth: .infinity, alignment: .leading).padding(16) }
}
struct SectionTitle: View {
    var title: String
    var detail: String = ""
    var body: some View { VStack(alignment: .leading, spacing: 5) { Text(title).font(.system(size: 20, weight: .bold)).foregroundStyle(Palette.ink).accessibilityAddTraits(.isHeader); if !detail.isEmpty { Text(detail).font(.system(size: 13)).foregroundStyle(Palette.muted) } }.frame(maxWidth: .infinity, alignment: .leading).padding(.top, 14).padding(.bottom, 4) }
}
struct FrostedArtwork: View {
    var color: Color
    var image: URL?
    var seed: String
    var alternatives: [URL] = []
    var body: some View {
        GeometryReader { proxy in
            ZStack {
                RadialGradient(colors: [Color(rgb: 0x7d7b85), color, Color(rgb: 0x14141b)], center: UnitPoint(x: 0.74, y: 0.19), startRadius: 0, endRadius: proxy.size.width * 0.85)
                ForEach(1...5, id: \.self) { index in Circle().stroke(.white.opacity(0.24), lineWidth: 1.05).frame(width: proxy.size.width * (0.095 + Double(index) * 0.067) * 2).position(x: proxy.size.width * 0.74, y: proxy.size.height * 0.19) }
                if !alternatives.isEmpty { EntryCover(urls: alternatives).frame(width: proxy.size.width, height: proxy.size.height).clipped() }
                else if let image { RemoteImage(url: image) { image in image.resizable().scaledToFill().frame(width: proxy.size.width, height: proxy.size.height).clipped() } placeholder: { Color.clear } }
                LinearGradient(colors: [.clear, Color(rgb: 0x23181a).opacity(0.9)], startPoint: UnitPoint(x: 0.5, y: 0.46), endPoint: .bottom)
            }
        }.clipped().allowsHitTesting(false).accessibilityHidden(true)
    }
}
private struct EntryCover: View {
    let urls: [URL]
    @State private var image: Image?
    var body: some View {
        GeometryReader { proxy in
            if let image { image.resizable().scaledToFill().frame(width: proxy.size.width, height: proxy.size.height).clipped() }
        }.task(id: urls) {
            image = nil
            for url in urls.prefix(6) {
                guard !Task.isCancelled else { return }
#if DEBUG
                if UITestFixtures.active, url.scheme == "fixture" { image = Image(String(url.absoluteString.dropFirst("fixture:".count))); return }
#endif
                if let bitmap = await ArtworkCache.shared.image(url) {
                    guard !Task.isCancelled else { return }; image = Image(uiImage: bitmap); return
                }
            }
        }.allowsHitTesting(false).accessibilityHidden(true)
    }
}
struct ModuleCard: View {
    var title: String
    var subtitle: String
    var icon: String
    var color: Color
    var image: URL? = nil
    var height: CGFloat = 96
    var alternatives: [URL] = []
    var body: some View {
        ZStack(alignment: .bottomLeading) {
            FrostedArtwork(color: color, image: image, seed: title, alternatives: alternatives)
            VStack(alignment: .leading, spacing: 4) { Text(title).font(.system(size: 16, weight: .bold)).lineLimit(2); if !subtitle.isEmpty { Text(subtitle).font(.system(size: 12)).lineLimit(1) } }.foregroundStyle(.white).shadow(color: .black.opacity(0.8), radius: 3, y: 1).padding(.leading, 12).padding(.trailing, 10).padding(.bottom, 12)
        }.frame(maxWidth: .infinity).frame(height: height).clipShape(RoundedRectangle(cornerRadius: 16)).overlay { RoundedRectangle(cornerRadius: 16).stroke(LinearGradient(colors: [.white.opacity(0.58), .white.opacity(0.18), .white.opacity(0.39)], startPoint: .topLeading, endPoint: .bottomTrailing), lineWidth: 1) }.contentShape(RoundedRectangle(cornerRadius: 16))
    }
}
struct CatalogCard: View {
    let item: MediaItem
    let chart: Bool
    var height: CGFloat = 154
    var body: some View {
        ChartCoverArtwork(item: item, chart: chart) { image in
            ZStack(alignment: .bottomLeading) {
                FrostedArtwork(color: Color(rgb: 0x62466d), image: image, seed: item.id)
                VStack(alignment: .leading, spacing: 4) { Text(item.title).font(.system(size: 16, weight: .bold)).lineLimit(2); if !chart { Text(item.raw.string("author", "name")).font(.system(size: 11)).lineLimit(1) } }.foregroundStyle(.white).shadow(color: .black.opacity(0.8), radius: 3, y: 1).padding(16)
                Text(Platform(rawValue: item.source)?.title ?? "").font(.system(size: 11, weight: .bold)).foregroundStyle(Palette.ink).padding(.horizontal, 8).padding(.vertical, 5).background(.ultraThinMaterial, in: Capsule()).frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading).padding(9)
            }.frame(maxWidth: .infinity).frame(height: height).clipShape(RoundedRectangle(cornerRadius: 17)).overlay { RoundedRectangle(cornerRadius: 17).stroke(.white.opacity(0.3), lineWidth: 1).allowsHitTesting(false) }.contentShape(RoundedRectangle(cornerRadius: 17))
        }
    }
}
func clockText(_ seconds: Double) -> String { guard seconds.isFinite else { return "0:00" }; let value = Int(max(0, seconds)); return value >= 3600 ? String(format: "%d:%02d:%02d", value / 3600, value / 60 % 60, value % 60) : String(format: "%d:%02d", value / 60, value % 60) }
