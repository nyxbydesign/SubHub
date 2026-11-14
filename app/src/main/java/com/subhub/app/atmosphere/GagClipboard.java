package com.subhub.app.atmosphere;

import android.content.ClipData;
import android.content.ClipDescription;

import java.util.ArrayList;
import java.util.List;

/** Bounded, session-only clipboard data. No editor text, URI coercion or persistence. */
final class GagClipboard {
    static final int MAX_TEXT_LENGTH = 2048;
    private static final int MAX_ITEMS = 8;
    static final long LIFETIME_MS = 120_000L;
    private static final String SENSITIVE = "android.content.extra.IS_SENSITIVE";
    private final ArrayList<Entry> history = new ArrayList<>();

    record Entry(String text, long copiedAt, long expiresAt) {}

    static boolean isPlainText(ClipDescription description) {
        return description != null && description.getMimeTypeCount() == 1
                && ClipDescription.MIMETYPE_TEXT_PLAIN.equals(description.getMimeType(0))
                && (description.getExtras() == null || !description.getExtras().getBoolean(SENSITIVE));
    }

    static String plainText(ClipData clip) {
        if (clip == null || !isPlainText(clip.getDescription()) || clip.getItemCount() != 1) return null;
        ClipData.Item item = clip.getItemAt(0);
        if (item.getUri() != null || item.getIntent() != null || item.getHtmlText() != null) return null;
        CharSequence text = item.getText();
        return text == null || text.length() == 0 || text.length() > MAX_TEXT_LENGTH ? null : text.toString();
    }

    void remember(String text, long copiedAt, long wallNow, long elapsedNow) {
        removeExpired(elapsedNow);
        long age = wallNow - copiedAt;
        if (text == null || text.isBlank() || text.length() > MAX_TEXT_LENGTH
                || copiedAt <= 0 || age < 0 || age >= LIFETIME_MS) return;
        for (Entry entry : history)
            if (entry.text().equals(text) && entry.copiedAt() >= copiedAt) return;
        history.removeIf(entry -> entry.text().equals(text));
        history.add(0, new Entry(text, copiedAt, elapsedNow + LIFETIME_MS - age));
        if (history.size() > MAX_ITEMS) history.remove(history.size() - 1);
    }

    List<Entry> entries(long now) {
        removeExpired(now);
        return List.copyOf(history);
    }

    void clear() { history.clear(); }

    private void removeExpired(long now) {
        history.removeIf(entry -> now >= entry.expiresAt());
    }

    static String applyWords(String value, java.util.function.UnaryOperator<String> complete) {
        StringBuilder result = new StringBuilder();
        for (int start = 0; start < value.length(); ) {
            int point = value.codePointAt(start);
            int end = start + Character.charCount(point);
            if (!GagRules.wordCharacter(point)) {
                result.append(value, start, end);
            } else {
                while (end < value.length()) {
                    int next = value.codePointAt(end);
                    if (!GagRules.wordCharacter(next) && !GagRules.apostrophe(next)) break;
                    end += Character.charCount(next);
                }
                result.append(complete.apply(value.substring(start, end)));
            }
            start = end;
        }
        return result.toString();
    }
}
