package com.subhub.app.fullaccess;

import android.content.Context;
import android.net.Uri;
import android.util.AtomicFile;
import com.subhub.app.R;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** An intentionally selected transcript. No SMS provider or permission is used by this adapter. */
final class FullAccessSmsExport {
    private static final int MAX_BYTES = 128 * 1024;
    private static final int MAX_LINES = 1000;
    private static final Pattern SENSITIVE_LINE = Pattern.compile(
            "\\b(?:otp|one[- ]time|passcode|passphrase|password|verification|security code|reset code"
                    + "|recovery code|access token|authentication)\\b");
    private static final Pattern SHORT_CODE = Pattern.compile("(?<![\\p{L}\\p{N}])\\p{Nd}{4,8}(?![\\p{L}\\p{N}])");

    private FullAccessSmsExport() { }

    private static AtomicFile file(Context context, boolean demo) {
        String name = "full-access-sms-export.json";
        return new AtomicFile(new File(context.getNoBackupFilesDir(), demo ? "demo-" + name : name));
    }

    static boolean available(Context context) { return file(context, false).getBaseFile().exists(); }

    static void clear(Context context) { file(context, false).delete(); }

    static boolean demoAvailable(Context context) { return file(context, true).getBaseFile().exists(); }

    static void clearDemo(Context context) { file(context, true).delete(); }

    static JSONArray readSelection(Context context, Uri selected) throws IOException {
        if (selected == null || !"content".equals(selected.getScheme())) throw new IOException("SMS export unavailable");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (InputStream source = context.getContentResolver().openInputStream(selected)) {
            if (source == null) throw new IOException("SMS export unavailable");
            byte[] chunk = new byte[8192];
            for (int count; (count = source.read(chunk)) != -1; ) {
                if (count > MAX_BYTES - bytes.size()) throw new IOException("SMS export too large");
                bytes.write(chunk, 0, count);
            }
        }
        String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray())).toString();
        JSONArray lines = new JSONArray();
        for (String line : text.split("\\R")) {
            String textLine = safeText(line);
            if (textLine.isEmpty()) continue;
            if (lines.length() == MAX_LINES) throw new IOException("SMS export too large");
            lines.put(textLine);
        }
        return lines;
    }

    static String safeText(String body) {
        if (body == null) return "";
        String clean = body.trim();
        if (clean.isEmpty() || SENSITIVE_LINE.matcher(clean.toLowerCase(Locale.ROOT)).find()) return "";
        return FullAccessDataSources.bounded(SHORT_CODE.matcher(clean).replaceAll("••••"), 1000);
    }

    static void store(Context context, long epoch, JSONArray lines) throws IOException, JSONException {
        store(context, epoch, lines, false);
    }

    static void store(Context context, long epoch, JSONArray lines, boolean demo) throws IOException, JSONException {
        AtomicFile export = file(context, demo);
        byte[] bytes = new JSONObject().put("epoch", epoch).put("lines", lines)
                .put("revision", UUID.randomUUID().toString()).toString().getBytes(StandardCharsets.UTF_8);
        FileOutputStream output = export.startWrite();
        try {
            output.write(bytes);
            export.finishWrite(output);
        } catch (IOException | RuntimeException failed) {
            export.failWrite(output);
            throw failed;
        }
    }

    static JSONObject page(Context context, long epoch, int offset, int limit) throws IOException, JSONException {
        return page(context, epoch, offset, limit, false);
    }

    static JSONObject page(Context context, long epoch, int offset, int limit, boolean demo)
            throws IOException, JSONException {
        AtomicFile export = file(context, demo);
        if (!export.getBaseFile().exists()) return FullAccessDataSources.response("unavailable", new JSONArray());
        JSONObject stored = new JSONObject(new String(export.readFully(), StandardCharsets.UTF_8));
        if (stored.getLong("epoch") != epoch) return FullAccessDataSources.response("unavailable", new JSONArray());
        JSONArray lines = stored.getJSONArray("lines");
        JSONArray rows = new JSONArray();
        int end = offset >= lines.length() ? lines.length() : offset + Math.min(limit, lines.length() - offset);
        for (int index = offset; index < end; index++) {
            rows.put(new JSONObject().put("id", Integer.toString(index)).put("title", lines.getString(index)));
        }
        JSONObject result = FullAccessDataSources.response("ok", rows);
        if (end < lines.length()) result.put("nextCursor", Integer.toString(end));
        return result;
    }

    static JSONObject conversations(Context context, long epoch, String cursor, int limit, boolean demo)
            throws IOException, JSONException {
        FullAccessSmsSource.pageSize(limit);
        if (cursor != null && !cursor.isEmpty()) throw new IllegalArgumentException("Invalid SMS export cursor");
        Transcript transcript = transcript(context, epoch, demo);
        if (transcript == null) return FullAccessDataSources.response("unavailable", new JSONArray());
        JSONArray rows = new JSONArray();
        if (transcript.lines.length() > 0) {
            JSONObject summary = line(transcript.lines, transcript.lines.length() - 1);
            rows.put(summary.put("id", transcript.node).put("title", context.getString(R.string.full_access_sms_export_title)));
        }
        return FullAccessDataSources.response("ok", rows);
    }

    static JSONObject thread(Context context, long epoch, String node, String cursor, int limit, boolean demo)
            throws IOException, JSONException {
        int count = FullAccessSmsSource.pageSize(limit);
        Transcript transcript = transcript(context, epoch, demo);
        if (transcript == null || !transcript.node.equals(node))
            return FullAccessDataSources.response("unavailable", new JSONArray());
        int end = exportEnd(node, cursor, transcript.lines.length());
        int first = Math.max(0, end - count);
        JSONArray rows = new JSONArray();
        // Plain-text exports do not describe senders or dates. Preserve file order without
        // fabricating those fields, and follow the viewer's newest-first response convention.
        for (int index = end - 1; index >= first; index--) rows.put(line(transcript.lines, index));
        JSONObject result = FullAccessDataSources.response("ok", rows)
                .put("title", context.getString(R.string.full_access_sms_export_title));
        if (first > 0) result.put("nextCursor", node + "-" + first);
        return result;
    }

    static int exportEnd(String node, String cursor, int length) {
        if (cursor == null || cursor.isEmpty()) return length;
        long end = FullAccessSmsSource.number(FullAccessSmsSource.afterPrefix(cursor, node + "-"));
        if (end > length) throw new IllegalArgumentException("Invalid SMS export cursor");
        return (int) end;
    }

    private static JSONObject line(JSONArray lines, int index) throws JSONException {
        String body = safeText(lines.getString(index));
        JSONObject item = new JSONObject().put("id", Integer.toString(index));
        return body.isEmpty() ? item.put("redacted", true) : item.put("detail", body);
    }

    private static Transcript transcript(Context context, long epoch, boolean demo) throws IOException, JSONException {
        AtomicFile export = file(context, demo);
        if (!export.getBaseFile().exists()) return null;
        byte[] bytes = export.readFully();
        JSONObject stored = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
        if (stored.getLong("epoch") != epoch) return null;
        JSONArray lines = stored.getJSONArray("lines");
        if (lines.length() > MAX_LINES) return null;
        return new Transcript(node(stored.optString("revision"), bytes, demo), lines);
    }

    static String node(String revision, byte[] bytes, boolean demo) {
        String prefix = demo ? "demo-export-" : "export-";
        if (!revision.isEmpty()) return prefix + UUID.fromString(revision).toString();
        // Older local exports have no revision. Bind their cursor to the existing file
        // so replacing the selection cannot silently reopen an old conversation.
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            return prefix + UUID.nameUUIDFromBytes(digest).toString();
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SMS export version unavailable", unavailable);
        }
    }

    private record Transcript(String node, JSONArray lines) { }
}
