import SwiftUI

enum Palette {
    static let accent = Color(red: 0.16, green: 0.39, blue: 0.37)
    static let paper = Color(uiColor: .systemGroupedBackground)
}

struct Artwork: View {
    @EnvironmentObject var store: AppStore
    var item: MediaItem
    var size: CGFloat = 52
    var body: some View {
        AsyncImage(url: store.artwork(item)) { image in image.resizable().scaledToFill() } placeholder: {
            ZStack { LinearGradient(colors: [Palette.accent.opacity(0.65), .black.opacity(0.8)], startPoint: .topLeading, endPoint: .bottomTrailing)
                Image(systemName: item.kind == "book" || item.kind == "episode" ? "book.closed" : "music.note").font(.system(size: size * 0.35)).foregroundStyle(.white.opacity(0.8)) }
        }.frame(width: size, height: size).clipShape(RoundedRectangle(cornerRadius: size > 100 ? 18 : 9))
    }
}

struct SongRow: View {
    @EnvironmentObject var store: AppStore
    @EnvironmentObject var player: Player
    let item: MediaItem
    var play: () -> Void
    var body: some View {
        HStack(spacing: 12) {
            Button(action: play) {
                HStack(spacing: 12) { Artwork(item: item)
                    VStack(alignment: .leading, spacing: 5) {
                        Text(item.title).font(.system(size: 16, weight: .medium)).foregroundStyle(player.current?.id == item.id ? Palette.accent : .primary).lineLimit(1)
                        Text(item.subtitle.isEmpty ? Platform(rawValue: item.source)?.title ?? "音浪" : item.subtitle).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                    }.frame(maxWidth: .infinity, alignment: .leading)
                }.contentShape(Rectangle())
            }.buttonStyle(.plain)
            Menu {
                Button("下一首播放", systemImage: "text.line.first.and.arrowtriangle.forward") { player.insertNext(item) }
                Button(store.liked.contains(where: { $0.id == item.id }) ? "取消喜欢" : "喜欢", systemImage: "heart") { store.toggleLike(item) }
                if item.kind == "song" {
                    Button("下载到本机", systemImage: "arrow.down.circle") { Task { await store.download(item) } }
                    if item.source != "library" { Button("推送到服务器", systemImage: "server.rack") { Task { await store.push(item) } } }
                    if item.source == "library" {
                        NavigationLink("加入歌单") { AddToPlaylistView(item: item) }
                        NavigationLink("查看歌曲详情") { SongDetailsView(item: item) }
                        if !item.raw.string("albumId").isEmpty { NavigationLink("关联专辑") { LibraryListView(kind: "album", identifier: item.raw.string("albumId"), title: item.raw.string("album")) } }
                    }
                }
            } label: { if store.downloading.contains(item.id) { ProgressView() } else { Image(systemName: "ellipsis").frame(width: 32, height: 44).foregroundStyle(.secondary) } }.accessibilityLabel("歌曲操作")
        }.padding(.vertical, 5)
    }
}

struct ErrorPanel: View {
    let text: String
    var retry: (() -> Void)?
    var body: some View {
        VStack(spacing: 15) { Image(systemName: "waveform.path").font(.largeTitle).foregroundStyle(.secondary)
            Text(text).font(.callout).multilineTextAlignment(.center).foregroundStyle(.secondary)
            if let retry { Button("重试", action: retry).buttonStyle(.bordered) }
        }.frame(maxWidth: .infinity).padding(32)
    }
}

struct SectionTitle: View {
    var title: String
    var detail: String = ""
    var body: some View { VStack(alignment: .leading, spacing: 5) { Text(title).font(.title3.bold()); if !detail.isEmpty { Text(detail).font(.caption).foregroundStyle(.secondary) } }.frame(maxWidth: .infinity, alignment: .leading).padding(.top, 10) }
}

struct ModuleCard: View {
    var title: String
    var subtitle: String
    var icon: String
    var color: Color
    var image: URL? = nil
    var body: some View {
        ZStack(alignment: .bottomLeading) {
            LinearGradient(colors: [color, color.opacity(0.65), .black.opacity(0.8)], startPoint: .topLeading, endPoint: .bottomTrailing)
            if let image { AsyncImage(url: image) { image in image.resizable().scaledToFill() } placeholder: { Color.clear }.overlay(.black.opacity(0.35)) }
            Image(systemName: icon).font(.system(size: 65, weight: .ultraLight)).foregroundStyle(.white.opacity(0.22)).frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topTrailing).padding(16)
            VStack(alignment: .leading, spacing: 5) { Text(title).font(.headline); Text(subtitle).font(.caption).opacity(0.85) }.foregroundStyle(.white).padding(16)
        }.frame(height: 145).clipShape(RoundedRectangle(cornerRadius: 20)).contentShape(RoundedRectangle(cornerRadius: 20))
    }
}

func clockText(_ seconds: Double) -> String { guard seconds.isFinite else { return "0:00" }; let value = Int(max(0, seconds)); return value >= 3600 ? String(format: "%d:%02d:%02d", value / 3600, value / 60 % 60, value % 60) : String(format: "%d:%02d", value / 60, value % 60) }
