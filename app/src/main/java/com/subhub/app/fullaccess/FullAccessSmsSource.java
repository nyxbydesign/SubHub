package com.subhub.app.fullaccess;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.provider.ContactsContract;
import android.provider.Telephony;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Reads only the SMS provider Android exposes to an ordinarily permissioned application. */
final class FullAccessSmsSource {
    private static final int MAX_PAGE_SIZE = 48;
    private static final int MAX_CONVERSATION_SCAN = 32_768;
    private static final String ID = Telephony.Sms._ID;
    private static final String THREAD = Telephony.Sms.THREAD_ID;
    private static final String DATE = Telephony.Sms.DATE;
    private static final String TYPE = Telephony.Sms.TYPE;
    private static final String SENT_OR_RECEIVED = TYPE + " IN (?,?)";
    private static final String SORT = DATE + " DESC, " + ID + " DESC";
    private static final String BEFORE = "(" + DATE + "<? OR (" + DATE + "=? AND " + ID + "<?))";
    private static final String INBOX = Integer.toString(Telephony.Sms.MESSAGE_TYPE_INBOX);
    private static final String SENT = Integer.toString(Telephony.Sms.MESSAGE_TYPE_SENT);
    private static final String[] METADATA = {ID, THREAD, DATE};
    private static final String[] MESSAGE = {ID, THREAD, DATE, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, TYPE};

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
                String text = FullAccessSmsExport.messageText(messages.getString(2));
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

    static JSONObject conversations(Context context, String cursor, int limit, boolean contactsAllowed)
            throws JSONException {
        if (!canRead(context)) return empty("permission_required");
        ConversationPage selected = new ConversationPage(conversationCursor(cursor), limit);
        int scanned = 0;
        boolean partial = false;
        // Deduplicate the newer prefix too, so an older message from a previously listed
        // conversation cannot surface that conversation again on the next page.
        try (Cursor metadata = context.getContentResolver().query(Telephony.Sms.CONTENT_URI,
                METADATA, SENT_OR_RECEIVED, new String[]{INBOX, SENT}, SORT)) {
            if (metadata == null) return empty("unavailable");
            while (metadata.moveToNext()) {
                if (scanned++ == MAX_CONVERSATION_SCAN) {
                    partial = true;
                    break;
                }
                if (!selected.offer(metadata.getLong(1), metadata.getLong(2), metadata.getLong(0))) break;
            }
        }
        JSONArray rows = conversationRows(context, selected.items, contactsAllowed);
        if (!canRead(context)) return empty("permission_required");
        JSONObject result = FullAccessDataSources.response("ok", rows);
        if (selected.more) result.put("nextCursor", "list-" + selected.last().suffix());
        if (partial) result.put("partial", true);
        return result;
    }

    private static JSONArray conversationRows(Context context, List<Conversation> items, boolean contactsAllowed)
            throws JSONException {
        JSONArray rows = new JSONArray();
        if (items.isEmpty()) return rows;
        Map<Long, Conversation> wanted = new HashMap<>();
        String[] args = new String[items.size() + 2];
        for (int index = 0; index < items.size(); index++) {
            Conversation item = items.get(index);
            wanted.put(item.latest.id, item);
            args[index] = Long.toString(item.latest.id);
        }
        args[items.size()] = INBOX;
        args[items.size() + 1] = SENT;
        String placeholders = String.join(",", Collections.nCopies(items.size(), "?"));
        Map<Long, JSONObject> matches = new HashMap<>();
        Map<String, String> titles = new HashMap<>();
        try (Cursor source = context.getContentResolver().query(Telephony.Sms.CONTENT_URI, MESSAGE,
                ID + " IN (" + placeholders + ") AND " + SENT_OR_RECEIVED, args, null)) {
            if (source == null) return rows;
            while (source.moveToNext()) {
                long id = source.getLong(0);
                Conversation item = wanted.get(id);
                if (item == null || source.getLong(1) != item.thread || source.getLong(2) != item.latest.time) continue;
                String address = source.getString(3);
                String name = titles.computeIfAbsent(address, value -> title(context, value, contactsAllowed));
                matches.put(id, message(source).put("id", "sms-" + item.thread).put("title", name));
            }
        }
        for (Conversation item : items) {
            JSONObject match = matches.get(item.latest.id);
            if (match != null) rows.put(match);
        }
        return rows;
    }

    static JSONObject thread(Context context, String node, String cursor, int limit, boolean contactsAllowed)
            throws JSONException {
        if (!canRead(context)) return empty("permission_required");
        long thread = threadId(node);
        Position before = threadCursor(thread, cursor);
        String selection = THREAD + "=? AND " + SENT_OR_RECEIVED;
        String threadArg = Long.toString(thread);
        String[] args = {threadArg, INBOX, SENT};
        if (before != null) {
            selection += " AND " + BEFORE;
            String time = Long.toString(before.time);
            args = new String[]{threadArg, INBOX, SENT, time, time, Long.toString(before.id)};
        }

        JSONArray rows = new JSONArray();
        Position last = null;
        String title = "";
        boolean more = false;
        int count = pageSize(limit);
        try (Cursor messages = context.getContentResolver().query(Telephony.Sms.CONTENT_URI, MESSAGE,
                selection, args, SORT)) {
            if (messages == null) return empty("unavailable");
            while (messages.moveToNext()) {
                if (messages.getLong(1) != thread) continue;
                if (rows.length() == count) {
                    more = true;
                    break;
                }
                if (last == null) title = title(context, messages.getString(3), contactsAllowed);
                last = new Position(messages.getLong(2), messages.getLong(0));
                rows.put(message(messages));
            }
        }
        if (!canRead(context)) return empty("permission_required");
        JSONObject result = FullAccessDataSources.response("ok", rows).put("title", title);
        if (more && last != null) result.put("nextCursor", node + "-" + last.suffix());
        return result;
    }

    private static JSONObject message(Cursor row) throws JSONException {
        String body = FullAccessSmsExport.messageText(row.getString(4));
        JSONObject result = new JSONObject().put("id", Long.toString(row.getLong(0)))
                .put("time", row.getLong(2))
                .put("outgoing", row.getInt(5) == Telephony.Sms.MESSAGE_TYPE_SENT);
        return body.isEmpty() ? result.put("redacted", true) : result.put("detail", body);
    }

    private static String title(Context context, String address, boolean contactsAllowed) {
        String display = FullAccessDataSources.bounded(address, 160);
        if (!contactsAllowed || display.isEmpty() || !canReadContacts(context)) return display;
        Uri uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(display));
        try (Cursor match = context.getContentResolver().query(uri,
                new String[]{ContactsContract.PhoneLookup.DISPLAY_NAME}, null, null, null)) {
            if (match != null && match.moveToFirst() && canReadContacts(context)) {
                String contact = FullAccessDataSources.bounded(match.getString(0), 160);
                if (!contact.isEmpty()) return contact;
            }
        } catch (RuntimeException unavailable) {
            // Contacts are optional. The SMS address remains available under SMS permission.
        }
        return display;
    }

    private static JSONObject empty(String status) throws JSONException {
        return FullAccessDataSources.response(status, new JSONArray());
    }

    private static boolean canRead(Context context) {
        return context.checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED;
    }

    private static boolean canReadContacts(Context context) {
        return context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED;
    }

    static int pageSize(int limit) {
        if (limit < 1 || limit > MAX_PAGE_SIZE) throw new IllegalArgumentException("Invalid SMS page size");
        return limit;
    }

    static long threadId(String node) {
        long thread = number(afterPrefix(node, "sms-"));
        if (thread == 0) throw new IllegalArgumentException("Invalid SMS thread");
        return thread;
    }

    static Position conversationCursor(String cursor) {
        return position(cursor, "list-");
    }

    static Position threadCursor(long thread, String cursor) {
        return position(cursor, "sms-" + thread + "-");
    }

    private static Position position(String token, String prefix) {
        if (token == null || token.isEmpty()) return null;
        String value = afterPrefix(token, prefix);
        int separator = value.indexOf('-');
        if (separator < 0) throw new IllegalArgumentException("Invalid SMS cursor");
        return new Position(number(value.substring(0, separator)), number(value.substring(separator + 1)));
    }

    static String afterPrefix(String token, String prefix) {
        if (token == null || token.length() > 80 || !token.startsWith(prefix))
            throw new IllegalArgumentException("Invalid SMS identifier");
        return token.substring(prefix.length());
    }

    static long number(String value) {
        if (value.isEmpty() || value.length() > 19 || (value.length() > 1 && value.charAt(0) == '0'))
            throw new IllegalArgumentException("Invalid SMS number");
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (character < '0' || character > '9') throw new IllegalArgumentException("Invalid SMS number");
        }
        return Long.parseLong(value);
    }

    record Position(long time, long id) {
        boolean olderThan(Position other) {
            return time < other.time || time == other.time && id < other.id;
        }

        String suffix() { return time + "-" + id; }
    }

    record Conversation(long thread, Position latest) { }

    static final class ConversationPage {
        final List<Conversation> items = new ArrayList<>();
        final Set<Long> seen = new HashSet<>();
        final Position before;
        final int limit;
        boolean more;

        ConversationPage(Position before, int limit) {
            this.before = before;
            this.limit = pageSize(limit);
        }

        boolean offer(long thread, long time, long id) {
            if (thread <= 0 || time < 0 || id < 0 || !seen.add(thread)) return true;
            Position latest = new Position(time, id);
            if (before != null && !latest.olderThan(before)) return true;
            if (items.size() == limit) {
                more = true;
                return false;
            }
            items.add(new Conversation(thread, latest));
            return true;
        }

        Position last() { return items.get(items.size() - 1).latest; }
    }
}
