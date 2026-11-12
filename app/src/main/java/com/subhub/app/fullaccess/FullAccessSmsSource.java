package com.subhub.app.fullaccess;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.provider.Telephony;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Reads only the SMS provider Android exposes to an ordinarily permissioned application. */
final class FullAccessSmsSource {
    private FullAccessSmsSource() { }

    static JSONObject page(Context context, long before, int limit) throws JSONException {
        if (context.checkSelfPermission(Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED)
            return FullAccessDataSources.response("permission_required", new JSONArray());
        String id = Telephony.Sms._ID;
        JSONArray rows = new JSONArray();
        String last = null;
        boolean more = false;
        try (Cursor messages = context.getContentResolver().query(Telephony.Sms.CONTENT_URI,
                new String[]{id, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE},
                id + "<?", new String[]{Long.toString(before)}, id + " DESC")) {
            if (messages == null) return FullAccessDataSources.response("unavailable", new JSONArray());
            while (messages.moveToNext()) {
                if (rows.length() == limit) {
                    more = true;
                    break;
                }
                last = Long.toString(messages.getLong(0));
                String text = FullAccessSmsExport.safeText(messages.getString(2));
                JSONObject row = new JSONObject().put("id", last)
                        .put("title", FullAccessDataSources.bounded(messages.getString(1), 160))
                        .put("time", messages.getLong(3));
                if (text.isEmpty()) row.put("redacted", true);
                else row.put("detail", text);
                rows.put(row);
            }
        }
        JSONObject result = FullAccessDataSources.response("ok", rows);
        if (more && last != null) result.put("nextCursor", last);
        return result;
    }
}
