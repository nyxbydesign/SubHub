package com.subhub.app.atmosphere;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import androidx.core.content.FileProvider;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.util.concurrent.ConcurrentHashMap;

/** Grants the selected editor a brief read of the image explicitly picked from Gagging. */
public final class GagClipboardFileProvider extends FileProvider {
    private static final String DIRECTORY = "gag-clipboard-paste";
    private static final long GRANT_MS = 30_000L;
    private static final int MAX_ACTIVE_PASTES = 6;
    private static final ConcurrentHashMap<Uri, Lease> active = new ConcurrentHashMap<>();
    private static final Handler cleanup = new Handler(Looper.getMainLooper());

    private record Lease(File file, long expiresAt) {}

    @Override public boolean onCreate() {
        boolean ready = super.onCreate();
        Context context = getContext();
        if (context != null) {
            File[] stale = new File(context.getCacheDir(), DIRECTORY).listFiles();
            if (stale != null) for (File file : stale) delete(file);
        }
        return ready;
    }

    static File prepare(Context context, GagClipboardImage image) throws IOException {
        File directory = new File(context.getCacheDir(), DIRECTORY);
        if (!directory.mkdirs() && !directory.isDirectory()) throw new IOException();
        File file = File.createTempFile("paste-", GagClipboardImage.extension(image.mime()), directory);
        try {
            Files.write(file.toPath(), image.bytes());
            return file;
        } catch (IOException | RuntimeException failure) {
            delete(file);
            throw failure;
        }
    }

    static Uri publish(Context context, File file, long remainingMs) {
        if (remainingMs <= 0 || active.size() >= MAX_ACTIVE_PASTES) throw new IllegalStateException();
        Uri uri = getUriForFile(context, context.getPackageName() + ".gag.clipboard", file);
        long duration = Math.min(GRANT_MS, remainingMs);
        active.put(uri, new Lease(file, SystemClock.elapsedRealtime() + duration));
        cleanup.postDelayed(() -> retire(context, file, uri), duration);
        return uri;
    }

    static void retire(Context context, File file, Uri uri) {
        try {
            if (uri != null && active.remove(uri) != null)
                context.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } finally { delete(file); }
    }

    static void retireAll(Context context) {
        for (var entry : active.entrySet()) retire(context, entry.getValue().file(), entry.getKey());
    }

    private static void delete(File file) {
        try { Files.deleteIfExists(file.toPath()); }
        catch (IOException | SecurityException unavailable) { /* Startup retries cleanup of private cache. */ }
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        Lease lease = active.get(uri);
        if (!"r".equals(mode) || lease == null || SystemClock.elapsedRealtime() >= lease.expiresAt())
            throw new FileNotFoundException();
        return super.openFile(uri, mode);
    }
}
