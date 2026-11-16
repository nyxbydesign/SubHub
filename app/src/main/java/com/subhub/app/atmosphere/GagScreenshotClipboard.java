package com.subhub.app.atmosphere;

import android.Manifest;
import android.content.ClipDescription;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.provider.MediaStore;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Reads only recent, saved screenshots after Clipboard use and a full Android photo grant. */
final class GagScreenshotClipboard {
    static final String PREFS = "gag_clipboard";
    static final String ENABLED_SINCE = "screenshots_enabled_since";
    static final String CLEARED_THROUGH = "screenshots_cleared_through";
    private static final String CLIPBOARD_OPENED = "screenshots_clipboard_opened";
    private static final String DISMISSED = "screenshots_dismissed";
    private static final String PICTURES = "Pictures/Screenshots/";
    private static final String DCIM = "DCIM/Screenshots/";
    private static final int MAX_ROWS = 24;
    private static final int MAX_IMPORTS = 8;
    private static final int MAX_DISMISSED = 64;
    private static final Pattern SCREENSHOT = Pattern.compile("screenshot(?:[_ -][^/]+)?\\.(?:png|jpe?g|webp)");

    enum Access { NONE, SELECTED, FULL }
    record Revision(long modifiedSeconds, long size, long generation) {}
    record Candidate(Uri uri, String mime, long savedAt, Revision revision) {}
    record Import(Candidate media, GagClipboardImage image) {}
    record Scan(Set<Uri> eligible, List<Import> imported) {}

    private GagScreenshotClipboard() {}

    static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static Access access(Context context) {
        if (context.checkSelfPermission(imagePermission()) == PackageManager.PERMISSION_GRANTED) return Access.FULL;
        return Build.VERSION.SDK_INT >= 34
                && context.checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
                == PackageManager.PERMISSION_GRANTED ? Access.SELECTED : Access.NONE;
    }

    static String[] requestedPermissions() {
        return Build.VERSION.SDK_INT >= 34
                ? new String[]{imagePermission(), Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED}
                : new String[]{imagePermission()};
    }

    private static String imagePermission() {
        return Build.VERSION.SDK_INT >= 33 ? Manifest.permission.READ_MEDIA_IMAGES : Manifest.permission.READ_EXTERNAL_STORAGE;
    }

    static boolean onClipboardOpened(Context context) {
        SharedPreferences prefs = preferences(context);
        if (prefs.getBoolean(CLIPBOARD_OPENED, false)) return false;
        prefs.edit().putBoolean(CLIPBOARD_OPENED, true).apply();
        return prefs.getLong(ENABLED_SINCE, 0) <= 0 && access(context) == Access.NONE;
    }

    static boolean updateAccess(Context context) {
        SharedPreferences prefs = preferences(context);
        long since = prefs.getLong(ENABLED_SINCE, 0);
        if (access(context) != Access.FULL) {
            if (since > 0) prefs.edit().remove(ENABLED_SINCE).putBoolean(CLIPBOARD_OPENED, true).apply();
            return false;
        }
        if (since > 0) return true;
        if (!prefs.getBoolean(CLIPBOARD_OPENED, false)) return false;
        prefs.edit().putLong(ENABLED_SINCE, System.currentTimeMillis()).apply();
        return true;
    }

    static boolean enabled(Context context) {
        SharedPreferences prefs = preferences(context);
        return prefs.getLong(ENABLED_SINCE, 0) > 0 && access(context) == Access.FULL;
    }

    static void clearThrough(Context context, long timestamp) {
        SharedPreferences prefs = preferences(context);
        if (timestamp > prefs.getLong(CLEARED_THROUGH, 0))
            prefs.edit().putLong(CLEARED_THROUGH, timestamp).remove(DISMISSED).apply();
    }

    static Map<Uri, Long> dismissed(Context context, long now) {
        Map<Uri, Long> dismissed = new java.util.HashMap<>();
        for (String item : preferences(context).getStringSet(DISMISSED, Set.of())) {
            int split = item.lastIndexOf('|');
            if (split <= 0) continue;
            try {
                long time = Long.parseLong(item.substring(split + 1));
                if (time > now - GagClipboard.LIFETIME_MS && time <= now)
                    dismissed.put(Uri.parse(item.substring(0, split)), time);
            } catch (NumberFormatException ignored) { }
        }
        return dismissed;
    }

    static void dismiss(Context context, Candidate screenshot) {
        Map<Uri, Long> dismissed = dismissed(context, System.currentTimeMillis());
        dismissed.put(screenshot.uri(), screenshot.savedAt());
        Set<String> saved = new HashSet<>();
        dismissed.entrySet().stream().sorted(Map.Entry.<Uri, Long>comparingByValue().reversed())
                .limit(MAX_DISMISSED).forEach(entry -> saved.add(entry.getKey() + "|" + entry.getValue()));
        preferences(context).edit().putStringSet(DISMISSED, saved).apply();
    }

    static Scan scan(ContentResolver resolver, long enabledSince, long clearedThrough, long now,
            Map<Uri, Revision> previous, Map<Uri, Long> dismissed, Set<Long> changed, CancellationSignal cancellation) {
        List<Candidate> rows = candidates(resolver, enabledSince, clearedThrough, now, cancellation);
        if (rows == null) return null;
        Set<Uri> eligible = new HashSet<>();
        List<Import> items = new ArrayList<>();
        for (Candidate row : rows) {
            if (row.savedAt() <= dismissed.getOrDefault(row.uri(), 0L)) continue;
            eligible.add(row.uri());
            if (Thread.currentThread().isInterrupted() || cancellation.isCanceled()) return null;
            if (items.size() >= MAX_IMPORTS || (!changed.contains(ContentUris.parseId(row.uri()))
                    && row.revision().equals(previous.get(row.uri())))) continue;
            GagClipboardImage image = GagClipboardImage.readScreenshot(resolver, row.uri(),
                    new ClipDescription("", new String[]{row.mime()}), row.revision().size());
            if (image != null) items.add(new Import(row, image));
        }
        return new Scan(Set.copyOf(eligible), List.copyOf(items));
    }

    private static List<Candidate> candidates(ContentResolver resolver, long enabledSince, long clearedThrough,
            long now, CancellationSignal cancellation) {
        boolean relative = Build.VERSION.SDK_INT >= 29;
        boolean generations = Build.VERSION.SDK_INT >= 30;
        String pathColumn = relative ? "relative_path" : "_data";
        List<String> columns = new ArrayList<>(List.of("_id", "_display_name", "mime_type", pathColumn,
                "date_added", "date_modified", "_size"));
        if (relative) columns.add("volume_name");
        if (generations) columns.add("generation_modified");
        String folder = relative ? pathColumn + " IN (?,?)" : "(" + pathColumn + " LIKE ? OR " + pathColumn + " LIKE ?)";
        String pending = relative ? " AND is_pending = 0" : "";
        String trashed = generations ? " AND is_trashed = 0" : "";
        Bundle query = new Bundle();
        query.putString(ContentResolver.QUERY_ARG_SQL_SELECTION, folder + pending + trashed + " AND _display_name LIKE ?"
                + " AND mime_type IN (?,?,?) AND date_added >= ? AND (date_added >= ? OR date_modified >= ?)");
        String cutoff = Long.toString(Math.max(now - GagClipboard.LIFETIME_MS, clearedThrough) / 1_000L);
        query.putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, new String[]{
                relative ? PICTURES : "%/" + PICTURES + "%", relative ? DCIM : "%/" + DCIM + "%",
                "Screenshot%", "image/png", "image/jpeg", "image/webp",
                Long.toString((enabledSince + 999L) / 1_000L), cutoff, cutoff});
        query.putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, "date_modified DESC, _id DESC");
        query.putInt(ContentResolver.QUERY_ARG_LIMIT, MAX_ROWS);
        try (Cursor cursor = resolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                columns.toArray(new String[0]), query, cancellation)) {
            if (cursor == null) return null;
            List<Candidate> result = new ArrayList<>();
            for (int read = 0; read < MAX_ROWS && cursor.moveToNext(); read++) {
                String name = cursor.getString(1);
                String mime = cursor.getString(2);
                if (!screenshot(name, mime, cursor.getString(3), relative)) continue;
                long addedAt = cursor.getLong(4) * 1_000L;
                long modified = cursor.getLong(5);
                long savedAt = Math.max(addedAt, modified * 1_000L);
                if (addedAt < enabledSince || savedAt <= clearedThrough || savedAt > now
                        || now - savedAt >= GagClipboard.LIFETIME_MS) continue;
                String volume = relative ? cursor.getString(7) : null;
                Uri collection = volume == null ? MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                        : MediaStore.Images.Media.getContentUri(volume);
                Uri uri = ContentUris.withAppendedId(collection, cursor.getLong(0));
                result.add(new Candidate(uri, mime, savedAt,
                        new Revision(modified, cursor.getLong(6), generations ? cursor.getLong(8) : 0)));
            }
            return result;
        } catch (RuntimeException unavailable) {
            return null;
        }
    }

    static boolean screenshot(String name, String mime, String path, boolean relative) {
        if (name == null || path == null || !("image/png".equals(mime) || "image/jpeg".equals(mime)
                || "image/webp".equals(mime)) || !SCREENSHOT.matcher(name.toLowerCase(Locale.ROOT)).matches()) return false;
        if (relative) return PICTURES.equals(path) || DCIM.equals(path);
        return path.endsWith("/" + PICTURES + name) || path.endsWith("/" + DCIM + name);
    }
}
