package com.subhub.app.atmosphere;

import android.util.AtomicFile;
import org.json.JSONArray;
import org.json.JSONException;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;

/** Saves only explicitly pinned text in credential-protected storage excluded from backup. */
final class GagClipboardPins {
    private final AtomicFile file;

    GagClipboardPins(File directory) {
        file = new AtomicFile(new File(directory, "gag_clipboard_pins.json"));
    }

    List<String> read() {
        try {
            JSONArray values = new JSONArray(new String(file.readFully(), StandardCharsets.UTF_8));
            var pins = new LinkedHashSet<String>();
            for (int index = 0; index < values.length(); index++) {
                if (values.opt(index) instanceof String text && GagClipboard.validText(text)) pins.add(text);
            }
            return List.copyOf(pins);
        } catch (FileNotFoundException missing) {
            return List.of();
        } catch (IOException | JSONException unavailable) {
            return null;
        }
    }

    boolean write(List<String> pins) {
        FileOutputStream stream = null;
        try {
            stream = file.startWrite();
            stream.write(new JSONArray(pins).toString().getBytes(StandardCharsets.UTF_8));
            file.finishWrite(stream);
            return pins.equals(read());
        } catch (IOException unavailable) {
            if (stream != null) file.failWrite(stream);
            return false;
        }
    }
}
