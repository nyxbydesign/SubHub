package com.subhub.app.popup;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import com.subhub.app.internal.InternalStorageScope;
import com.subhub.app.remote.RemoteAssets;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.BooleanSupplier;

/** Decoder for an exact internal manifest. It cannot open device paths, SAF trees or pack folders. */
final class OwnedPopupArtwork implements AutoCloseable {
    private static final String BUILT_IN = "popup_storm/guardian_shield.webp";
    private final List<PopupLibrary.BitmapSource> images;
    private final Random random = new Random();

    private OwnedPopupArtwork(List<PopupLibrary.BitmapSource> images) { this.images = images; }
    static OwnedPopupArtwork empty() { return new OwnedPopupArtwork(new ArrayList<>()); }

    /** Run on the binding's counted worker. Cancellation is observed between bounded decodes. */
    static OwnedPopupArtwork load(Context physicalHost, InternalStorageScope storage,
            PopupArtworkManifest manifest, BooleanSupplier current) throws IOException {
        OwnedPopupArtwork result = empty();
        try {
            if (manifest.builtInFallback) {
                if (!current.getAsBoolean()) return result;
                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                try (InputStream input = physicalHost.getAssets().open(BUILT_IN)) {
                    BitmapFactory.decodeStream(input, null, bounds);
                }
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0)
                    throw new IOException("Bundled popup artwork unavailable");
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inSampleSize = 1;
                options.inPreferredConfig = Bitmap.Config.ARGB_8888;
                while (Math.max(bounds.outWidth, bounds.outHeight) / options.inSampleSize > manifest.decodeEdge())
                    options.inSampleSize *= 2;
                Bitmap bitmap;
                try (InputStream input = physicalHost.getAssets().open(BUILT_IN)) {
                    bitmap = BitmapFactory.decodeStream(input, null, options);
                }
                if (bitmap == null) throw new IOException("Bundled popup artwork unavailable");
                if (current.getAsBoolean())
                    result.images.add(new PopupLibrary.BitmapSource("asset://" + BUILT_IN, bitmap));
                else bitmap.recycle();
                return result;
            }
            RemoteAssets assets = new RemoteAssets(storage.directory(InternalStorageScope.Area.ASSETS));
            long bytes = 0;
            for (String hash : manifest.enabled) {
                if (!current.getAsBoolean()) break;
                File file = assets.finish(hash); // Validates length/hash and rejects symlink blobs.
                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(file.getPath(), bounds);
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0)
                    throw new IOException("Owned popup artwork is not an image");
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inSampleSize = 1;
                options.inPreferredConfig = Bitmap.Config.ARGB_8888;
                while (Math.max(bounds.outWidth, bounds.outHeight) / options.inSampleSize > manifest.decodeEdge())
                    options.inSampleSize *= 2;
                long estimate = ((long) bounds.outWidth / options.inSampleSize + 1)
                        * ((long) bounds.outHeight / options.inSampleSize + 1) * 4;
                if (bytes + estimate > PopupArtworkManifest.MAX_DECODE_BYTES)
                    throw new IOException("Owned popup artwork exceeds decoded budget");
                Bitmap bitmap = BitmapFactory.decodeFile(file.getPath(), options);
                if (bitmap == null) throw new IOException("Owned popup artwork could not decode");
                if (!current.getAsBoolean()) { bitmap.recycle(); break; }
                if (bytes + bitmap.getAllocationByteCount() > PopupArtworkManifest.MAX_DECODE_BYTES) {
                    bitmap.recycle();
                    throw new IOException("Owned popup artwork exceeds decoded budget");
                }
                bytes += bitmap.getAllocationByteCount();
                result.images.add(new PopupLibrary.BitmapSource(hash, bitmap));
            }
            return result;
        } catch (IOException | RuntimeException | Error failure) {
            result.close();
            throw failure;
        }
    }

    int count() { return images.size(); }
    PopupLibrary.BitmapSource randomBitmap() {
        return images.isEmpty() ? null : images.get(random.nextInt(images.size()));
    }
    /** Only after every window referencing this snapshot is physically detached. */
    @Override public void close() {
        for (PopupLibrary.BitmapSource image : images)
            if (!image.bitmap.isRecycled()) image.bitmap.recycle();
        images.clear();
    }
}
