package com.subhub.app.atmosphere;

import android.content.ClipDescription;
import android.util.AtomicFile;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Explicit pins in credential-protected storage excluded from backup. */
final class GagClipboardPins {
    private static final int MAX_IMAGES = 8;
    private static final String IMAGE_PREFIX = "gag_clipboard_pin_";
    private static final Pattern IMAGE_ID = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern IMAGE_FILE = Pattern.compile(IMAGE_PREFIX + "([0-9a-f]{64})(?:\\.(?:new|bak))?");
    private final File directory;
    private final AtomicFile file;

    GagClipboardPins(File directory) {
        this.directory = directory;
        file = new AtomicFile(new File(directory, "gag_clipboard_pins.json"));
    }

    List<GagClipboard.Entry> read() {
        try {
            JSONArray values = new JSONArray(new String(file.readFully(), StandardCharsets.UTF_8));
            List<GagClipboard.Entry> pins = new ArrayList<>();
            Set<String> textPins = new HashSet<>();
            Set<String> images = new HashSet<>();
            for (int index = 0; index < values.length(); index++) {
                Object value = values.opt(index);
                if (value instanceof String text && GagClipboard.validText(text) && textPins.add(text)) {
                    pins.add(GagClipboard.pinned(text, null));
                } else if (value instanceof JSONObject record && images.size() < MAX_IMAGES) {
                    String id = record.optString("image");
                    if (images.contains(id)) continue;
                    GagClipboardImage image = readImage(id, record.optString("mime"));
                    if (image != null && images.add(id)) pins.add(GagClipboard.pinned(null, image));
                }
            }
            return List.copyOf(pins);
        } catch (FileNotFoundException missing) {
            return List.of();
        } catch (IOException | JSONException unavailable) {
            return null;
        }
    }

    boolean write(List<GagClipboard.Entry> pins) {
        if (pins.stream().filter(pin -> pin.image() != null).count() > MAX_IMAGES) return false;
        Set<String> previous = referencedImages();
        if (previous == null) return false;
        discardUnreferencedImages(previous);
        JSONArray entries = new JSONArray();
        try {
            for (GagClipboard.Entry pin : pins) {
                if (pin.image() == null) {
                    entries.put(pin.text());
                    continue;
                }
                GagClipboardImage image = pin.image();
                byte[] bytes = image.bytes();
                if (bytes.length == 0 || bytes.length > GagClipboardImage.MAX_BYTES
                        || GagClipboardImage.extension(image.mime()) == null) return false;
                String id = digest(bytes);
                File contents = imageFile(id);
                if ((!contents.isFile() || contents.length() != bytes.length)
                        && !writeAtomic(new AtomicFile(contents), bytes)) return false;
                entries.put(new JSONObject().put("image", id).put("mime", image.mime()));
            }
            return writeAtomic(file, entries.toString().getBytes(StandardCharsets.UTF_8));
        } catch (JSONException unavailable) {
            return false;
        } finally {
            // A failed metadata publication can leave a prepared image. Re-read the
            // current index before deciding which bytes can be discarded.
            Set<String> current = referencedImages();
            if (current != null) discardUnreferencedImages(current);
        }
    }

    private Set<String> referencedImages() {
        try {
            JSONArray entries = new JSONArray(new String(file.readFully(), StandardCharsets.UTF_8));
            Set<String> images = new HashSet<>();
            for (int index = 0; index < entries.length(); index++) {
                if (entries.opt(index) instanceof JSONObject record) {
                    String id = record.optString("image");
                    if (IMAGE_ID.matcher(id).matches()) images.add(id);
                }
            }
            return images;
        } catch (FileNotFoundException missing) {
            File base = file.getBaseFile();
            if (base.exists() || new File(base.getPath() + ".bak").exists()) return null;
            return Set.of();
        } catch (IOException | JSONException unavailable) {
            return null;
        }
    }

    private GagClipboardImage readImage(String id, String mime) {
        if (!IMAGE_ID.matcher(id).matches() || GagClipboardImage.extension(mime) == null) return null;
        File contents = imageFile(id);
        if (!contents.isFile() || contents.length() > GagClipboardImage.MAX_BYTES) return null;
        try {
            byte[] bytes = new AtomicFile(contents).readFully();
            if (bytes.length > GagClipboardImage.MAX_BYTES || !id.equals(digest(bytes))) return null;
            return GagClipboardImage.decode(bytes, new ClipDescription("", new String[]{mime}));
        } catch (IOException | RuntimeException unavailable) {
            return null;
        }
    }

    private File imageFile(String id) { return new File(directory, IMAGE_PREFIX + id); }

    private void discardUnreferencedImages(Set<String> images) {
        File[] candidates = directory.listFiles((dir, name) -> name.startsWith(IMAGE_PREFIX));
        if (candidates == null) return;
        for (File candidate : candidates) {
            var match = IMAGE_FILE.matcher(candidate.getName());
            if (match.matches() && !images.contains(match.group(1))) new AtomicFile(imageFile(match.group(1))).delete();
        }
    }

    private static boolean writeAtomic(AtomicFile destination, byte[] bytes) {
        FileOutputStream stream = null;
        try {
            stream = destination.startWrite();
            stream.write(bytes);
            destination.finishWrite(stream);
            stream = null;
            return MessageDigest.isEqual(bytes, destination.readFully());
        } catch (IOException unavailable) {
            if (stream != null) destination.failWrite(stream);
            return false;
        }
    }

    private static String digest(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                hex.append(Character.forDigit((value & 0xff) >>> 4, 16));
                hex.append(Character.forDigit(value & 0xf, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
