package com.subhub.app.atmosphere;

import android.content.ClipData;
import android.content.ClipDescription;
import android.net.Uri;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Bounded IME-process history with a separate lease on the visible editor. */
final class GagClipboard {
    static final int MAX_TEXT_LENGTH = 32_768;
    private static final int MAX_PREVIEW_CODE_POINTS = 256;
    private static final int MAX_EXPANDED_PASTE_LENGTH = 65_536;
    private static final int MAX_ITEMS = 8;
    static final long LIFETIME_MS = 6 * 60 * 60 * 1_000L;
    private static final String SENSITIVE = "android.content.extra.IS_SENSITIVE";
    private final ArrayList<Entry> history = new ArrayList<>();
    private final GagClipboardPins pinStore;
    private List<Entry> pins;
    private InputConnection attachedEditor;
    private long clearedThrough;

    record Entry(String text, GagClipboardImage image, long copiedAt, long expiresAt,
            GagScreenshotClipboard.Candidate screenshot, boolean pinned) {}

    GagClipboard() { this(null); }

    GagClipboard(GagClipboardPins pinStore) {
        this.pinStore = pinStore;
        List<String> stored = pinStore == null ? List.of() : pinStore.read();
        pins = stored == null ? List.of() : stored.stream().map(GagClipboard::pinned).collect(Collectors.toList());
    }

    private static Entry pinned(String text) {
        return new Entry(text, null, 0, Long.MAX_VALUE, null, true);
    }

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
        if (!validText(text) || pins.contains(pinned(text))) return;
        remember(text, null, copiedAt, wallNow, elapsedNow);
    }

    static boolean validText(String text) {
        return text != null && text.length() <= MAX_TEXT_LENGTH && firstNonWhitespace(text) < text.length();
    }

    static String previewText(String text) {
        int start = firstNonWhitespace(text);
        int end = start;
        for (int count = 0; count < MAX_PREVIEW_CODE_POINTS && end < text.length(); count++) {
            end += Character.charCount(text.codePointAt(end));
        }
        return end == text.length() ? text.substring(start) : text.substring(start, end) + "…";
    }

    private static int firstNonWhitespace(String text) {
        int index = 0;
        while (index < text.length()) {
            int point = text.codePointAt(index);
            if (!Character.isWhitespace(point)) break;
            index += Character.charCount(point);
        }
        return index;
    }

    void remember(GagClipboardImage image, long copiedAt, long wallNow, long elapsedNow) {
        if (image != null) remember(null, image, copiedAt, wallNow, elapsedNow);
    }

    private void remember(String text, GagClipboardImage image, long copiedAt, long wallNow, long elapsedNow) {
        remember(text, image, copiedAt, wallNow, elapsedNow, null);
    }

    void rememberScreenshot(GagScreenshotClipboard.Import item, long wallNow, long elapsedNow) {
        remember(null, item.image(), item.media().savedAt(), wallNow, elapsedNow, item.media());
    }

    private void remember(String text, GagClipboardImage image, long copiedAt, long wallNow, long elapsedNow,
            GagScreenshotClipboard.Candidate screenshot) {
        removeExpired(elapsedNow);
        long age = wallNow - copiedAt;
        if (!eligibleTimestamp(copiedAt) || age < 0 || age >= LIFETIME_MS) return;
        if (screenshot != null) {
            // Revisions from one media row occupy a single slot. A manually copied image
            // remains independently authorized if screenshot access later changes.
            history.removeIf(entry -> entry.screenshot() != null && entry.screenshot().uri().equals(screenshot.uri()));
        }
        for (Entry entry : history) {
            if (sameItem(entry, text, image)
                    && (entry.copiedAt() >= copiedAt || (screenshot != null && entry.screenshot() == null))) return;
        }
        history.removeIf(entry -> sameItem(entry, text, image));
        int position = 0;
        while (position < history.size() && history.get(position).copiedAt() > copiedAt) position++;
        history.add(position, new Entry(text, image, copiedAt, elapsedNow + LIFETIME_MS - age, screenshot, false));
        if (history.size() > MAX_ITEMS) history.remove(history.size() - 1);
    }

    private static boolean sameItem(Entry entry, String text, GagClipboardImage image) {
        return text != null ? text.equals(entry.text()) : entry.image() != null && image.sameBytes(entry.image());
    }

    List<Entry> entries(long now) {
        List<Entry> result = new ArrayList<>(pins);
        result.addAll(recentEntries(now));
        return List.copyOf(result);
    }

    List<Entry> recentEntries(long now) {
        removeExpired(now);
        return List.copyOf(history);
    }

    boolean contains(Entry entry, long now) {
        removeExpired(now);
        return entry.pinned() ? pins.contains(entry) : history.contains(entry);
    }

    boolean pin(Entry entry, long now) {
        if (entry.text() == null || entry.pinned() || !contains(entry, now)) return false;
        List<Entry> updated = new ArrayList<>(pins);
        updated.add(0, pinned(entry.text()));
        if (!savePins(updated)) return false;
        history.remove(entry);
        return true;
    }

    boolean unpin(Entry entry, long wallNow, long elapsedNow) {
        if (!entry.pinned() || !remove(entry, 0)) return false;
        remember(entry.text(), wallNow, wallNow, elapsedNow);
        return true;
    }

    boolean edit(Entry entry, String text, long wallNow, long elapsedNow) {
        if (!validText(text) || entry.text() == null || !contains(entry, elapsedNow)) return false;
        if (text.equals(entry.text())) return true;
        if (entry.pinned()) {
            List<Entry> updated = new ArrayList<>(pins);
            updated.set(updated.indexOf(entry), pinned(text));
            if (!savePins(updated.stream().distinct().collect(Collectors.toList()))) return false;
            history.removeIf(recent -> text.equals(recent.text()));
            clearedThrough = Math.max(clearedThrough, wallNow);
        } else {
            if (pins.contains(pinned(text))) return remove(entry, 0);
            Entry replacement = new Entry(text, null, entry.copiedAt(), entry.expiresAt(), null, false);
            history.set(history.indexOf(entry), replacement);
            history.removeIf(recent -> recent != replacement && text.equals(recent.text()));
            clearedThrough = Math.max(clearedThrough, entry.copiedAt());
        }
        return true;
    }

    boolean forgetPins() { return savePins(List.of()); }

    private boolean savePins(List<Entry> updated) {
        if (pinStore == null || !pinStore.write(updated.stream().map(Entry::text).collect(Collectors.toList()))) return false;
        pins = List.copyOf(updated);
        return true;
    }

    Map<Uri, GagScreenshotClipboard.Revision> screenshotRevisions(long now) {
        removeExpired(now);
        Map<Uri, GagScreenshotClipboard.Revision> revisions = new java.util.HashMap<>();
        for (Entry entry : history) {
            if (entry.screenshot() != null) revisions.put(entry.screenshot().uri(), entry.screenshot().revision());
        }
        return Map.copyOf(revisions);
    }

    boolean retainScreenshots(Set<Uri> eligible) {
        return history.removeIf(entry -> entry.screenshot() != null && !eligible.contains(entry.screenshot().uri()));
    }

    void clear() { history.clear(); }

    void clearThrough(long copiedAt) {
        clearedThrough = Math.max(clearedThrough, copiedAt);
        clear();
    }

    boolean remove(Entry entry, long wallNow) {
        if (entry.pinned()) {
            List<Entry> updated = new ArrayList<>(pins);
            if (!updated.remove(entry) || !savePins(updated)) return false;
            clearedThrough = Math.max(clearedThrough, wallNow);
            return true;
        }
        if (!history.remove(entry)) return false;
        if (entry.screenshot() == null)
            clearedThrough = Math.max(clearedThrough, entry.copiedAt());
        return true;
    }

    private void removeExpired(long now) {
        history.removeIf(entry -> now >= entry.expiresAt());
    }

    static String applyWords(String value, java.util.function.UnaryOperator<String> complete) {
        StringBuilder result = new StringBuilder(value.length());
        for (int start = 0; start < value.length(); ) {
            int point = value.codePointAt(start);
            int end = start + Character.charCount(point);
            if (!GagRules.wordCharacter(point)) {
                if (result.length() + end - start > MAX_EXPANDED_PASTE_LENGTH) return null;
                result.append(value, start, end);
            } else {
                while (end < value.length()) {
                    int next = value.codePointAt(end);
                    if (!GagRules.wordCharacter(next) && !GagRules.apostrophe(next)) break;
                    end += Character.charCount(next);
                }
                String replacement = complete.apply(value.substring(start, end));
                // Keep a rule that expands a long clip from creating an oversized IME transaction.
                if (replacement.length() > MAX_EXPANDED_PASTE_LENGTH - result.length()) return null;
                result.append(replacement);
            }
            start = end;
        }
        return result.toString();
    }
}
