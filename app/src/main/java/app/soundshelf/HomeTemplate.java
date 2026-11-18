package app.soundshelf;

import java.time.ZonedDateTime;

enum HomeTemplate {
    CLASSIC("经典影像", "沉浸封面 · 温暖质感"),
    GALLERY("留白画廊", "柔和色块 · 轻盈排版"),
    RADIO("声波电台", "流动声波 · 鲜明色彩");

    static final String KEY = "home-template", ROTATION_KEY = "home-template-rotation", AUTO = "AUTO";
    final String label, description;

    HomeTemplate(String label, String description) { this.label = label; this.description = description; }

    static HomeTemplate read(String value) {
        try { return valueOf(value); }
        catch (IllegalArgumentException | NullPointerException ignored) { return CLASSIC; }
    }

    static HomeTemplate resolve(String mode, String rotation, ZonedDateTime now) {
        if (!AUTO.equals(mode)) return read(mode);
        // Use wall-clock buckets rather than counting redraws or navigation events.
        long slot = "hourly".equals(rotation) ? Math.floorDiv(now.toEpochSecond(), 3600) : now.toLocalDate().toEpochDay();
        return values()[Math.floorMod(slot, values().length)];
    }

}
