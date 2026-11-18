package app.soundshelf;

import java.text.Collator;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

enum TrackSort {
    ORIGINAL("歌单原顺序"), ADDED("加入日期"), TITLE("歌曲名称"), ARTIST("艺术家"), ALBUM("专辑"), DURATION("时长");

    final String label;
    TrackSort(String label) { this.label = label; }

    static Instant parseDate(String value) {
        if (value.isEmpty()) return null;
        try { return Instant.parse(value); }
        catch (DateTimeParseException unsupported) {
            try { return LocalDate.parse(value).atStartOfDay(ZoneOffset.UTC).toInstant(); }
            catch (DateTimeParseException invalid) { return null; }
        }
    }

    static Instant date(MediaEntry entry) {
        Instant added = addedToPlaylist(entry);
        return added != null || !"library".equals(entry.source()) ? added : parseDate(entry.raw().optString("created"));
    }

    static Instant addedToPlaylist(MediaEntry entry) {
        var raw = entry.raw();
        return parseDate(MediaEntry.first(raw, "playlistAddedAt", "addedAt", "dateAdded"));
    }

    List<MediaEntry> apply(List<MediaEntry> entries, boolean descending) {
        var sorted = new ArrayList<>(entries);
        Collator collator = Collator.getInstance(Locale.getDefault());
        collator.setStrength(Collator.PRIMARY);
        Comparator<MediaEntry> order = switch (this) {
            case ADDED -> {
                var dates = new java.util.IdentityHashMap<MediaEntry, Instant>();
                for (MediaEntry entry : entries) dates.put(entry, date(entry));
                var values = descending ? Comparator.<Instant>reverseOrder() : Comparator.<Instant>naturalOrder();
                yield Comparator.comparing(dates::get, Comparator.nullsLast(values));
            }
            case TITLE -> Comparator.comparing(MediaEntry::title, collator);
            case ARTIST -> Comparator.comparing(MediaEntry::subtitle, collator).thenComparing(MediaEntry::title, collator);
            case ALBUM -> Comparator.comparing((MediaEntry entry) -> MediaEntry.first(entry.raw(), "album", "albumName"), collator)
                .thenComparing(MediaEntry::title, collator);
            case DURATION -> Comparator.comparingInt(TrackSort::seconds);
            case ORIGINAL -> null;
        };
        if (order != null) sorted.sort(descending && this != ADDED ? order.reversed() : order);
        return sorted;
    }

    static int seconds(MediaEntry entry) {
        int duration = entry.raw().optInt("duration", -1);
        if (duration >= 0) return duration;
        int seconds = 0;
        try { for (String part : entry.raw().optString("interval").split(":")) seconds = seconds * 60 + Integer.parseInt(part); }
        catch (NumberFormatException missing) { return 0; }
        return seconds;
    }
}
