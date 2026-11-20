import SwiftUI

struct PlayerMenuView: View {
    @EnvironmentObject private var store: AppStore
    @EnvironmentObject private var player: Player
    @Environment(\.dismiss) private var dismiss
    @AppStorage("player-vinyl") private var vinyl = false
    @AppStorage("quality") private var quality = "320k"
    @State private var destination: Destination?
    private enum Destination: String, Identifiable {
        case mode, speed, queue, sleep, cast, style, quality, effects, settings
        var id: String { rawValue }
    }
    private var current: MediaItem? { player.current }
    private var book: Bool { current?.kind == "episode" }
    private var online: Bool { current?.source != "library" && current?.source != "offline" && !book }
    private var savedBook: MediaItem? { current.flatMap { try? BookPlayback.book(for: $0, shelf: store.bookshelf) } }
    private var saved: Bool {
        if book { return savedBook.map { item in store.bookshelf.contains { $0.id == item.id } } ?? false }
        return current.map(store.isLiked) ?? false
    }
    var body: some View {
        NavigationStack {
            VStack(spacing: 14) {
                header
                ScrollView {
                    VStack(spacing: 5) {
                        actions.padding(.bottom, 7)
                        if let item = current, item.source == "library", !book {
                            NavigationLink { SongDetailsView(item: item) } label: { row("info.circle", "查看", "歌曲详情、专辑与艺术家") }
                        }
                        if let item = current, item.source != "offline" {
                            Button { Task { await store.download(item) }; dismiss() } label: {
                                row("arrow.down.circle", book ? "下载当前章节" : "下载到手机", store.downloading.contains(item.id) ? "下载中" : store.hasDownload(item) ? "已下载" : "")
                            }.disabled(store.downloading.contains(item.id) || (book && store.hasDownload(item))).accessibilityIdentifier("player-menu-download")
                            if !book, item.source != "library" {
                                if UserDefaults.standard.bool(forKey: "server-enabled"), !(UserDefaults.standard.string(forKey: "server-url") ?? "").isEmpty {
                                    Button { Task { await store.push(item) }; dismiss() } label: { row("server.rack", "推送到服务端下载") }.accessibilityIdentifier("player-menu-push")
                                } else { NavigationLink { SettingsView() } label: { row("server.rack", "推送到服务端下载", "未配置") }.accessibilityIdentifier("player-menu-push") }
                            }
                        }
                        Button { destination = .cast } label: { row("airplayaudio", "投送", player.dlna.current?.name ?? "") }.accessibilityIdentifier("player-menu-cast")
                        if !book {
                            Button { destination = .style } label: { row("opticaldisc", "播放器样式", vinyl ? "旋转黑胶" : "方形封面") }.accessibilityIdentifier("player-menu-style")
                            Button { if online { destination = .quality } } label: { row("music.note", "音质", online ? QualityPicker.options.first { $0.0 == quality }?.1 ?? quality : "原始音质", chevron: online) }.disabled(!online).accessibilityIdentifier("player-menu-quality")
                            Button { destination = .effects } label: { row("slider.horizontal.3", "音效", AudioEffects.shared.config.enabled ? "已开启" : "原声") }.accessibilityIdentifier("player-menu-effects")
                        }
                        Button { destination = .settings } label: { row("gearshape", "播放设置") }.accessibilityIdentifier("player-menu-settings")
                        HStack(spacing: 7) {
                            YLIcon(systemName: "speaker.wave.3", size: 20).foregroundStyle(Palette.accent).frame(width: 31)
                            Text("调节音量").font(.system(size: 15, weight: .bold))
                            SystemVolume().frame(minWidth: 60, minHeight: 40)
                        }.padding(.horizontal, 9).padding(.vertical, 7).androidSurface()
                    }.padding(.bottom, 12)
                }
            }.padding(.horizontal, 16).padding(.top, 18)
                .background { AndroidBackground() }.foregroundStyle(Palette.ink).buttonStyle(.plain)
                .toolbar(.hidden, for: .navigationBar)
        }.presentationDetents([.fraction(0.78), .large]).presentationCornerRadius(30).presentationDragIndicator(.visible)
            .sheet(item: $destination) { target in sheet(target).environmentObject(store).environmentObject(player) }
    }
    private var header: some View {
        HStack(spacing: 12) {
            if let current { Artwork(item: current, size: 47) }
            VStack(alignment: .leading, spacing: 6) {
                Text(current?.title ?? "音浪").font(.system(size: 17, weight: .bold)).lineLimit(1)
                Text(current?.subtitle ?? "").font(.system(size: 13)).foregroundStyle(Palette.muted).lineLimit(1)
            }.frame(maxWidth: .infinity, alignment: .leading)
            Button { dismiss() } label: { YLIcon(systemName: "xmark", size: 22).frame(width: 44, height: 44) }.accessibilityLabel("关闭播放器菜单")
        }
    }
    private var actions: some View {
        HStack(spacing: 7) {
            if current?.source != "offline", !book || savedBook != nil {
                tile(saved ? "star.fill" : "star", saved ? "取消收藏" : "加入收藏") {
                    if book, let savedBook { store.toggleShelf(savedBook) } else if let current { store.toggleLike(current) }
                    dismiss()
                }
            }
            if !book { tile(player.mode.icon, "播放模式") { destination = .mode } }
            tile("slider.horizontal.3", "播放速度") { destination = .speed }
            tile("list.bullet", "播放队列") { destination = .queue }
            tile("timer", "定时关闭") { destination = .sleep }
        }
    }
    private func tile(_ icon: String, _ title: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(spacing: 6) {
                YLIcon(systemName: icon, size: 22).foregroundStyle(Palette.accent).frame(height: 28)
                Text(title).font(.system(size: 12, weight: .bold)).lineLimit(2).multilineTextAlignment(.center)
            }.padding(.horizontal, 2).padding(.top, 10).padding(.bottom, 9).frame(maxWidth: .infinity, minHeight: 71).androidSurface()
        }.accessibilityLabel(title).accessibilityIdentifier("player-menu-action-" + title)
    }
    private func row(_ icon: String, _ title: String, _ value: String = "", chevron: Bool = true) -> some View {
        HStack(spacing: 8) {
            YLIcon(systemName: icon, size: 20).foregroundStyle(Palette.accent).frame(width: 31, height: 32)
            Text(title).font(.system(size: 15, weight: .bold))
            Spacer(minLength: 4)
            if !value.isEmpty { Text(value).font(.system(size: 13)).foregroundStyle(Palette.muted).lineLimit(2) }
            if chevron { YLIcon(systemName: "chevron.right", size: 15).foregroundStyle(Palette.muted).frame(width: 18) }
        }.padding(.leading, 9).padding(.trailing, 8).padding(.vertical, 6).frame(minHeight: 50).androidSurface().contentShape(Rectangle())
    }
    @ViewBuilder private func sheet(_ target: Destination) -> some View {
        switch target {
        case .mode: PlayerChoice(title: "播放模式", rows: PlayMode.allCases.map(\.title), selected: player.mode.title) { value in if let mode = PlayMode.allCases.first(where: { $0.title == value }) { player.mode = mode } }
        case .speed: PlayerChoice(title: "播放速度", rows: [0.5, 0.75, 1, 1.25, 1.5, 1.75, 2, 2.5, 3].map { String(format: "%g×", $0) }, selected: String(format: "%g×", player.speed)) { player.speed = Float($0.dropLast()) ?? 1 }
        case .queue: QueueView()
        case .sleep: PlayerChoice(title: "定时关闭", rows: ["15 分钟", "30 分钟", "45 分钟", "60 分钟", "90 分钟", "播完当前内容暂停", "关闭定时"], selected: "") { value in
            if value == "播完当前内容暂停" { player.sleepAfterCurrent = true; player.sleepUntil = nil }
            else { player.setSleep(Int(value.components(separatedBy: " ").first ?? "")) }
        }
        case .cast: CastView(player: player)
        case .style: PlayerChoice(title: "播放器样式", rows: ["旋转黑胶", "方形封面"], selected: vinyl ? "旋转黑胶" : "方形封面") { vinyl = $0 == "旋转黑胶" }
        case .quality: PlayerChoice(title: "音质", rows: QualityPicker.options.map { $0.1 }, selected: QualityPicker.options.first { $0.0 == quality }?.1 ?? quality) { value in
            if let option = QualityPicker.options.first(where: { $0.1 == value }) { quality = option.0; player.reloadQuality() }
        }
        case .effects: EffectsView()
        case .settings: NavigationStack { PlaybackPreferencesView() }
        }
    }
}

private struct PlaybackPreferencesView: View {
    @AppStorage("player-pause-on-unplug") private var pauseOnUnplug = true
    @AppStorage("player-keep-screen-on") private var keepScreenOn = false
    var body: some View {
        AndroidForm { AndroidSection { Toggle("耳机断开时暂停", isOn: $pauseOnUnplug); Toggle("播放页保持屏幕常亮", isOn: $keepScreenOn) } }.androidPage("播放设置")
    }
}
