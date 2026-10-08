package com.subhub.app.capture.export;

import android.content.*;
import android.graphics.*;
import android.media.ExifInterface;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import java.io.*;
import java.util.*;

/** Bounded decoding and completed-copy publication. */
public final class ExportMedia {
    private ExportMedia() { }
    public static Bitmap image(Context context, Uri uri, int edge) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        if (Build.VERSION.SDK_INT >= 28) return ImageDecoder.decodeBitmap(
                ImageDecoder.createSource(resolver, uri), (decoder, info, source) -> {
                    decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                    int max = Math.max(info.getSize().getWidth(), info.getSize().getHeight());
                    if (max > edge) decoder.setTargetSampleSize(Math.max(1, (int) Math.ceil(max / (double) edge)));
                });
        BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
        try (InputStream stream = resolver.openInputStream(uri)) { BitmapFactory.decodeStream(stream, null, bounds); }
        BitmapFactory.Options options = new BitmapFactory.Options(); options.inSampleSize = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight) / options.inSampleSize > edge) options.inSampleSize *= 2;
        Bitmap decoded;
        try (InputStream stream = resolver.openInputStream(uri)) { decoded = BitmapFactory.decodeStream(stream, null, options); }
        if (decoded == null) throw new IOException("Unsupported image");
        try (InputStream stream = resolver.openInputStream(uri)) {
            if (stream == null) throw new IOException("Source is unavailable");
            int orientation = new ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1);
            Matrix matrix = new Matrix();
            switch (orientation) {
                case 2: matrix.setScale(-1, 1); break;
                case 3: matrix.setRotate(180); break;
                case 4: matrix.setScale(1, -1); break;
                case 5: matrix.setRotate(90); matrix.postScale(-1, 1); break;
                case 6: matrix.setRotate(90); break;
                case 7: matrix.setRotate(270); matrix.postScale(-1, 1); break;
                case 8: matrix.setRotate(270); break;
                default: break;
            }
            Bitmap rotated = Bitmap.createBitmap(decoded, 0, 0, decoded.getWidth(), decoded.getHeight(), matrix, true);
            if (rotated != decoded) decoded.recycle(); return rotated;
        } catch (IOException e) { decoded.recycle(); throw e; }
    }
    public static List<Bitmap> assets(File directory, int edge, int limit) throws IOException {
        File[] files = new File(directory, "assets").listFiles(); List<Bitmap> result = new ArrayList<>();
        if (files == null) return result;
        Arrays.sort(files, Comparator.comparing(File::getName));
        try {
            for (File file : files) {
                if (result.size() >= limit) break;
                BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
                BitmapFactory.Options options = new BitmapFactory.Options(); options.inSampleSize = 1;
                while (Math.max(bounds.outWidth, bounds.outHeight) / options.inSampleSize > edge) options.inSampleSize *= 2;
                Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
                if (bitmap == null) throw new IOException("Export artwork unavailable");
                result.add(bitmap);
            }
            return result;
        } catch (IOException | RuntimeException error) { for (Bitmap b : result) b.recycle(); throw error; }
    }
    public static boolean isVideo(Context context, Uri source) throws IOException {
        String type = context.getContentResolver().getType(source);
        if (type != null && type.startsWith("video/")) return true;
        if (type != null && type.startsWith("image/")) return false;
        throw new IOException("Choose a supported photo or video");
    }
    public static void validate(File file, boolean video) throws IOException {
        if (!file.isFile() || file.length() == 0) throw new IOException("Export is empty");
        if (video) {
            MediaExtractor extractor = new MediaExtractor();
            try {
                extractor.setDataSource(file.getAbsolutePath());
                for (int i = 0; i < extractor.getTrackCount(); i++) {
                    MediaFormat f = extractor.getTrackFormat(i);
                    if ("video/avc".equals(f.getString(MediaFormat.KEY_MIME))) {
                        extractor.selectTrack(i);
                        if (extractor.getSampleTime() >= 0) return;
                    }
                }
                throw new IOException("Export contains no playable video track");
            } finally { extractor.release(); }
        } else {
            BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new IOException("Exported photo is invalid");
        }
    }
    public static Uri publish(Context context, ExportJobStore store, ExportJobStore.Item item,
            File file, boolean video) throws IOException {
        validate(file, video);
        String name = "SubHub_" + item.job + "_" + item.id + (video ? ".mp4" : ".jpg");
        ContentValues values = new ContentValues(); values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        values.put(MediaStore.MediaColumns.MIME_TYPE, video ? "video/mp4" : "image/jpeg");
        String folder = video ? Environment.DIRECTORY_MOVIES : Environment.DIRECTORY_PICTURES;
        File legacy = null;
        if (Build.VERSION.SDK_INT >= 29) {
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, folder + "/SubHub/Exports");
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        } else {
            File directory = new File(Environment.getExternalStoragePublicDirectory(folder), "SubHub/Exports");
            if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Gallery storage unavailable");
            legacy = new File(directory, name); File temporary = new File(directory, "." + name + ".pending");
            try { copy(file, new FileOutputStream(temporary));
                if (!temporary.renameTo(legacy)) throw new IOException("Could not finish gallery copy");
            } finally { if (temporary.exists() && !temporary.delete()) temporary.deleteOnExit(); }
            values.put(MediaStore.MediaColumns.DATA, legacy.getAbsolutePath());
        }
        Uri collection = video ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI : MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        Uri result = context.getContentResolver().insert(collection, values);
        if (result == null) { if (legacy != null) legacy.delete(); throw new IOException("Gallery refused the export"); }
        try {
            store.update(item.id, ExportJobStore.State.RUNNING, result, "Saving copy", 99);
            if (Build.VERSION.SDK_INT >= 29) {
                OutputStream stream = context.getContentResolver().openOutputStream(result, "w");
                if (stream == null) throw new IOException("Gallery output unavailable");
                copy(file, stream);
                try (InputStream original = new FileInputStream(file);
                     InputStream saved = context.getContentResolver().openInputStream(result)) {
                    if (saved == null || !java.security.MessageDigest.isEqual(digest(original), digest(saved)))
                        throw new IOException("Gallery copy validation failed");
                }
                ContentValues ready = new ContentValues(); ready.put(MediaStore.MediaColumns.IS_PENDING, 0);
                if (context.getContentResolver().update(result, ready, null, null) != 1)
                    throw new IOException("Could not publish gallery copy");
            }
            store.update(item.id, ExportJobStore.State.SAVED, result, "Saved", 100);
            return result;
        } catch (RuntimeException | IOException error) {
            context.getContentResolver().delete(result, null, null); throw error;
        }
    }
    private static void copy(File input, OutputStream output) throws IOException {
        try (InputStream from = new FileInputStream(input); OutputStream to = output) {
            byte[] buffer = new byte[65536]; int read;
            while ((read = from.read(buffer)) != -1) to.write(buffer, 0, read);
            to.flush();
        }
    }
    private static byte[] digest(InputStream stream) throws IOException {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[65536]; int count;
            while ((count = stream.read(buffer)) != -1) digest.update(buffer, 0, count);
            return digest.digest();
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IOException("Cannot validate saved copy", impossible); }
    }
}
