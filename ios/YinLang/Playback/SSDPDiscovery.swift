import Foundation
import Darwin

struct SSDPDiscoveryReport: Sendable {
    var locations: Set<String> = []
    var sent = 0
    var replies = 0
    var interfaces = 0
    var socketErrors: Set<Int32> = []
}

// Each LAN interface gets its own bound socket. A cellular/VPN default route
// must not silently send SSDP away from the receiver's Wi-Fi/Ethernet network.
enum SSDPDiscovery {
    static func isDeviceIPv4(_ text: String) -> Bool {
        var address = in_addr()
        guard inet_pton(AF_INET, text, &address) == 1 else { return false }
        let value = UInt32(bigEndian: address.s_addr)
        return value != 0 && value != UInt32.max && value >> 24 != 127 && value >> 24 < 224
    }

    static func discover(host: String? = nil) -> SSDPDiscoveryReport {
        var report = SSDPDiscoveryReport()
        if let host, !isDeviceIPv4(host) { return report }
        let addresses = lanAddresses()
        report.interfaces = addresses.count
        var handles: [Int32] = []
        defer { for handle in handles { close(handle) } }
        for var address in addresses {
            let handle = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP)
            guard handle >= 0 else { report.socketErrors.insert(errno); continue }
            var local = sockaddr_in()
            local.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
            local.sin_family = sa_family_t(AF_INET)
            local.sin_addr = address
            let bound = withUnsafePointer(to: &local) { pointer in
                pointer.withMemoryRebound(to: sockaddr.self, capacity: 1) { Darwin.bind(handle, $0, socklen_t(MemoryLayout<sockaddr_in>.size)) }
            }
            guard bound == 0 else { report.socketErrors.insert(errno); close(handle); continue }
            if host == nil {
                guard setsockopt(handle, IPPROTO_IP, IP_MULTICAST_IF, &address, socklen_t(MemoryLayout<in_addr>.size)) == 0 else {
                    report.socketErrors.insert(errno); close(handle); continue
                }
                var ttl: UInt8 = 2
                if setsockopt(handle, IPPROTO_IP, IP_MULTICAST_TTL, &ttl, socklen_t(MemoryLayout<UInt8>.size)) != 0 { report.socketErrors.insert(errno) }
            }
            let flags = fcntl(handle, F_GETFL, 0)
            guard flags >= 0, fcntl(handle, F_SETFL, flags | O_NONBLOCK) == 0 else { report.socketErrors.insert(errno); close(handle); continue }
            handles.append(handle)
        }
        guard !handles.isEmpty else { return report }
        var target = sockaddr_in()
        target.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        target.sin_family = sa_family_t(AF_INET)
        target.sin_port = UInt16(1900).bigEndian
        inet_pton(AF_INET, host ?? "239.255.255.250", &target.sin_addr)
        let types = ["urn:schemas-upnp-org:device:MediaRenderer:1", "urn:schemas-upnp-org:service:AVTransport:1", "ssdp:all"]
        let start = ProcessInfo.processInfo.systemUptime
        var round = 0
        var buffer = [UInt8](repeating: 0, count: 65_536)
        while !Task.isCancelled, ProcessInfo.processInfo.systemUptime - start < 8 {
            // A second wave also gives the first-run local-network prompt time
            // to complete. Continue listening past MX for slower receivers.
            if round == 0 || (round == 1 && ProcessInfo.processInfo.systemUptime - start >= 3) {
                for handle in handles {
                    for type in types {
                        let message = "M-SEARCH * HTTP/1.1\r\nHOST: \(host ?? "239.255.255.250"):1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: \(type)\r\n\r\n"
                        let bytes = Array(message.utf8)
                        let count = bytes.withUnsafeBytes { data in
                            withUnsafePointer(to: &target) { pointer in
                                pointer.withMemoryRebound(to: sockaddr.self, capacity: 1) { sendto(handle, data.baseAddress, data.count, 0, $0, socklen_t(MemoryLayout<sockaddr_in>.size)) }
                            }
                        }
                        if count == bytes.count { report.sent += 1 } else { report.socketErrors.insert(errno) }
                    }
                }
                round += 1
            }
            var descriptors = handles.map { pollfd(fd: $0, events: Int16(POLLIN), revents: 0) }
            let ready = descriptors.withUnsafeMutableBufferPointer { Darwin.poll($0.baseAddress, nfds_t($0.count), 200) }
            if ready < 0 { if errno != EINTR { report.socketErrors.insert(errno); break }; continue }
            for descriptor in descriptors where descriptor.revents & Int16(POLLIN) != 0 {
                // Bound each drain so a noisy LAN cannot starve the deadline.
                for _ in 0..<32 {
                    let count = recv(descriptor.fd, &buffer, buffer.count, 0)
                    guard count > 0 else { break }
                    let response = String(decoding: buffer.prefix(count), as: UTF8.self)
                    guard response.uppercased().hasPrefix("HTTP/1.1 200") || response.uppercased().hasPrefix("HTTP/1.0 200") else { continue }
                    report.replies += 1
                    for line in response.components(separatedBy: .newlines) {
                        guard let colon = line.firstIndex(of: ":"), line[..<colon].trimmingCharacters(in: .whitespaces).lowercased() == "location" else { continue }
                        let location = String(line[line.index(after: colon)...]).trimmingCharacters(in: .whitespacesAndNewlines)
                        guard let url = URL(string: location), ["http", "https"].contains(url.scheme?.lowercased() ?? ""), url.host != nil, url.user == nil, url.password == nil else { continue }
                        if report.locations.count < 128 { report.locations.insert(url.absoluteString) }
                    }
                }
            }
        }
        return report
    }

    private static func lanAddresses() -> [in_addr] {
        var list: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&list) == 0 else { return [] }
        defer { freeifaddrs(list) }
        var cursor = list
        var seen: Set<UInt32> = []
        var result: [in_addr] = []
        while let entry = cursor {
            defer { cursor = entry.pointee.ifa_next }
            let flags = entry.pointee.ifa_flags
            guard flags & UInt32(IFF_UP) != 0, flags & UInt32(IFF_MULTICAST) != 0,
                  flags & UInt32(IFF_LOOPBACK | IFF_POINTOPOINT) == 0,
                  let address = entry.pointee.ifa_addr, address.pointee.sa_family == UInt8(AF_INET) else { continue }
            let ipv4 = UnsafeRawPointer(address).assumingMemoryBound(to: sockaddr_in.self).pointee.sin_addr
            if seen.insert(ipv4.s_addr).inserted { result.append(ipv4) }
        }
        return result
    }
}
