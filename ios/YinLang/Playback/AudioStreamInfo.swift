import AVFoundation
import AudioToolbox

struct AudioStreamInfo: Equatable {
    var format = ""
    var bitsPerSecond = 0.0
    var sampleRate = 0.0

    var text: String {
        let rate = bitsPerSecond.isFinite && bitsPerSecond > 0 ? Self.number(bitsPerSecond / 1000) : "--"
        let sample = sampleRate.isFinite && sampleRate > 0 ? Self.number(sampleRate / 1000) : "--"
        return "\(format.isEmpty ? "格式未知" : format) · \(rate) kbps · \(sample) kHz"
    }
    private static func number(_ value: Double) -> String {
        String(format: "%.2f", locale: Locale(identifier: "en_US_POSIX"), value)
            .replacingOccurrences(of: #"\.?0+$"#, with: "", options: .regularExpression)
    }
    static func read(_ track: AVAssetTrack) async -> AudioStreamInfo {
        let descriptions = (try? await track.load(.formatDescriptions)) ?? []
        let bitrate = Double((try? await track.load(.estimatedDataRate)) ?? 0)
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
        return AudioStreamInfo(format: format, bitsPerSecond: bitrate, sampleRate: basic.mSampleRate)
    }
}
