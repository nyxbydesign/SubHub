package com.subhub.app.atmosphere;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.os.Bundle;
import android.os.PersistableBundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;

import androidx.test.platform.app.InstrumentationRegistry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Shell-only synthetic editor in the instrumentation APK's separate package. */
public final class GagEditorFixtureActivity extends Activity {
    static final String ACTION = "com.subhub.app.test.GAG_EDITOR_FIXTURE";
    static final String EDITOR = "Gag spacing test editor";
    static final String WATCH_SEPARATOR_BATCH = "gag_watch_separator_batch";
    static final String BATCHED_SEPARATOR = EDITOR + " batched separator";
    static final String CLIPBOARD_TEXT = "Synthetic clipboard fixture";
    static final String LONG_CLIPBOARD_PREFIX = "Long clipboard fixture ";
    static final String LONG_CLIPBOARD_TEXT = LONG_CLIPBOARD_PREFIX + "abc 123 ".repeat(768) + "tail";
    static final String SEED_LONG_CLIPBOARD = "gag_seed_long_clipboard";
    static final String DELETION_TEXT = "keep über café tail";
    static final String GRAPHEME_DELETE = "gag_grapheme_delete";
    static final String GRAPHEME_PREFIX = "definately\u2764\ufe0f";
    static final String SUPPRESS_CLIPBOARD_OVERLAY = "gag_suppress_clipboard_overlay";
    private EditText editor;
    private boolean keyboardRequested;
    private boolean seedClipboard;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (!ACTION.equals(getIntent().getAction())) {
            finish();
            return;
        }

        boolean longClipboard = getIntent().getBooleanExtra(SEED_LONG_CLIPBOARD, false);
        editor = getIntent().getBooleanExtra(WATCH_SEPARATOR_BATCH, false)
                ? new EditText(this) {
                    private boolean startsAfterHello;

                    @Override public void onBeginBatchEdit() {
                        super.onBeginBatchEdit();
                        startsAfterHello = TextUtils.equals("hello", getText());
                    }

                    @Override public void onEndBatchEdit() {
                        super.onEndBatchEdit();
                        if (startsAfterHello && TextUtils.equals("hello d", getText())) {
                            setContentDescription(BATCHED_SEPARATOR);
                        }
                    }
                }
                : new EditText(this);
        editor.setSingleLine(!longClipboard);
        editor.setInputType(InputType.TYPE_CLASS_TEXT | (longClipboard ? InputType.TYPE_TEXT_FLAG_MULTI_LINE : 0));
        editor.setContentDescription(EDITOR);
        boolean middle = getIntent().getBooleanExtra("gag_spacing_middle", false);
        boolean selected = getIntent().getBooleanExtra("gag_deletion_selection", false);
        boolean grapheme = getIntent().getBooleanExtra(GRAPHEME_DELETE, false);
        seedClipboard = getIntent().getBooleanExtra("gag_seed_clipboard", false);
        editor.setText(grapheme ? GRAPHEME_PREFIX + " tail" : selected ? DELETION_TEXT : middle ? " next" : "");
        if (longClipboard) {
            editor.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) { }
                @Override public void onTextChanged(CharSequence text, int start, int before, int count) { }
                @Override public void afterTextChanged(Editable text) {
                    editor.setContentDescription(longClipboardResult(text));
                }
            });
        }
        setContentView(editor);
        editor.requestFocus();
        if (selected) editor.setSelection(5, 14);
        else if (grapheme) editor.setSelection(GRAPHEME_PREFIX.length());
        else editor.setSelection(0);
    }

    static String longClipboardResult(CharSequence text) {
        return EDITOR + " length=" + text.length() + " exact=" + TextUtils.equals(LONG_CLIPBOARD_TEXT, text);
    }

    static Path reserveFailureCapture(String label) throws IOException {
        var context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        return Files.createTempDirectory(context.getFilesDir().toPath(), label + "-").resolve("failure.png");
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && editor != null && !keyboardRequested) {
            // Wait for window focus dispatch so the IME can serve the focused editor.
            editor.post(() -> {
                if (!keyboardRequested && editor.hasWindowFocus() && editor.hasFocus()) {
                    keyboardRequested = getSystemService(InputMethodManager.class)
                            .showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT);
                }
            });
        }
        if (hasFocus && seedClipboard) {
            seedClipboard = false;
            String text = getIntent().getBooleanExtra(SEED_LONG_CLIPBOARD, false)
                    ? LONG_CLIPBOARD_TEXT : CLIPBOARD_TEXT;
            ClipData clip = ClipData.newPlainText("Fixture", text);
            if (getIntent().getBooleanExtra(SUPPRESS_CLIPBOARD_OVERLAY, false)) {
                // Android honors this for emulator clipboard synchronization.
                PersistableBundle extras = new PersistableBundle();
                extras.putBoolean("com.android.systemui.SUPPRESS_CLIPBOARD_OVERLAY", true);
                clip.getDescription().setExtras(extras);
            }
            getSystemService(ClipboardManager.class).setPrimaryClip(clip);
        }
    }
}
