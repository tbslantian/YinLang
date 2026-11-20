import SwiftUI
import UIKit

struct CastView: View {
    @EnvironmentObject var player: Player
    @EnvironmentObject var store: AppStore
    @StateObject private var dlna: DLNA
    @State private var address = ""
    @State private var deviceIP = ""
    @State private var television = false
    init(player: Player) { _dlna = StateObject(wrappedValue: player.dlna) }
    var body: some View {
        NavigationStack {
            AndroidForm {
                AndroidSection("AirPlay") {
                    HStack { Label("AirPlay 音乐投送", systemImage: "airplayaudio"); Spacer(); AirPlayPicker(dark: false).frame(width: 44, height: 44).disabled(dlna.current != nil) }
                    Text("当前系统输出：" + player.audioOutputName).font(.system(size: 13)).foregroundStyle(Palette.muted)
                    Text("选中设备即可传递封面和歌曲信息，手机无需打开电视画面。电视的封面布局和页面由接收设备控制。").font(.caption).foregroundStyle(Palette.muted)
                    SystemVolume().frame(minHeight: 44).accessibilityLabel("系统输出音量").disabled(dlna.current != nil)
                    Text("Apple TV 音量不可调时，请用电视或功放遥控器；也可在 Apple TV“设置 → 遥控器与设备 → 音量控制”中检查自动或 HDMI 设置。").font(.caption).foregroundStyle(Palette.muted)
                    if dlna.current != nil { Button("停止 DLNA，切回系统音频") { Task { await player.stopCasting() } } }
                }
                AndroidSection {
                    DisclosureGroup("屏幕镜像（可选）") {
                        Button { television = true } label: { Label("打开封面／唱片与同步歌词", systemImage: "tv") }.disabled(player.current == nil || dlna.current != nil)
                        Text("旋转唱片与同步歌词需要在控制中心开启“屏幕镜像 → Apple TV”，再打开此画面并横放设备，保持音浪在前台。普通 AirPlay 音乐投送无需此步骤。").font(.caption).foregroundStyle(Palette.muted)
                    }
                }
                AndroidSection("DLNA 音频设备") {
                    Button("搜索局域网设备") { Task { await dlna.search() } }.disabled(dlna.searching)
                    DisclosureGroup("按设备 IP 或描述地址连接") {
                        TextField("设备 IP，例如 192.168.1.20", text: $deviceIP).textInputAutocapitalization(.never).autocorrectionDisabled().keyboardType(.decimalPad)
                        Button("按 IP 搜索") { Task { await dlna.search(host: deviceIP) } }.disabled(deviceIP.isEmpty || dlna.searching)
                        TextField("描述地址：http://设备IP:端口/description.xml", text: $address).textInputAutocapitalization(.never).autocorrectionDisabled().keyboardType(.URL)
                        Button("连接描述地址") { Task { await dlna.addDescription(address) } }.disabled(address.isEmpty || dlna.searching)
                    }
                    if dlna.searching { ProgressView() }
                    if !dlna.error.isEmpty { Text(dlna.error).font(.caption).foregroundStyle(Palette.muted) }
                    if !dlna.discoveryDetails.isEmpty { DisclosureGroup("搜索详情") { Text(dlna.discoveryDetails).font(.caption).foregroundStyle(Palette.muted).textSelection(.enabled) } }
                    Button("打开本地网络权限设置") { if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) } }
                    Text("接收设备需要支持并开启 DLNA／UPnP。蓝牙、AirPlay 和厂商专用投送不等同于 DLNA；自动发现还依赖安装签名中的多播权限，按 IP 搜索仅对响应单播搜索的设备有效。").font(.caption).foregroundStyle(Palette.muted)
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
        }.fullScreenCover(isPresented: $television) { TelevisionView().environmentObject(store).environmentObject(player) }
    }
}
