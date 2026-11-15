package com.subhub.app.atmosphere;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import com.subhub.app.R;

/** Edits a history entry in isolation from the receiving app. */
public final class GagClipboardEditActivity extends AppCompatActivity {
    private static Edit pending;
    private Edit editing;
    private EditText input;

    private static final class Edit {
        final GagClipboard clipboard;
        final GagClipboard.Entry entry;
        String draft;

        Edit(GagClipboard clipboard, GagClipboard.Entry entry) {
            this.clipboard = clipboard;
            this.entry = entry;
            draft = entry.text();
        }
    }

    static boolean open(Context keyboard, GagClipboard clipboard, GagClipboard.Entry entry) {
        if (pending != null) return false;
        pending = new Edit(clipboard, entry);
        try {
            keyboard.startActivity(new Intent(keyboard, GagClipboardEditActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (ActivityNotFoundException | SecurityException unavailable) {
            pending = null;
            return false;
        }
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        editing = pending;
        if (editing == null || !editing.clipboard.contains(editing.entry, SystemClock.elapsedRealtime())) {
            finish();
            return;
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        int padding = Math.round(16 * getResources().getDisplayMetrics().density);
        ViewCompat.setOnApplyWindowInsetsListener(content, (view, insets) -> {
            var bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            view.setPadding(bars.left + padding, bars.top + padding, bars.right + padding, bars.bottom);
            return insets;
        });
        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.END);
        content.addView(actions, new LinearLayout.LayoutParams(-1, -2));
        Button cancel = new Button(this);
        cancel.setText(android.R.string.cancel);
        cancel.setOnClickListener(view -> finish());
        actions.addView(cancel);
        Button save = new Button(this);
        save.setAllCaps(false);
        save.setText(R.string.gag_clipboard_save);
        save.setOnClickListener(view -> save());
        actions.addView(save);
        input = new EditText(this);
        input.setHint(R.string.gag_clipboard_edit_text);
        input.setGravity(Gravity.TOP | Gravity.START);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setImeOptions(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(GagClipboard.MAX_TEXT_LENGTH)});
        input.setSaveEnabled(false);
        input.setText(editing.draft);
        input.setSelection(input.length());
        content.addView(input, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(content);
        input.requestFocus();
    }

    private void save() {
        String text = input.getText().toString();
        if (!GagClipboard.validText(text)) {
            input.setError(getString(R.string.gag_clipboard_text_required));
        } else if (editing.clipboard.edit(editing.entry, text, System.currentTimeMillis(), SystemClock.elapsedRealtime())) {
            finish();
        } else {
            input.setError(getString(R.string.gag_clipboard_change_failed));
        }
    }

    @Override protected void onStop() {
        if (input != null && !isFinishing()) editing.draft = input.getText().toString();
        super.onStop();
    }

    @Override protected void onDestroy() {
        if (isFinishing() && pending == editing) pending = null;
        super.onDestroy();
    }
}
