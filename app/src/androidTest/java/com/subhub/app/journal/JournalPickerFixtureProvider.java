package com.subhub.app.journal;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.RandomAccessFile;

/** Synthetic picker documents exist only in the instrumentation APK's cache. */
public final class JournalPickerFixtureProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }
    private String name(Uri uri) {
        String name = uri.getLastPathSegment();
        if (name == null || !name.matches("small[1-6]|photo|oversize|hiddenoversize"))
            throw new SecurityException("Unknown synthetic document");
        return name;
    }
    @Override public String getType(Uri uri) { return name(uri).equals("photo") ? "image/png" : "text/plain"; }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
        String name = name(uri);
        String[] columns = projection == null ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : projection;
        MatrixCursor result = new MatrixCursor(columns); Object[] row = new Object[columns.length];
        for (int i = 0; i < columns.length; i++) row[i] = OpenableColumns.DISPLAY_NAME.equals(columns[i]) ? name + (name.equals("photo") ? ".png" : ".txt")
                : OpenableColumns.SIZE.equals(columns[i]) ? name.equals("hiddenoversize") ? null
                : name.equals("oversize") ? JournalAttachment.MAX_BYTES + 1 : 64L : null;
        result.addRow(row); return result;
    }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        String name = name(uri);
        if (!"r".equals(mode)) throw new FileNotFoundException("Read only");
        try {
            File file = new File(getContext().getCacheDir(), "journal-picker-" + name);
            if (name.equals("photo")) {
                android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(256, 192, android.graphics.Bitmap.Config.ARGB_8888);
                bitmap.eraseColor(android.graphics.Color.rgb(136, 79, 177));
                try (java.io.FileOutputStream out = new java.io.FileOutputStream(file)) { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out); }
                finally { bitmap.recycle(); }
                return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
            }
            try (RandomAccessFile out = new RandomAccessFile(file, "rw")) {
                out.setLength(name.endsWith("oversize") ? JournalAttachment.MAX_BYTES + 1 : 64);
                out.seek(0); out.writeUTF(name);
            }
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
        } catch (Exception failure) { throw new FileNotFoundException("Synthetic document unavailable"); }
    }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) {
        File file = new File(getContext().getCacheDir(), "journal-picker-" + name(uri));
        return file.delete() ? 1 : 0;
    }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
