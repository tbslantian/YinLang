package app.soundshelf;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.CornerPathEffect;
import android.view.View;

final class UiIcon extends View {
    enum Kind { BACK, DOWN, NEXT, ARROW_RIGHT, CLOSE, PLAY, PAUSE, PREVIOUS_TRACK, NEXT_TRACK, ELLIPSIS, QUEUE, LYRICS,
        SEARCH, CHART, LIST, HEART, HEART_FILLED, STAR, STAR_FILLED, ALBUM, BOOK, DISCOVER, PLUGIN, SETTINGS,
        DOWNLOAD, DELETE, SERVER, AIRPLAY, SUN, MOON, REFRESH, STORAGE, ARTIST, SONG, SORT, SEQUENCE, REPEAT, REPEAT_ONE, SHUFFLE,
        VOLUME_LOW, VOLUME_HIGH, BACK_15, FORWARD_30, TIMER, SLIDERS, INFO }

    private final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final CornerPathEffect softCorner = new CornerPathEffect(0.85f);
    private final float size;
    private Kind kind;

    UiIcon(Context context, Kind kind, float dp, int color) {
        super(context);
        this.kind = kind;
        size = dp * getResources().getDisplayMetrics().density;
        ink.setColor(color);
        ink.setStrokeWidth(1.75f);
        ink.setStrokeCap(Paint.Cap.ROUND);
        ink.setStrokeJoin(Paint.Join.ROUND);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    void setKind(Kind value) {
        if (kind != value) {
            kind = value;
            invalidate();
        }
    }

    void setInk(int color) {
        if (ink.getColor() != color) { ink.setColor(color); invalidate(); }
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float edge = Math.min(size, Math.min(getWidth(), getHeight()));
        canvas.save();
        canvas.translate((getWidth() - edge) / 2, (getHeight() - edge) / 2);
        canvas.scale(edge / 24, edge / 24);
        ink.setStyle(Paint.Style.STROKE);
        switch (kind) {
            case BACK -> lines(canvas, 14, 5, 7, 12, 14, 19);
            case DOWN -> lines(canvas, 5, 9, 12, 16, 19, 9);
            case NEXT -> lines(canvas, 9, 5, 16, 12, 9, 19);
            case CLOSE -> { lines(canvas, 6, 6, 18, 18); lines(canvas, 18, 6, 6, 18); }
            case INFO -> {
                canvas.drawCircle(12, 12, 9, ink);
                canvas.drawPoint(12, 7, ink);
                lines(canvas, 12, 11, 12, 17);
            }
            case PLAY -> {
                triangle(canvas, 6.5f, 20, 4, 20);
            }
            case PAUSE -> {
                ink.setStyle(Paint.Style.FILL);
                canvas.drawRoundRect(6, 4, 10.5f, 20, 1.35f, 1.35f, ink);
                canvas.drawRoundRect(13.5f, 4, 18, 20, 1.35f, 1.35f, ink);
            }
            case PREVIOUS_TRACK, NEXT_TRACK -> {
                if (kind == Kind.PREVIOUS_TRACK) { canvas.translate(24, 0); canvas.scale(-1, 1); }
                triangle(canvas, 2, 12.7f, 5.5f, 18.5f);
                triangle(canvas, 11.3f, 22, 5.5f, 18.5f);
            }
            case ELLIPSIS -> {
                ink.setStyle(Paint.Style.FILL);
                for (int x : new int[]{5, 12, 19}) canvas.drawCircle(x, 12, 1.7f, ink);
            }
            case QUEUE -> {
                for (int y : new int[]{6, 12, 18}) { canvas.drawPoint(4, y, ink); lines(canvas, 8, y, 20, y); }
            }
            case LYRICS -> {
                lines(canvas, 8, 17, 8, 21, 13, 17);
                canvas.drawRoundRect(3, 4, 21, 17, 5, 5, ink);
                lines(canvas, 8, 9, 10, 9, 10, 12, 9, 13);
                lines(canvas, 13, 9, 15, 9, 15, 12, 14, 13);
            }
            case SEARCH -> { canvas.drawCircle(10, 10, 6, ink); lines(canvas, 14.5f, 14.5f, 20, 20); }
            case CHART -> {
                lines(canvas, 4, 4, 4, 20, 21, 20);
                lines(canvas, 9, 16, 9, 12);
                lines(canvas, 14, 16, 14, 8);
                lines(canvas, 19, 16, 19, 4);
            }
            case LIST -> {
                for (int y : new int[]{5, 12, 19}) {
                    canvas.drawPoint(4, y, ink);
                    lines(canvas, 8, y, 20, y);
                }
            }
            case HEART, HEART_FILLED -> {
                if (kind == Kind.HEART_FILLED) ink.setStyle(Paint.Style.FILL);
                path.reset();
                path.moveTo(12, 20);
                path.cubicTo(8, 17, 3, 12, 3, 8.5f);
                path.cubicTo(3, 4, 9, 2.5f, 12, 7);
                path.cubicTo(15, 2.5f, 21, 4, 21, 8.5f);
                path.cubicTo(21, 12, 16, 17, 12, 20);
                canvas.drawPath(path, ink);
            }
            case STAR, STAR_FILLED -> {
                if (kind == Kind.STAR_FILLED) ink.setStyle(Paint.Style.FILL);
                lines(canvas, 12, 3, 14.8f, 8.9f, 21.5f, 9.6f, 16.5f, 14.2f, 17.8f, 21, 12, 17.7f,
                    6.2f, 21, 7.5f, 14.2f, 2.5f, 9.6f, 9.2f, 8.9f, 12, 3);
            }
            case SEQUENCE, ARROW_RIGHT -> { lines(canvas, 4, 12, 20, 12); lines(canvas, 15, 7, 20, 12, 15, 17); }
            case REPEAT, REPEAT_ONE -> {
                lines(canvas, 4, 11, 4, 8, 6, 6, 20, 6); lines(canvas, 17, 3, 20, 6, 17, 9);
                lines(canvas, 20, 13, 20, 16, 18, 18, 4, 18); lines(canvas, 7, 15, 4, 18, 7, 21);
                if (kind == Kind.REPEAT_ONE) lines(canvas, 11, 11, 13, 9.5f, 13, 14);
            }
            case SHUFFLE -> {
                lines(canvas, 3, 6, 7, 6, 10, 9); lines(canvas, 14, 15, 17, 18, 21, 18);
                lines(canvas, 3, 18, 7, 18, 17, 6, 21, 6);
                lines(canvas, 18, 3, 21, 6, 18, 9); lines(canvas, 18, 15, 21, 18, 18, 21);
            }
            case VOLUME_LOW, VOLUME_HIGH -> {
                ink.setStyle(Paint.Style.FILL);
                lines(canvas, 3, 9, 7, 9, 12, 4, 12, 20, 7, 15, 3, 15, 3, 9);
                if (kind == Kind.VOLUME_HIGH) {
                    ink.setStyle(Paint.Style.STROKE);
                    canvas.drawArc(10, 7, 20, 17, -60, 120, false, ink);
                    canvas.drawArc(8, 3, 24, 21, -58, 116, false, ink);
                }
            }
            case BACK_15, FORWARD_30 -> {
                boolean back = kind == Kind.BACK_15;
                canvas.drawArc(4, 4, 20, 20, -90, back ? 305 : -305, false, ink);
                lines(canvas, back ? 7 : 17, 1, 12, 4, back ? 7 : 17, 7);
                ink.setStyle(Paint.Style.FILL);
                ink.setTextAlign(Paint.Align.CENTER);
                ink.setTextSize(8);
                ink.setFakeBoldText(true);
                canvas.drawText(back ? "15" : "30", 12, 15, ink);
                ink.setFakeBoldText(false);
            }
            case TIMER -> {
                canvas.drawCircle(12, 13, 8, ink);
                lines(canvas, 9, 2, 15, 2); lines(canvas, 12, 8, 12, 13, 15, 14);
            }
            case SLIDERS -> {
                lines(canvas, 3, 6, 8, 6); lines(canvas, 12, 6, 21, 6); canvas.drawCircle(10, 6, 2, ink);
                lines(canvas, 3, 12, 14, 12); lines(canvas, 18, 12, 21, 12); canvas.drawCircle(16, 12, 2, ink);
                lines(canvas, 3, 18, 6, 18); lines(canvas, 10, 18, 21, 18); canvas.drawCircle(8, 18, 2, ink);
            }
            case ALBUM -> {
                canvas.drawCircle(12, 12, 9, ink);
                canvas.drawCircle(12, 12, 2.5f, ink);
                canvas.drawArc(6, 6, 18, 18, 185, 52, false, ink);
            }
            case BOOK -> {
                lines(canvas, 12, 20, 9, 17, 3, 17, 3, 4, 9, 4, 12, 7, 15, 4, 21, 4, 21, 17, 15, 17, 12, 20, 12, 7);
            }
            case DISCOVER -> {
                canvas.drawCircle(12, 12, 9, ink);
                lines(canvas, 16, 8, 13.5f, 13.5f, 8, 16, 10.5f, 10.5f, 16, 8);
            }
            case PLUGIN -> {
                lines(canvas, 7, 3, 7, 8, 17, 8, 17, 3);
                lines(canvas, 5, 8, 19, 8, 19, 11, 16, 15, 8, 15, 5, 11, 5, 8);
                lines(canvas, 12, 15, 12, 21);
            }
            case SETTINGS -> {
                canvas.drawCircle(12, 12, 5.5f, ink);
                canvas.drawCircle(12, 12, 2, ink);
                for (int angle = 0; angle < 360; angle += 45) {
                    canvas.save();
                    canvas.rotate(angle, 12, 12);
                    canvas.drawLine(12, 4, 12, 6.5f, ink);
                    canvas.restore();
                }
            }
            case DOWNLOAD -> {
                lines(canvas, 12, 3, 12, 15);
                lines(canvas, 7, 11, 12, 16, 17, 11);
                lines(canvas, 4, 16, 4, 20, 20, 20, 20, 16);
            }
            case DELETE -> {
                lines(canvas, 4, 6, 20, 6);
                lines(canvas, 9, 6, 9, 3.5f, 15, 3.5f, 15, 6);
                lines(canvas, 6.5f, 6, 7.5f, 20, 16.5f, 20, 17.5f, 6);
                lines(canvas, 10, 10, 10.5f, 16.5f);
                lines(canvas, 14, 10, 13.5f, 16.5f);
            }
            case SERVER -> {
                canvas.drawRoundRect(3, 4, 21, 10, 2, 2, ink);
                canvas.drawRoundRect(3, 14, 21, 20, 2, 2, ink);
                canvas.drawPoint(7, 7, ink);
                canvas.drawPoint(7, 17, ink);
            }
            case AIRPLAY -> {
                lines(canvas, 5, 17, 3, 17, 3, 4, 21, 4, 21, 17, 19, 17);
                lines(canvas, 7, 20, 12, 14, 17, 20, 7, 20);
            }
            case SUN -> {
                canvas.drawCircle(12, 12, 4.2f, ink);
                for (int ray = 0; ray < 8; ray++) {
                    canvas.save();
                    canvas.rotate(ray * 45f, 12, 12);
                    canvas.drawLine(12, 2.6f, 12, 4.7f, ink);
                    canvas.restore();
                }
            }
            case MOON -> {
                path.reset();
                path.moveTo(14.3f, 3.5f);
                path.cubicTo(8.1f, 2.7f, 3.8f, 7.3f, 4.0f, 12.4f);
                path.cubicTo(4.2f, 19.0f, 11.7f, 22.7f, 17.2f, 18.9f);
                path.cubicTo(18.7f, 17.9f, 19.7f, 16.5f, 20.2f, 14.8f);
                path.cubicTo(13.3f, 17.2f, 8.5f, 9.1f, 14.3f, 3.5f);
                path.close();
                canvas.drawPath(path, ink);
            }
            case REFRESH -> {
                canvas.drawArc(4, 4, 20, 20, 22, 306, false, ink);
                lines(canvas, 20, 3, 20, 8, 15, 8);
            }
            case STORAGE -> {
                lines(canvas, 3, 13, 6, 5, 18, 5, 21, 13, 21, 19, 3, 19, 3, 13, 21, 13);
                canvas.drawPoint(17, 16, ink);
            }
            case ARTIST -> {
                canvas.drawCircle(12, 7, 3.5f, ink);
                canvas.drawArc(4, 13, 20, 26, 180, 180, false, ink);
            }
            case SONG -> {
                canvas.drawOval(4, 14, 11, 20, ink);
                lines(canvas, 11, 17, 11, 4, 19, 4, 19, 8, 11, 8);
            }
            case SORT -> {
                lines(canvas, 4, 6, 19, 6);
                lines(canvas, 4, 12, 15, 12);
                lines(canvas, 4, 18, 10, 18);
            }
        }
        canvas.restore();
    }

    private void triangle(Canvas canvas, float left, float right, float top, float bottom) {
        ink.setStyle(Paint.Style.FILL);
        ink.setPathEffect(softCorner);
        lines(canvas, left, top, right, (top + bottom) / 2, left, bottom, left, top);
        ink.setPathEffect(null);
    }

    private void lines(Canvas canvas, float... points) {
        path.reset();
        path.moveTo(points[0], points[1]);
        for (int i = 2; i < points.length; i += 2) path.lineTo(points[i], points[i + 1]);
        canvas.drawPath(path, ink);
    }
}
