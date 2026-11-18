package app.soundshelf;

import java.math.BigDecimal;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import org.json.JSONObject;

final class LibrarySongDetails {
    static final String UNKNOWN = "无数据";
    record Artist(String id, String name) {}

    static MediaEntry merge(MediaEntry original, JSONObject fresh) throws Exception {
        if (!original.id().equals(fresh.optString("id"))) throw new IllegalArgumentException("歌曲信息不匹配");
        var merged = new JSONObject(original.raw().toString());
        for (var keys = fresh.keys(); keys.hasNext();) {
            String key = keys.next();
            merged.put(key, fresh.get(key));
        }
        return MediaEntry.library(merged);
    }

    static List<Artist> artists(JSONObject song, boolean albumArtists) {
        var result = new LinkedHashMap<String, Artist>();
        var credits = song.optJSONArray(albumArtists ? "albumArtists" : "artists");
        for (int i = 0; credits != null && i < credits.length(); i++) {
            JSONObject credit = credits.optJSONObject(i);
            if (credit == null) continue;
            String id = MediaEntry.first(credit, "id"), name = MediaEntry.first(credit, "name");
            if (!id.isEmpty()) result.putIfAbsent(id, new Artist(id, name.isEmpty() ? "艺术家" : name));
        }
        // Names such as AC/DC are one artist. Only server IDs establish a relationship.
        if (result.isEmpty()) {
            String id = MediaEntry.first(song, albumArtists ? "albumArtistId" : "artistId");
            String name = artistName(song, albumArtists);
            if (!id.isEmpty()) result.put(id, new Artist(id, UNKNOWN.equals(name) ? "艺术家" : name));
        }
        return new ArrayList<>(result.values());
    }

    static String artistName(JSONObject song, boolean albumArtists) {
        String display = albumArtists ? MediaEntry.first(song, "displayAlbumArtist", "albumArtist")
            : MediaEntry.first(song, "displayArtist", "artist");
        if (!display.isEmpty()) return display;
        var credits = song.optJSONArray(albumArtists ? "albumArtists" : "artists");
        var names = new ArrayList<String>();
        for (int i = 0; credits != null && i < credits.length(); i++) {
            JSONObject credit = credits.optJSONObject(i);
            if (credit == null) continue;
            String name = MediaEntry.first(credit, "name");
            if (!name.isEmpty() && !names.contains(name)) names.add(name);
        }
        return names.isEmpty() ? UNKNOWN : String.join(" / ", names);
    }

    static String text(JSONObject value, String... keys) {
        String result = MediaEntry.first(value, keys);
        return result.isEmpty() ? UNKNOWN : result;
    }

    static String number(JSONObject value, String key, String unit, boolean allowZero) {
        if (!value.has(key) || value.isNull(key)) return UNKNOWN;
        double number = value.optDouble(key, Double.NaN);
        if (!Double.isFinite(number) || number < 0 || (!allowZero && number == 0)) return UNKNOWN;
        return BigDecimal.valueOf(number).stripTrailingZeros().toPlainString() + unit;
    }

    static String duration(JSONObject song) {
        long seconds = song.optLong("duration", -1);
        if (seconds < 0) return UNKNOWN;
        return seconds >= 3600 ? String.format(Locale.ROOT, "%d小时%d分%d秒", seconds / 3600, seconds / 60 % 60, seconds % 60)
            : String.format(Locale.ROOT, "%d分%d秒", seconds / 60, seconds % 60);
    }

    static String fileSize(JSONObject song) {
        long bytes = song.optLong("size", -1);
        if (bytes < 0) return UNKNOWN;
        return bytes >= 1024 * 1024 ? String.format(Locale.ROOT, "%.1f MB", bytes / (1024d * 1024))
            : bytes >= 1024 ? String.format(Locale.ROOT, "%.1f KB", bytes / 1024d) : bytes + " B";
    }

    static String date(JSONObject song, String... keys) {
        String raw = MediaEntry.first(song, keys);
        if (raw.isEmpty()) return UNKNOWN;
        var instant = TrackSort.parseDate(raw);
        return instant == null ? raw : DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date.from(instant));
    }

    static String gain(JSONObject song, String key, boolean decibels) {
        JSONObject replay = song.optJSONObject("replayGain");
        if (replay == null || !replay.has(key) || replay.isNull(key)) return UNKNOWN;
        double number = replay.optDouble(key, Double.NaN);
        if (!Double.isFinite(number) || (!decibels && number < 0)) return UNKNOWN;
        // Zero and negative gain are valid values, unlike an absent field.
        return BigDecimal.valueOf(number).stripTrailingZeros().toPlainString() + (decibels ? " dB" : "");
    }
}
