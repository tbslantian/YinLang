package app.soundshelf;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

/** Decorative artwork scales locally, without downloading homepage imagery. */
@android.annotation.SuppressLint("ViewConstructor") // Created programmatically with the selected template.
final class HomeEntryArtwork extends View {
    private final HomeTemplate template;
    private final boolean books, dark;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    HomeEntryArtwork(Context context, HomeTemplate template, boolean books, boolean dark) {
        super(context);
        this.template = template;
        this.books = books;
        this.dark = dark;
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (getWidth() == 0 || getHeight() == 0) return;
        canvas.save();
        float scale = Math.min(getWidth() / 180f, getHeight() / 208f);
        canvas.translate((getWidth() - 180 * scale) / 2, (getHeight() - 208 * scale) / 2);
        canvas.scale(scale, scale);
        if (template == HomeTemplate.GALLERY) gallery(canvas);
        else radio(canvas);
        canvas.restore();
    }

    private void gallery(Canvas canvas) {
        fill(dark ? 0x253e7064 : 0x546e9d8b);
        if (books) paint.setColor(dark ? 0x304b3430 : 0x55c4a17e);
        canvas.drawOval(9, 29, 176, 192, paint);
        canvas.save();
        canvas.rotate(books ? 9 : -12, 96, 106);
        if (books) {
            fill(0x20000000);
            canvas.drawRoundRect(41, 45, 151, 181, 7, 7, paint);
            fill(dark ? 0xffd7b792 : 0xfff7e7ce);
            canvas.drawRoundRect(31, 30, 142, 166, 5, 5, paint);
            fill(dark ? 0xff8f6756 : 0xffa8755e);
            canvas.drawRoundRect(25, 28, 135, 160, 5, 5, paint);
            fill(0x35000000);
            canvas.drawRect(35, 28, 39, 160, paint);
            stroke(0xffe8ccab, 1.3f);
            canvas.drawRoundRect(50, 45, 118, 142, 2, 2, paint);
            for (int i = 0; i < 4; i++) canvas.drawLine(65, 72 + i * 9, 103 - (i % 2) * 10, 72 + i * 9, paint);
            fill(0xffe8ccab);
            canvas.drawCircle(84, 119, 4, paint);
            fill(0xff637b6a);
            path.reset(); path.moveTo(105, 160); path.lineTo(105, 184); path.lineTo(113, 178); path.lineTo(121, 184); path.lineTo(121, 160); path.close();
            canvas.drawPath(path, paint);
        } else {
            paint.setStyle(Paint.Style.FILL);
            paint.setShader(new LinearGradient(19, 44, 135, 158,
                dark ? 0xff75968a : 0xffa9c3b2, dark ? 0xff304a41 : 0xff537d69, Shader.TileMode.CLAMP));
            canvas.drawRoundRect(18, 40, 137, 160, 3, 3, paint);
            paint.setShader(null);
            fill(0xff1c2c2b);
            canvas.drawCircle(105, 106, 60, paint);
            stroke(0xff354a45, .8f);
            for (int radius = 30; radius < 57; radius += 5) canvas.drawCircle(105, 106, radius, paint);
            fill(dark ? 0xffbcd6bc : 0xffd6e6c6);
            canvas.drawCircle(105, 106, 23, paint);
            fill(0xff314f43);
            canvas.drawCircle(105, 106, 4, paint);
            stroke(0x40ffffff, 9);
            canvas.drawArc(54, 55, 156, 157, 210, 30, false, paint);
        }
        canvas.restore();
        fill(dark ? 0xffa8c6b7 : 0xff688c79);
        if (books) paint.setColor(dark ? 0xffd5b99b : 0xff9e765e);
        canvas.drawCircle(22, 190, 3, paint);
        canvas.drawCircle(33, 190, 2, paint);
    }

    private void radio(Canvas canvas) {
        stroke(0x28ffffff, 1);
        for (int radius = 52; radius < 160; radius += 24) canvas.drawCircle(126, 95, radius, paint);
        fill(0x18ffffff);
        canvas.drawCircle(124, 92, 64, paint);
        if (books) {
            stroke(0xfff7d7bf, 8);
            canvas.drawArc(new RectF(61, 40, 151, 144), 180, 180, false, paint);
            canvas.drawLine(61, 92, 61, 125, paint);
            canvas.drawLine(151, 92, 151, 125, paint);
            fill(0xfff7d7bf);
            canvas.drawRoundRect(52, 100, 72, 134, 9, 9, paint);
            canvas.drawRoundRect(140, 100, 160, 134, 9, 9, paint);
            stroke(0xaaffffff, 2);
            path.reset(); path.moveTo(82, 95); path.quadTo(94, 88, 107, 96); path.quadTo(120, 88, 132, 95);
            path.lineTo(132, 121); path.quadTo(120, 115, 107, 122); path.quadTo(94, 115, 82, 121); path.close();
            canvas.drawPath(path, paint);
            canvas.drawLine(107, 96, 107, 122, paint);
        } else {
            fill(0xff162943);
            canvas.drawCircle(119, 85, 49, paint);
            stroke(0xff3d586e, 1);
            for (int radius = 28; radius <= 43; radius += 5) canvas.drawCircle(119, 85, radius, paint);
            fill(0xffb6e9cd);
            canvas.drawCircle(119, 85, 19, paint);
            fill(0xff254a51);
            canvas.drawCircle(119, 85, 3, paint);
            stroke(0x90ffffff, 4);
            canvas.drawArc(78, 44, 160, 126, 205, 38, false, paint);
        }
        fill(0x90ffffff);
        float[] heights = {13, 26, 18, 39, 28, 49, 23, 34, 17, 29, 12};
        for (int i = 0; i < heights.length; i++) {
            float x = 31 + i * 12;
            canvas.drawRoundRect(x, 169 - heights[i] / 2, x + 4, 169 + heights[i] / 2, 2, 2, paint);
        }
    }

    private void fill(int color) { paint.setShader(null); paint.setStyle(Paint.Style.FILL); paint.setColor(color); }
    private void stroke(int color, float width) {
        fill(color); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(width); paint.setStrokeCap(Paint.Cap.ROUND);
    }
}
