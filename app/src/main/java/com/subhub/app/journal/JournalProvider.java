package com.subhub.app.journal;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import com.subhub.app.people.PersonId;
import java.io.FileNotFoundException;
import java.util.List;

/** Only one currently authorized entry reference can be granted to a native media/document viewer. */
public final class JournalProvider extends ContentProvider {
    static Uri uri(Context context, boolean dom, PersonId target, String entry, JournalAttachment file) {
        return new Uri.Builder().scheme("content").authority(context.getPackageName() + ".journal")
                .appendPath(dom ? "dom" : "sub").appendPath(target.value()).appendPath(entry).appendPath(file.hash).build();
    }
    @Override public boolean onCreate() { return true; }
    static Uri draftUri(Context context, boolean dom, String draft, JournalAttachment file) {
        return uri(context, dom, PersonId.LOCAL, "draft_" + draft, file);
    }
    private JournalAttachment reference(Uri uri) throws Exception {
        List<String> path = uri.getPathSegments();
        if (path.size() != 4 || !(path.get(0).equals("dom") || path.get(0).equals("sub"))) throw new SecurityException("Invalid journal URI");
        if (path.get(2).startsWith("draft_") && path.get(1).equals(PersonId.LOCAL.value()))
            return JournalRuntime.get(getContext()).draftReference(path.get(0).equals("dom"), path.get(2).substring(6), path.get(3));
        JournalEntry entry = JournalRuntime.get(getContext()).entry(path.get(0).equals("dom"), new PersonId(path.get(1)), path.get(2));
        if (entry != null) for (JournalAttachment file : entry.media()) if (file.hash.equals(path.get(3))) return file;
        throw new FileNotFoundException("Journal attachment unavailable");
    }
    @Override public String getType(Uri uri) {
        try { return reference(uri).mime; } catch (Exception unavailable) { return null; }
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
        try {
            JournalAttachment file = reference(uri);
            if (projection != null && projection.length > 2) return null;
            String[] columns = projection == null ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : projection;
            MatrixCursor cursor = new MatrixCursor(columns); Object[] row = new Object[columns.length];
            for (int i = 0; i < columns.length; i++) row[i] = OpenableColumns.DISPLAY_NAME.equals(columns[i]) ? file.name
                    : OpenableColumns.SIZE.equals(columns[i]) ? file.length : null;
            cursor.addRow(row); return cursor;
        } catch (Exception unavailable) { return null; }
    }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("Journal attachments are read only");
        try {
            reference(uri); List<String> path = uri.getPathSegments();
            if (path.get(2).startsWith("draft_") && path.get(1).equals(PersonId.LOCAL.value()))
                return ParcelFileDescriptor.open(JournalRuntime.get(getContext()).draftFile(path.get(0).equals("dom"),
                        path.get(2).substring(6), path.get(3)), ParcelFileDescriptor.MODE_READ_ONLY);
            return ParcelFileDescriptor.open(JournalRuntime.get(getContext()).mediaFile(path.get(0).equals("dom"),
                    new PersonId(path.get(1)), path.get(2), path.get(3)), ParcelFileDescriptor.MODE_READ_ONLY);
        } catch (Exception unavailable) { throw new FileNotFoundException("Journal attachment unavailable"); }
    }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new SecurityException("Journal provider is read only"); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new SecurityException("Journal provider is read only"); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new SecurityException("Journal provider is read only"); }
}
