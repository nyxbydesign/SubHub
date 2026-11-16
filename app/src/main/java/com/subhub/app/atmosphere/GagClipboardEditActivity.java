package com.subhub.app.atmosphere;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
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
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
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
        boolean requestedKeyboard;

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

        int popupWidth = dp(Math.min(300, Math.max(1, getResources().getConfiguration().screenWidthDp - 24)));
        int padding = getResources().getDimensionPixelSize(R.dimen.ui_card_padding_compact);
        int gap = getResources().getDimensionPixelSize(R.dimen.ui_gap_control);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundResource(R.drawable.bg_native_dialog);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        content.setPadding(padding, padding, padding, padding);
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));

        TextView title = new TextView(this);
        title.setText(R.string.gag_clipboard_edit);
        title.setTextAppearance(R.style.TextAppearance_SubHub_Section);
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
        LinearLayout.LayoutParams inputLayout = new LinearLayout.LayoutParams(-1, -2);
        inputLayout.topMargin = gap;
        content.addView(input, inputLayout);

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.END);
        Button cancel = new Button(this);
        cancel.setText(android.R.string.cancel);
        cancel.setOnClickListener(view -> finish());
        Button save = new Button(this);
        save.setText(R.string.gag_clipboard_save);
        save.setOnClickListener(view -> save());
        cancel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        save.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        boolean stack = cancel.getMeasuredWidth() + save.getMeasuredWidth() + gap > popupWidth - 2 * padding;
        actions.setOrientation(stack ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        int actionWidth = stack ? LinearLayout.LayoutParams.MATCH_PARENT : LinearLayout.LayoutParams.WRAP_CONTENT;
        actions.addView(cancel, new LinearLayout.LayoutParams(actionWidth, -2));
        LinearLayout.LayoutParams saveLayout = new LinearLayout.LayoutParams(actionWidth, -2);
        if (stack) saveLayout.topMargin = gap;
        else saveLayout.setMarginStart(gap);
        actions.addView(save, saveLayout);
        LinearLayout.LayoutParams actionsLayout = new LinearLayout.LayoutParams(-1, -2);
        actionsLayout.topMargin = gap;
        content.addView(actions, actionsLayout);

        setContentView(scroll);
        WindowManager.LayoutParams popup = window.getAttributes();
        popup.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        popup.width = popupWidth;
        popup.height = WindowManager.LayoutParams.WRAP_CONTENT;
        popup.y = dp(8);
        if (Build.VERSION.SDK_INT >= 30) popup.setFitInsetsTypes(WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
        window.setAttributes(popup);
        input.requestFocus();
    }

    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (!focused || input == null || editing.requestedKeyboard) return;
        input.post(() -> {
            if (editing.requestedKeyboard || isFinishing() || isDestroyed()
                    || !input.isAttachedToWindow() || !input.hasWindowFocus() || !input.requestFocus()) return;
            editing.requestedKeyboard = true;
            WindowCompat.getInsetsController(getWindow(), input).show(WindowInsetsCompat.Type.ime());
        });
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
