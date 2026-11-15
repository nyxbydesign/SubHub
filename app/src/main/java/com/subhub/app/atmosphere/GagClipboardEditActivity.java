package com.subhub.app.atmosphere;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.core.view.ViewCompat;
import com.subhub.app.R;

/** Edits a history entry in isolation from the receiving app. */
public final class GagClipboardEditActivity extends Activity {
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
        var window = getWindow();
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        setFinishOnTouchOutside(false);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        int padding = dp(12);
        content.setPadding(padding, padding, padding, dp(4));
        var background = new GradientDrawable();
        background.setColor(0xff252527);
        background.setCornerRadius(dp(14));
        content.setBackground(background);

        TextView title = new TextView(this);
        title.setText(R.string.gag_clipboard_edit);
        title.setTextColor(Color.WHITE);
        title.setTextSize(16);
        ViewCompat.setAccessibilityHeading(title, true);
        content.addView(title, new LinearLayout.LayoutParams(-1, -2));

        input = new EditText(this);
        input.setHint(R.string.gag_clipboard_edit_text);
        input.setGravity(Gravity.TOP | Gravity.START);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setImeOptions(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(GagClipboard.MAX_TEXT_LENGTH)});
        input.setMinLines(2);
        input.setMaxLines(3);
        input.setTextSize(16);
        input.setSaveEnabled(false);
        input.setText(editing.draft);
        input.setSelection(input.length());
        content.addView(input, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.END);
        content.addView(actions, new LinearLayout.LayoutParams(-1, -2));
        Button cancel = new Button(this);
        cancel.setAllCaps(false);
        cancel.setText(android.R.string.cancel);
        cancel.setOnClickListener(view -> finish());
        actions.addView(cancel);
        Button save = new Button(this);
        save.setAllCaps(false);
        save.setText(R.string.gag_clipboard_save);
        save.setOnClickListener(view -> save());
        actions.addView(save);
        setContentView(content);
        WindowManager.LayoutParams popup = window.getAttributes();
        popup.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        popup.width = dp(Math.min(300, Math.max(1, getResources().getConfiguration().screenWidthDp - 24)));
        popup.height = WindowManager.LayoutParams.WRAP_CONTENT;
        popup.y = dp(8);
        if (Build.VERSION.SDK_INT >= 30) popup.setFitInsetsTypes(WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
        window.setAttributes(popup);
        input.requestFocus();
    }

    private int dp(int size) {
        return Math.round(size * getResources().getDisplayMetrics().density);
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
