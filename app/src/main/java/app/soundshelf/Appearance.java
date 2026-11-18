package app.soundshelf;

enum Appearance {
    LIGHT("浅色 · 杂志风"), DARK("深色 · 播放风"), AUTO("按时间切换");

    static final int DEFAULT_LIGHT = 7 * 60;
    static final int DEFAULT_DARK = 19 * 60;
    final String label;

    Appearance(String label) { this.label = label; }

    static Appearance read(String value) {
        try { return valueOf(value); }
        catch (IllegalArgumentException ignored) { return LIGHT; }
    }

    boolean darkAt(int now, int lightStart, int darkStart) {
        return switch (this) {
            case LIGHT -> false;
            case DARK -> true;
            case AUTO -> lightStart < darkStart ? now < lightStart || now >= darkStart
                : now >= darkStart && now < lightStart;
        };
    }
}
