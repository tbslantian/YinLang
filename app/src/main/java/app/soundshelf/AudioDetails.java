package app.soundshelf;

import android.content.Context;
import android.media.MediaDataSource;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Bundle;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;

record AudioDetails(String format, int bitrate, int sampleRate, long bytes) {
    static final AudioDetails EMPTY = new AudioDetails("", 0, 0, 0);
    private static final int[] AAC_RATES = {96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050,
        16000, 12000, 11025, 8000, 7350};

    AudioDetails with(AudioDetails actual) {
        return new AudioDetails(actual.format.isEmpty() ? format : actual.format,
            actual.bitrate > 0 ? actual.bitrate : bitrate, actual.sampleRate > 0 ? actual.sampleRate : sampleRate,
            actual.bytes > 0 ? actual.bytes : bytes);
    }

    String label(int durationMs) {
        var fields = new ArrayList<String>(3);
        if (!format.isEmpty()) fields.add(format);
        long rate = bitrate > 0 ? bitrate : bytes > 0 && durationMs > 0 ? Math.round(bytes * 8000d / durationMs) : 0;
        if (rate > 0) fields.add(Math.max(1, Math.round(rate / 1000d)) + " kbps");
        if (sampleRate > 0) {
            String hz = java.math.BigDecimal.valueOf(sampleRate, 3).stripTrailingZeros().toPlainString();
            fields.add(hz + " kHz");
        }
        return String.join("  ·  ", fields);
    }

    static AudioDetails fromPrefix(AudioSignature signature, byte[] data, String mime, long length) {
        int offset = AudioSignature.audioOffset(data);
        if (offset < 0 || offset > data.length - 4) return new AudioDetails(format(mime), 0, 0, length);
        int rate = 0, bitrate = 0;
        switch (signature) {
            case FLAC -> {
                if (offset + 21 <= data.length && (u(data[offset + 4]) & 127) == 0) {
                    rate = u(data[offset + 18]) << 12 | u(data[offset + 19]) << 4 | u(data[offset + 20]) >>> 4;
                }
            }
            case WAV -> {
                for (int i = offset + 12; i + 16 <= data.length;) {
                    if (data[i] == 'f' && data[i + 1] == 'm' && data[i + 2] == 't' && data[i + 3] == ' ') {
                        rate = le32(data, i + 12);
                        if (i + 20 <= data.length) bitrate = le32(data, i + 16) * 8;
                        break;
                    }
                    int size = le32(data, i + 4);
                    if (size < 0 || size > data.length - i - 8) break;
                    i += 8 + size + (size & 1);
                }
            }
            case MP3 -> {
                int version = (u(data[offset + 1]) >>> 3) & 3;
                int rateIndex = (u(data[offset + 2]) >>> 2) & 3;
                if (rateIndex < 3 && version != 1) {
                    rate = new int[]{44100, 48000, 32000}[rateIndex] / (version == 3 ? 1 : version == 2 ? 2 : 4);
                }
            }
            case AAC -> {
                int rateIndex = (u(data[offset + 2]) >>> 2) & 15;
                if (rateIndex < AAC_RATES.length) rate = AAC_RATES[rateIndex];
            }
            case OPUS -> rate = 48000;
            case VORBIS -> {
                if (offset + 26 < data.length) {
                    int payload = offset + 27 + u(data[offset + 26]);
                    if (payload + 16 <= data.length) rate = le32(data, payload + 12);
                }
            }
            default -> {}
        }
        String type = switch (signature) {
            case MP4 -> "M4A";
            case HLS -> "HLS";
            case UNKNOWN, ERROR -> format(mime);
            default -> signature.label;
        };
        return new AudioDetails(type, bitrate, rate, length);
    }

    static AudioDetails extract(Context context, String address, Bundle headers, CachedAudio cached, boolean hls) {
        var extractor = new MediaExtractor();
        try {
            if (cached != null) {
                // The playback engine owns this complete offline file.
                extractor.setDataSource(new MediaDataSource() {
                    @Override public long getSize() { return cached.getSize(); }
                    @Override public int readAt(long at, byte[] dst, int offset, int size) throws IOException {
                        return cached.readAt(at, dst, offset, size);
                    }
                    @Override public void close() {}
                });
            } else if (address.startsWith("http")) {
                if (hls) return EMPTY;
                var requestHeaders = new HashMap<String, String>();
                for (String key : headers.keySet()) requestHeaders.put(key, headers.getString(key, ""));
                extractor.setDataSource(address, requestHeaders);
            } else {
                extractor.setDataSource(context, Uri.parse(address), null);
            }
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat track = extractor.getTrackFormat(i);
                String mime = track.getString(MediaFormat.KEY_MIME);
                if (mime == null || !mime.startsWith("audio/")) continue;
                return new AudioDetails(format(mime), integer(track, MediaFormat.KEY_BIT_RATE),
                    integer(track, MediaFormat.KEY_SAMPLE_RATE), cached == null ? 0 : cached.getSize());
            }
        } catch (IOException | RuntimeException ignored) {
            // Keep format data from the original audio response when probing is unavailable.
        } finally { extractor.release(); }
        return EMPTY;
    }

    private static int integer(MediaFormat format, String key) {
        return format.containsKey(key) ? format.getInteger(key) : 0;
    }

    static String format(String mime) {
        if (mime == null) return "";
        return switch (mime.toLowerCase(Locale.ROOT)) {
            case "audio/flac", "audio/x-flac" -> "FLAC";
            case "audio/mpeg", "audio/mp3" -> "MP3";
            case "audio/mp4a-latm", "audio/aac", "audio/aac-adts" -> "AAC";
            case "audio/mp4", "audio/x-m4a" -> "M4A";
            case "audio/raw", "audio/wav", "audio/x-wav" -> "PCM";
            case "audio/opus" -> "Opus";
            case "audio/vorbis" -> "Vorbis";
            case "audio/ogg" -> "Ogg";
            case "audio/alac" -> "ALAC";
            case "audio/ac3" -> "AC-3";
            case "audio/eac3", "audio/eac3-joc" -> "E-AC-3";
            case "audio/ape" -> "APE";
            default -> "";
        };
    }

    private static int le32(byte[] data, int offset) {
        return u(data[offset]) | u(data[offset + 1]) << 8 | u(data[offset + 2]) << 16 | u(data[offset + 3]) << 24;
    }
    private static int u(byte value) { return value & 255; }
}
