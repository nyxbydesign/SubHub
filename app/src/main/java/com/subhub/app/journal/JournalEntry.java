package com.subhub.app.journal;

import com.subhub.app.remote.RemoteProtocol;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** A bounded entry is independent of its author role and its explicit sharing audience. */
public final class JournalEntry {
    public static final int MAX_TEXT = 4000, MAX_ATTACHMENTS = 5;
    public static final long MAX_RECORDING_BYTES = 10L * 1024 * 1024;
    public final String id, text;
    public final long created, updated;
    public final JournalAttachment audio;
    public final List<JournalAttachment> attachments;

    public JournalEntry(String id, long created, long updated, String text,
            JournalAttachment audio, List<JournalAttachment> attachments) {
        if (id == null || !id.matches("[0-9a-f]{32}") || created < 0 || updated < created
                || updated > 253402300799999L || text == null || text.length() > MAX_TEXT
                || !text.equals(cleanText(text)) || attachments == null || attachments.size() > MAX_ATTACHMENTS
                || audio != null && (!"audio/mp4".equals(audio.mime) || audio.length > MAX_RECORDING_BYTES)
                || text.isEmpty() && audio == null && attachments.isEmpty())
            throw new IllegalArgumentException("Invalid journal entry");
        Set<String> hashes = new HashSet<>();
        if (audio != null) hashes.add(audio.hash);
        for (JournalAttachment item : attachments)
            if (item == null || !hashes.add(item.hash)) throw new IllegalArgumentException("Duplicate attachment");
        this.id = id; this.created = created; this.updated = updated; this.text = text; this.audio = audio;
        this.attachments = Collections.unmodifiableList(new ArrayList<>(attachments));
    }

    public static String cleanText(String value) {
        StringBuilder result = new StringBuilder();
        value.codePoints().filter(c -> c == '\n' || c == '\t' || !Character.isISOControl(c))
                .forEach(result::appendCodePoint);
        return result.toString().trim();
    }

    public List<JournalAttachment> media() {
        List<JournalAttachment> result = new ArrayList<>(attachments);
        if (audio != null) result.add(audio);
        return result;
    }

    public JSONObject json() throws JSONException {
        JSONArray files = new JSONArray();
        for (JournalAttachment item : attachments) files.put(item.json());
        return new JSONObject().put("schema", 1).put("id", id).put("created", created).put("updated", updated)
                .put("text", text).put("audio", audio == null ? JSONObject.NULL : audio.json()).put("attachments", files);
    }

    public static JournalEntry parse(JSONObject value) throws JSONException {
        keys(value, Set.of("schema", "id", "created", "updated", "text", "audio", "attachments"));
        if (RemoteProtocol.integer(value, "schema") != 1) throw new JSONException("Unsupported journal entry");
        JSONArray files = value.getJSONArray("attachments");
        if (files.length() > MAX_ATTACHMENTS) throw new JSONException("Too many attachments");
        List<JournalAttachment> attachments = new ArrayList<>();
        for (int i = 0; i < files.length(); i++) attachments.add(JournalAttachment.parse(files.getJSONObject(i)));
        return new JournalEntry(text(value, "id"), RemoteProtocol.integer(value, "created"),
                RemoteProtocol.integer(value, "updated"), text(value, "text"),
                value.isNull("audio") ? null : JournalAttachment.parse(value.getJSONObject("audio")), attachments);
    }

    static void keys(JSONObject value, Set<String> allowed) throws JSONException {
        if (value.length() != allowed.size()) throw new JSONException("Invalid journal fields");
        for (Iterator<String> names = value.keys(); names.hasNext();)
            if (!allowed.contains(names.next())) throw new JSONException("Invalid journal field");
    }

    static String text(JSONObject value, String key) throws JSONException {
        Object raw = value.get(key);
        if (!(raw instanceof String)) throw new JSONException("Invalid journal text");
        return (String) raw;
    }
}
