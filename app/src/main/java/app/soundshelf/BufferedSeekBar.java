package app.soundshelf;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.widget.SeekBar;

/** Draw all three layers explicitly so device SeekBar themes cannot hide cache coverage. */
final class BufferedSeekBar extends SeekBar {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path outline = new Path();
    private final RectF track = new RectF();
    private final float thickness;
    private final int background, buffered, played;

    BufferedSeekBar(Context context, float heightDp, int background, int buffered, int played) {
        super(context);
        thickness = heightDp * getResources().getDisplayMetrics().density;
        this.background = background;
        this.buffered = buffered;
        this.played = played;
        setThumb(null);
        setThumbOffset(0);
        setSplitTrack(false);
    }

    @Override protected synchronized void onDraw(Canvas canvas) {
        float left = getPaddingLeft(), right = getWidth() - getPaddingRight();
        float center = getPaddingTop() + (getHeight() - getPaddingTop() - getPaddingBottom()) / 2f;
        track.set(left, center - thickness / 2, right, center + thickness / 2);
        outline.reset();
        outline.addRoundRect(track, thickness / 2, thickness / 2, Path.Direction.CW);
        int save = canvas.save();
        canvas.clipPath(outline);
        paint.setColor(background);
        canvas.drawRect(track, paint);
        float range = Math.max(1f, getMax() - getMin());
        drawFill(canvas, (getSecondaryProgress() - getMin()) / range, buffered);
        drawFill(canvas, (getProgress() - getMin()) / range, played);
        canvas.restoreToCount(save);
    }

    private void drawFill(Canvas canvas, float fraction, int color) {
        float width = track.width() * Math.max(0, Math.min(1, fraction));
        if (width <= 0) return;
        paint.setColor(color);
        if (getLayoutDirection() == LAYOUT_DIRECTION_RTL)
            canvas.drawRect(track.right - width, track.top, track.right, track.bottom, paint);
        else canvas.drawRect(track.left, track.top, track.left + width, track.bottom, paint);
    }
}
