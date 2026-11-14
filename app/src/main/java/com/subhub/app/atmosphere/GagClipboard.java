package com.subhub.app.atmosphere;

import android.content.ClipData;
import android.content.ClipDescription;
import android.net.Uri;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

import java.util.ArrayList;
import java.util.List;

/** Bounded IME-process history with a separate lease on the visible editor. */
final class GagClipboard {
    static final int MAX_TEXT_LENGTH = 2048;
    private static final int MAX_ITEMS = 8;
    static final long LIFETIME_MS = 6 * 60 * 60 * 1_000L;
    private static final String SENSITIVE = "android.content.extra.IS_SENSITIVE";
    private final ArrayList<Entry> history = new ArrayList<>();
    private InputConnection attachedEditor;
    private long clearedThrough;

    record Entry(String text, GagClipboardImage image, long copiedAt, long expiresAt) {}

    boolean attach(InputConnection editor, EditorInfo info, String keyboardPackage) {
        attachedEditor = GagEditorPolicy.allowsReplacement(info, keyboardPackage) ? editor : null;
        return attachedEditor != null;
    }

    void detach() { attachedEditor = null; }

    boolean allows(InputConnection editor) { return editor != null && editor == attachedEditor; }

    boolean eligibleTimestamp(long copiedAt) { return copiedAt > clearedThrough; }

    static boolean isPlainText(ClipDescription description) {
        return description != null && description.getMimeTypeCount() == 1
                && ClipDescription.MIMETYPE_TEXT_PLAIN.equals(description.getMimeType(0))
                && !sensitive(description);
    }

    private static boolean sensitive(ClipDescription description) {
        return description.getExtras() != null && description.getExtras().getBoolean(SENSITIVE);
    }

    static boolean accepts(ClipDescription description) {
        if (description == null || sensitive(description)) return false;
        if (isPlainText(description)) return true;
        return description.hasMimeType("image/*");
    }

    static Uri imageUri(ClipData clip) {
        if (clip == null || !accepts(clip.getDescription()) || isPlainText(clip.getDescription())
                || clip.getItemCount() != 1) return null;
        ClipData.Item item = clip.getItemAt(0);
        Uri uri = item.getUri();
        return uri != null && "content".equals(uri.getScheme()) && item.getIntent() == null
                && item.getText() == null && item.getHtmlText() == null ? uri : null;
    }

    static String plainText(ClipData clip) {
        if (clip == null || !isPlainText(clip.getDescription()) || clip.getItemCount() != 1) return null;
        ClipData.Item item = clip.getItemAt(0);
        if (item.getUri() != null || item.getIntent() != null || item.getHtmlText() != null) return null;
        CharSequence text = item.getText();
        return text == null || text.length() == 0 || text.length() > MAX_TEXT_LENGTH ? null : text.toString();
    }

    void remember(String text, long copiedAt, long wallNow, long elapsedNow) {
        if (text == null || text.isBlank() || text.length() > MAX_TEXT_LENGTH) return;
        remember(text, null, copiedAt, wallNow, elapsedNow);
    }

    void remember(GagClipboardImage image, long copiedAt, long wallNow, long elapsedNow) {
        if (image != null) remember(null, image, copiedAt, wallNow, elapsedNow);
    }

    private void remember(String text, GagClipboardImage image, long copiedAt, long wallNow, long elapsedNow) {
        removeExpired(elapsedNow);
        long age = wallNow - copiedAt;
        if (!eligibleTimestamp(copiedAt) || age < 0 || age >= LIFETIME_MS) return;
        for (Entry entry : history)
            if (sameItem(entry, text, image) && entry.copiedAt() >= copiedAt) return;
        history.removeIf(entry -> sameItem(entry, text, image));
        int position = 0;
        while (position < history.size() && history.get(position).copiedAt() > copiedAt) position++;
        history.add(position, new Entry(text, image, copiedAt, elapsedNow + LIFETIME_MS - age));
        if (history.size() > MAX_ITEMS) history.remove(history.size() - 1);
    }

    private static boolean sameItem(Entry entry, String text, GagClipboardImage image) {
        return text != null ? text.equals(entry.text()) : entry.image() != null && image.sameBytes(entry.image());
    }

    List<Entry> entries(long now) {
        removeExpired(now);
        return List.copyOf(history);
    }

    void clear() { history.clear(); }

    void clearThrough(long copiedAt) {
        clearedThrough = Math.max(clearedThrough, copiedAt);
        clear();
    }

    void remove(Entry entry) {
        if (history.remove(entry)) clearedThrough = Math.max(clearedThrough, entry.copiedAt());
    }

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
