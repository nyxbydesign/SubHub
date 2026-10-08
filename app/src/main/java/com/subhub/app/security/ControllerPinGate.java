package com.subhub.app.security;

import android.app.Activity;
import android.text.InputType;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.subhub.app.R;
import com.subhub.app.penance.TamperTributeReporter;

/** Consistent setup and unlock dialogs for settings-changing surfaces. */
public final class ControllerPinGate {
    private ControllerPinGate() {}

    public static void ensureConfigured(Activity activity, Runnable authorized) {
        if (ControllerPinManager.isConfigured(activity)) {
            authorized.run();
            return;
        }
        LinearLayout panel = panel(activity);
        panel.addView(title(activity, activity.getString(R.string.controller_pin_setup_title)));
        TextView explainer = explainer(activity, activity.getString(R.string.controller_pin_setup_body));
        EditText pin = pinInput(activity, R.string.controller_pin_label);
        EditText confirmation = pinInput(activity, R.string.controller_pin_confirm_label);
        panel.addView(explainer);
        panel.addView(pin);
        panel.addView(confirmation);
        AlertDialog dialog = com.subhub.app.util.ThemedDialogs.builder(activity)
                .setView(panel)
                .setCancelable(false)
                .setPositiveButton(R.string.controller_pin_set, null)
                .create();
        dialog.setOnShowListener(ignored -> {
            styleDialog(activity, dialog);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                    String value = pin.getText().toString();
                    if (!value.equals(confirmation.getText().toString())) {
                        confirmation.setError(activity.getString(R.string.controller_pin_mismatch));
                    } else if (!ControllerPinManager.setPin(activity, value)) {
                        pin.setError(activity.getString(R.string.controller_pin_invalid));
                    } else {
                        dialog.dismiss();
                        // First launch opens into the clean Sub dashboard. The PIN holder can
                        // explicitly enter Dom mode after setup.
                        ControllerPinManager.enterSubMode();
                        authorized.run();
                    }
                });
        });
        dialog.show();
    }

    public static void require(Activity activity, Runnable authorized, boolean finishOnCancel) {
        if (!ControllerPinManager.isConfigured(activity)) {
            ensureConfigured(activity, authorized);
            return;
        }
        if (ControllerPinManager.isDomModeActive()) {
            authorized.run();
            return;
        }
        LinearLayout panel = panel(activity);
        panel.addView(title(activity, activity.getString(R.string.controller_pin_unlock_title)));
        panel.addView(explainer(activity, activity.getString(R.string.controller_pin_unlock_body)));
        EditText pin = pinInput(activity, R.string.controller_pin_label);
        ControllerCredentialChoice credential = new ControllerCredentialChoice(activity, panel, pin);
        panel.addView(pin);
        AlertDialog dialog = com.subhub.app.util.ThemedDialogs.builder(activity)
                .setView(panel)
                .setNegativeButton(android.R.string.cancel, (ignored, which) -> {
                    if (finishOnCancel) activity.finish();
                })
                .setPositiveButton(R.string.controller_pin_unlock, null)
                .setOnCancelListener(ignored -> {
                    if (finishOnCancel) activity.finish();
                })
                .create();
        dialog.setOnShowListener(ignored -> {
            styleDialog(activity, dialog);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                    if (credential.verify()) {
                        dialog.dismiss();
                        authorized.run();
                    }
                });
        });
        dialog.show();
    }

    private static LinearLayout panel(Activity activity) {
        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        int horizontal = dp(activity, 24);
        panel.setPadding(horizontal, dp(activity, 20), horizontal, dp(activity, 4));
        panel.setBackgroundColor(activity.getColor(R.color.surface));
        return panel;
    }

    private static TextView title(Activity activity, String text) {
        TextView view = new TextView(activity);
        view.setText(text);
        view.setTextColor(activity.getColor(R.color.text_primary));
        view.setTextSize(18f);
        view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        view.setLetterSpacing(0.08f);
        view.setIncludeFontPadding(false);
        view.setPadding(0, 0, 0, dp(activity, 10));
        return view;
    }

    private static TextView explainer(Activity activity, String text) {
        TextView view = new TextView(activity);
        view.setText(text);
        view.setTextColor(activity.getColor(R.color.text_secondary));
        view.setTextSize(12f);
        view.setPadding(0, 0, 0, dp(activity, 10));
        return view;
    }

    private static EditText pinInput(Activity activity, int hint) {
        EditText input = new EditText(activity);
        input.setHint(hint);
        input.setSingleLine(true);
        input.setMaxLines(1);
        input.setInputType(InputType.TYPE_CLASS_NUMBER
                | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        input.setTextColor(activity.getColor(R.color.text_primary));
        input.setHintTextColor(activity.getColor(R.color.text_muted));
        input.setBackgroundTintList(ColorStateList.valueOf(activity.getColor(R.color.accent)));
        input.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 50)));
        return input;
    }

    private static void styleDialog(Activity activity, AlertDialog dialog) {
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(R.drawable.bg_native_dialog);
            window.setDimAmount(0.72f);
        }
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(activity.getColor(R.color.accent_text));
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
                .setTextColor(activity.getColor(R.color.text_secondary));
    }

    private static int dp(Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
