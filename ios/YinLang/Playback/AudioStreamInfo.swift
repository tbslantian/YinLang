import AVFoundation
import AudioToolbox

struct AudioStreamInfo: Equatable {
    var format = ""
    var bitsPerSecond = 0.0
    var sampleRate = 0.0

    var text: String {
        let sample = sampleRate.isFinite && sampleRate > 0 ? Self.number(sampleRate / 1000) : "--"
        let rate = bitsPerSecond.isFinite && bitsPerSecond > 0 ? Self.number(bitsPerSecond / 1000) : "--"
        return "\(format.isEmpty ? "格式未知" : format) · \(rate) kbps · \(sample) kHz"
    }
    private static func number(_ value: Double) -> String {
        String(format: "%.2f", locale: Locale(identifier: "en_US_POSIX"), value)
            .replacingOccurrences(of: #"\.?0+$"#, with: "", options: .regularExpression)
    }
    static func read(_ track: AVAssetTrack, asset: AVURLAsset? = nil, url: URL? = nil, headers: [String: String] = [:]) async -> AudioStreamInfo {
        let descriptions = (try? await track.load(.formatDescriptions)) ?? []
        var bitrate = Double((try? await track.load(.estimatedDataRate)) ?? 0)
        guard let description = descriptions.first,
              let basic = CMAudioFormatDescriptionGetStreamBasicDescription(description)?.pointee else { return AudioStreamInfo(bitsPerSecond: bitrate) }
        let format: String
        switch basic.mFormatID {
        case kAudioFormatMPEGLayer3: format = "MP3"
        case kAudioFormatMPEG4AAC, kAudioFormatMPEG4AAC_HE, kAudioFormatMPEG4AAC_HE_V2: format = "AAC"
        case kAudioFormatAppleLossless: format = "ALAC"
        case kAudioFormatFLAC: format = "FLAC"
        case kAudioFormatLinearPCM: format = "PCM"
        case kAudioFormatOpus: format = "OPUS"
        default:
            let code = basic.mFormatID
            format = String(bytes: [UInt8((code >> 24) & 255), UInt8((code >> 16) & 255), UInt8((code >> 8) & 255), UInt8(code & 255)], encoding: .ascii)?.trimmingCharacters(in: .whitespaces).uppercased() ?? ""
        }
        // HTTP PCM tracks may report no estimated rate. Their stream
        // description gives the exact rate, without using the requested tier.
        if bitrate <= 0, basic.mSampleRate > 0 {
            if basic.mBytesPerPacket > 0, basic.mFramesPerPacket > 0 {
                bitrate = basic.mSampleRate * Double(basic.mBytesPerPacket) * 8 / Double(basic.mFramesPerPacket)
            } else if basic.mFormatID == kAudioFormatLinearPCM {
                bitrate = basic.mSampleRate * Double(basic.mBitsPerChannel) * Double(basic.mChannelsPerFrame)
            }
        }
        var info = AudioStreamInfo(format: format, bitsPerSecond: bitrate, sampleRate: basic.mSampleRate)
        // AVFoundation often reports zero estimatedDataRate for variable-rate
        // FLAC/ALAC. Compute the actual average from this response's byte count
        // and measured duration; never substitute the requested quality tier.
        if (!bitrate.isFinite || bitrate <= 0), let asset, let url,
           let duration = try? await asset.load(.duration).seconds, duration.isFinite, duration > 0,
           let bytes = await contentLength(url, headers: headers), bytes > 0 {
            info.bitsPerSecond = Double(bytes) * 8 / duration
        }
        return info
    }
    private static func contentLength(_ url: URL, headers: [String: String]) async -> Int64? {
        if url.isFileURL { return (try? url.resourceValues(forKeys: [.fileSizeKey])).flatMap { $0.fileSize }.map(Int64.init) }
        guard ["https", "http"].contains(url.scheme?.lowercased() ?? ""), !Task.isCancelled else { return nil }
        let config = URLSessionConfiguration.ephemeral; config.timeoutIntervalForRequest = 10
        let session = URLSession(configuration: config); defer { session.invalidateAndCancel() }
        var request = URLRequest(url: url); request.allHTTPHeaderFields = headers
        request.setValue("identity", forHTTPHeaderField: "Accept-Encoding"); request.httpMethod = "HEAD"
        if let (_, response) = try? await session.data(for: request), let http = response as? HTTPURLResponse,
           (200..<300).contains(http.statusCode), let value = http.value(forHTTPHeaderField: "Content-Length"), let size = Int64(value), size > 0 { return size }
        guard !Task.isCancelled else { return nil }
        // Some music hosts reject HEAD. Read only response headers for a one-
        // byte Range request; cancel immediately even when Range is ignored.
        request.httpMethod = "GET"; request.setValue("bytes=0-0", forHTTPHeaderField: "Range")
        guard let (_, response) = try? await session.bytes(for: request), let http = response as? HTTPURLResponse else { return nil }
        if http.statusCode == 206, let range = http.value(forHTTPHeaderField: "Content-Range"), let value = range.split(separator: "/").last { return Int64(value) }
        if http.statusCode == 200, let value = http.value(forHTTPHeaderField: "Content-Length") { return Int64(value) }
        return nil
    }
}
