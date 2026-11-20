import Foundation

struct DLNADevice: Identifiable, Hashable {
    var id: String
    var name: String
    var transport: URL
    var rendering: URL?
    var transportService = "urn:schemas-upnp-org:service:AVTransport:1"
    var renderingService = "urn:schemas-upnp-org:service:RenderingControl:1"
    var descriptionURL: URL?
}

private final class DeviceXML: NSObject, XMLParserDelegate {
    var friendlyName = ""
    var identifier = ""
    var urlBase = ""
    var services: [(String, String)] = []
    private var field = "", text = "", serviceType = "", control = ""
    func parser(_ parser: XMLParser, didStartElement elementName: String, namespaceURI: String?, qualifiedName qName: String?, attributes attributeDict: [String: String] = [:]) { field = elementName; text = ""; if elementName == "service" { serviceType = ""; control = "" } }
    func parser(_ parser: XMLParser, foundCharacters string: String) { text += string }
    func parser(_ parser: XMLParser, didEndElement elementName: String, namespaceURI: String?, qualifiedName qName: String?) {
        let value = text.trimmingCharacters(in: .whitespacesAndNewlines)
        if elementName == "friendlyName" && friendlyName.isEmpty { friendlyName = value }
        if elementName == "UDN" && identifier.isEmpty { identifier = value }
        if elementName == "URLBase" { urlBase = value }
        if elementName == "serviceType" { serviceType = value }
        if elementName == "controlURL" { control = value }
        if elementName == "service" { services.append((serviceType, control)) }
        text = ""
    }
}

@MainActor
final class DLNA: ObservableObject {
    @Published var devices: [DLNADevice] = []
    @Published var current: DLNADevice?
    @Published var searching = false
    @Published var error = ""
    @Published private(set) var discoveryDetails = ""
    @Published var volume = 0.5
    private var relay: AudioRelay?
    func search(host: String? = nil) async {
        guard !searching else { return }
        let host = host?.trimmingCharacters(in: .whitespacesAndNewlines)
        if let host, !SSDPDiscovery.isDeviceIPv4(host) { error = "请输入设备的 IPv4 地址，例如 192.168.1.20"; return }
        searching = true; error = ""; discoveryDetails = ""; defer { searching = false }
        let task = Task.detached(priority: .utility) { SSDPDiscovery.discover(host: host) }
        let report = await withTaskCancellationHandler(operation: { await task.value }, onCancel: { task.cancel() })
        guard !Task.isCancelled else { return }
        // Previously confirmed descriptions are revalidated by unicast HTTP,
        // even when the current re-signing profile cannot multicast.
        let saved = host == nil ? UserDefaults.standard.stringArray(forKey: "dlna-descriptions") ?? [] : []
        let found = await load(Array(report.locations.union(saved)).sorted())
        guard !Task.isCancelled else { return }
        if host == nil { devices = found } else { merge(found) }
        remember(found)
        let codes = report.socketErrors.sorted().map { String($0) }.joined(separator: ", ")
        discoveryDetails = "\(host == nil ? "自动搜索" : "按 IP 搜索")：\(report.interfaces) 个网络接口，发送 \(report.sent) 次，收到 \(report.replies) 个回应，找到 \(found.count) 个可播放设备。" + (codes.isEmpty ? "" : " 网络错误代码：" + codes)
        if found.isEmpty {
            if report.interfaces == 0 { error = "未找到可用局域网连接，请让手机和接收设备连接同一 Wi-Fi 或有线局域网。" }
            else if host != nil { error = "该 IP 未返回可播放的 DLNA 服务。请检查设备 IP 和投送开关；部分设备不响应按 IP 搜索，可填写完整设备描述地址。" }
            else if report.sent == 0 { error = "搜索请求未能发送。请开启“设置 → 音浪 → 本地网络”；自动搜索还需要重签后的应用具备多播权限，可先尝试按设备 IP 搜索。" }
            else if report.locations.isEmpty { error = "未收到 DLNA 设备回应。请检查接收设备的 DLNA／UPnP 开关和同一局域网连接，也可按设备 IP 搜索。" }
            else { error = "收到设备回应，但未读取到可播放的 DLNA 服务。请确认设备开启了音频投送接收功能。" }
        }
    }
    func addDescription(_ address: String) async {
        guard !searching else { return }
        let address = address.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let url = Self.descriptionURL(address) else { error = "请输入 DLNA 设备描述 XML 的完整 HTTP 地址"; return }
        searching = true; error = ""; defer { searching = false }
        let found = await load([url.absoluteString])
        guard !Task.isCancelled else { return }
        merge(found); remember(found)
        error = found.isEmpty ? "该地址未返回可播放的 DLNA 设备描述，请检查地址及本地网络权限。" : ""
    }
    private func load(_ locations: [String]) async -> [DLNADevice] {
        await withTaskGroup(of: DLNADevice?.self) { group in
            var pending = Array(locations.prefix(128)).makeIterator()
            for _ in 0..<4 { if let location = pending.next() { group.addTask { await Self.inspect(location) } } }
            var found: [String: DLNADevice] = [:]
            while let result = await group.next() {
                if let result { found[result.id] = result }
                if !Task.isCancelled, let location = pending.next() { group.addTask { await Self.inspect(location) } }
            }
            return found.values.sorted { $0.name.localizedStandardCompare($1.name) == .orderedAscending }
        }
    }
    nonisolated private static func descriptionURL(_ value: String) -> URL? {
        guard let url = URL(string: value), ["http", "https"].contains(url.scheme?.lowercased() ?? ""), url.host != nil, url.user == nil, url.password == nil else { return nil }
        return url
    }
    nonisolated private static func inspect(_ location: String) async -> DLNADevice? {
        guard let url = descriptionURL(location), !Task.isCancelled else { return nil }
        var request = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 4)
        request.setValue("text/xml, application/xml", forHTTPHeaderField: "Accept")
        guard let (data, response) = try? await URLSession.shared.data(for: request), data.count <= 512 * 1024,
              (response as? HTTPURLResponse)?.statusCode == 200 else { return nil }
        let document = DeviceXML(), parser = XMLParser(data: data)
        parser.shouldProcessNamespaces = true; parser.shouldResolveExternalEntities = false; parser.delegate = document
        guard parser.parse(), let transport = document.services.first(where: { $0.0.contains(":AVTransport:") && !$0.1.isEmpty }) else { return nil }
        let base = URL(string: document.urlBase, relativeTo: url)?.absoluteURL ?? url
        guard let control = URL(string: transport.1, relativeTo: base)?.absoluteURL, descriptionURL(control.absoluteString) != nil else { return nil }
        let rendering = document.services.first { $0.0.contains(":RenderingControl:") && !$0.1.isEmpty }
        let renderURL = rendering.flatMap { URL(string: $0.1, relativeTo: base)?.absoluteURL }.flatMap { descriptionURL($0.absoluteString) }
        return DLNADevice(id: document.identifier.isEmpty ? control.absoluteString : document.identifier,
                          name: document.friendlyName.isEmpty ? url.host ?? "DLNA" : document.friendlyName,
                          transport: control, rendering: renderURL, transportService: transport.0,
                          renderingService: rendering?.0 ?? "urn:schemas-upnp-org:service:RenderingControl:1", descriptionURL: url)
    }
    private func merge(_ found: [DLNADevice]) {
        for device in found { devices.removeAll { $0.id == device.id }; devices.append(device) }
        devices.sort { $0.name.localizedStandardCompare($1.name) == .orderedAscending }
    }
    private func remember(_ found: [DLNADevice]) {
        guard !found.isEmpty else { return }
        let saved = UserDefaults.standard.stringArray(forKey: "dlna-descriptions") ?? []
        var seen: Set<String> = []
        let addresses = (found.compactMap { $0.descriptionURL?.absoluteString } + saved).filter { seen.insert($0).inserted }
        UserDefaults.standard.set(Array(addresses.prefix(32)), forKey: "dlna-descriptions")
    }
    func soap(_ device: DLNADevice, action: String, values: [(String, String)] = [], rendering: Bool = false) async throws -> String {
        let service = rendering ? device.renderingService : device.transportService
        guard let url = rendering ? device.rendering : device.transport else { throw AppFailure.message("设备不支持音量控制") }
        let parameters = [("InstanceID", "0")] + values
        let xml = "<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body><u:\(action) xmlns:u=\"\(service)\">" + parameters.map { "<\($0.0)>\(Self.escape($0.1))</\($0.0)>" }.joined() + "</u:\(action)></s:Body></s:Envelope>"
        var request = URLRequest(url: url); request.httpMethod = "POST"; request.httpBody = Data(xml.utf8); request.timeoutInterval = 10
        request.setValue("text/xml; charset=\"utf-8\"", forHTTPHeaderField: "Content-Type"); request.setValue("\"\(service)#\(action)\"", forHTTPHeaderField: "SOAPAction")
        let (data, response) = try await URLSession.shared.data(for: request)
        guard (response as? HTTPURLResponse)?.statusCode == 200 else { throw AppFailure.message("设备执行 \(action) 失败") }
        return String(decoding: data, as: UTF8.self)
    }
    func cast(_ item: MediaItem, resolved: URL, headers: [String: String], to device: DLNADevice) async throws {
        guard !resolved.pathExtension.lowercased().contains("m3u8") else { throw AppFailure.message("DLNA 暂不转发 HLS，请使用 AirPlay") }
        relay?.stop(); let relay = AudioRelay(); let url = try await relay.start(source: resolved, headers: headers); self.relay = relay
        let metadata = "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\"><item id=\"0\" parentID=\"0\" restricted=\"1\"><dc:title>\(Self.escape(item.title))</dc:title><upnp:artist>\(Self.escape(item.subtitle))</upnp:artist><upnp:class>object.item.audioItem.musicTrack</upnp:class></item></DIDL-Lite>"
        _ = try await soap(device, action: "SetAVTransportURI", values: [("CurrentURI", url.absoluteString), ("CurrentURIMetaData", metadata)])
        _ = try await soap(device, action: "Play", values: [("Speed", "1")]); current = device
    }
    func stop() async { if let current { _ = try? await soap(current, action: "Stop") }; current = nil; relay?.stop(); relay = nil }
    static func xmlValue(_ xml: String, tag: String) -> String {
        guard let start = xml.range(of: "<\(tag)>"), let end = xml.range(of: "</\(tag)>", range: start.upperBound..<xml.endIndex) else { return "" }; return String(xml[start.upperBound..<end.lowerBound])
    }
    static func seconds(_ text: String) -> Double { text.split(separator: ":").reduce(0.0) { $0 * 60 + (Double($1) ?? 0) } }
    static func escape(_ value: String) -> String { value.replacingOccurrences(of: "&", with: "&amp;").replacingOccurrences(of: "<", with: "&lt;").replacingOccurrences(of: ">", with: "&gt;").replacingOccurrences(of: "\"", with: "&quot;") }
}
