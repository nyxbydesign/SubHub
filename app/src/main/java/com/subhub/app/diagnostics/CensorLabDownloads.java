package com.subhub.app.diagnostics;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import androidx.annotation.RequiresApi;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;

/** Explicit local export; publish a Downloads row only after the complete ZIP is closed. */
final class CensorLabDownloads {
    private CensorLabDownloads() {}

    static File requireBundle(Context context, File bundle) throws IOException {
        File root = new File(context.getCacheDir(), "censor-lab/exports").getCanonicalFile();
        if (bundle == null || !bundle.isFile() || bundle.length() == 0
                || !bundle.getCanonicalPath().startsWith(root.getPath() + File.separator)
                || !bundle.getName().matches("subhub-censor-lab-[A-Za-z0-9_-]+\\.zip")) {
            throw new IOException("The Censor Lab ZIP is unavailable");
        }
        return bundle;
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    static Uri save(Context context, File bundle) throws IOException {
        requireBundle(context, bundle);
        ContentResolver resolver = context.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, bundle.getName());
        values.put(MediaStore.Downloads.MIME_TYPE, "application/zip");
        values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
        values.put(MediaStore.Downloads.IS_PENDING, 1);
        Uri destination = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (destination == null) throw new IOException("Downloads refused the ZIP");
        try {
            copy(context, bundle, destination);
            ContentValues ready = new ContentValues();
            ready.put(MediaStore.Downloads.IS_PENDING, 0);
            if (resolver.update(destination, ready, null, null) != 1) {
                throw new IOException("Could not finish saving the ZIP");
            }
            return destination;
        } catch (IOException | RuntimeException failure) {
            try { resolver.delete(destination, null, null); }
            catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    static void copy(Context context, File bundle, Uri destination) throws IOException {
        requireBundle(context, bundle);
        long expected = bundle.length();
        long copied = 0;
        try (FileInputStream input = new FileInputStream(bundle);
                OutputStream output = context.getContentResolver().openOutputStream(destination, "w")) {
            if (output == null) throw new IOException("The ZIP destination is unavailable");
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
                copied += read;
            }
            if (copied != expected) throw new IOException("The ZIP changed while saving");
        }
    }
}
