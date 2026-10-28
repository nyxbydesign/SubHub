package com.subhub.app.tasks;

import android.content.Context;
import android.net.Uri;
import com.subhub.app.remote.RemoteAssets;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Picker bytes are copied into private, relationship-scoped storage without retaining URI grants. */
public final class TaskMedia {
    private TaskMedia() { }
    public static TaskAttachment importMedia(Context context, Uri uri, RemoteAssets assets) throws Exception {
        if (uri == null || !"content".equals(uri.getScheme())) throw new IOException("Choose media from the document picker");
        String mime = context.getContentResolver().getType(uri);
        if ("audio/x-wav".equals(mime)) mime = "audio/wav";
        // Validate the declared type before opening an arbitrary provider stream.
        new TaskAttachment("0000000000000000000000000000000000000000000000000000000000000000", mime, 1);
        File temp = com.subhub.app.storage.DisposableCache.temporary(context.getCacheDir(), "task-import-");
        try {
            long length = 0;
            try (InputStream input = context.getContentResolver().openInputStream(uri);
                    OutputStream output = new FileOutputStream(temp)) {
                if (input == null) throw new IOException("Attachment unavailable");
                byte[] buffer = new byte[8192]; int read;
                while ((read = input.read(buffer)) != -1) {
                    length += read;
                    if (length > RemoteAssets.MAX_ASSET_BYTES) throw new IOException("Attachment exceeds 25 MiB");
                    output.write(buffer, 0, read);
                }
            }
            requireHeader(temp, mime);
            TaskAttachment result = new TaskAttachment(RemoteAssets.digest(temp), mime, length);
            assets.reserve(result.hash, result.length);
            if (!assets.isComplete(result.hash)) {
                long offset = assets.offset(result.hash);
                try (RandomAccessFile input = new RandomAccessFile(temp, "r")) {
                    input.seek(offset);
                    while (offset < length) {
                        byte[] bytes = new byte[(int) Math.min(RemoteAssets.CHUNK_BYTES, length - offset)];
                        input.readFully(bytes); offset = assets.append(result.hash, offset, bytes);
                    }
                }
                assets.finish(result.hash);
            }
            return result;
        } finally { com.subhub.app.storage.DisposableCache.releaseTemporary(temp); }
    }
    static void requireHeader(File file, String mime) throws IOException {
        byte[] bytes = new byte[16]; int n;
        try (InputStream input = new FileInputStream(file)) { n = input.read(bytes); }
        if (n < 4) throw new IOException("Invalid media file");
        String head = new String(bytes, StandardCharsets.ISO_8859_1);
        boolean valid;
        switch (mime) {
            case "image/jpeg": valid = (bytes[0] & 255) == 255 && (bytes[1] & 255) == 216 && (bytes[2] & 255) == 255; break;
            case "image/png": valid = (bytes[0] & 255) == 137 && head.startsWith("\u0089PNG\r\n\u001a\n"); break;
            case "image/webp": valid = head.startsWith("RIFF") && head.substring(8, 12).equals("WEBP"); break;
            case "image/gif": valid = head.startsWith("GIF87a") || head.startsWith("GIF89a"); break;
            case "audio/wav": valid = head.startsWith("RIFF") && head.substring(8, 12).equals("WAVE"); break;
            case "audio/ogg": valid = head.startsWith("OggS"); break;
            case "audio/mpeg": valid = head.startsWith("ID3") || ((bytes[0] & 255) == 255 && (bytes[1] & 224) == 224); break;
            case "audio/mp4": case "video/mp4": valid = head.substring(4, 8).equals("ftyp"); break;
            case "video/webm": valid = (bytes[0] & 255) == 26 && (bytes[1] & 255) == 69 && (bytes[2] & 255) == 223 && (bytes[3] & 255) == 163; break;
            default: valid = false;
        }
        if (!valid) throw new IOException("File contents do not match the media type");
    }
    public static Map<String, TaskAttachment> references(TaskEngine engine) throws JSONException {
        Map<String, TaskAttachment> result = new LinkedHashMap<>();
        for (TaskDefinition task : engine.definitions()) if (task.attachment != null) put(result, task.attachment);
        JSONArray actions = engine.json().getJSONArray("actions");
        for (int i = 0; i < actions.length(); i++) {
            TaskAction action = TaskAction.parse(actions.getJSONObject(i));
            for (TaskAttachment attachment : action.attachments) put(result, attachment);
        }
        return result;
    }
    /** A peer-provided hash cannot grant permission to serve a private draft from this device. */
    public static Map<String, TaskAttachment> published(TaskEngine engine, boolean authorDom) throws JSONException {
        Map<String, TaskAttachment> result = new LinkedHashMap<>();
        JSONArray actions = engine.json().getJSONArray("actions");
        for (int i = 0; i < actions.length(); i++) {
            TaskAction action = TaskAction.parse(actions.getJSONObject(i));
            if (action.domAction() != authorDom) continue;
            if (action.definition != null && action.definition.attachment != null) put(result, action.definition.attachment);
            for (TaskAttachment attachment : action.attachments) put(result, attachment);
        }
        return result;
    }
    private static void put(Map<String, TaskAttachment> result, TaskAttachment attachment) {
        TaskAttachment previous = result.put(attachment.hash, attachment);
        if (previous != null && !previous.compatible(attachment))
            throw new SecurityException("Conflicting task attachment");
    }
}
