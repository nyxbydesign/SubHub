package com.subhub.app.tasks;

import com.subhub.app.remote.RemoteAssets;
import com.subhub.app.remote.RemoteProtocol;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.Set;

/** Only content hashes and media types cross the pairing boundary; device URIs never do. */
public final class TaskAttachment {
    private static final Set<String> TYPES = Set.of("image/jpeg", "image/png", "image/webp", "image/gif",
            "audio/mpeg", "audio/ogg", "audio/wav", "audio/mp4", "video/mp4", "video/webm");
    public final String hash, mime;
    public final long length;

    public TaskAttachment(String hash, String mime, long length) {
        if (hash == null || !hash.matches("[0-9a-f]{64}") || mime == null || !TYPES.contains(mime)
                || length <= 0 || length > RemoteAssets.MAX_ASSET_BYTES)
            throw new IllegalArgumentException("Choose an image, audio track or video of at most 25 MiB");
        this.hash = hash; this.mime = mime; this.length = length;
    }
    public JSONObject json() throws JSONException {
        return new JSONObject().put("hash", hash).put("mime", mime).put("length", length);
    }
    public boolean compatible(TaskAttachment other) {
        return other != null && hash.equals(other.hash) && length == other.length
                && (mime.equals(other.mime) || mp4(mime) && mp4(other.mime));
    }
    private static boolean mp4(String mime) { return "audio/mp4".equals(mime) || "video/mp4".equals(mime); }
    public static TaskAttachment parse(JSONObject value) throws JSONException {
        TaskJson.keys(value, Set.of("hash", "mime", "length"));
        return new TaskAttachment(TaskJson.text(value, "hash", 64), TaskJson.text(value, "mime", 32),
                RemoteProtocol.integer(value, "length"));
    }
}
