package app.soundshelf;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import java.io.ByteArrayOutputStream;
import java.util.Map;

/** Small, authenticated artwork shared by the media session and compact player. */
final class PlaybackArtwork {
    private static final int SESSION_EDGE = 256;
    private static final int THUMBNAIL_EDGE = 320;
    record Request(String address, String cacheKey, String originalKey) {}

    // Capture the account and authenticated URL before dispatching background work.
    static Request request(MediaSources sources, MediaEntry entry) {
        String address = entry.cover().trim();
        if (address.isEmpty()) return null;
        try {
            if ("library".equals(entry.source())) {
                String identity = sources.navCacheKey() + "\n" + address + "\n" + SESSION_EDGE;
                address = sources.navidrome().endpoint("getCoverArt", "&size=" + SESSION_EDGE + "&id=" + Http.enc(address));
                return new Request(address, "cover:" + identity, "");
            }
            if (address.startsWith("//")) address = "https:" + address;
            Http.url(address);
            return new Request(CoverThumbnail.address(address, THUMBNAIL_EDGE),
                "cover-thumb:" + THUMBNAIL_EDGE + ":" + address, "cover:" + address);
        } catch (Exception ignored) { return null; }
    }

    static Bitmap load(Request request, MediaCache.Bucket cache, long revision, boolean network) {
        if (Thread.currentThread().isInterrupted()) return null;
        try {
            Bitmap bitmap = decode(cache.read(request.cacheKey()));
            if (bitmap == null && !request.originalKey().isEmpty()) bitmap = decode(cache.read(request.originalKey()));
            if (bitmap == null && network && !Thread.currentThread().isInterrupted()) {
                // Ask for a thumbnail once; never retry a failed thumbnail with the full image.
                var response = Http.sample(request.address(), Map.of(), 7000, 1_500_000);
                if (response.status() >= 200 && response.status() < 300 && !Thread.currentThread().isInterrupted()) {
                    bitmap = decode(response.bytes());
                    if (bitmap != null) {
                        var output = new ByteArrayOutputStream();
                        if (bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 82, output)) {
                            cache.write(request.cacheKey(), output.toByteArray(), revision);
                        }
                    }
                }
            }
            return Thread.currentThread().isInterrupted() ? null : shrink(bitmap, SESSION_EDGE);
        } catch (Exception ignored) { return null; }
    }

    private static Bitmap decode(byte[] data) {
        if (data == null || data.length == 0) return null;
        var bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        var options = new BitmapFactory.Options();
        options.inSampleSize = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight) / options.inSampleSize / 2 >= THUMBNAIL_EDGE) {
            options.inSampleSize *= 2;
        }
        return shrink(BitmapFactory.decodeByteArray(data, 0, data.length, options), THUMBNAIL_EDGE);
    }

    private static Bitmap shrink(Bitmap bitmap, int edge) {
        if (bitmap == null) return null;
        int longest = Math.max(bitmap.getWidth(), bitmap.getHeight());
        if (longest <= edge) return bitmap;
        return Bitmap.createScaledBitmap(bitmap, Math.max(1, bitmap.getWidth() * edge / longest),
            Math.max(1, bitmap.getHeight() * edge / longest), true);
    }
}
