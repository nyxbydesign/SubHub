package com.subhub.app.util;

import android.content.Context;
import android.graphics.Typeface;
import android.view.Window;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.subhub.app.R;

/** One app-owned dialog shell, including confirmation, picker and secure PIN surfaces. */
public final class ThemedDialogs {
    private ThemedDialogs() { }

    public static MaterialAlertDialogBuilder builder(Context context) {
        return new MaterialAlertDialogBuilder(context, R.style.ThemeOverlay_SubHub_AlertDialog) {
            @Override public MaterialAlertDialogBuilder setView(android.view.View view) {
                if (view instanceof android.widget.EditText) {
                    android.widget.FrameLayout wrapper = new android.widget.FrameLayout(context);
                    wrapper.addView(view, new android.widget.FrameLayout.LayoutParams(-1, -2));
                    view = wrapper;
                }
                InputFocusPolicy.openForReading(view);
                return super.setView(view);
            }
        }
                .setBackground(ContextCompat.getDrawable(context, R.drawable.bg_native_dialog));
    }

    public static LinearLayout formPanel(Context context) {
        LinearLayout panel = new LinearLayout(context);
        panel.setOrientation(LinearLayout.VERTICAL);
        int inset = dp(context, 24);
        panel.setPadding(inset, dp(context, 20), inset, dp(context, 4));
        return panel;
    }

    public static TextView formTitle(Context context, CharSequence text) {
        TextView title = new TextView(context);
        title.setText(text);
        title.setTextColor(context.getColor(R.color.text_primary));
        UiIdentity.textSize(title, R.dimen.ui_text_title);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setLetterSpacing(0.02f);
        title.setIncludeFontPadding(false);
        title.setPadding(0, 0, 0, dp(context, 10));
        return title;
    }

    /** Apply the form appearance after the dialog window and native action buttons are installed. */
    public static void styleForm(AlertDialog dialog) {
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(R.drawable.bg_native_dialog);
            window.setDimAmount(0.72f);
        }
        Context context = dialog.getContext();
        Button primary = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        Button cancel = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
        if (primary != null) primary.setTextColor(context.getColor(R.color.accent_text));
        if (cancel != null) cancel.setTextColor(context.getColor(R.color.text_secondary));
    }

    private static int dp(Context context, int size) {
        return Math.round(size * context.getResources().getDisplayMetrics().density);
    }
}
