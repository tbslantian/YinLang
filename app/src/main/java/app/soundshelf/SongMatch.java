package app.soundshelf;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/** Conservative matching for recordings found on another music platform. */
final class SongMatch {
    private SongMatch() {}

    static boolean same(MediaEntry wanted, MediaEntry candidate) {
        String title = text(wanted.title());
        if (title.isEmpty() || !title.equals(text(candidate.title()))) return false;
        Set<String> artists = artists(wanted.subtitle());
        Set<String> found = artists(candidate.subtitle());
        if (artists.isEmpty() || found.isEmpty() || artists.stream().noneMatch(found::contains)) return false;
        int a = TrackSort.seconds(wanted), b = TrackSort.seconds(candidate);
        return a <= 0 || b <= 0 || Math.abs(a - b) <= Math.max(8, Math.min(a, b) / 20);
    }

    static int distance(MediaEntry wanted, MediaEntry candidate) {
        int a = TrackSort.seconds(wanted), b = TrackSort.seconds(candidate);
        return a > 0 && b > 0 ? Math.abs(a - b) : Integer.MAX_VALUE;
    }

    private static Set<String> artists(String value) {
        return java.util.Arrays.stream(value.split("[、/&,，;；]|(?i)\\s+(?:feat\\.?|ft\\.?)\\s+"))
            .map(SongMatch::text).filter(part -> !part.isEmpty()).collect(Collectors.toSet());
    }

    private static String text(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT)
            .replaceAll("[^\\p{L}\\p{N}]", "");
    }
}
