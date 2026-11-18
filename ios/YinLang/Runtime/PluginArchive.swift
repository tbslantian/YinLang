import Foundation

enum PluginArchive {
    static func decompress(_ data: Data, raw: Bool = false, partial: Bool = false) throws -> Data {
        var stream = z_stream()
        guard inflateInit2_(&stream, raw ? -MAX_WBITS : MAX_WBITS, ZLIB_VERSION, Int32(MemoryLayout<z_stream>.size)) == Z_OK else { throw AppFailure.message("解压初始化失败") }
        defer { inflateEnd(&stream) }
        return try data.withUnsafeBytes { input in
            stream.next_in = UnsafeMutablePointer(mutating: input.bindMemory(to: Bytef.self).baseAddress)
            stream.avail_in = uInt(data.count)
            var output = Data(); var chunk = [UInt8](repeating: 0, count: 16384)
            while true {
                let (status, count) = chunk.withUnsafeMutableBytes { buffer -> (Int32, Int) in
                    stream.next_out = buffer.bindMemory(to: Bytef.self).baseAddress; stream.avail_out = uInt(buffer.count)
                    let status = inflate(&stream, Z_NO_FLUSH); return (status, buffer.count - Int(stream.avail_out))
                }
                output.append(contentsOf: chunk.prefix(count))
                guard output.count <= 8_000_000 else { throw AppFailure.message("插件或解压内容过大") }
                if status == Z_STREAM_END { return output }
                if partial && stream.avail_in == 0 { return output }
                guard status == Z_OK, count > 0 else { throw AppFailure.message("压缩内容无效") }
            }
        }
    }
    static func compress(_ data: Data) throws -> Data {
        var size = compressBound(uLong(data.count)); var result = Data(count: Int(size))
        let status = result.withUnsafeMutableBytes { output in data.withUnsafeBytes { input in
            compress2(output.bindMemory(to: Bytef.self).baseAddress, &size, input.bindMemory(to: Bytef.self).baseAddress, uLong(data.count), Z_DEFAULT_COMPRESSION)
        } }
        guard status == Z_OK else { throw AppFailure.message("压缩失败") }; result.count = Int(size); return result
    }
    // Read central-directory entries so ZIPs with data descriptors work too.
    static func read(_ data: Data, name: String, address: String, books: Bool) throws -> Plugin {
        guard data.count <= 8_000_000 else { throw AppFailure.message("插件文件超过 8 MB") }
        if !books {
            guard let script = String(data: data, encoding: .utf8), !script.isEmpty else { throw AppFailure.message("请选择 UTF-8 JavaScript 音源") }
            return Plugin(id: UUID().uuidString, name: name, version: "", script: script, address: address, books: false)
        }
        func number(_ offset: Int, _ width: Int) throws -> Int {
            guard offset >= 0, offset + width <= data.count else { throw AppFailure.message("ZIP 文件截断") }
            return (0..<width).reduce(0) { $0 | Int(data[offset + $1]) << (8 * $1) }
        }
        guard data.count >= 22 else { throw AppFailure.message("ZIP 文件不完整") }
        let end = stride(from: data.count - 22, through: max(0, data.count - 65557), by: -1).first { (try? number($0, 4)) == 0x06054b50 }
        guard let end else { throw AppFailure.message("找不到 ZIP 目录") }
        let count = try number(end + 10, 2); var position = try number(end + 16, 4)
        guard count <= 512 else { throw AppFailure.message("插件包文件过多") }
        var files: [String: String] = [:]; var total = 0
        for _ in 0..<count {
            guard try number(position, 4) == 0x02014b50 else { throw AppFailure.message("ZIP 目录无效") }
            let flags = try number(position + 8, 2), method = try number(position + 10, 2), crc = try number(position + 16, 4)
            let size = try number(position + 20, 4), expanded = try number(position + 24, 4), length = try number(position + 28, 2)
            let extra = try number(position + 30, 2), comment = try number(position + 32, 2), local = try number(position + 42, 4)
            guard position + 46 + length <= data.count else { throw AppFailure.message("ZIP 路径无效") }
            let path = String(decoding: data[(position + 46)..<(position + 46 + length)], as: UTF8.self).replacingOccurrences(of: "\\", with: "/")
            position += 46 + length + extra + comment
            guard !path.hasPrefix("/"), !path.components(separatedBy: "/").contains(".."), !path.contains("__MACOSX"), path.hasSuffix(".js") || path.hasSuffix("manifest.json") else { continue }
            guard flags & 1 == 0, [0, 8].contains(method), expanded <= 3_000_000, total + expanded <= 3_000_000,
                  try number(local, 4) == 0x04034b50 else { throw AppFailure.message("不支持的 ZIP 插件") }
            let start = try local + 30 + number(local + 26, 2) + number(local + 28, 2)
            guard start >= 0, start + size <= data.count else { throw AppFailure.message("ZIP 数据无效") }
            let compressed = data.subdata(in: start..<(start + size)); let bytes = try method == 0 ? compressed : decompress(compressed, raw: true)
            let actualCRC = bytes.withUnsafeBytes { crc32(0, $0.bindMemory(to: Bytef.self).baseAddress, uInt(bytes.count)) }
            guard bytes.count == expanded, Int(actualCRC) == crc else { throw AppFailure.message("插件校验失败") }
            guard let text = String(data: bytes, encoding: .utf8) else { throw AppFailure.message("插件编码无效") }
            total += bytes.count; files[path] = text.replacingOccurrences(of: "\u{feff}", with: "")
        }
        let manifestPath = files.keys.filter { $0 == "manifest.json" || $0.hasSuffix("/manifest.json") }.min { $0.count < $1.count }
        let manifest = manifestPath.flatMap { files[$0]?.data(using: .utf8) }.flatMap { try? JSONSerialization.jsonObject(with: $0) as? JSONObject } ?? [:]
        let prefix = manifestPath.map { String($0.dropLast("manifest.json".count)) } ?? ""
        let scripts = (manifest["scripts"] as? [String])?.map { prefix + $0 } ?? files.keys.filter { $0.hasSuffix(".js") }.sorted()
        guard !scripts.isEmpty, scripts.allSatisfy({ files[$0] != nil && $0.hasSuffix(".js") }) else { throw AppFailure.message("ZIP 缺少插件脚本") }
        return Plugin(id: UUID().uuidString, name: manifest.string("name").isEmpty ? name : manifest.string("name"), version: manifest.string("version"), script: scripts.compactMap { files[$0] }.joined(separator: "\n;\n"), address: address, books: true)
    }
}
