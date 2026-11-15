package com.subhub.app.fullaccess;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import androidx.annotation.RequiresApi;
import androidx.core.content.FileProvider;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/** Publishes only a fully acknowledged download. Failed transfers remain private and are discarded. */
public final class FullAccessDownloads {
    private static final String DIRECTORY = "full-access-downloads";
    private static final String STAGING = ".full-access-staging";
    private static final Object PUBLISH_LOCK = new Object();

    @FunctionalInterface
    public interface Writer {
        void write(OutputStream output) throws IOException;
    }

    private FullAccessDownloads() { }

    public static Uri save(Context context, String name, String mime, BooleanSupplier current, Writer writer)
            throws IOException {
        Objects.requireNonNull(current);
        Objects.requireNonNull(writer);
        requireCurrent(current);
        String safeName = FullAccessFiles.safeName(name);
        String safeMime = mime != null && mime.matches("[A-Za-z0-9][A-Za-z0-9._+-]{0,63}/[A-Za-z0-9][A-Za-z0-9._+-]{0,95}")
                ? mime : "application/octet-stream";
        if (Build.VERSION.SDK_INT >= 29) return saveInDownloads(context, safeName, safeMime, current, writer);
        return saveInApp(context, safeName, current, writer);
    }

    @RequiresApi(29)
    private static Uri saveInDownloads(Context context, String name, String mime, BooleanSupplier current,
            Writer writer) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        values.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/SubHub/");
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri item = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (item == null) throw new IOException("Download storage unavailable");
        boolean complete = false;
        try {
            try (OutputStream output = resolver.openOutputStream(item, "w")) {
                if (output == null) throw new IOException("Download storage unavailable");
                writer.write(guarded(output, current));
            }
            requireCurrent(current);
            values.clear();
            values.put(MediaStore.MediaColumns.IS_PENDING, 0);
            if (resolver.update(item, values, null, null) != 1) throw new IOException("Could not finish download");
            requireCurrent(current);
            complete = true;
            return item;
        } finally {
            if (!complete) {
                try {
                    resolver.delete(item, null, null);
                } catch (RuntimeException unavailable) {
                    // Android's pending row remains private until it expires.
                }
            }
        }
    }

    private static Uri saveInApp(Context context, String name, BooleanSupplier current, Writer writer)
            throws IOException {
        Path staging = Files.createDirectories(context.getFilesDir().toPath().resolve(STAGING));
        Path partial = Files.createTempFile(staging, "download-", ".part");
        try {
            try (OutputStream output = Files.newOutputStream(partial)) {
                writer.write(guarded(output, current));
            }
            synchronized (PUBLISH_LOCK) {
                requireCurrent(current);
                Path directory = Files.createDirectories(context.getFilesDir().toPath().resolve(DIRECTORY));
                Path target = availableName(directory, name);
                Uri result = FileProvider.getUriForFile(context, context.getPackageName() + ".fullaccess.files",
                        target.toFile());
                Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE);
                try {
                    requireCurrent(current);
                } catch (IOException ended) {
                    Files.deleteIfExists(target);
                    throw ended;
                }
                return result;
            }
        } finally {
            Files.deleteIfExists(partial);
        }
    }

    private static Path availableName(Path directory, String name) {
        Path original = directory.resolve(name);
        if (!Files.exists(original)) return original;
        int extension = name.lastIndexOf('.');
        String base = extension > 0 ? name.substring(0, extension) : name;
        String suffix = extension > 0 ? name.substring(extension) : "";
        int number = 2;
        Path candidate;
        do {
            candidate = directory.resolve(base + " (" + number++ + ")" + suffix);
        } while (Files.exists(candidate));
        return candidate;
    }

    private static OutputStream guarded(OutputStream output, BooleanSupplier current) {
        return new FilterOutputStream(output) {
            @Override public void write(int value) throws IOException {
                requireCurrent(current);
                out.write(value);
            }

            @Override public void write(byte[] data, int offset, int length) throws IOException {
                requireCurrent(current);
                out.write(data, offset, length);
            }
        };
    }

    private static void requireCurrent(BooleanSupplier current) throws IOException {
        if (Thread.currentThread().isInterrupted() || !current.getAsBoolean()) throw new IOException("File view closed");
    }
}
