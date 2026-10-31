package com.subhub.app.util;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;

/** Synthetic files and unknown-size pipes for imported-source boundary checks. */
public final class InputFixtureProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }
    private String name(Uri uri) {
        String name = uri.getLastPathSegment();
        if (name == null || !name.matches("photo|pipephoto|pipeoversize|largevideo"))
            throw new SecurityException("Unknown document fixture");
        return name;
    }
    @Override public String getType(Uri uri) { return name(uri).equals("largevideo") ? "video/mp4" : "image/png"; }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
        String[] columns = projection == null ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : projection;
        MatrixCursor cursor = new MatrixCursor(columns);
        Object[] row = new Object[columns.length];
        for (int i = 0; i < columns.length; i++) row[i] = OpenableColumns.DISPLAY_NAME.equals(columns[i]) ? name(uri)
                : OpenableColumns.SIZE.equals(columns[i]) ? 1L : null;
        cursor.addRow(row);
        return cursor;
    }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        String name = name(uri);
        if (!"r".equals(mode)) throw new FileNotFoundException("Read only");
        try {
            if (name.startsWith("pipe")) {
                ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
                new Thread(() -> {
                    try (java.io.OutputStream output = new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])) {
                        if (name.equals("pipephoto")) writePhoto(output);
                        else {
                            byte[] zeros = new byte[65536];
                            long left = MediaInput.IMAGE_BYTES + 1;
                            while (left > 0) { int count = (int) Math.min(zeros.length, left); output.write(zeros, 0, count); left -= count; }
                        }
                    } catch (java.io.IOException closedReader) { /* Expected after rejection. */ }
                }, "input-fixture-pipe").start();
                return pipe[0];
            }
            File file = new File(getContext().getCacheDir(), "input-fixture-" + name);
            if (name.equals("largevideo")) {
                try (RandomAccessFile output = new RandomAccessFile(file, "rw")) { output.setLength(MediaInput.VIDEO_BYTES + 1); }
            } else try (FileOutputStream output = new FileOutputStream(file)) { writePhoto(output); }
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
        } catch (java.io.IOException failed) { throw new FileNotFoundException("Fixture unavailable"); }
    }
    private static void writePhoto(java.io.OutputStream output) {
        Bitmap photo = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888);
        try { photo.eraseColor(android.graphics.Color.MAGENTA); photo.compress(Bitmap.CompressFormat.PNG, 100, output); }
        finally { photo.recycle(); }
    }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) {
        return new File(getContext().getCacheDir(), "input-fixture-" + name(uri)).delete() ? 1 : 0;
    }
}
