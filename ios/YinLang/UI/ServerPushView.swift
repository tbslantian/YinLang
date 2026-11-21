import SwiftUI

struct ServerPushOption {
    static let qualities = ["master", "atmos_plus", "atmos", "hires", "flac24bit", "flac", "320k", "192k", "128k"]
    let quality: String
    let url: URL
    let headers: [String: String]
    let bytes: Int64?
    let preparedAt = Date()
    static func platformQualities(_ platform: String) -> [String] {
        switch platform {
        case "tx": return qualities.filter { $0 != "192k" }
        case "wy": return qualities.filter { !["192k", "atmos_plus"].contains($0) }
        case "kw", "kg": return ["128k", "320k", "flac", "flac24bit", "hires"]
        case "mg": return ["128k", "320k", "flac"]
        default: return qualities
        }
    }
    static func label(_ quality: String) -> String { QualityPicker.options.first { $0.0 == quality }?.1 ?? quality }
    func sizeLabel(for item: MediaItem) -> String {
        if let bytes { return ByteCountFormatter.string(fromByteCount: bytes, countStyle: .file) }
        // Old saved LX entries can carry sizes in any of these root/meta maps.
        // Placeholder platform qualities must not be treated as measured sizes.
        for info in [item.raw, item.raw.object("meta")] {
            for field in ["types", "_types", "qualitys", "_qualitys"] {
                let entry: JSONObject?
                if let rows = info[field] as? [JSONObject] { entry = rows.first { $0.string("type") == quality } }
                else { entry = info.object(field)[quality] as? JSONObject }
                guard let entry, entry["isPlatformQuality"] as? Bool != true else { continue }
                let value = entry.string("size").trimmingCharacters(in: .whitespacesAndNewlines)
                if let count = Int64(value), count > 0 { return ByteCountFormatter.string(fromByteCount: count, countStyle: .file) + "（平台标注）" }
                if value.range(of: #"^\d+(?:\.\d+)?\s*(?:B|KB|MB|GB|KiB|MiB|GiB)$"#, options: [.regularExpression, .caseInsensitive]) != nil { return value + "（平台标注）" }
            }
        }
        return "大小未知（音源未提供）"
    }
}

@MainActor
private final class ServerPushModel: ObservableObject {
    @Published var qualities: [String] = []
    @Published var options: [String: ServerPushOption] = [:]
    @Published var failures: [String: String] = [:]
    @Published var checking: String?
    @Published var selected = ""
    @Published var loading = true
    @Published var submitting = false
    @Published var completed = false
    @Published var error = ""
    private var scan: Task<Void, Never>?
    let item: MediaItem
    init(item: MediaItem) { self.item = item }
    func start(_ store: AppStore) {
        scan?.cancel(); qualities = []; options = [:]; failures = [:]; selected = ""; error = ""; loading = true
        scan = Task {
            defer { if !Task.isCancelled { loading = false; checking = nil } }
            do {
                let candidates = try await store.pushQualities(item)
                try Task.checkCancellation()
                qualities = candidates
                let preferred = UserDefaults.standard.string(forKey: "quality") ?? "320k"
                let order = qualities.contains(preferred) ? [preferred] + qualities.filter { $0 != preferred } : qualities
                for quality in order {
                    try Task.checkCancellation(); checking = quality
                    do {
                        let option = try await store.preparePush(item, quality: quality)
                        try Task.checkCancellation(); options[quality] = option
                        if selected.isEmpty { selected = quality }
                    } catch { try Task.checkCancellation(); failures[quality] = error.localizedDescription }
                }
                if options.isEmpty { error = "没有验证成功的可下载音质，请检查音源后重试。" }
            } catch { if !Task.isCancelled { self.error = error.localizedDescription } }
        }
    }
    func stop() { scan?.cancel(); scan = nil }
    func submit(_ store: AppStore) async {
        guard !submitting, let option = options[selected] else { return }
        stop(); loading = false; checking = nil; submitting = true; error = ""
        defer { submitting = false }
        do { try await store.push(item, option: option); completed = true }
        catch { self.error = error.localizedDescription }
    }
}

struct ServerPushView: View {
    @EnvironmentObject private var store: AppStore
    @Environment(\.dismiss) private var dismiss
    @StateObject private var model: ServerPushModel
    init(item: MediaItem) { _model = StateObject(wrappedValue: ServerPushModel(item: item)) }
    var body: some View {
        NavigationStack {
            AndroidForm {
                HStack(spacing: 12) {
                    Artwork(item: model.item, size: 52)
                    VStack(alignment: .leading, spacing: 5) { Text(model.item.title).font(.headline); Text(model.item.subtitle).font(.caption).foregroundStyle(Palette.muted) }
                }
                if model.completed {
                    Label("已推送到服务端下载", systemImage: "checkmark.circle.fill").foregroundStyle(Palette.accent)
                    Button("完成") { dismiss() }.buttonStyle(AndroidButtonStyle(primary: true))
                } else {
                    Text("选择可用音质，再推送到服务端下载。获取过程中可以选择已验证的音质。").font(.caption).foregroundStyle(Palette.muted)
                    if model.loading { HStack { ProgressView(); Text(model.checking.map { "正在验证 " + ServerPushOption.label($0) } ?? "正在获取音质…").font(.caption) } }
                    ForEach(model.qualities, id: \.self) { quality in
                        Button { model.selected = quality } label: {
                            HStack(spacing: 12) {
                                YLIcon(systemName: model.selected == quality ? "checkmark.circle.fill" : "circle", size: 22).foregroundStyle(Palette.accent)
                                VStack(alignment: .leading, spacing: 5) {
                                    Text(ServerPushOption.label(quality)).font(.system(size: 16, weight: .bold)).foregroundStyle(Palette.ink)
                                    Text(detail(quality)).font(.caption).foregroundStyle(Palette.muted)
                                }
                                Spacer(minLength: 0)
                                if model.checking == quality { ProgressView() }
                            }.frame(maxWidth: .infinity, alignment: .leading).padding(13).androidSurface().contentShape(Rectangle())
                        }.buttonStyle(.plain).disabled(model.options[quality] == nil || model.submitting).accessibilityIdentifier("server-push-quality-" + quality)
                    }
                    if !model.error.isEmpty { ErrorPanel(text: model.error) }
                    Button { Task { await model.submit(store) } } label: { HStack { if model.submitting { ProgressView() }; Text(model.submitting ? "正在推送…" : "推送所选音质") } }
                        .buttonStyle(AndroidButtonStyle(primary: true)).disabled(model.options[model.selected] == nil || model.submitting)
                    if !model.loading { Button("重新获取音质") { model.start(store) }.disabled(model.submitting) }
                }
            }.androidPage("推送到服务端下载", category: "") { Button("关闭") { dismiss() }.disabled(model.submitting) }
        }.presentationDetents([.large]).presentationDragIndicator(.visible).presentationCornerRadius(30)
            .interactiveDismissDisabled(model.submitting).onAppear { model.start(store) }.onDisappear { model.stop() }
    }
    private func detail(_ quality: String) -> String {
        if let option = model.options[quality] { return option.sizeLabel(for: model.item) + " · 可用" }
        if let reason = model.failures[quality] { return "不可用 · " + reason }
        return model.checking == quality ? "正在获取地址和文件大小" : "等待验证"
    }
}
