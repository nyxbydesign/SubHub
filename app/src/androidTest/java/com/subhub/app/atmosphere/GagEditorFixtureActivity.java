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

/** Shell-only synthetic editor in the instrumentation APK's separate package. */
public final class GagEditorFixtureActivity extends Activity {
    static final String ACTION = "com.subhub.app.test.GAG_EDITOR_FIXTURE";
    static final String EDITOR = "Gag spacing test editor";
    static final String CLIPBOARD_TEXT = "Synthetic clipboard fixture";
    static final String LONG_CLIPBOARD_PREFIX = "Long clipboard fixture ";
    static final String LONG_CLIPBOARD_TEXT = LONG_CLIPBOARD_PREFIX + "abc 123 ".repeat(768) + "tail";
    static final String SEED_LONG_CLIPBOARD = "gag_seed_long_clipboard";
    static final String DELETION_TEXT = "keep über café tail";
    static final String SUPPRESS_CLIPBOARD_OVERLAY = "gag_suppress_clipboard_overlay";
    private boolean seedClipboard;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (!ACTION.equals(getIntent().getAction())) {
            finish();
            return;
        }

        EditText editor = new EditText(this);
        editor.setSingleLine(true);
        editor.setInputType(InputType.TYPE_CLASS_TEXT);
        editor.setContentDescription(EDITOR);
        boolean middle = getIntent().getBooleanExtra("gag_spacing_middle", false);
        boolean selected = getIntent().getBooleanExtra("gag_deletion_selection", false);
        seedClipboard = getIntent().getBooleanExtra("gag_seed_clipboard", false);
        editor.setText(selected ? DELETION_TEXT : middle ? " next" : "");
        if (getIntent().getBooleanExtra(SEED_LONG_CLIPBOARD, false)) {
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
        editor.post(() -> {
            if (selected) editor.setSelection(5, 14);
            else editor.setSelection(0);
            getSystemService(InputMethodManager.class).showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT);
        });
    }

    static String longClipboardResult(CharSequence text) {
        return EDITOR + " length=" + text.length() + " exact=" + TextUtils.equals(LONG_CLIPBOARD_TEXT, text);
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
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
