package com.subhub.app.censor.android;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ColorSpace;
import android.os.Looper;
import com.subhub.app.internal.InternalStorageScope;
import com.subhub.app.remote.RemoteAssets;
import com.subhub.app.runtime.RuntimeConfiguration;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import java.util.function.BooleanSupplier;

/**
 * Exclusive bounded software images from one acknowledged internal manifest. No device library,
 * SAF tree, pack folder or remote transport fallback. The containing Censor component owns the
 * preparation worker until actual drain; returned bitmaps transfer to SnapshotRenderSession.
 */
final class OwnedCensorArtwork implements SnapshotRenderSession.ArtworkLoader {
    private final InternalStorageScope storage;
    private final OwnedCensorArtworkSelection selection;

    OwnedCensorArtwork(InternalStorageScope storage, RuntimeConfiguration initial) {
        this.storage = Objects.requireNonNull(storage); Objects.requireNonNull(initial);
        if (!storage.targetId().equals(initial.owner.target.id))
            throw new SecurityException("Artwork storage does not match runtime owner");
        selection = new OwnedCensorArtworkSelection(initial.owner, initial.configRevision, initial.artworkRevision,
                initial.censorAssets, initial.disabledAssets);
    }
    /** Apply only from the bound component's synchronous configuration-adoption receiver. */
    void update(RuntimeConfiguration adopted) {
        Objects.requireNonNull(adopted);
        selection.update(adopted.owner, adopted.configRevision, adopted.artworkRevision,
                adopted.censorAssets, adopted.disabledAssets);
    }

    @Override public List<Bitmap> load(SnapshotPaintPolicy policy, long revision, BooleanSupplier obsolete) {
        if (Looper.myLooper() == Looper.getMainLooper()) throw new IllegalStateException("Decode owned Censor artwork off main");
        Objects.requireNonNull(policy); Objects.requireNonNull(obsolete);
        OwnedCensorArtworkSelection.Snapshot selected = selection.snapshot();
        if (revision != selected.artworkRevision || obsolete.getAsBoolean()) return Collections.emptyList();
        int dimension = policy.artworkDimension, maximum = policy.artworkCount;
        long budget = policy.maximumArtworkBytes;
        if (dimension < 1 || dimension > 1024 || maximum < 1 || maximum > 16 || budget < 4 || budget > 64L * 1024 * 1024)
            throw new IllegalArgumentException("Owned Censor artwork budget");
        List<Bitmap> result = new ArrayList<>(maximum); long bytes = 0; boolean transferred = false;
        try {
            RemoteAssets assets = new RemoteAssets(storage.directory(InternalStorageScope.Area.ASSETS));
            for (String hash : new TreeSet<>(selected.enabled)) {
                if (obsolete.getAsBoolean() || !selection.current(selected)) return Collections.emptyList();
                if (result.size() >= maximum) break;
                if (selected.disabled.contains(hash)) continue;
                File file = assets.finish(hash); // Verifies the immutable blob's digest and owned path.
                Bitmap image = decode(file, dimension, budget - bytes);
                if (image == null) throw new IOException("Owned Censor image exceeds budget or cannot decode");
                result.add(image); bytes += image.getAllocationByteCount();
            }
            if (obsolete.getAsBoolean() || !selection.current(selected)) return Collections.emptyList();
            transferred = true; return result;
        } catch (IOException unavailable) {
            throw new IllegalStateException("Owned Censor artwork unavailable", unavailable);
        } finally {
            if (!transferred) for (Bitmap image : result) if (!image.isRecycled()) image.recycle();
        }
    }

    private static Bitmap decode(File file, int dimension, long remaining) {
        if (remaining < 4) return null;
        Bitmap bitmap = null; boolean transferred = false;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getPath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
            int sample = 1;
            while (((long) bounds.outWidth + sample - 1) / sample > dimension
                    || ((long) bounds.outHeight + sample - 1) / sample > dimension) {
                if (sample >= 1 << 30) return null;
                sample *= 2;
            }
            long width = ((long) bounds.outWidth + sample - 1) / sample;
            long height = ((long) bounds.outHeight + sample - 1) / sample;
            if (4 * width * height > remaining) return null;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample; options.inScaled = false; options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            options.inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB);
            bitmap = BitmapFactory.decodeFile(file.getPath(), options);
            if (bitmap == null || bitmap.getConfig() == Bitmap.Config.HARDWARE || bitmap.getWidth() > dimension
                    || bitmap.getHeight() > dimension || bitmap.getAllocationByteCount() > remaining) return null;
            transferred = true; return bitmap;
        } finally {
            if (!transferred && bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
        }
    }
}
