import SwiftUI

// Values and artwork are taken from Android MainActivity.applyAppearance and UiIcon.
// Phone and tablet use the same components; only available width changes layout.
enum Palette {
    static func color(_ light: UInt32, _ dark: UInt32) -> Color {
        Color(uiColor: UIColor { traits in UIColor(rgb: traits.userInterfaceStyle == .dark ? dark : light) })
    }
    static var accent: Color {
        switch UserDefaults.standard.string(forKey: "personal-accent") ?? "original" {
        case "blue": return color(0x405f85, 0xaac7ec)
        case "green": return color(0x35694b, 0x9bd5b4)
        default: return color(0xa9432f, 0xa4e7d7)
        }
    }
    static var tint: Color {
        switch UserDefaults.standard.string(forKey: "personal-accent") ?? "original" {
        case "blue": return color(0xe4ebf5, 0x243348)
        case "green": return color(0xe3eee5, 0x20392c)
        default: return color(0xf4e1d7, 0x243d3c)
        }
    }
    static var background: Color { color(0xf7f1ea, 0x111219) }
    static var paper: Color { color(0xfffbf6, 0x20222b).opacity(UserDefaults.standard.string(forKey: "personal-theme") == "transparent" ? 0.73 : 1) }
    static var ink: Color { color(0x251b18, 0xf6f4f2) }
    static var muted: Color { color(0x66564e, 0xb7bac6) }
    static var soft: Color { color(0xede3da, 0x2a2d39) }
    static var onAccent: Color { color(0xffffff, 0x112e29) }
}
extension UIColor {
    convenience init(rgb: UInt32) { self.init(red: CGFloat((rgb >> 16) & 255) / 255, green: CGFloat((rgb >> 8) & 255) / 255, blue: CGFloat(rgb & 255) / 255, alpha: 1) }
}
extension Color { init(rgb: UInt32) { self.init(uiColor: UIColor(rgb: rgb)) } }

struct AndroidBackground: View {
    @Environment(\.colorScheme) private var scheme
    @Environment(\.androidMaterialSettings) private var material
    @AppStorage("personal-theme") private var theme = "default"
    var body: some View {
        Group {
            if material { Palette.color(0xfaf9ff, 0x14151d) }
            else if theme == "transparent" { LinearGradient(colors: (scheme == .dark ? [0x142631, 0x302638, 0x152c28] : [0xe3edf4, 0xf2e5ef, 0xe0eee7]).map { Color(rgb: $0) }, startPoint: .topLeading, endPoint: .bottomTrailing) }
            else { Palette.background }
        }.ignoresSafeArea()
    }
}

struct YLIcon: View {
    let systemName: String
    var size: CGFloat = 22
    init(systemName: String, size: CGFloat = 22) { self.systemName = systemName; self.size = size }
    private var asset: String? {
        let map = ["chevron.left":"back", "chevron.down":"down", "chevron.right":"next", "arrow.right":"arrow_right", "xmark":"close", "play.fill":"play", "pause.fill":"pause", "backward.end.fill":"previous_track", "forward.end.fill":"next_track", "ellipsis":"ellipsis", "list.bullet":"queue", "music.note.list":"list", "quote.bubble":"lyrics", "quote.bubble.fill":"lyrics", "magnifyingglass":"search", "chart.bar":"chart", "square.stack":"list", "bookmark":"book", "heart":"heart", "heart.fill":"heart_filled", "star":"star", "star.fill":"star_filled", "opticaldisc":"album", "book.closed":"book", "book.pages":"book", "books.vertical":"book", "safari":"discover", "puzzlepiece.extension":"plugin", "gearshape":"settings", "slider.horizontal.3":"sliders", "arrow.down.circle":"download", "trash":"delete", "server.rack":"server", "airplayaudio":"airplay", "airplayaudio.circle.fill":"airplay", "sun.max":"sun", "moon":"moon", "arrow.clockwise":"refresh", "externaldrive":"storage", "person.2":"artist", "person":"artist", "music.note":"song", "arrow.up.arrow.down":"sort", "arrow.right.to.line":"sequence", "repeat":"repeat", "repeat.1":"repeat_one", "shuffle":"shuffle", "speaker":"volume_low", "speaker.wave.3":"volume_high", "gobackward.15":"back_15", "goforward.30":"forward_30", "timer":"timer", "timer.circle.fill":"timer", "info.circle":"info"]
        return map[systemName].map { "android-icon-" + $0 }
    }
    var body: some View {
        Group { if let asset { Image(asset).renderingMode(.template).resizable().scaledToFit() } else { Image(systemName: systemName).resizable().scaledToFit() } }.frame(width: size, height: size).accessibilityHidden(true)
    }
}

private struct AndroidPage: ViewModifier {
    var title: String
    var category: String
    var root: Bool
    var trailing: AnyView
    @Environment(\.dismiss) private var dismiss
    @Environment(\.androidAppearanceRevision) private var revision
    func body(content: Content) -> some View {
        content
            .safeAreaInset(edge: .top, spacing: 0) {
                HStack(spacing: 12) {
                    if !root { Button { dismiss() } label: { YLIcon(systemName: "chevron.left", size: 24).frame(width: 44, height: 48) }.buttonStyle(.plain).accessibilityLabel("返回") }
                    VStack(alignment: .leading, spacing: 4) {
                        if !category.isEmpty { Text(category).font(.system(size: 11, weight: .bold)).tracking(0.6).foregroundStyle(Palette.muted) }
                        Text(title).font(.system(size: root ? 34 : 25, weight: .bold)).foregroundStyle(Palette.ink).lineLimit(2).accessibilityAddTraits(.isHeader).accessibilityIdentifier("page-title-" + title)
                    }.frame(maxWidth: .infinity, alignment: .leading)
                    trailing
                }.padding(.horizontal, 16).padding(.top, 12).padding(.bottom, 14).background { AndroidBackground() }
            }
            .toolbar(.hidden, for: .navigationBar)
            .background { AndroidBackground() }
            .foregroundStyle(Palette.ink)
    }
}
extension View {
    func androidPage(_ title: String, category: String = "音浪", root: Bool = false) -> some View { modifier(AndroidPage(title: title, category: category, root: root, trailing: AnyView(EmptyView()))) }
    func androidPage<T: View>(_ title: String, category: String = "音浪", root: Bool = false, @ViewBuilder trailing: () -> T) -> some View { modifier(AndroidPage(title: title, category: category, root: root, trailing: AnyView(trailing()))) }
    func androidSurface(radius: CGFloat = 16) -> some View { background(Palette.paper, in: RoundedRectangle(cornerRadius: radius)).overlay { RoundedRectangle(cornerRadius: radius).stroke(Palette.ink.opacity(0.04), lineWidth: 1).allowsHitTesting(false) } }
}

struct AndroidForm<Content: View>: View {
    @ViewBuilder var content: Content
    var body: some View { ScrollView { VStack(alignment: .leading, spacing: 16) { content }.padding(.horizontal, 16).padding(.top, 4).padding(.bottom, 22).frame(maxWidth: .infinity, alignment: .leading) }.background { AndroidBackground() }.font(.system(size: 14)).textFieldStyle(AndroidFieldStyle()).toggleStyle(AndroidToggleStyle()).pickerStyle(.menu).buttonStyle(AndroidButtonStyle()) }
}
struct AndroidList<Content: View>: View {
    @ViewBuilder var content: Content
    var body: some View { ScrollView { LazyVStack(alignment: .leading, spacing: 8) { content }.padding(.horizontal, 16).padding(.top, 4).padding(.bottom, 22).frame(maxWidth: .infinity, alignment: .leading) }.background { AndroidBackground() }.buttonStyle(.plain) }
}
struct AndroidSection<Content: View>: View {
    var title: String = ""
    @ViewBuilder var content: Content
    init(_ title: String = "", @ViewBuilder content: () -> Content) { self.title = title; self.content = content() }
    var body: some View { VStack(alignment: .leading, spacing: 12) { if !title.isEmpty { Text(title).font(.system(size: 20, weight: .bold)).accessibilityAddTraits(.isHeader).padding(.top, 8) }; VStack(alignment: .leading, spacing: 14) { content }.frame(maxWidth: .infinity, alignment: .leading).padding(16).androidSurface(radius: 18) } }
}
struct AndroidFieldStyle: TextFieldStyle {
    func _body(configuration: TextField<Self._Label>) -> some View { configuration.font(.system(size: 14)).padding(.horizontal, 13).padding(.vertical, 13).background(Palette.soft, in: RoundedRectangle(cornerRadius: 12)) }
}
struct AndroidButtonStyle: ButtonStyle {
    var primary = false
    func makeBody(configuration: Configuration) -> some View { configuration.label.font(.system(size: 14, weight: .semibold)).foregroundStyle(primary ? Palette.onAccent : Palette.accent).padding(.horizontal, 18).padding(.vertical, 12).background(primary ? Palette.accent : Palette.tint, in: Capsule()).opacity(configuration.isPressed ? 0.7 : 1) }
}
struct AndroidToggleStyle: ToggleStyle {
    func makeBody(configuration: Configuration) -> some View {
        Button { configuration.isOn.toggle() } label: {
            HStack { configuration.label.font(.system(size: 14)).foregroundStyle(Palette.ink); Spacer(minLength: 14); Capsule().fill(configuration.isOn ? Palette.accent : Palette.soft).frame(width: 46, height: 26).overlay(alignment: configuration.isOn ? .trailing : .leading) { Circle().fill(configuration.isOn ? Palette.onAccent : Palette.muted).frame(width: 20, height: 20).padding(3) } }.frame(minHeight: 44).contentShape(Rectangle())
        }.buttonStyle(.plain).accessibilityValue(configuration.isOn ? "已启用" : "已停用")
    }
}
struct AndroidField: View {
    let title: String
    @Binding var text: String
    var hint = ""
    var secure = false
    var body: some View { VStack(alignment: .leading, spacing: 7) { Text(title).font(.system(size: 13, weight: .semibold)).foregroundStyle(Palette.muted); if secure { SecureField(hint.isEmpty ? title : hint, text: $text) } else { TextField(hint.isEmpty ? title : hint, text: $text).textInputAutocapitalization(.never).autocorrectionDisabled() } }.textFieldStyle(AndroidFieldStyle()) }
}
struct AndroidSearch: View {
    @Binding var text: String
    var hint: String
    var submit: () -> Void = {}
    var body: some View { HStack(spacing: 8) { YLIcon(systemName: "magnifyingglass", size: 18).foregroundStyle(Palette.accent); TextField(hint, text: $text).font(.system(size: 14)).submitLabel(.search).onSubmit(submit).textInputAutocapitalization(.never).autocorrectionDisabled(); if !text.isEmpty { Button { text = ""; submit() } label: { YLIcon(systemName: "xmark", size: 16) }.buttonStyle(.plain).accessibilityLabel("清除搜索") } }.padding(.horizontal, 15).frame(minHeight: 48).androidSurface(radius: 24) }
}
struct AndroidRow: View {
    var title: String
    var subtitle = ""
    var icon = "chevron.right"
    var body: some View { HStack(spacing: 12) { YLIcon(systemName: icon, size: 19).foregroundStyle(Palette.accent).frame(width: 36, height: 36).background(Palette.soft.opacity(0.5), in: RoundedRectangle(cornerRadius: 11)); VStack(alignment: .leading, spacing: 5) { Text(title).font(.system(size: 15, weight: .bold)).foregroundStyle(Palette.ink); if !subtitle.isEmpty { Text(subtitle).font(.system(size: 13)).foregroundStyle(Palette.muted) } }.frame(maxWidth: .infinity, alignment: .leading); YLIcon(systemName: "chevron.right", size: 15).foregroundStyle(Palette.muted) }.padding(.horizontal, 11).padding(.vertical, 9).frame(minHeight: 54).androidSurface().contentShape(RoundedRectangle(cornerRadius: 16)) }
}
struct AndroidChips: View {
    var names: [(String, String)]
    @Binding var selection: String
    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(names, id: \.0) { value, title in
                    Button { selection = value } label: {
                        Text(title).font(.system(size: 13, weight: .semibold))
                            .foregroundStyle(selection == value ? Palette.onAccent : Palette.muted)
                            .padding(.horizontal, 16).frame(minWidth: 58, minHeight: 44)
                            .background(selection == value ? Palette.accent : Palette.paper, in: Capsule())
                            .contentShape(Capsule())
                    }.buttonStyle(.plain).accessibilityIdentifier("chip-" + value)
                        .accessibilityAddTraits(selection == value ? [.isSelected] : [])
                }
            }.padding(.vertical, 2)
        }.frame(height: 48).zIndex(1).accessibilityIdentifier("android-chips")
    }
}

private struct AndroidAppearanceRevision: EnvironmentKey { static let defaultValue = "" }
private struct AndroidMaterialSettings: EnvironmentKey { static let defaultValue = false }
extension EnvironmentValues {
    var androidAppearanceRevision: String { get { self[AndroidAppearanceRevision.self] } set { self[AndroidAppearanceRevision.self] = newValue } }
    var androidMaterialSettings: Bool { get { self[AndroidMaterialSettings.self] } set { self[AndroidMaterialSettings.self] = newValue } }
}
struct AndroidPreferenceGroup<Content: View>: View {
    @ViewBuilder var content: Content
    var body: some View { VStack(spacing: 0) { content }.padding(.horizontal, 2).background(Palette.color(0xf1f2f8, 0x20232e), in: RoundedRectangle(cornerRadius: 22)) }
}
struct AndroidPreferenceDivider: View {
    var body: some View { Rectangle().fill(Palette.color(0xdedfe6, 0x3b3e48)).frame(height: 1).padding(.leading, 58) }
}
struct AndroidPreferenceRow: View {
    var icon: String
    var title: String
    var detail = ""
    var value = ""
    var actionable = true
    var body: some View {
        HStack(spacing: 12) {
            YLIcon(systemName: icon, size: 25).foregroundStyle(Palette.muted).frame(width: 32, height: 32)
            VStack(alignment: .leading, spacing: 5) { Text(title).font(.system(size: 17)); if !detail.isEmpty { Text(detail).font(.system(size: 13)).foregroundStyle(Palette.muted) } }.frame(maxWidth: .infinity, alignment: .leading)
            if !value.isEmpty { Text(value).font(.system(size: 14)).foregroundStyle(Palette.muted).multilineTextAlignment(.trailing).frame(maxWidth: 118, alignment: .trailing).lineLimit(2) }
            if actionable { YLIcon(systemName: "arrow.up.arrow.down", size: 17).foregroundStyle(Palette.muted).frame(width: 18, height: 24) }
        }.foregroundStyle(Palette.ink).padding(.horizontal, 16).padding(.vertical, 17).frame(minHeight: 68).contentShape(Rectangle())
    }
}
struct DiscoveryHero: View {
    var books = false
    var showsArrow = true
    var body: some View {
        GeometryReader { proxy in
            ZStack(alignment: .bottomLeading) {
                if books {
                    Color(rgb: 0xf1e2cc)
                    Image("android-hero_books").resizable().scaledToFill().frame(width: proxy.size.width, height: proxy.size.height).clipped().accessibilityHidden(true)
                    LinearGradient(colors: [Color(rgb: 0x0f1723).opacity(0.03), Color(rgb: 0x1a2831).opacity(0.9)], startPoint: .top, endPoint: .bottom)
                } else {
                    Palette.accent
                    Image("android-library_discovery_record").resizable().scaledToFit().frame(width: 112, height: 112).offset(x: 12, y: 12).frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topTrailing).accessibilityHidden(true)
                }
                VStack(alignment: .leading, spacing: 4) { Text("发现").font(.system(size: 26, weight: .bold)); HStack { Text(books ? "听书 · 广播 · 播客" : "探索音乐库").font(.system(size: 13)); Spacer(minLength: 4); if showsArrow { YLIcon(systemName: "arrow.right", size: 19).rotationEffect(.degrees(-45)) } } }.foregroundStyle(books ? .white : Palette.onAccent).padding(.horizontal, 16).padding(.bottom, 22)
            }.frame(width: proxy.size.width, height: proxy.size.height).clipShape(RoundedRectangle(cornerRadius: 18))
        }.contentShape(RoundedRectangle(cornerRadius: 18))
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(books ? "发现，听书、广播、播客" : "发现，探索音乐库")
    }
}
struct LibraryShortcut: View {
    var title: String
    var icon: String
    var height: CGFloat = 56
    var body: some View { HStack(spacing: 8) { YLIcon(systemName: icon, size: 20).foregroundStyle(Palette.accent).frame(width: 22, height: 32); Text(title).font(.system(size: 14, weight: .bold)).foregroundStyle(Palette.ink); Spacer(minLength: 0) }.padding(.horizontal, 10).frame(maxWidth: .infinity).frame(minHeight: height).androidSurface(radius: 18).contentShape(RoundedRectangle(cornerRadius: 18)) }
}
struct BookTile: View {
    @EnvironmentObject var store: AppStore
    let book: MediaItem
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            GeometryReader { proxy in
                ZStack { Palette.color(0xe5ebe3, 0x293e42); Text(book.title).font(.system(size: max(13, proxy.size.width / 6), weight: .bold)).foregroundStyle(Palette.color(0x566951, 0xb9cfbf)).lineLimit(3).multilineTextAlignment(.center).padding(8); RemoteImage(url: store.artwork(book)) { image in image.resizable().scaledToFill().frame(width: proxy.size.width, height: proxy.size.height).clipped() } placeholder: { Color.clear } }
            }.aspectRatio(1 / 1.3, contentMode: .fit).clipShape(RoundedRectangle(cornerRadius: 10))
            Text(book.title).font(.system(size: 14, weight: .bold)).foregroundStyle(Palette.ink).lineLimit(2).frame(minHeight: 34, alignment: .top)
            Text(book.subtitle.isEmpty ? "点击收听" : book.subtitle).font(.system(size: 11)).foregroundStyle(Palette.muted).lineLimit(1)
        }.frame(maxWidth: .infinity, alignment: .leading)
    }
}
struct BookGrid: View {
    var rows: [MediaItem]
    @State private var width: CGFloat = 350
    @Environment(\.dynamicTypeSize) private var typeSize
    private var count: Int { width >= 568 ? 4 : typeSize.isAccessibilitySize ? 2 : 3 }
    var body: some View { LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 12), count: count), spacing: 18) { ForEach(rows) { book in NavigationLink { BookDetailView(book: book) } label: { BookTile(book: book) }.buttonStyle(.plain).accessibilityIdentifier("book-tile-" + book.id) } }.background { GeometryReader { proxy in Color.clear.onAppear { width = proxy.size.width }.onChange(of: proxy.size.width) { _, value in width = value } } } }
}
struct AndroidChoice: View {
    var title: String
    @Binding var selection: String
    var options: [(String, String)]
    var icon: String? = nil
    var detail = ""
    @State private var presented = false
    var body: some View {
        Button { presented = true } label: {
            if let icon { AndroidPreferenceRow(icon: icon, title: title, detail: detail, value: options.first { $0.0 == selection }?.1 ?? selection) }
            else { HStack(spacing: 12) { Text(title).font(.system(size: 14, weight: .semibold)).foregroundStyle(Palette.ink); Spacer(minLength: 8); Text(options.first { $0.0 == selection }?.1 ?? selection).font(.system(size: 13)).foregroundStyle(Palette.muted).lineLimit(2); YLIcon(systemName: "chevron.right", size: 15).foregroundStyle(Palette.muted) }.frame(minHeight: 44).contentShape(Rectangle()) }
        }.buttonStyle(.plain).accessibilityLabel(title)
            .sheet(isPresented: $presented) { PlayerChoice(title: title, rows: options.map { $0.1 }, selected: options.first { $0.0 == selection }?.1 ?? "") { value in if let key = options.first(where: { $0.1 == value })?.0 { selection = key } } }
    }
}
struct RemoteImage<Content: View, Placeholder: View>: View {
    var url: URL?
    var content: (Image) -> Content
    var placeholder: () -> Placeholder
    @State private var loaded: UIImage?
    @State private var loadedURL: URL?
    init(url: URL?, @ViewBuilder content: @escaping (Image) -> Content, @ViewBuilder placeholder: @escaping () -> Placeholder) { self.url = url; self.content = content; self.placeholder = placeholder }
    var body: some View {
#if DEBUG
        if UITestFixtures.active, let url, url.scheme == "fixture" { content(Image(String(url.absoluteString.dropFirst("fixture:".count)))) }
        else { cachedImage }
#else
        cachedImage
#endif
    }
    private var cachedImage: some View {
        Group {
            if let url, let image = (loadedURL == url ? loaded : nil) ?? ArtworkCache.shared.memoryImage(url) { content(Image(uiImage: image)) }
            else { placeholder() }
        }.task(id: url) {
            guard let url else { loaded = nil; loadedURL = nil; return }
            let image = await ArtworkCache.shared.image(url)
            guard !Task.isCancelled else { return }
            loadedURL = url; loaded = image
        }
    }
}
