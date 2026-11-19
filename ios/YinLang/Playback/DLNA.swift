import Foundation
import Darwin

struct DLNADevice: Identifiable, Hashable {
    var id: String
    var name: String
    var transport: URL
    var rendering: URL?
}

private final class DeviceXML: NSObject, XMLParserDelegate {
    var friendlyName = ""
    var services: [(String, String)] = []
    private var field = "", text = "", serviceType = "", control = ""
    func parser(_ parser: XMLParser, didStartElement elementName: String, namespaceURI: String?, qualifiedName qName: String?, attributes attributeDict: [String: String] = [:]) { field = elementName; text = ""; if elementName == "service" { serviceType = ""; control = "" } }
    func parser(_ parser: XMLParser, foundCharacters string: String) { text += string }
    func parser(_ parser: XMLParser, didEndElement elementName: String, namespaceURI: String?, qualifiedName qName: String?) {
        let value = text.trimmingCharacters(in: .whitespacesAndNewlines)
        if elementName == "friendlyName" && friendlyName.isEmpty { friendlyName = value }
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
    @Published var volume = 0.5
    private var relay: AudioRelay?
    nonisolated static func discoverLocations() -> Set<String> {
        let handle = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP)
        guard handle >= 0 else { return [] }; defer { close(handle) }
        var wait = timeval(tv_sec: 0, tv_usec: 500000); setsockopt(handle, SOL_SOCKET, SO_RCVTIMEO, &wait, socklen_t(MemoryLayout<timeval>.size))
        var target = sockaddr_in(); target.sin_len = UInt8(MemoryLayout<sockaddr_in>.size); target.sin_family = sa_family_t(AF_INET); target.sin_port = UInt16(1900).bigEndian; inet_pton(AF_INET, "239.255.255.250", &target.sin_addr)
        let message = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n"
        message.withCString { bytes in withUnsafePointer(to: &target) { pointer in pointer.withMemoryRebound(to: sockaddr.self, capacity: 1) { _ = sendto(handle, bytes, strlen(bytes), 0, $0, socklen_t(MemoryLayout<sockaddr_in>.size)) } } }
        let deadline = Date().addingTimeInterval(4); var results: Set<String> = []; var buffer = [UInt8](repeating: 0, count: 65536)
        while Date() < deadline {
            let count = recv(handle, &buffer, buffer.count, 0); if count <= 0 { continue }
            let response = String(decoding: buffer.prefix(count), as: UTF8.self)
            for line in response.components(separatedBy: "\r\n") where line.lowercased().hasPrefix("location:") { results.insert(String(line.dropFirst(9)).trimmingCharacters(in: .whitespaces)) }
        }
        return results
    }
    func search() async {
        guard !searching else { return }; searching = true; error = ""; defer { searching = false }
        let locations = await Task.detached { Self.discoverLocations() }.value
        var found: [DLNADevice] = []
        for location in locations {
            guard let url = URL(string: location), let (data, _) = try? await URLSession.shared.data(from: url) else { continue }
            let document = DeviceXML(), parser = XMLParser(data: data); parser.delegate = document
            guard parser.parse(), let transport = document.services.first(where: { $0.0.contains(":AVTransport:") }), let control = URL(string: transport.1, relativeTo: url)?.absoluteURL else { continue }
            let rendering = document.services.first { $0.0.contains(":RenderingControl:") }.flatMap { URL(string: $0.1, relativeTo: url)?.absoluteURL }
            found.append(.init(id: location, name: document.friendlyName.isEmpty ? url.host ?? "DLNA" : document.friendlyName, transport: control, rendering: rendering))
        }
        devices = found.sorted { $0.name < $1.name }; if devices.isEmpty { error = "未找到 DLNA 设备，请确认手机与设备处于同一局域网并允许本地网络访问。" }
    }
    func soap(_ device: DLNADevice, action: String, values: [(String, String)] = [], rendering: Bool = false) async throws -> String {
        let service = "urn:schemas-upnp-org:service:\(rendering ? "RenderingControl" : "AVTransport"):1"
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
