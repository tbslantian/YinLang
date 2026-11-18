package app.soundshelf;

import android.animation.ObjectAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;

final class RecordArt extends FrameLayout {
    private final FrameLayout disc;
    private final ImageView cover;
    private final View needle;
    private final ObjectAnimator rotation;
    private Boolean playing;

    RecordArt(Context context, ImageView cover) {
        super(context);
        this.cover = cover;
        disc = new FrameLayout(context);
        disc.addView(new Grooves(context), new FrameLayout.LayoutParams(-1, -1));
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        cover.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override public void getOutline(View view, android.graphics.Outline outline) {
                outline.setOval(0, 0, view.getWidth(), view.getHeight());
            }
        });
        cover.setClipToOutline(true);
        disc.addView(cover, new FrameLayout.LayoutParams(0, 0, android.view.Gravity.CENTER));
        addView(disc, new FrameLayout.LayoutParams(0, 0, android.view.Gravity.CENTER));
        needle = new Needle(context);
        addView(needle, new FrameLayout.LayoutParams(-1, -1));
        rotation = ObjectAnimator.ofFloat(disc, View.ROTATION, 0, 360);
        rotation.setDuration(24_000);
        rotation.setInterpolator(new LinearInterpolator());
        rotation.setRepeatCount(ObjectAnimator.INFINITE);
        needle.setRotation(-18);
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int diameter = Math.round(Math.min(MeasureSpec.getSize(widthSpec), MeasureSpec.getSize(heightSpec)) * .89f);
        var record = disc.getLayoutParams();
        record.width = record.height = diameter;
        var label = cover.getLayoutParams();
        label.width = label.height = Math.round(diameter * .65f);
        super.onMeasure(widthSpec, heightSpec);
    }

    @Override protected void onSizeChanged(int width, int height, int previousWidth, int previousHeight) {
        super.onSizeChanged(width, height, previousWidth, previousHeight);
        needle.setPivotX(width * .75f);
        needle.setPivotY(height * .06f);
    }
    void playing(boolean enabled) {
        if (playing != null && playing == enabled) return;
        playing = enabled;
        needle.animate().rotation(enabled ? 0 : -18).setDuration(240).start();
        if (!android.animation.ValueAnimator.areAnimatorsEnabled()) return;
        if (enabled) {
            if (rotation.isPaused()) rotation.resume();
            else if (!rotation.isStarted()) rotation.start();
        } else if (rotation.isRunning()) rotation.pause();
    }
    @Override protected void onDetachedFromWindow() {
        playing = null;
        rotation.cancel();
        needle.animate().cancel();
        super.onDetachedFromWindow();
    }

    private static final class Grooves extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        Grooves(Context context) { super(context); }
        @Override protected void onDraw(Canvas canvas) {
            float center = getWidth() * .5f;
            float radius = center - 2;
            if (radius <= 0) return;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xff0e1515);
            canvas.drawCircle(center, center, radius, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.25f);
            for (int i = 0; i < 16; i++) {
                paint.setColor(i % 4 == 0 ? 0xff33403b : 0xff242e2c);
                canvas.drawCircle(center, center, radius * (.67f + i * .0195f), paint);
            }
            paint.setColor(0xff4b5651);
            canvas.drawCircle(center, center, radius - 1, paint);
            paint.setColor(0xff3b5149);
            paint.setStrokeWidth(4);
            canvas.drawArc(center - radius, center - radius, center + radius, center + radius, 207, 36, false, paint);
        }
    }

    private static final class Needle extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path arm = new Path();
        Needle(Context context) { super(context); setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); }
        @Override protected void onDraw(Canvas canvas) {
            float size = getWidth();
            float x = size * .75f, y = getHeight() * .06f;
            paint.setColor(0xff13201c);
            canvas.drawCircle(x, y, size * .036f, paint);
            paint.setColor(0xffd2dbd2);
            canvas.drawCircle(x, y, size * .025f, paint);
            paint.setColor(0xff98aa9e);
            canvas.drawCircle(x, y, size * .014f, paint);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setStrokeWidth(size * .018f);
            paint.setStyle(Paint.Style.STROKE);
            paint.setColor(0xffc9d2c8);
            arm.reset();
            arm.moveTo(x, y);
            arm.lineTo(size * .72f, getHeight() * .23f);
            arm.lineTo(size * .64f, getHeight() * .35f);
            canvas.drawPath(arm, paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.WHITE);
            canvas.drawCircle(size * .637f, getHeight() * .351f, size * .014f, paint);
        }
    }
}
