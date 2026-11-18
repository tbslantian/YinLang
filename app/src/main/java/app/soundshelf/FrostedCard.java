package app.soundshelf;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.Shader;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import java.util.ArrayList;

final class FrostedCard extends FrameLayout {
    private final Paint scene = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint reflection = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint captionShade = new Paint();
    private final RectF imageBounds = new RectF();
    private final Path edge = new Path();
    private final RenderNode blurredArtwork = new RenderNode("Card artwork blur");
    private final ArrayList<View> samples = new ArrayList<>();
    private final int base, variant;
    private final float radius, density;
    private Bitmap artwork;
    private int captionTint;
    private boolean blurDirty = true;
    private float tilt, touchX, touchY;
    private boolean touching;

    FrostedCard(Context context, int base, int variant, int corner) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        radius = corner * density;
        this.base = base;
        this.variant = variant;
        setBackground(rounded(base, radius));
        setClipToOutline(true);
        setWillNotDraw(false);
        if (Build.VERSION.SDK_INT >= 31) {
            blurredArtwork.setRenderEffect(RenderEffect.createBlurEffect(7 * density, 7 * density, Shader.TileMode.CLAMP));
        }
    }

    void setArtwork(Bitmap bitmap) {
        artwork = bitmap;
        blurDirty = true;
        invalidate();
        for (View sample : samples) sample.invalidate();
    }

    void shadeCaption(int color) {
        captionTint = color;
        updateCaptionShade(getHeight());
        invalidate();
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        blurDirty = true;
        edge.rewind();
        edge.setFillType(Path.FillType.EVEN_ODD);
        if (w > 0 && h > 0) {
            float band = Math.min(3 * density, Math.min(w, h) / 3f);
            float inner = Math.max(0, radius - band);
            edge.addRoundRect(0, 0, w, h, radius, radius, Path.Direction.CW);
            edge.addRoundRect(band, band, w - band, h - band, inner, inner, Path.Direction.CW);
        }
        updateCaptionShade(h);
    }

    private void updateCaptionShade(int height) {
        captionShade.setShader(height > 0 && captionTint != 0
            ? new LinearGradient(0, height * .46f, 0, height,
                new int[]{captionTint & 0x00ffffff, captionTint}, null, Shader.TileMode.CLAMP) : null);
    }

    void reflectScroll(int offset) {
        tilt = (float) Math.sin(offset / (210f * density) + variant % 13 * .25f);
        invalidate();
    }

    FrameLayout glass(int corner, int tint) {
        float round = corner * density;
        var surface = rounded(tint, round);
        surface.setStroke(Math.max(1, Math.round(density * .65f)), 0x5cffffff);
        // Draw the optical layers without child views so the label sets the measured size.
        var pane = new FrameLayout(getContext()) {
            @Override protected void onDraw(Canvas canvas) {
                float x = 0, y = 0;
                for (View layer = this; layer != FrostedCard.this; ) {
                    if (!(layer.getParent() instanceof View parent)) return;
                    x += layer.getLeft() - parent.getScrollX();
                    y += layer.getTop() - parent.getScrollY();
                    layer = parent;
                }
                int save = canvas.save();
                canvas.translate(-x, -y);
                drawBlurredArtwork(canvas);
                canvas.restoreToCount(save);
                surface.setBounds(0, 0, getWidth(), getHeight());
                surface.draw(canvas);
            }
        };
        pane.setBackground(rounded(0x01000000, round));
        pane.setClipToOutline(true);
        pane.setWillNotDraw(false);
        samples.add(pane);
        return pane;
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        drawArtwork(canvas);
        if (captionTint != 0) canvas.drawRect(0, getHeight() * .46f, getWidth(), getHeight(), captionShade);
    }

    private void drawBlurredArtwork(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        if (Build.VERSION.SDK_INT < 31 || !canvas.isHardwareAccelerated() || w == 0 || h == 0) return;
        if (blurDirty) {
            blurredArtwork.setPosition(0, 0, w, h);
            var recording = blurredArtwork.beginRecording(w, h);
            drawArtwork(recording);
            blurredArtwork.endRecording();
            blurDirty = false;
        }
        canvas.drawRenderNode(blurredArtwork);
    }

    private void drawArtwork(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;
        scene.setShader(null);
        scene.setStyle(Paint.Style.FILL);
        scene.setAlpha(255);
        if (artwork != null) {
            float scale = Math.max(w / (float) artwork.getWidth(), h / (float) artwork.getHeight());
            float dw = artwork.getWidth() * scale, dh = artwork.getHeight() * scale;
            imageBounds.set((w - dw) / 2, (h - dh) / 2, (w + dw) / 2, (h + dh) / 2);
            canvas.drawBitmap(artwork, null, imageBounds, scene);
            return;
        }
        float cx = w * (.63f + Math.floorMod(variant, 23) / 100f), cy = h * .19f;
        scene.setShader(new RadialGradient(cx, cy, w * .85f, new int[]{0xff7d7b85, base, 0xff14141b}, null, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, h, scene);
        scene.setShader(null);
        scene.setStyle(Paint.Style.STROKE);
        scene.setStrokeWidth(1.05f * density);
        scene.setColor(0x3dffffff);
        for (int i = 1; i <= 5; i++) canvas.drawCircle(cx, cy, w * (.095f + i * .067f), scene);
        scene.setStyle(Paint.Style.FILL);
    }

    @Override public void draw(Canvas canvas) {
        super.draw(canvas);
        if (getWidth() <= 1 || getHeight() <= 1) return;
        float w = getWidth(), h = getHeight(), x = w * (.45f + .19f * tilt);
        int save = canvas.save();
        canvas.clipPath(edge);
        drawBlurredArtwork(canvas);
        reflection.setStyle(Paint.Style.FILL);
        reflection.setColor(0x19ffffff);
        canvas.drawRect(0, 0, w, h, reflection);
        reflection.setAlpha(255);
        reflection.setShader(new RadialGradient(touching ? touchX : x, touching ? touchY : h * .02f,
            w * .68f, touching ? 0x8cffffff : 0x48ffffff, 0x00ffffff, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, h, reflection);
        reflection.setStyle(Paint.Style.STROKE);
        reflection.setStrokeWidth(density);
        reflection.setShader(new LinearGradient(x / 2, 0, w, h,
            new int[]{0x96ffffff, 0x30ffffff, 0x64ffffff}, null, Shader.TileMode.CLAMP));
        float inset = density / 2;
        canvas.drawRoundRect(inset, inset, w - inset, h - inset, radius, radius, reflection);
        reflection.setShader(null);
        canvas.restoreToCount(save);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                touching = true;
                touchX = event.getX(); touchY = event.getY();
                invalidate();
            }
            case MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { touching = false; invalidate(); }
        }
        return super.onTouchEvent(event);
    }

    @Override public void setPressed(boolean pressed) {
        if (pressed != isPressed()) {
            animate().scaleX(pressed ? .987f : 1f).scaleY(pressed ? .987f : 1f).setDuration(pressed ? 95 : 180).start();
        }
        super.setPressed(pressed);
    }

    private static GradientDrawable rounded(int color, float radius) {
        var shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(radius);
        return shape;
    }
}
