import SwiftUI

@main
struct YinLangApp: App {
    @StateObject private var store = AppStore()
    @StateObject private var player = Player()
    @AppStorage("appearance") private var appearance = "light"
    @AppStorage("personal-accent") private var accent = "original"
    @AppStorage("personal-theme") private var theme = "default"
    @Environment(\.scenePhase) private var scenePhase
    var body: some Scene {
        WindowGroup {
            TimelineView(.periodic(from: .now, by: 60)) { context in
                RootView().environmentObject(store).environmentObject(player)
                    .tint(Palette.accent).environment(\.androidAppearanceRevision, accent + theme).preferredColorScheme(Appearance.scheme(appearance, date: context.date))
            }
                .onAppear { player.attach(store)
#if DEBUG
                    UITestFixtures.prepare(store, player)
#endif
                }
                .onChange(of: scenePhase) { _, phase in if phase == .active { Task { await store.refreshIfDue() } } }
                .onOpenURL { url in
                    Task {
                        let access = url.startAccessingSecurityScopedResource(); defer { if access { url.stopAccessingSecurityScopedResource() } }
                        do { try await store.install(Data(contentsOf: url), name: url.lastPathComponent, address: "", books: url.pathExtension.lowercased() == "zip") }
                        catch { store.message = error.localizedDescription }
                    }
                }
        }
    }
}

enum Destination: String, CaseIterable, Identifiable {
    case home, music, library, books, downloads, settings
    var id: String { rawValue }
    var title: String { switch self { case .home: return "音浪"; case .music: return "音乐"; case .library: return "音乐库"; case .books: return "有声书"; case .downloads: return "本机下载"; case .settings: return "总设置" } }
    var icon: String { switch self { case .home: return "house"; case .music: return "music.note"; case .library: return "opticaldisc"; case .books: return "book.closed"; case .downloads: return "arrow.down.circle"; case .settings: return "gearshape" } }
}

struct RootView: View {
    @EnvironmentObject private var store: AppStore
    @EnvironmentObject private var player: Player
    var body: some View {
        VStack(spacing: 0) {
            NavigationStack {
#if DEBUG
            if UITestFixtures.active { UITestScreen() } else { HomeView() }
#else
            HomeView()
#endif
            }.frame(maxWidth: .infinity, maxHeight: .infinity)
                .overlay(alignment: .bottom) { ResolutionNoticeView(includesSuccess: false) }
            // Reserve real layout space outside the navigation stack, including
            // pushed pages and changes to loading / one- or two-line lyrics.
            if player.current != nil { MiniPlayer().fixedSize(horizontal: false, vertical: true).accessibilityElement(children: .contain).accessibilityIdentifier("mini-player") }
        }.background { AndroidBackground() }
        .fullScreenCover(isPresented: $player.showPlayer) { PlayerView().environmentObject(store).environmentObject(player) }
        .sheet(isPresented: Binding(get: { player.showQueue && !player.showPlayer }, set: { player.showQueue = $0 })) { QueueView().environmentObject(store).environmentObject(player) }
        .alert("音浪", isPresented: Binding(get: { store.message != nil }, set: { if !$0 { store.message = nil } })) { Button("好") { store.message = nil } } message: { Text(store.message ?? "") }
    }
}
