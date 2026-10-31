package com.subhub.app.journal;

import com.subhub.app.remote.RemoteProtocol;
import java.util.Set;
import org.json.JSONException;
import org.json.JSONObject;

/** An attachment describes private copied bytes, never a picker URI or filesystem path. */
public final class JournalAttachment {
    public static final long MAX_BYTES = 25L * 1024 * 1024;
    public static final int MAX_NAME = 120;
    public final String hash, name, mime;
    public final long length;

    public JournalAttachment(String hash, String name, String mime, long length) {
        if (hash == null || !hash.matches("[0-9a-f]{64}") || name == null || name.isEmpty()
                || !name.equals(cleanName(name)) || mime == null || mime.length() > 120
                || !mime.matches("[a-z0-9.+-]+/[a-z0-9.+-]+") || length < 1 || length > MAX_BYTES)
            throw new IllegalArgumentException("Invalid journal attachment");
        this.hash = hash; this.name = name; this.mime = mime; this.length = length;
    }

    public static String cleanName(String raw) {
        StringBuilder result = new StringBuilder();
        if (raw != null) raw.codePoints().filter(value -> !Character.isISOControl(value)
                && value != '/' && value != '\\' && !(value >= 0x202a && value <= 0x202e)
                && !(value >= 0x2066 && value <= 0x2069)).limit(MAX_NAME).forEach(result::appendCodePoint);
        String value = result.toString().trim();
        if (value.length() > MAX_NAME) value = value.substring(0, value.offsetByCodePoints(0, MAX_NAME / 2));
        return value;
    }

    public JSONObject json() throws JSONException {
        return new JSONObject().put("hash", hash).put("name", name).put("mime", mime).put("length", length);
    }

    public static JournalAttachment parse(JSONObject value) throws JSONException {
        JournalEntry.keys(value, Set.of("hash", "name", "mime", "length"));
        return new JournalAttachment(JournalEntry.text(value, "hash"), JournalEntry.text(value, "name"),
                JournalEntry.text(value, "mime"), RemoteProtocol.integer(value, "length"));
    }
}
