import SwiftUI

struct CastView: View {
    @EnvironmentObject var player: Player
    @StateObject private var dlna: DLNA
    @State private var address = ""
    init(player: Player) { _dlna = StateObject(wrappedValue: player.dlna) }
    var body: some View {
        NavigationStack {
            AndroidForm {
                AndroidSection("AirPlay") {
                    HStack { Label("选择系统音频设备", systemImage: "airplayaudio"); Spacer(); AirPlayPicker(dark: false).frame(width: 44, height: 44).disabled(dlna.current != nil) }
                    Text("当前系统输出：" + player.audioOutputName).font(.system(size: 13)).foregroundStyle(Palette.muted)
                    SystemVolume().frame(minHeight: 44).accessibilityLabel("系统输出音量").disabled(dlna.current != nil)
                    Text("Apple TV 音量不可调时，请用电视或功放遥控器；也可在 Apple TV“设置 → 遥控器与设备 → 音量控制”中检查自动或 HDMI 设置。").font(.caption).foregroundStyle(Palette.muted)
                    if dlna.current != nil { Button("停止 DLNA，切回系统音频") { Task { await player.stopCasting() } } }
                }
                AndroidSection("封面与歌词") {
                    Text("封面和歌曲信息由接收设备显示。歌词提供静态文本，接收设备可能不显示；观看同步歌词可使用系统屏幕镜像，并打开播放器歌词页。").font(.caption).foregroundStyle(Palette.muted)
                }
                AndroidSection("DLNA 音频设备") {
                    Button("搜索局域网设备") { Task { await dlna.search() } }.disabled(dlna.searching)
                    TextField("设备描述地址，例如 http://192.168.1.10:端口/description.xml", text: $address).textInputAutocapitalization(.never).autocorrectionDisabled().keyboardType(.URL)
                    Button("手动连接") { Task { await dlna.addDescription(address) } }.disabled(address.isEmpty)
                    if dlna.searching { ProgressView() }
                    if !dlna.error.isEmpty { Text(dlna.error).font(.caption).foregroundStyle(Palette.muted) }
                    ForEach(dlna.devices) { device in Button { Task { await player.cast(to: device) } } label: { HStack { Label(device.name, systemImage: "hifispeaker"); Spacer(); if dlna.current == device { YLIcon(systemName: "checkmark") } } } }
                }
                if let current = dlna.current {
                    AndroidSection("正在投送：" + current.name) {
                        Slider(value: $dlna.volume, in: 0...1, onEditingChanged: { editing in if !editing { Task { _ = try? await dlna.soap(current, action: "SetVolume", values: [("Channel", "Master"), ("DesiredVolume", "\(Int(dlna.volume * 100))")], rendering: true) } } })
                        Button("停止投送，切回本机") { Task { await player.stopCasting() } }
                    }
                }
                AndroidSection { Text("DLNA 转发当前音频与所需请求头；接收设备需要支持文件的音频格式。HLS 不进行转码。").font(.caption).foregroundStyle(Palette.muted) }
            }.androidPage("投送").task { await dlna.search() }
        }
    }
}
