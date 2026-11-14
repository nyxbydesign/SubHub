package com.subhub.app.atmosphere;

import android.content.ClipDescription;
import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputContentInfo;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

record GagClipboardImage(byte[] bytes, String mime, Bitmap thumbnail) {
    private static final int MAX_BYTES = 2 * 1024 * 1024;
    private static final int MAX_PIXELS = 16 * 1024 * 1024;
    private static final int THUMBNAIL_EDGE = 160;

    static GagClipboardImage read(ContentResolver resolver, Uri source, ClipDescription description) {
        try (InputStream input = resolver.openInputStream(source)) {
            if (input == null) return null;
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            for (int count; (count = input.read(buffer)) != -1; ) {
                if (Thread.currentThread().isInterrupted() || count > MAX_BYTES - output.size()) return null;
                output.write(buffer, 0, count);
            }
            return decode(output.toByteArray(), description);
        } catch (IOException | RuntimeException unavailable) {
            return null;
        }
    }

    static GagClipboardImage decode(byte[] bytes, ClipDescription description) {
        if (bytes.length == 0 || bytes.length > MAX_BYTES) return null;
        BitmapFactory.Options sample = new BitmapFactory.Options();
        sample.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, sample);
        String mime = sample.outMimeType;
        if (extension(mime) == null || !description.hasMimeType(mime) || sample.outWidth <= 0
                || sample.outHeight <= 0 || (long) sample.outWidth * sample.outHeight > MAX_PIXELS) return null;
        int scale = 1;
        int edge = Math.max(sample.outWidth, sample.outHeight);
        while (edge / scale > THUMBNAIL_EDGE) scale *= 2;
        sample.inJustDecodeBounds = false;
        sample.inSampleSize = scale;
        Bitmap thumbnail = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, sample);
        return thumbnail == null ? null : new GagClipboardImage(bytes, mime, thumbnail);
    }

    static String extension(String mime) {
        if (mime == null) return null;
        return switch (mime) {
            case "image/png" -> ".png";
            case "image/jpeg" -> ".jpg";
            case "image/webp" -> ".webp";
            case "image/gif" -> ".gif";
            default -> null;
        };
    }

    boolean sameBytes(GagClipboardImage other) { return Arrays.equals(bytes, other.bytes); }

    boolean supports(EditorInfo editor) {
        if (editor == null || editor.contentMimeTypes == null) return false;
        for (String type : editor.contentMimeTypes)
            if (type != null && ClipDescription.compareMimeTypes(mime, type)) return true;
        return false;
    }

    boolean commit(InputConnection connection, EditorInfo editor, Uri uri) {
        if (!supports(editor)) return false;
        return connection.commitContent(new InputContentInfo(uri,
                        new ClipDescription("", new String[]{mime}), null),
                InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION, null);
    }
}
