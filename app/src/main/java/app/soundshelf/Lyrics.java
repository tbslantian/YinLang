package app.soundshelf;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.regex.Pattern;

final class Lyrics {
    record Line(int time, String text, String translation) {}
    private static final Pattern TIME = Pattern.compile("\\[(\\d{1,4}):(\\d{1,2})(?:[.:](\\d{1,6}))?]");
    private static final Pattern OFFSET = Pattern.compile("(?im)^\\s*\\[offset:\\s*([+-]?\\d{1,8})\\s*]");
    private static final Pattern META = Pattern.compile("(?i)^\\[(?:ar|al|ti|by|au|re|ve|length|offset|id):[^]]*]$");

    static List<Line> parse(String text, String translated) {
        var times = timed(text);
        var translations = timed(translated);
        var lines = new ArrayList<Line>();
        if (!times.isEmpty()) {
            times.forEach((time, words) -> lines.add(new Line(time, words, translations.getOrDefault(time, ""))));
        } else {
            for (String line : text.split("\\R")) {
                String value = line.trim();
                if (!value.isEmpty() && !META.matcher(value).matches()) lines.add(new Line(-1, value, ""));
            }
        }
        return List.copyOf(lines);
    }

    private static TreeMap<Integer, String> timed(String text) {
        var rows = new TreeMap<Integer, String>();
        var offset = OFFSET.matcher(text);
        int shift = offset.find() ? Integer.parseInt(offset.group(1)) : 0;
        for (String line : text.split("\\R")) {
            var stamps = TIME.matcher(line);
            var times = new ArrayList<Integer>();
            int last = 0;
            while (stamps.find() && line.substring(last, stamps.start()).trim().isEmpty()) {
                String fraction = stamps.group(3);
                int ms = fraction == null ? 0 : Integer.parseInt((fraction + "00").substring(0, 3));
                int time = Integer.parseInt(stamps.group(1)) * 60_000 + Integer.parseInt(stamps.group(2)) * 1000 + ms;
                times.add(Math.max(0, time + shift));
                last = stamps.end();
            }
            String words = line.substring(last).trim();
            for (int time : times) rows.put(time, words);
        }
        return rows;
    }

    static int current(List<Line> lines, int position) {
        if (lines.isEmpty() || lines.get(0).time() < 0) return -1;
        int low = 0, high = lines.size();
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (lines.get(mid).time() <= position) low = mid + 1;
            else high = mid;
        }
        return low - 1;
    }
}
