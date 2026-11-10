package com.subhub.app.people;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import com.subhub.app.capture.export.ExportMedia;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;

/** Only a small, re-encoded square thumbnail leaves the selected image's temporary URI grant. */
public final class ProfileImages {
    public static final int EDGE = 192;
    private ProfileImages() { }

    public static String importImage(Context context, Uri uri) throws IOException {
        Bitmap source = ExportMedia.image(context, uri, 384);
        if (source == null) throw new IOException("Image unavailable");
        try { return encode(source); }
        finally { source.recycle(); }
    }

    public static String encode(Bitmap source) throws IOException {
        int edge = Math.min(source.getWidth(), source.getHeight());
        Bitmap crop = Bitmap.createBitmap(source, (source.getWidth() - edge) / 2,
                (source.getHeight() - edge) / 2, edge, edge);
        Bitmap thumbnail = Bitmap.createScaledBitmap(crop, EDGE, EDGE, true);
        try {
            for (int quality : new int[] {88, 75, 60, 40}) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                if (!thumbnail.compress(Bitmap.CompressFormat.JPEG, quality, bytes))
                    throw new IOException("Image unavailable");
                if (bytes.size() <= ProfileData.MAX_AVATAR_BYTES)
                    return Base64.getEncoder().encodeToString(bytes.toByteArray());
            }
            throw new IOException("Image is too complex");
        } finally {
            if (thumbnail != source && thumbnail != crop) thumbnail.recycle();
            if (crop != source) crop.recycle();
        }
    }

    public static Bitmap decode(String avatar) {
        if (avatar.isEmpty()) return null;
        try {
            byte[] bytes = Base64.getDecoder().decode(avatar);
            if (bytes.length > ProfileData.MAX_AVATAR_BYTES) return null;
            BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
            if (bounds.outWidth != EDGE || bounds.outHeight != EDGE || !"image/jpeg".equals(bounds.outMimeType)) return null;
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        } catch (IllegalArgumentException invalid) { return null; }
    }

    public static void requireValid(String avatar) {
        if (avatar.isEmpty()) return;
        Bitmap decoded = decode(avatar);
        if (decoded == null) throw new IllegalArgumentException("Invalid profile image");
        decoded.recycle();
    }
}
