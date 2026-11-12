package com.subhub.app.fullaccess;

import android.Manifest;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.provider.ContactsContract;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

final class FullAccessContactsSource {
    private FullAccessContactsSource() { }

    static JSONObject page(Context context, long after, int limit) throws JSONException {
        if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED)
            return FullAccessDataSources.response("permission_required", new JSONArray());
        ContentResolver resolver = context.getContentResolver();
        Map<String, String> names = new LinkedHashMap<>();
        String id = ContactsContract.Contacts._ID;
        String name = ContactsContract.Contacts.DISPLAY_NAME_PRIMARY;
        boolean more = false;
        try (Cursor contacts = resolver.query(ContactsContract.Contacts.CONTENT_URI, new String[]{id, name},
                id + ">?", new String[]{Long.toString(after)}, id + " ASC")) {
            if (contacts == null) return FullAccessDataSources.response("unavailable", new JSONArray());
            while (contacts.moveToNext()) {
                if (names.size() == limit) {
                    more = true;
                    break;
                }
                names.put(Long.toString(contacts.getLong(0)), FullAccessDataSources.bounded(contacts.getString(1), 160));
            }
        }

        Map<String, LinkedHashSet<String>> values = details(resolver, new ArrayList<>(names.keySet()));
        JSONArray rows = new JSONArray();
        String last = null;
        for (Map.Entry<String, String> entry : names.entrySet()) {
            last = entry.getKey();
            LinkedHashSet<String> contact = values.get(last);
            String detail = contact == null ? "" : FullAccessDataSources.bounded(String.join("\n", contact), 512);
            rows.put(new JSONObject().put("id", last).put("title", entry.getValue()).put("detail", detail));
        }
        JSONObject result = FullAccessDataSources.response("ok", rows);
        if (more && last != null) result.put("nextCursor", last);
        return result;
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
