package com.subhub.app.censor.android;

import android.graphics.Bitmap;
import com.subhub.app.internal.InternalStorageScope;
import com.subhub.app.runtime.RuntimeConfiguration;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Shared API26+ owned artwork decoder for modern and legacy renderers. This is data selection,
 * never OS/effect admission. No Product/controller construction, DEVICE library or remote fallback.
 */
public final class OwnedCensorArtworkSource {
    private final OwnedCensorArtwork loader;
    public OwnedCensorArtworkSource(InternalStorageScope storage, RuntimeConfiguration initial) {
        loader = new OwnedCensorArtwork(storage, initial);
    }
    /** Serialized exact-owner module adoption; only an acknowledged immutable source snapshot. */
    public void adopt(RuntimeConfiguration configuration) { loader.update(configuration); }
    /** Off-main. Returned software bitmaps transfer exclusively to caller, which must recycle them. */
    public List<Bitmap> load(int maximumDimension, int maximumCount, long artworkRevision, BooleanSupplier obsolete) {
        return loader.load(new SnapshotPaintPolicy(maximumDimension, maximumCount), artworkRevision, obsolete);
    }
}
