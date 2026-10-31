package com.subhub.app.util;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.MediaExtractor;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.system.Os;
import android.system.OsConstants;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** Keeps decoders on one bounded source; original document identity stays with the caller. */
public final class MediaInput implements AutoCloseable {
    public static final long IMAGE_BYTES = 25L * 1024 * 1024;
    public static final long VIDEO_BYTES = 1024L * 1024 * 1024;
    private final AssetFileDescriptor descriptor;
    private final File temporary;
    private final long length;

    private MediaInput(AssetFileDescriptor descriptor, File temporary, long length) {
        this.descriptor = descriptor;
        this.temporary = temporary;
        this.length = length;
    }

    public static MediaInput video(Context context, Uri uri) throws IOException {
        AssetFileDescriptor source = context.getContentResolver().openAssetFileDescriptor(uri, "r");
        if (source == null) throw new IOException("Video is unavailable");
        boolean retained = false;
        try {
            long stat = source.getParcelFileDescriptor().getStatSize();
            long offset = source.getStartOffset();
            long declared = source.getDeclaredLength();
            long available = stat >= offset && offset >= 0 ? stat - offset : -1;
            if (available >= 0 && (declared < 0 || declared <= available)) {
                long length = declared < 0 ? available : declared;
                if (length <= 0 || length > VIDEO_BYTES) throw new IOException("Video must be between 1 byte and 1 GiB");
                try {
                    Os.lseek(source.getFileDescriptor(), 0, OsConstants.SEEK_CUR);
                    retained = true;
                    return new MediaInput(source, null, length);
                } catch (android.system.ErrnoException nonSeekable) { /* Bounded stream fallback. */ }
            }
        } finally { if (!retained) source.close(); }
        File temporary = stage(context, uri, VIDEO_BYTES);
        try {
            AssetFileDescriptor staged = new AssetFileDescriptor(
                    android.os.ParcelFileDescriptor.open(temporary, android.os.ParcelFileDescriptor.MODE_READ_ONLY), 0, temporary.length());
            return new MediaInput(staged, temporary, temporary.length());
        } catch (IOException | RuntimeException error) { temporary.delete(); throw error; }
    }

    public static File stage(Context context, Uri uri, long maximum) throws IOException {
        File temporary = File.createTempFile("media-input-", ".source", context.getCacheDir());
        boolean complete = false;
        try (InputStream input = context.getContentResolver().openInputStream(uri);
             FileOutputStream output = new FileOutputStream(temporary)) {
            if (BoundedCopy.copy(input, output, maximum) == 0) throw new IOException("File is empty");
            complete = true;
            return temporary;
        } catch (IOException | RuntimeException error) {
            complete = false;
            throw error;
        } finally { if (!complete) temporary.delete(); }
    }

    public void configure(MediaExtractor reader) throws IOException {
        reader.setDataSource(descriptor.getFileDescriptor(), descriptor.getStartOffset(), length);
    }

    public void configure(MediaMetadataRetriever reader) {
        reader.setDataSource(descriptor.getFileDescriptor(), descriptor.getStartOffset(), length);
    }

    @Override public void close() throws IOException {
        try { descriptor.close(); }
        finally { if (temporary != null) temporary.delete(); }
    }
}
