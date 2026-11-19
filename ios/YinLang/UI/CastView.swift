import SwiftUI

struct CastView: View {
    @EnvironmentObject var player: Player
    @StateObject private var dlna: DLNA
    init(player: Player) { _dlna = StateObject(wrappedValue: player.dlna) }
    var body: some View {
        NavigationStack {
            Form {
                Section("AirPlay") { HStack { Label("选择系统音频设备", systemImage: "airplayaudio"); Spacer(); AirPlayPicker(dark: false).frame(width: 44, height: 44) } }
                Section("DLNA 音频设备") {
                    Button("搜索局域网设备") { Task { await dlna.search() } }.disabled(dlna.searching)
                    if dlna.searching { ProgressView() }
                    if !dlna.error.isEmpty { Text(dlna.error).font(.caption).foregroundStyle(.secondary) }
                    ForEach(dlna.devices) { device in Button { Task { await player.cast(to: device) } } label: { HStack { Label(device.name, systemImage: "hifispeaker"); Spacer(); if dlna.current == device { Image(systemName: "checkmark") } } } }
                }
                if let current = dlna.current {
                    Section("正在投送：" + current.name) {
                        Slider(value: $dlna.volume, in: 0...1, onEditingChanged: { editing in if !editing { Task { _ = try? await dlna.soap(current, action: "SetVolume", values: [("Channel", "Master"), ("DesiredVolume", "\(Int(dlna.volume * 100))")], rendering: true) } } })
                        Button("停止投送，切回本机") { Task { await player.stopCasting() } }
                    }
                }
                Section { Text("DLNA 转发当前音频与所需请求头；接收设备需要支持文件的音频格式。HLS 不进行转码。").font(.caption).foregroundStyle(.secondary) }
            }.navigationTitle("投送").task { await dlna.search() }
        }
    }
}
