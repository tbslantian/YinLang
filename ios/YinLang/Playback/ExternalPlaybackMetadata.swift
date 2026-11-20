import AVFoundation
import MediaPlayer
import UIKit

// AirPlay's receiver decides what it displays. Publish actual media fields;
// never overwrite the song title with a lyric line to simulate timed lyrics.
@MainActor
struct ExternalPlaybackMetadata {
    private(set) var artwork: MPMediaItemArtwork?
    private(set) var artworkJPEG: Data?
    private(set) var plainLyrics = ""

    mutating func setArtwork(_ image: UIImage, jpeg: Data?) {
        artwork = MPMediaItemArtwork(boundsSize: image.size) { _ in image }
        artworkJPEG = jpeg
    }
    mutating func setLyrics(_ text: String, lines: [LyricLine]) {
        let plain = lines.isEmpty ? text : lines.map(\.text).joined(separator: "\n")
        plainLyrics = String(plain.trimmingCharacters(in: .whitespacesAndNewlines).prefix(50_000))
    }
    func items(for item: MediaItem) -> [AVMetadataItem] {
        var metadata: [AVMetadataItem] = [text(.commonIdentifierTitle, item.title), text(.commonIdentifierArtist, item.subtitle)]
        let album = item.kind == "episode" ? item.subtitle : item.raw.string("albumName", "album")
        if !album.isEmpty { metadata.append(text(.commonIdentifierAlbumName, album)) }
        if let artworkJPEG {
            let cover = AVMutableMetadataItem()
            cover.identifier = .commonIdentifierArtwork
            cover.value = artworkJPEG as NSData
            cover.dataType = kCMMetadataBaseDataType_JPEG as String
            metadata.append(cover)
        }
        if !plainLyrics.isEmpty {
            // Public, static lyric fields. Apple TV's audio Now Playing UI does
            // not promise to render these or provide synchronized lyrics.
            metadata.append(text(.iTunesMetadataLyrics, plainLyrics))
            metadata.append(text(.id3MetadataUnsynchronizedLyric, plainLyrics))
        }
        return metadata
    }
    private func text(_ identifier: AVMetadataIdentifier, _ value: String) -> AVMetadataItem {
        let metadata = AVMutableMetadataItem()
        metadata.identifier = identifier; metadata.value = value as NSString
        metadata.extendedLanguageTag = "und"; metadata.dataType = kCMMetadataBaseDataType_UTF8 as String
        return metadata
    }
}
