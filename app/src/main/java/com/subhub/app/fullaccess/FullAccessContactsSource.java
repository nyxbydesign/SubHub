package com.subhub.app.fullaccess;

import android.Manifest;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.provider.ContactsContract;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

final class FullAccessContactsSource {
    static final int PHOTO_EDGE = 96;
    static final int PHOTO_BYTES = 8 * 1024;
    private static final int MAX_PHOTO_SOURCE_BYTES = 512 * 1024;
    private static final int MAX_PHOTO_SOURCE_EDGE = 8192;
    private static final int[] PHOTO_QUALITY = {80, 65, 50};

    private record Contact(String name, String thumbnailUri) { }

    private FullAccessContactsSource() { }

    static JSONObject page(Context context, long after, int limit) throws JSONException {
        return page(context, after, limit, true);
    }

    static JSONObject page(Context context, long after, int limit, boolean includePhotos) throws JSONException {
        if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED)
            return FullAccessDataSources.response("permission_required", new JSONArray());
        ContentResolver resolver = context.getContentResolver();
        Map<String, Contact> names = new LinkedHashMap<>();
        String id = ContactsContract.Contacts._ID;
        String name = ContactsContract.Contacts.DISPLAY_NAME_PRIMARY;
        boolean more = false;
        try (Cursor contacts = resolver.query(ContactsContract.Contacts.CONTENT_URI,
                new String[]{id, name, ContactsContract.Contacts.PHOTO_THUMBNAIL_URI},
                id + ">?", new String[]{Long.toString(after)}, id + " ASC")) {
            if (contacts == null) return FullAccessDataSources.response("unavailable", new JSONArray());
            while (contacts.moveToNext()) {
                if (names.size() == limit) {
                    more = true;
                    break;
                }
                names.put(Long.toString(contacts.getLong(0)), new Contact(
                        FullAccessDataSources.bounded(contacts.getString(1), 160), contacts.getString(2)));
            }
        }

        Map<String, LinkedHashSet<String>> values = details(resolver, new ArrayList<>(names.keySet()));
        JSONArray rows = new JSONArray();
        String last = null;
        for (Map.Entry<String, Contact> entry : names.entrySet()) {
            last = entry.getKey();
            LinkedHashSet<String> contact = values.get(last);
            String detail = contact == null ? "" : FullAccessDataSources.bounded(String.join("\n", contact), 512);
            JSONObject row = new JSONObject().put("id", last)
                    .put("title", entry.getValue().name()).put("detail", detail);
            String thumbnail = entry.getValue().thumbnailUri();
            if (includePhotos) {
                String photo = readPhoto(resolver, thumbnail);
                if (photo != null) row.put("photo", photo);
            } else if (thumbnail != null) {
                row.put("hasPhoto", true);
            }
            rows.put(row);
        }
        JSONObject result = FullAccessDataSources.response("ok", rows);
        if (more && last != null) result.put("nextCursor", last);
        return result;
    }

    static boolean validPhotoIds(String node) {
        String[] ids = node.split(",", -1);
        if (ids.length > FullAccessDataSources.DETAIL_BATCH_SIZE) return false;
        for (String id : ids) {
            try {
                if (FullAccessSmsSource.number(id) <= 0) return false;
            } catch (IllegalArgumentException invalid) {
                return false;
            }
        }
        return true;
    }

    static JSONObject photos(Context context, String node) throws JSONException {
        if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED)
            return FullAccessDataSources.response("permission_required", new JSONArray());
        ContentResolver resolver = context.getContentResolver();
        String[] ids = node.split(",", -1);
        String placeholders = String.join(",", Collections.nCopies(ids.length, "?"));
        String id = ContactsContract.Contacts._ID;
        JSONArray items = new JSONArray();
        try (Cursor cursor = resolver.query(ContactsContract.Contacts.CONTENT_URI,
                new String[]{id, ContactsContract.Contacts.PHOTO_THUMBNAIL_URI},
                id + " IN (" + placeholders + ")", ids, null)) {
            if (cursor == null) return FullAccessDataSources.response("unavailable", items);
            while (items.length() < ids.length && cursor.moveToNext()) {
                String photo = readPhoto(resolver, cursor.getString(1));
                JSONObject row = new JSONObject().put("id", Long.toString(cursor.getLong(0)));
                if (photo != null) row.put("photo", photo);
                items.put(row);
            }
        }
        return FullAccessDataSources.response("ok", items);
    }

    private static String readPhoto(ContentResolver resolver, String thumbnailUri) {
        if (thumbnailUri == null || Thread.currentThread().isInterrupted()) return null;
        Uri uri = Uri.parse(thumbnailUri);
        if (!ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())
                || !ContactsContract.AUTHORITY.equals(uri.getAuthority())) return null;
        try (InputStream input = resolver.openInputStream(uri)) {
            if (input == null) return null;
            ByteArrayOutputStream source = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (count > MAX_PHOTO_SOURCE_BYTES - source.size()
                        || Thread.currentThread().isInterrupted()) return null;
                source.write(buffer, 0, count);
            }
            return encodePhoto(source.toByteArray());
        } catch (IOException | RuntimeException unavailable) {
            // Missing or malformed photos leave the contact available with its initials.
            return null;
        }
    }

    static String encodePhoto(byte[] source) {
        if (source.length == 0 || source.length > MAX_PHOTO_SOURCE_BYTES) return null;
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(source, 0, source.length, bounds);
        int edge = Math.max(bounds.outWidth, bounds.outHeight);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || edge > MAX_PHOTO_SOURCE_EDGE) return null;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = 1;
        while (edge / options.inSampleSize > 2 * PHOTO_EDGE) options.inSampleSize *= 2;
        Bitmap decoded = BitmapFactory.decodeByteArray(source, 0, source.length, options);
        if (decoded == null) return null;
        Bitmap cropped = decoded;
        Bitmap thumbnail = decoded;
        try {
            int side = Math.min(decoded.getWidth(), decoded.getHeight());
            cropped = Bitmap.createBitmap(decoded, (decoded.getWidth() - side) / 2,
                    (decoded.getHeight() - side) / 2, side, side);
            thumbnail = Bitmap.createScaledBitmap(cropped, PHOTO_EDGE, PHOTO_EDGE, true);
            ByteArrayOutputStream encoded = new ByteArrayOutputStream();
            for (int quality : PHOTO_QUALITY) {
                encoded.reset();
                if (!thumbnail.compress(Bitmap.CompressFormat.JPEG, quality, encoded)) return null;
                if (encoded.size() <= PHOTO_BYTES)
                    return Base64.getEncoder().encodeToString(encoded.toByteArray());
            }
            return null;
        } finally {
            if (thumbnail != decoded && thumbnail != cropped) thumbnail.recycle();
            if (cropped != decoded) cropped.recycle();
            decoded.recycle();
        }
    }

    private static Map<String, LinkedHashSet<String>> details(ContentResolver resolver, List<String> ids) {
        Map<String, LinkedHashSet<String>> result = new LinkedHashMap<>();
        if (ids.isEmpty()) return result;
        String contactId = ContactsContract.Data.CONTACT_ID;
        String mime = ContactsContract.Data.MIMETYPE;
        String arguments = String.join(",", Collections.nCopies(ids.size(), "?"));
        String[] bind = Arrays.copyOf(ids.toArray(new String[0]), ids.size() + 2);
        bind[ids.size()] = ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE;
        bind[ids.size() + 1] = ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE;
        try (Cursor details = resolver.query(ContactsContract.Data.CONTENT_URI,
                new String[]{contactId, ContactsContract.Data.DATA1},
                contactId + " IN (" + arguments + ") AND " + mime + " IN (?,?)", bind,
                contactId + " ASC, " + ContactsContract.Data.IS_SUPER_PRIMARY + " DESC")) {
            if (details == null) return result;
            for (int examined = 0; examined < 2000 && details.moveToNext(); examined++) {
                String value = FullAccessDataSources.bounded(details.getString(1), 160);
                if (value.isEmpty()) continue;
                LinkedHashSet<String> contact = result.computeIfAbsent(details.getString(0), ignored -> new LinkedHashSet<>());
                if (contact.size() < 6) contact.add(value);
            }
        }
        return result;
    }
}
