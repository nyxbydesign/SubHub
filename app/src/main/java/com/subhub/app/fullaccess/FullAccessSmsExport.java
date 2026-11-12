package com.subhub.app.fullaccess;

import android.content.Context;
import android.net.Uri;
import android.util.AtomicFile;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
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
    private static final Pattern SHORT_CODE = Pattern.compile("(?<![\\p{L}\\p{N}])\\d{4,8}(?![\\p{L}\\p{N}])");

    private FullAccessSmsExport() { }

    private static AtomicFile file(Context context) {
        return new AtomicFile(new File(context.getNoBackupFilesDir(), "full-access-sms-export.json"));
    }

    static boolean available(Context context) {
        return file(context).getBaseFile().exists();
    }

    static void clear(Context context) { file(context).delete(); }

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
        AtomicFile export = file(context);
        byte[] bytes = new JSONObject().put("epoch", epoch).put("lines", lines).toString().getBytes(StandardCharsets.UTF_8);
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
        AtomicFile export = file(context);
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
}
