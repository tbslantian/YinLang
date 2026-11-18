package app.soundshelf;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class PlayOrder {
    enum Mode {
        SEQUENTIAL("顺序播放"), REPEAT_ALL("列表循环"), REPEAT_ONE("单曲循环"), SHUFFLE("随机播放");
        final String label;
        Mode(String label) { this.label = label; }
        static Mode read(String name) {
            try { return valueOf(name); } catch (IllegalArgumentException error) { return SEQUENTIAL; }
        }
    }

    private final List<Integer> shuffled = new ArrayList<>();
    private int cursor;
    private int cycleStart;

    void reset(int current, int size) {
        shuffled.clear();
        shuffled.add(current);
        cursor = cycleStart = 0;
        appendCycle(current, size, false);
    }

    private void appendCycle(int current, int size, boolean includeCurrent) {
        var remaining = new ArrayList<Integer>();
        for (int index = 0; index < size; index++) if (index != current) remaining.add(index);
        Collections.shuffle(remaining);
        if (includeCurrent) remaining.add(1 + java.util.concurrent.ThreadLocalRandom.current().nextInt(remaining.size()), current);
        shuffled.addAll(remaining);
    }

    int next(Mode mode, int current, int size, int direction, boolean completed) {
        if (size < 1) return -1;
        if (mode == Mode.REPEAT_ONE && completed) return current;
        if (mode == Mode.SHUFFLE) {
            if (shuffled.isEmpty() || shuffled.get(cursor) != current) reset(current, size);
            if (direction < 0) return cursor > 0 ? shuffled.get(--cursor) : -1;
            if (size == 1) return current;
            if (cursor == shuffled.size() - 1) {
                int oldCycle = cycleStart;
                shuffled.subList(0, oldCycle).clear();
                cursor -= oldCycle;
                cycleStart = cursor;
                appendCycle(current, size, true);
            }
            return shuffled.get(++cursor);
        }
        int next = current + direction;
        if (mode == Mode.REPEAT_ALL) return Math.floorMod(next, size);
        return next >= 0 && next < size ? next : -1;
    }
}
