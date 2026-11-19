import SwiftUI
import UniformTypeIdentifiers

struct SettingsView: View {
    @AppStorage("server-url") private var address = ""
    @AppStorage("server-enabled") private var enabled = false
    @State private var token = Keychain.read("server-token")
    @EnvironmentObject private var store: AppStore
    var body: some View {
        AndroidForm {
            SectionTitle(title: "外观")
            NavigationLink { PersonalizationView() } label: { AndroidRow(title: "个性化", subtitle: "首页模板 · 主题 · 播放条歌词 · 发现页", icon: "slider.horizontal.3") }.buttonStyle(.plain)
            NavigationLink { LibraryAudioQualityView() } label: { AndroidRow(title: "在线播放音质", subtitle: "Navidrome · 移动网络与 Wi-Fi", icon: "music.note") }.buttonStyle(.plain)
            SectionTitle(title: "存储")
            NavigationLink { CacheSettingsView() } label: { AndroidRow(title: "缓存管理", subtitle: "音乐与有声书缓存", icon: "externaldrive") }.buttonStyle(.plain)
            AndroidSection("服务器下载") {
                Toggle("启用推送到服务器", isOn: $enabled)
                AndroidField(title: "下载服务地址", text: $address, hint: "https://nas.example.com")
                AndroidField(title: "服务令牌", text: $token, hint: "输入令牌", secure: true)
                HStack(spacing: 8) {
                    Button("保存") { do { try Keychain.write("server-token", token); store.message = "已保存" } catch { store.message = error.localizedDescription } }.buttonStyle(AndroidButtonStyle(primary: true))
                    Button("测试连接") { Task { do { try Keychain.write("server-token", token); _ = try await store.server("/health"); store.message = "下载服务连接成功" } catch { store.message = error.localizedDescription } } }
                }
            }
            NavigationLink { ServerSettingsView() } label: { AndroidRow(title: "服务器任务", subtitle: "查看音乐与有声书下载进度", icon: "server.rack") }.buttonStyle(.plain)
            SectionTitle(title: "应用")
            NavigationLink { AboutView() } label: { AndroidRow(title: "关于", subtitle: "版本与 GitHub", icon: "info.circle") }.buttonStyle(.plain)
        }.androidPage("总设置")
    }
}
struct PersonalizationView: View {
    @AppStorage("appearance") private var appearance = "light"
    @AppStorage("light-minute") private var lightMinute = 420
    @AppStorage("dark-minute") private var darkMinute = 1140
    @AppStorage("home-template") private var template = "classic"
    @AppStorage("home-rotation") private var rotation = "daily"
    @AppStorage("personal-theme") private var theme = "default"
    @AppStorage("personal-accent") private var accent = "original"
    @AppStorage("mini-lyrics") private var lyrics = "off"
    @AppStorage("discover-kind") private var discover = "songs"
    var body: some View {
        AndroidForm {
            AndroidSection("首页") {
                AndroidChoice(title: "首页模板", selection: $template, options: [("classic", "经典影像"), ("gallery", "留白画廊"), ("radio", "声波电台"), ("auto", "自动轮换")])
                Text("经典影像 / 留白画廊 / 声波电台").font(.system(size: 12)).foregroundStyle(Palette.muted)
                if template == "auto" { AndroidChoice(title: "轮换频率", selection: $rotation, options: [("daily", "每天"), ("hourly", "每小时")]) }
                ScrollView(.horizontal, showsIndicators: false) { HStack(spacing: 12) { ForEach(["classic", "gallery", "radio"], id: \.self) { value in Button { template = value } label: { HomeEntry(books: false, template: value, width: 260) }.buttonStyle(.plain).accessibilityLabel("选择" + value) } } }
            }
            AndroidSection("外观") {
                AndroidChoice(title: "主题", selection: $theme, options: [("default", "默认"), ("transparent", "透明")])
                AndroidChoice(title: "亮度", selection: $appearance, options: [("system", "跟随系统"), ("light", "浅色"), ("dark", "深色"), ("scheduled", "按时间切换")])
                AndroidChoice(title: "主题色", selection: $accent, options: [("original", "音浪原色"), ("blue", "深海蓝"), ("green", "溪流绿")])
                if appearance == "scheduled" { DatePicker("切换到浅色", selection: time($lightMinute), displayedComponents: .hourAndMinute); DatePicker("切换到深色", selection: time($darkMinute), displayedComponents: .hourAndMinute) }
            }
            AndroidSection("播放条") { AndroidChoice(title: "显示歌词", selection: $lyrics, options: [("off", "关闭"), ("single", "单行歌词"), ("double", "双行歌词")]) }
            AndroidSection("发现页") { AndroidChoice(title: "推荐类型", selection: $discover, options: [("songs", "歌曲"), ("albums", "专辑")]) }
        }.androidPage("个性化")
    }
    private func time(_ minute: Binding<Int>) -> Binding<Date> { Binding(get: { Calendar.current.startOfDay(for: Date()).addingTimeInterval(Double(minute.wrappedValue * 60)) }, set: { value in let parts = Calendar.current.dateComponents([.hour, .minute], from: value); minute.wrappedValue = (parts.hour ?? 0) * 60 + (parts.minute ?? 0) }) }
}
struct CacheSettingsView: View {
    @AppStorage("music-cache") private var music = 512
    @AppStorage("book-cache") private var books = 512
    var body: some View { AndroidForm {
        AndroidSection("音乐缓存") { AndroidChoice(title: "缓存上限", selection: Binding(get: { String(music) }, set: { music = Int($0) ?? 512 }), options: [0,128,256,512,1024,2048,4096,8192].map { (String($0), $0 == 0 ? "关闭" : "\($0) MB") }); Button("清除音乐缓存") { Task { await MediaCache.shared.clear(books: false) } } }
        AndroidSection("有声书缓存") { AndroidChoice(title: "缓存上限", selection: Binding(get: { String(books) }, set: { books = Int($0) ?? 512 }), options: [0,128,256,512,1024,2048,4096,8192].map { (String($0), $0 == 0 ? "关闭" : "\($0) MB") }); Button("清除有声书缓存") { Task { await MediaCache.shared.clear(books: true) } } }
        Text("设为 0 可关闭缓存，手动下载的文件独立保存。").font(.system(size: 12)).foregroundStyle(Palette.muted)
    }.androidPage("缓存管理").onChange(of: music) { _, limit in Task { await MediaCache.shared.trim(books: false, limitMB: limit) } }.onChange(of: books) { _, limit in Task { await MediaCache.shared.trim(books: true, limitMB: limit) } } }
}
struct LibraryAudioQualityView: View {
    @AppStorage("nav-bitrate-wifi") private var wifi = "0"
    @AppStorage("nav-bitrate-mobile") private var mobile = "192"
    @AppStorage("nav-format") private var format = "mp3"
    var body: some View { AndroidForm { AndroidSection("在线播放音质") { AndroidChoice(title: "Wi-Fi", selection: $wifi, options: rateOptions); AndroidChoice(title: "移动网络", selection: $mobile, options: rateOptions); AndroidChoice(title: "转码格式", selection: $format, options: [("mp3", "MP3"), ("opus", "OPUS"), ("aac", "AAC")]); Text("下一首或重新播放时生效，需要服务器支持相应转码。下载保留原文件。").font(.system(size: 12)).foregroundStyle(Palette.muted) } }.androidPage("在线播放音质") }
    private var rateOptions: [(String,String)] { [("0", "原始文件")] + ["128","192","256","320"].map { ($0, $0 + " kbps") } }
}
struct AboutView: View {
    var body: some View { AndroidForm { AndroidSection { Text("音浪 YinLang").font(.system(size: 25, weight: .bold)); LabeledContent("版本", value: Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? ""); Link("GitHub 主页", destination: URL(string: "https://github.com/tbslantian")!); NavigationLink { LicenseView() } label: { AndroidRow(title: "第三方许可", icon: "info.circle") }.buttonStyle(.plain) } }.androidPage("关于") }
}

struct QualityPicker: View {
    @Binding var quality: String
    var body: some View { AndroidChoice(title: "在线播放音质", selection: $quality, options: [("128k", "标准 128k"), ("192k", "较高 192k"), ("320k", "高品质 320k"), ("flac", "无损 FLAC"), ("flac24bit", "24 bit FLAC"), ("hires", "Hi-Res"), ("atmos", "全景声"), ("atmos_plus", "全景声 Plus"), ("master", "母带")]) }
}

struct ConnectionView: View {
    @EnvironmentObject var store: AppStore
    @State private var alias = UserDefaults.standard.string(forKey: "nav-alias") ?? ""
    @State private var host = ""
    @State private var port = ""
    @State private var path = ""
    @State private var https = true
    @State private var user = UserDefaults.standard.string(forKey: "nav-user") ?? ""
    @State private var password = ""
    @State private var busy = false
    private var base: String {
        var parts = URLComponents(string: host.contains("://") ? host : (https ? "https://" : "http://") + host)
        if !port.isEmpty { parts?.port = Int(port) }
        if !path.isEmpty { parts?.path = path.hasPrefix("/") ? path : "/" + path }
        return parts?.url?.absoluteString ?? ""
    }
    var body: some View {
        AndroidForm {
            AndroidSection("基本信息") {
                AndroidField(title: "别名", text: $alias, hint: "Navidrome")
                AndroidField(title: "主机地址", text: $host, hint: "example.com")
                HStack(alignment: .top, spacing: 12) { AndroidField(title: "端口", text: $port, hint: "可选").keyboardType(.numberPad); AndroidField(title: "路径", text: $path, hint: "可选") }
                Toggle("HTTPS", isOn: $https)
            }
            AndroidSection("账号") { AndroidField(title: "用户名", text: $user, hint: "输入用户名"); AndroidField(title: "密码", text: $password, hint: "输入密码", secure: true) }
            AndroidSection("连接地址预览") { Text(base.isEmpty ? "请填写服务器地址" : base).font(.system(size: 12)).foregroundStyle(Palette.muted).textSelection(.enabled) }
            HStack { Button("连接并保存") { Task { await connect() } }.buttonStyle(AndroidButtonStyle(primary: true)).disabled(busy); if busy { ProgressView() } }
            NavigationLink { LibraryAudioQualityView() } label: { AndroidRow(title: "在线播放音质", subtitle: "移动网络与 Wi-Fi", icon: "music.note") }.buttonStyle(.plain)
            if store.nav != nil { Button("断开连接", role: .destructive) { store.disconnect() } }
        }.androidPage("音乐库设置", category: "音乐库").onAppear {
            let value = UserDefaults.standard.string(forKey: "nav-url") ?? ""
            if let parts = URLComponents(string: value) { host = parts.host ?? ""; port = parts.port.map(String.init) ?? ""; path = parts.path; https = parts.scheme != "http" }
            password = Keychain.read("nav-password:" + Disk.hash(value + "|" + user))
        }
    }
    private func connect() async { busy = true; defer { busy = false }; do { try await store.connect(base: base, user: user, password: password); UserDefaults.standard.set(alias, forKey: "nav-alias"); store.message = "音乐库连接成功" } catch { store.message = error.localizedDescription } }
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
        AndroidForm {
            if !books { AndroidSection("在线播放") { QualityPicker(quality: $quality); Text("播放会在所选音质失败后逐级回退；下载使用所选档位。").font(.caption).foregroundStyle(Palette.muted) } }
            AndroidSection("导入\(books ? "听书 ZIP 插件" : "LX JavaScript 音源")") {
                Button("从文件导入", systemImage: "doc.badge.plus") { filePicker = true }
                TextField("更新或导入直链", text: $address).textInputAutocapitalization(.never).autocorrectionDisabled().keyboardType(.URL)
                Button("从网址导入", systemImage: "link") { Task { await installURL() } }.disabled(busy || address.isEmpty)
                if busy { ProgressView() }
            }
            AndroidSection("已安装") {
                ForEach(store.plugins.filter { $0.books == books }) { plugin in
                    NavigationLink {
                        PluginEditor(pluginID: plugin.id)
                    } label: { AndroidRow(title: plugin.name, subtitle: (plugin.enabled ? "已启用" : "已停用") + (plugin.version.isEmpty ? "" : " · " + plugin.version), icon: "puzzlepiece.extension") }.buttonStyle(.plain)
                }
                if store.plugins.filter({ $0.books == books }).isEmpty { Text("尚未安装音源").foregroundStyle(Palette.muted) }
            }
            if !books {
                AndroidSection("音源与歌单更新") {
                    Toggle("自动检查音源更新", isOn: $autoUpdates)
                    Button("立即检查音源更新") { Task { await store.updateSources() } }
                    AndroidChoice(title: "自动更新歌单", selection: Binding(get: { String(updateHours) }, set: { updateHours = Int($0) ?? 6 }), options: [("0","每次打开"),("3","每 3 小时"),("6","每 6 小时"),("12","每 12 小时"),("24","每天"),("-1","关闭")])
                    Button("立即刷新关注的歌单") { Task { await store.refreshFollowed() } }
                    Text("自动更新会在应用进入前台时按周期执行。").font(.caption).foregroundStyle(Palette.muted)
                }
            }
            if books {
                AndroidSection("听书源登录与设置") {
                    ForEach(Array(sourceRows.enumerated()), id: \.offset) { _, source in NavigationLink(source.string("name")) { BookSourceSettings(source: source) } }
                    if !sourceError.isEmpty { Text(sourceError).font(.caption).foregroundStyle(Palette.muted) }
                }
                AndroidSection { Button("重新安装官方公开源合集 5.7") { Task { UserDefaults.standard.set(false, forKey: "bundled-books-installed"); await store.installBundledBooks(); await loadSources() } } }
            }
        }.androidPage(books ? "听书插件" : "音乐设置").task { if books { await loadSources() } }
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
        AndroidForm {
            if let plugin {
                Toggle("启用", isOn: Binding(get: { self.plugin?.enabled ?? false }, set: { value in if let index = store.plugins.firstIndex(where: { $0.id == pluginID }) { store.plugins[index].enabled = value; store.invalidate(pluginID) } }))
                LabeledContent("版本", value: plugin.version.isEmpty ? "未声明" : plugin.version)
                TextField("更新直链", text: $address).textInputAutocapitalization(.never).autocorrectionDisabled()
                Button("保存更新地址") { if let index = store.plugins.firstIndex(where: { $0.id == pluginID }) { store.plugins[index].address = address } }
                Button("检查并更新") { Task { busy = true; defer { busy = false }; do { try await store.installFromURL(address, books: plugin.books); store.message = "已更新插件" } catch { store.message = error.localizedDescription } } }.disabled(busy || address.isEmpty)
                if busy { ProgressView() }
                Button("移除插件", role: .destructive) { store.invalidate(pluginID); store.plugins.removeAll { $0.id == pluginID } }
            }
        }.androidPage(plugin?.name ?? "插件").onAppear { address = plugin?.address ?? "" }
    }
}

struct BookSourceSettings: View {
    @EnvironmentObject var store: AppStore
    var source: JSONObject
    @State private var values: JSONObject = [:]
    var body: some View {
        AndroidForm {
            if let url = URL(string: source.string("loginUrl")), ["https", "http"].contains(url.scheme ?? "") { NavigationLink("在应用内登录") { LoginWebView(url: url).androidPage(source.string("name")).navigationBarTitleDisplayMode(.inline) } }
            ForEach(Array(source.rows("config").enumerated()), id: \.offset) { _, row in
                let key = row.string("key", "id")
                if row.string("type") == "boolean" || row.string("type") == "switch" || row["default"] is Bool {
                    Toggle(row.string("label", "title", "name"), isOn: Binding(get: { values[key] as? Bool ?? row["default"] as? Bool ?? false }, set: { values[key] = $0 }))
                } else { TextField(row.string("label", "title", "name"), text: Binding(get: { values[key] as? String ?? row.string("default") }, set: { values[key] = $0 })) }
            }
            Button("保存源设置") { Task { do { _ = try await store.bookCall(source.string("id"), pluginID: source.string("_plugin"), action: "setPrefs", args: [values]); store.message = "已保存" } catch { store.message = error.localizedDescription } } }
        }.androidPage(source.string("name"))
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
        AndroidForm {
            AndroidSection("yinlangserver") {
                TextField("服务器地址", text: $address).textInputAutocapitalization(.never).autocorrectionDisabled().keyboardType(.URL)
                SecureField("令牌", text: $token)
                Button("保存并连接") { Task { do { try Keychain.write("server-token", token); _ = try await store.server("/health"); await load(); store.message = "下载服务器连接成功" } catch { store.message = error.localizedDescription } } }
            }
            AndroidSection("下载任务") {
                Button("刷新任务") { Task { await load() } }.disabled(busy)
                if busy { ProgressView() }; if !error.isEmpty { Text(error).font(.caption) }
                ForEach(Array(jobs.enumerated()), id: \.offset) { _, job in VStack(alignment: .leading, spacing: 6) { Text(job.string("title")); Text(job.string("status") + " · " + job.string("error")).font(.caption).foregroundStyle(Palette.muted) } }
                Button("清除已完成与失败记录", role: .destructive) { Task { do { _ = try await store.server("/v1/jobs/history", method: "DELETE"); await load() } catch { store.message = error.localizedDescription } } }
            }
        }.androidPage("下载服务器").task { if !address.isEmpty { await load() } }
    }
    private func load() async { busy = true; defer { busy = false }; do { jobs = try await store.server("/v1/jobs").rows("jobs"); error = "" } catch { self.error = error.localizedDescription } }
}

struct LicenseView: View {
    @State private var text = ""
    var body: some View { ScrollView { Text(text).font(.system(.caption, design: .monospaced)).textSelection(.enabled).padding() }.androidPage("第三方许可").task { if let url = Bundle.main.url(forResource: "catalog-licenses", withExtension: "txt", subdirectory: "Runtime") { text = (try? String(contentsOf: url, encoding: .utf8)) ?? "" } } }
}
