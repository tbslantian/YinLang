package app.soundshelf;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

enum AudioSignature {
    FLAC("flac", "FLAC", 1), WAV("wav", "WAV", 0), MP3("mp3", "MP3", -1), AAC("aac", "AAC", -1),
    MP4("m4a", "MP4 音频", 0), OPUS("opus", "Opus", -1), VORBIS("ogg", "Vorbis", -1),
    OGG("ogg", "Ogg 音频", 0), HLS("", "HLS", 0), ERROR("", "错误页面", 0), UNKNOWN("", "未知音频", 0);

    final String extension, label;
    final int encoding;

    AudioSignature(String extension, String label, int encoding) {
        this.extension = extension;
        this.label = label;
        this.encoding = encoding;
    }

    static AudioSignature read(byte[] data) {
        int offset = audioOffset(data);
        if (offset < 0) return UNKNOWN;
        if (ascii(data, offset, "fLaC")) return FLAC;
        if ((ascii(data, offset, "RIFF") || ascii(data, offset, "RF64")) && ascii(data, offset + 8, "WAVE")) return WAV;
        if (ascii(data, offset + 4, "ftyp")) return MP4;
        if (ascii(data, offset, "OggS")) {
            int payload = data.length > offset + 26 ? offset + 27 + u(data[offset + 26]) : data.length;
            if (ascii(data, payload, "OpusHead")) return OPUS;
            if (ascii(data, payload, "\u0001vorbis")) return VORBIS;
            return OGG;
        }
        if (offset <= data.length - 7 && u(data[offset]) == 0xff) {
            int second = u(data[offset + 1]), third = u(data[offset + 2]);
            int aacRate = (third >>> 2) & 15;
            int aacSize = (u(data[offset + 3]) & 3) << 11 | u(data[offset + 4]) << 3 | u(data[offset + 5]) >>> 5;
            if ((second & 0xf6) == 0xf0 && aacRate < 13 && aacSize >= 7) return AAC;
            if ((second & 0xe0) == 0xe0 && (second & 0x18) != 8 && (second & 6) != 0
                && (third & 0xf0) != 0xf0 && (third & 12) != 12) return MP3;
        }
        if (offset != 0) return UNKNOWN;
        String head = new String(data, 0, Math.min(data.length, 96), StandardCharsets.UTF_8)
            .replace("\ufeff", "").trim().toLowerCase(Locale.ROOT);
        if (head.startsWith("#extm3u")) return HLS;
        if (head.startsWith("<") || head.startsWith("{") || head.startsWith("[")) return ERROR;
        return UNKNOWN;
    }

    static int audioOffset(byte[] bytes) {
        if (bytes.length < 10 || !ascii(bytes, 0, "ID3")) return 0;
        int length = 0;
        for (int i = 6; i < 10; i++) {
            int value = u(bytes[i]);
            if (value > 127) return -1;
            length = (length << 7) | value;
        }
        boolean footer = bytes[3] == 4 && (bytes[5] & 0x10) != 0;
        return length + (footer ? 20 : 10);
    }

    private static boolean ascii(byte[] bytes, int offset, String text) {
        if (offset < 0 || offset > bytes.length - text.length()) return false;
        for (int i = 0; i < text.length(); i++) if (u(bytes[offset + i]) != text.charAt(i)) return false;
        return true;
    }

    private static int u(byte value) { return value & 255; }
}
