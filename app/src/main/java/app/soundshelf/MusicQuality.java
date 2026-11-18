package app.soundshelf;

import java.util.List;
import java.util.Locale;
import org.json.JSONObject;

enum MusicQuality {
    STANDARD("128k", "128k"),
    HIGH("320k", "高音质"),
    LOSSLESS("flac", "无损音质"),
    LOSSLESS_24("flac24bit", "24bit无损"),
    HIGH_RESOLUTION("hires", "高解析度"),
    SPATIAL("atmos", "空间音频"),
    MASTER("master", "母带音质");

    final String key;
    final String label;

    MusicQuality(String key, String label) {
        this.key = key;
        this.label = label;
    }

    static String label(String key) {
        if ("atmos_plus".equals(key)) return SPATIAL.label;
        for (MusicQuality item : values()) if (item.key.equals(key)) return item.label;
        return key;
    }

    List<String> advertisedKeys() {
        return this == SPATIAL ? List.of(key, "atmos_plus") : List.of(key);
    }

    static boolean matches(String requested, String actual) {
        return requested.equals(actual) || (SPATIAL.advertisedKeys().contains(requested) && SPATIAL.advertisedKeys().contains(actual));
    }

    static List<String> attempts(String requested, boolean strict, java.util.Set<String> known) {
        if (strict) return List.of(requested);
        var order = List.of("master", "atmos", "hires", "flac24bit", "flac", "320k", "192k", "128k");
        int start = order.indexOf(matches(requested, "atmos") ? "atmos" : requested);
        if (start < 0) return List.of(requested);
        var result = new java.util.ArrayList<>(order.subList(start, order.size()));
        result.set(0, requested);
        for (int i = start - 1; i >= 0; i--) {
            String quality = order.get(i);
            if (known.stream().anyMatch(actual -> matches(quality, actual))) result.add(quality);
        }
        return result;
    }

    static boolean incompatibleMime(String quality, String mime) {
        if (List.of("audio/mpeg", "audio/mp3", "audio/aac", "audio/aacp", "audio/opus", "audio/vorbis").contains(mime)) {
            return incompatibleEncoding(quality, -1);
        }
        return List.of("audio/flac", "audio/x-flac", "audio/wav", "audio/x-wav").contains(mime) && incompatibleEncoding(quality, 1);
    }

    static boolean incompatibleEncoding(String quality, int encoding) {
        return encoding < 0 && List.of("flac", "flac24bit", "hires").contains(quality)
            || encoding > 0 && List.of("128k", "192k", "320k").contains(quality);
    }

    String size(MediaEntry entry) {
        JSONObject metadata = entry.raw().optJSONObject("_types");
        var rows = entry.raw().optJSONArray("types");
        for (String candidate : advertisedKeys()) {
            if (metadata != null && metadata.optJSONObject(candidate) != null) {
                String direct = formatSize(metadata.optJSONObject(candidate).opt("size"));
                if (!direct.isEmpty()) return direct;
            }
            for (int i = 0; rows != null && i < rows.length(); i++) {
                JSONObject row = rows.optJSONObject(i);
                if (row != null && candidate.equals(row.optString("type"))) {
                    String size = formatSize(row.opt("size"));
                    if (!size.isEmpty()) return size;
                }
            }
        }
        return "";
    }

    private static String formatSize(Object size) {
        if (size instanceof Number number && number.longValue() > 0) {
            return String.format(Locale.ROOT, "%.2f MB", number.doubleValue() / (1024 * 1024));
        }
        if (!(size instanceof String text)) return "";
        String value = text.trim();
        if (value.matches("[1-9][0-9]{0,15}")) return formatSize(Long.parseLong(value));
        return value.matches("(?i)[0-9]+(?:\\.[0-9]+)?\\s*(?:B|KB|MB|GB|KiB|MiB|GiB)") ?
            value.replaceFirst("(?<=\\d)(?=[A-Za-z])", " ") : "";
    }
}
