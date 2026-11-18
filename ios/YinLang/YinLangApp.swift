import SwiftUI

@main
struct YinLangApp: App {
    @StateObject private var store = AppStore()
    @StateObject private var player = Player()
    @AppStorage("appearance") private var appearance = "system"
    var body: some Scene {
        WindowGroup {
            RootView().environmentObject(store).environmentObject(player)
                .tint(Palette.accent).preferredColorScheme(appearance == "light" ? .light : appearance == "dark" ? .dark : nil)
                .onAppear { player.attach(store) }
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
    @Environment(\.horizontalSizeClass) private var sizeClass
    @EnvironmentObject private var store: AppStore
    @EnvironmentObject private var player: Player
    @State private var selection: Destination? = .home
    var body: some View {
        Group {
            if sizeClass == .regular {
                NavigationSplitView {
                    List(Destination.allCases, selection: $selection) { page in Label(page.title, systemImage: page.icon).tag(page) }.navigationTitle("音浪")
                } detail: { NavigationStack { destination(selection ?? .home) } }
            } else { NavigationStack { HomeView() } }
        }
        .safeAreaInset(edge: .bottom, spacing: 0) { if player.current != nil { MiniPlayer() } }
        .sheet(isPresented: $player.showPlayer) { PlayerView().environmentObject(store).environmentObject(player) }
        .sheet(isPresented: $player.showQueue) { QueueView().environmentObject(store).environmentObject(player) }
        .alert("音浪", isPresented: Binding(get: { store.message != nil }, set: { if !$0 { store.message = nil } })) { Button("好") { store.message = nil } } message: { Text(store.message ?? "") }
    }
    @ViewBuilder private func destination(_ page: Destination) -> some View {
        switch page { case .home: HomeView(); case .music: MusicView(); case .library: LibraryView(); case .books: BooksView(); case .downloads: DownloadsView(); case .settings: SettingsView() }
    }
}
