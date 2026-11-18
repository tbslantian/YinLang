package app.soundshelf;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

final class AlbumBackdrop extends Drawable {
    static final int FALLBACK = 0xff23202b;
    static final int LIGHT_FALLBACK = 0xfff1e8e3;
    private static final int WIDTH = 36, HEIGHT = 52;
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint image = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final boolean dark;
    private Bitmap blurred;
    private int top;

    AlbumBackdrop(boolean dark) {
        this.dark = dark;
        top = dark ? FALLBACK : LIGHT_FALLBACK;
    }

    int shade(Bitmap cover) {
        var sample = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888);
        var canvas = new Canvas(sample);
        canvas.drawColor(dark ? FALLBACK : LIGHT_FALLBACK);
        float scale = Math.max(WIDTH / (float) cover.getWidth(), HEIGHT / (float) cover.getHeight());
        float width = cover.getWidth() * scale, height = cover.getHeight() * scale;
        canvas.drawBitmap(cover, null, new RectF((WIDTH - width) / 2, (HEIGHT - height) / 2,
            (WIDTH + width) / 2, (HEIGHT + height) / 2), image);
        int[] pixels = new int[WIDTH * HEIGHT];
        sample.getPixels(pixels, 0, WIDTH, 0, 0, WIDTH, HEIGHT);
        for (int i = 0; i < 2; i++) pixels = blur(pixels);
        int color = pixels[WIDTH / 2];
        top = Color.rgb(tint(Color.red(color)), tint(Color.green(color)), tint(Color.blue(color)));
        sample.setPixels(pixels, 0, WIDTH, 0, 0, WIDTH, HEIGHT);
        blurred = sample;
        invalidateSelf();
        return top;
    }

    private int tint(int component) { return dark ? component / 2 : 223 + component / 8; }

    private static int[] blur(int[] input) {
        int[] output = new int[input.length];
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                int red = 0, green = 0, blue = 0;
                for (int dy = -4; dy <= 4; dy++) {
                    int row = Math.max(0, Math.min(HEIGHT - 1, y + dy)) * WIDTH;
                    for (int dx = -4; dx <= 4; dx++) {
                        int pixel = input[row + Math.max(0, Math.min(WIDTH - 1, x + dx))];
                        red += Color.red(pixel);
                        green += Color.green(pixel);
                        blue += Color.blue(pixel);
                    }
                }
                output[y * WIDTH + x] = Color.rgb(red / 81, green / 81, blue / 81);
            }
        }
        return output;
    }

    @Override protected void onBoundsChange(android.graphics.Rect bounds) {
        if (bounds.height() > 0) paint.setShader(new LinearGradient(0, bounds.top, 0, bounds.bottom,
            dark ? new int[]{0xaa101017, 0xa7101017, 0xe40d0c13}
                : new int[]{0xcafff5ee, 0xd8fff8f3, 0xf4fffaf7},
            new float[]{0f, .47f, 1f}, Shader.TileMode.CLAMP));
    }

    @Override public void draw(Canvas canvas) {
        if (blurred == null) canvas.drawColor(top);
        else canvas.drawBitmap(blurred, null, getBounds(), image);
        canvas.drawRect(getBounds(), paint);
    }

    @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
    @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); }
    @Override public int getOpacity() { return PixelFormat.OPAQUE; }
}
