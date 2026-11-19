import SwiftUI
import UniformTypeIdentifiers

struct SettingsView: View {
    @AppStorage("appearance") private var appearance = "light"
    @AppStorage("light-minute") private var lightMinute = 420
    @AppStorage("dark-minute") private var darkMinute = 1140
    @AppStorage("home-template") private var template = "classic"
    @AppStorage("home-rotation") private var rotation = "daily"
    @AppStorage("quality") private var quality = "320k"
    @AppStorage("music-cache") private var musicCache = 512
    @AppStorage("book-cache") private var bookCache = 512
    var body: some View {
        Form {
            Section("首页与页面外观") {
                Picker("明暗", selection: $appearance) { Text("跟随系统").tag("system"); Text("浅色杂志风").tag("light"); Text("深色播放风").tag("dark"); Text("按时间切换").tag("scheduled") }
                if appearance == "scheduled" {
                    DatePicker("切换浅色", selection: timeBinding($lightMinute), displayedComponents: .hourAndMinute)
                    DatePicker("切换深色", selection: timeBinding($darkMinute), displayedComponents: .hourAndMinute)
                }
                Picker("首页模板", selection: $template) { Text("经典影像（默认）").tag("classic"); Text("留白画廊").tag("gallery"); Text("声波电台").tag("radio"); Text("自动轮换").tag("auto") }
                if template == "auto" { Picker("轮换频率", selection: $rotation) { Text("每天").tag("daily"); Text("每小时").tag("hourly") } }
            }
            Section("音乐与有声书") {
                NavigationLink("音乐设置 · LX 音源") { PluginSettingsView(books: false) }
                NavigationLink("听书插件") { PluginSettingsView(books: true) }
                NavigationLink("Navidrome / Subsonic") { ConnectionView() }
                QualityPicker(quality: $quality)
            }
            Section("缓存管理") {
                Stepper("音乐：\(musicCache) MB", value: $musicCache, in: 0...8192, step: 128)
                Stepper("有声书：\(bookCache) MB", value: $bookCache, in: 0...8192, step: 128)
                Button("清除音乐缓存") { Task { await MediaCache.shared.clear(books: false) } }
                Button("清除有声书缓存") { Task { await MediaCache.shared.clear(books: true) } }
                Text("设为 0 可关闭缓存，手动下载的文件独立保存。").font(.caption).foregroundStyle(.secondary)
            }
            Section("下载服务") { NavigationLink("服务器连接与任务") { ServerSettingsView() } }
            Section("关于") {
                LabeledContent("音浪 YinLang", value: (Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "0.8.0") + " (iOS)")
                Link("GitHub 主页", destination: URL(string: "https://github.com/tbslantian")!)
                NavigationLink("第三方许可") { LicenseView() }
            }
        }.navigationTitle("总设置").onChange(of: musicCache) { _, value in Task { await MediaCache.shared.trim(books: false, limitMB: value) } }.onChange(of: bookCache) { _, value in Task { await MediaCache.shared.trim(books: true, limitMB: value) } }
    }
    private func timeBinding(_ minute: Binding<Int>) -> Binding<Date> {
        Binding(get: { Calendar.current.startOfDay(for: Date()).addingTimeInterval(Double(minute.wrappedValue * 60)) }, set: { value in let parts = Calendar.current.dateComponents([.hour, .minute], from: value); minute.wrappedValue = (parts.hour ?? 0) * 60 + (parts.minute ?? 0) })
    }
}

struct QualityPicker: View {
    @Binding var quality: String
    var body: some View { Picker("在线播放音质", selection: $quality) {
        Text("标准 128k").tag("128k"); Text("较高 192k").tag("192k"); Text("高品质 320k").tag("320k"); Text("无损 FLAC").tag("flac"); Text("24 bit FLAC").tag("flac24bit"); Text("Hi-Res").tag("hires"); Text("全景声").tag("atmos"); Text("全景声 Plus").tag("atmos_plus"); Text("母带").tag("master")
    } }
}

struct ConnectionView: View {
    @EnvironmentObject var store: AppStore
    @State private var alias = UserDefaults.standard.string(forKey: "nav-alias") ?? ""
    @State private var base = UserDefaults.standard.string(forKey: "nav-url") ?? ""
    @State private var user = UserDefaults.standard.string(forKey: "nav-user") ?? ""
    @State private var password = ""
    @State private var busy = false
    @AppStorage("nav-bitrate") private var bitrate = "0"
    @AppStorage("nav-format") private var format = "mp3"
    var body: some View {
        Form {
            Section("服务器") {
                TextField("别名", text: $alias)
                TextField("主机地址（含 http:// 或 https://）", text: $base).textInputAutocapitalization(.never).autocorrectionDisabled().keyboardType(.URL)
                Text("可包含端口与路径，例如 https://music.example.com:443/navidrome").font(.caption).foregroundStyle(.secondary)
            }
            Section("账号") { TextField("用户名", text: $user).textInputAutocapitalization(.never).autocorrectionDisabled(); SecureField("密码", text: $password) }
            Section("连接地址预览") { Text(base.isEmpty ? "请填写服务器地址" : base).font(.caption).textSelection(.enabled) }
            Button { Task { busy = true; defer { busy = false }; do { try await store.connect(base: base, user: user, password: password); UserDefaults.standard.set(alias, forKey: "nav-alias"); store.message = "音乐库连接成功" } catch { store.message = error.localizedDescription } } } label: { HStack { Text("连接并保存"); if busy { Spacer(); ProgressView() } } }.disabled(busy)
            Section("在线播放音质") {
                Picker("码率", selection: $bitrate) { Text("原始文件").tag("0"); ForEach(["128", "192", "256", "320"], id: \.self) { Text($0 + " kbps").tag($0) } }
                Picker("转码格式", selection: $format) { Text("MP3").tag("mp3"); Text("OPUS").tag("opus"); Text("AAC").tag("aac") }
                Text("下一首或重新播放时生效，需要服务器支持相应转码。下载保留原文件。").font(.caption).foregroundStyle(.secondary)
            }
            if store.nav != nil { Button("断开连接", role: .destructive) { store.disconnect() } }
        }.navigationTitle("音乐库设置").onAppear { password = Keychain.read("nav-password:" + Disk.hash(base + "|" + user)) }
    }
}

struct PluginSettingsView: View {
    @EnvironmentObject var store: AppStore
    var books: Bool
    @State private var filePicker = false
    @State private var address = ""
    @State private var busy = false
    @State private var sourceRows: [JSONObject] = []
    @State private var sourceError = ""
    @AppStorage("quality") private var quality = "320k"
    @AppStorage("auto-source-updates") private var autoUpdates = false
    @AppStorage("playlist-update-hours") private var updateHours = 6
    var body: some View {
        Form {
            if !books { Section("在线播放") { QualityPicker(quality: $quality); Text("播放会在所选音质失败后逐级回退；下载使用所选档位。").font(.caption).foregroundStyle(.secondary) } }
            Section("导入\(books ? "听书 ZIP 插件" : "LX JavaScript 音源")") {
                Button("从文件导入", systemImage: "doc.badge.plus") { filePicker = true }
                TextField("更新或导入直链", text: $address).textInputAutocapitalization(.never).autocorrectionDisabled().keyboardType(.URL)
                Button("从网址导入", systemImage: "link") { Task { await installURL() } }.disabled(busy || address.isEmpty)
                if busy { ProgressView() }
            }
            Section("已安装") {
                ForEach(store.plugins.filter { $0.books == books }) { plugin in
                    NavigationLink {
                        PluginEditor(pluginID: plugin.id)
                    } label: { VStack(alignment: .leading, spacing: 5) { Text(plugin.name); Text((plugin.enabled ? "已启用" : "已停用") + (plugin.version.isEmpty ? "" : " · " + plugin.version)).font(.caption).foregroundStyle(.secondary) } }
                }.onDelete { indices in let ids = indices.map { store.plugins.filter { $0.books == books }[$0].id }; ids.forEach { store.invalidate($0) }; store.plugins.removeAll { ids.contains($0.id) } }
                if store.plugins.filter({ $0.books == books }).isEmpty { Text("尚未安装音源").foregroundStyle(.secondary) }
            }
            if !books {
                Section("音源与歌单更新") {
                    Toggle("自动检查音源更新", isOn: $autoUpdates)
                    Button("立即检查音源更新") { Task { await store.updateSources() } }
                    Picker("自动更新歌单", selection: $updateHours) { Text("每次打开").tag(0); Text("每 3 小时").tag(3); Text("每 6 小时").tag(6); Text("每 12 小时").tag(12); Text("每天").tag(24); Text("关闭").tag(-1) }
                    Button("立即刷新关注的歌单") { Task { await store.refreshFollowed() } }
                    Text("自动更新会在应用进入前台时按周期执行。").font(.caption).foregroundStyle(.secondary)
                }
            }
            if books {
                Section("听书源登录与设置") {
                    ForEach(Array(sourceRows.enumerated()), id: \.offset) { _, source in NavigationLink(source.string("name")) { BookSourceSettings(source: source) } }
                    if !sourceError.isEmpty { Text(sourceError).font(.caption).foregroundStyle(.secondary) }
                }
                Section { Button("重新安装官方公开源合集 5.7") { Task { UserDefaults.standard.set(false, forKey: "bundled-books-installed"); await store.installBundledBooks(); await loadSources() } } }
            }
        }.navigationTitle(books ? "听书插件" : "音乐设置").task { if books { await loadSources() } }
            .fileImporter(isPresented: $filePicker, allowedContentTypes: books ? [.zip] : [.javaScript, .plainText, .data]) { result in
                Task {
                    do { let url = try result.get(); let access = url.startAccessingSecurityScopedResource(); defer { if access { url.stopAccessingSecurityScopedResource() } }; busy = true; defer { busy = false }; try await store.install(Data(contentsOf: url), name: url.lastPathComponent, address: "", books: books); if books { await loadSources() } }
                    catch { store.message = error.localizedDescription }
                }
            }
    }
    private func installURL() async { busy = true; defer { busy = false }; do { try await store.installFromURL(address, books: books); address = ""; if books { await loadSources() } } catch { store.message = error.localizedDescription } }
    private func loadSources() async { do { sourceRows = try await store.bookSources() } catch { sourceError = error.localizedDescription } }
}

struct PluginEditor: View {
    @EnvironmentObject var store: AppStore
    let pluginID: String
    @State private var address = ""
    @State private var busy = false
    var plugin: Plugin? { store.plugins.first { $0.id == pluginID } }
    var body: some View {
        Form {
            if let plugin {
                Toggle("启用", isOn: Binding(get: { self.plugin?.enabled ?? false }, set: { value in if let index = store.plugins.firstIndex(where: { $0.id == pluginID }) { store.plugins[index].enabled = value; store.invalidate(pluginID) } }))
                LabeledContent("版本", value: plugin.version.isEmpty ? "未声明" : plugin.version)
                TextField("更新直链", text: $address).textInputAutocapitalization(.never).autocorrectionDisabled()
                Button("保存更新地址") { if let index = store.plugins.firstIndex(where: { $0.id == pluginID }) { store.plugins[index].address = address } }
                Button("检查并更新") { Task { busy = true; defer { busy = false }; do { try await store.installFromURL(address, books: plugin.books); store.message = "已更新插件" } catch { store.message = error.localizedDescription } } }.disabled(busy || address.isEmpty)
                if busy { ProgressView() }
            }
        }.navigationTitle(plugin?.name ?? "插件").onAppear { address = plugin?.address ?? "" }
    }
}

struct BookSourceSettings: View {
    @EnvironmentObject var store: AppStore
    var source: JSONObject
    @State private var values: JSONObject = [:]
    var body: some View {
        Form {
            if let url = URL(string: source.string("loginUrl")), ["https", "http"].contains(url.scheme ?? "") { NavigationLink("在应用内登录") { LoginWebView(url: url).navigationTitle(source.string("name")).navigationBarTitleDisplayMode(.inline) } }
            ForEach(Array(source.rows("config").enumerated()), id: \.offset) { _, row in
                let key = row.string("key", "id")
                if row.string("type") == "boolean" || row.string("type") == "switch" || row["default"] is Bool {
                    Toggle(row.string("label", "title", "name"), isOn: Binding(get: { values[key] as? Bool ?? row["default"] as? Bool ?? false }, set: { values[key] = $0 }))
                } else { TextField(row.string("label", "title", "name"), text: Binding(get: { values[key] as? String ?? row.string("default") }, set: { values[key] = $0 })) }
            }
            Button("保存源设置") { Task { do { _ = try await store.bookCall(source.string("id"), pluginID: source.string("_plugin"), action: "setPrefs", args: [values]); store.message = "已保存" } catch { store.message = error.localizedDescription } } }
        }.navigationTitle(source.string("name"))
    }
}

struct ServerSettingsView: View {
    @EnvironmentObject var store: AppStore
    @AppStorage("server-url") private var address = ""
    @State private var token = Keychain.read("server-token")
    @State private var jobs: [JSONObject] = []
    @State private var busy = false
    @State private var error = ""
    var body: some View {
        Form {
            Section("yinlangserver") {
                TextField("服务器地址", text: $address).textInputAutocapitalization(.never).autocorrectionDisabled().keyboardType(.URL)
                SecureField("令牌", text: $token)
                Button("保存并连接") { Task { do { try Keychain.write("server-token", token); _ = try await store.server("/health"); await load(); store.message = "下载服务器连接成功" } catch { store.message = error.localizedDescription } } }
            }
            Section("下载任务") {
                Button("刷新任务") { Task { await load() } }.disabled(busy)
                if busy { ProgressView() }; if !error.isEmpty { Text(error).font(.caption) }
                ForEach(Array(jobs.enumerated()), id: \.offset) { _, job in VStack(alignment: .leading, spacing: 6) { Text(job.string("title")); Text(job.string("status") + " · " + job.string("error")).font(.caption).foregroundStyle(.secondary) } }
                Button("清除已完成与失败记录", role: .destructive) { Task { do { _ = try await store.server("/v1/jobs/history", method: "DELETE"); await load() } catch { store.message = error.localizedDescription } } }
            }
        }.navigationTitle("下载服务器").task { if !address.isEmpty { await load() } }
    }
    private func load() async { busy = true; defer { busy = false }; do { jobs = try await store.server("/v1/jobs").rows("jobs"); error = "" } catch { self.error = error.localizedDescription } }
}

struct LicenseView: View {
    @State private var text = ""
    var body: some View { ScrollView { Text(text).font(.system(.caption, design: .monospaced)).textSelection(.enabled).padding() }.navigationTitle("第三方许可").task { if let url = Bundle.main.url(forResource: "catalog-licenses", withExtension: "txt", subdirectory: "Runtime") { text = (try? String(contentsOf: url, encoding: .utf8)) ?? "" } } }
}
