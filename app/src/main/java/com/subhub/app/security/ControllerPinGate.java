package com.subhub.app.security;

import android.app.Activity;
import android.text.InputType;
import android.graphics.Typeface;
import android.view.ViewGroup;
import android.view.Window;
import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;
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
        if (ControllerPinManager.hasCredentials(activity) || ControllerPinManager.allowsUnkeyedAccess(activity)) {
            authorized.run();
            return;
        }
        if (!allowsLocalPin()) { notifyLocked(activity); return; }
        showPinSetup(activity, authorized, false);
    }

    /** First local setup does not require a controller session that does not exist yet. */
    public static void setupLocalPin(Activity activity, Runnable changed) {
        if (!allowsLocalPin()) { notifyLocked(activity); return; }
        if (ControllerPinManager.hasCredentials(activity)) {
            unlock(activity, () -> changePin(activity, changed), false);
        } else showPinSetup(activity, changed, true);
    }

    private static boolean allowsLocalPin() {
        return keyholderState() == KeyholderProvider.State.NONE
                && com.subhub.app.people.People.isLocalConfigurationAllowed();
    }

    private static KeyholderProvider.State keyholderState() {
        try { return com.subhub.app.people.People.keyholder().state(); }
        catch (RuntimeException unavailable) { return KeyholderProvider.State.UNAVAILABLE; }
    }

    private static void showPinSetup(Activity activity, Runnable authorized, boolean cancelable) {
        LinearLayout panel = panel(activity);
        panel.addView(title(activity, activity.getString(R.string.controller_pin_setup_title)));
        TextView explainer = explainer(activity, activity.getString(R.string.controller_pin_setup_body));
        EditText pin = pinInput(activity, R.string.controller_pin_label);
        EditText confirmation = pinInput(activity, R.string.controller_pin_confirm_label);
        panel.addView(explainer);
        panel.addView(pin);
        panel.addView(confirmation);
        com.google.android.material.dialog.MaterialAlertDialogBuilder builder = com.subhub.app.util.ThemedDialogs.builder(activity)
                .setView(panel)
                .setCancelable(cancelable)
                .setPositiveButton(R.string.controller_pin_set, null);
        if (cancelable) builder.setNegativeButton(android.R.string.cancel, null);
        AlertDialog dialog = builder.create();
        dialog.setOnShowListener(ignored -> {
            styleDialog(activity, dialog);
            bindSubmit(dialog, pin, confirmation);
            com.subhub.app.util.InputFocusPolicy.focusController(dialog, pin);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                    if (!allowsLocalPin()) { dialog.dismiss(); return; }
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
        watchAuthority(activity, dialog, ControllerPinGate::allowsLocalPin, () -> {});
    }

    /** Local PIN replacement requires local authority and an authenticated controller session. */
    public static void changePin(Activity activity, Runnable changed) {
        if (!allowsLocalPin()) { notifyLocked(activity); return; }
        if (!ControllerPinManager.isDomModeActive()) { notifyLocked(activity); return; }
        if (!ControllerPinManager.hasCredentials(activity) && !ControllerPinManager.allowsUnkeyedAccess(activity)) { ensureConfigured(activity, changed); return; }
        require(activity, () -> {
            LinearLayout content = panel(activity);
            content.addView(title(activity, activity.getString(R.string.keyholder_pin_change)));
            EditText pin = pinInput(activity, R.string.controller_pin_label);
            EditText confirmation = pinInput(activity, R.string.controller_pin_confirm_label);
            pin.setSaveEnabled(false); confirmation.setSaveEnabled(false);
            content.addView(pin); content.addView(confirmation);
            AlertDialog dialog = com.subhub.app.util.ThemedDialogs.builder(activity).setView(content)
                    .setNegativeButton(android.R.string.cancel, null).setPositiveButton(R.string.controller_pin_set, null).create();
            dialog.setOnShowListener(ignored -> {
                styleDialog(activity, dialog);
                bindSubmit(dialog, pin, confirmation);
                com.subhub.app.util.InputFocusPolicy.focusController(dialog, pin);
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                    if (!ControllerPinManager.isDomModeActive() || !allowsLocalPin()) { dialog.dismiss(); return; }
                    if (!pin.getText().toString().equals(confirmation.getText().toString()))
                        confirmation.setError(activity.getString(R.string.controller_pin_mismatch));
                    else if (!ControllerPinManager.setPin(activity, pin.getText().toString()))
                        pin.setError(activity.getString(R.string.controller_pin_invalid));
                    else { dialog.dismiss(); changed.run(); }
                });
            });
            dialog.show();
            watchAuthority(activity, dialog, () -> allowsLocalPin()
                    && ControllerPinManager.isDomModeActive(), () -> {});
        }, false);
    }

    /** A protected action never opens an authentication dialog or changes the current role. */
    public static void require(Activity activity, Runnable authorized, boolean finishIfLocked) {
        if (ControllerPinManager.isDomModeActive()) {
            authorized.run();
            return;
        }
        notifyLocked(activity);
        if (finishIfLocked) activity.finish();
    }

    public static void notifyLocked(android.content.Context context) {
        android.widget.Toast.makeText(context, keyholderState() == KeyholderProvider.State.NONE
                ? R.string.controller_pin_unlock_title : R.string.keyholder_remote_unlock,
                android.widget.Toast.LENGTH_SHORT).show();
    }

    /** Keep protected actions readable and tappable so their existing guard can explain the lock. */
    public static void markLocked(android.view.View view) {
        boolean locked = !ControllerPinManager.isDomModeActive();
        view.setAlpha(locked ? .45f : 1f);
        androidx.core.view.ViewCompat.setStateDescription(view,
                locked ? view.getContext().getString(R.string.controller_pin_unlock_title) : null);
    }

    /** Used only by an explicit role-unlock control. */
    public static void unlock(Activity activity, Runnable authorized, boolean finishOnCancel) {
        KeyholderProvider.State keyholder = keyholderState();
        if (keyholder == KeyholderProvider.State.PENDING || keyholder == KeyholderProvider.State.UNAVAILABLE) {
            android.widget.Toast.makeText(activity, keyholder == KeyholderProvider.State.PENDING
                    ? R.string.keyholder_pairing_in_progress : R.string.keyholder_remote_code_unavailable,
                    android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        if (ControllerPinManager.allowsUnkeyedAccess(activity)) {
            ControllerPinManager.enterDomMode(); authorized.run(); return;
        }
        if (!ControllerPinManager.hasCredentials(activity)) {
            ensureConfigured(activity, () -> unlock(activity, authorized, finishOnCancel));
            return;
        }
        if (ControllerPinManager.isDomModeActive()) {
            authorized.run();
            return;
        }
        showCredentialPrompt(activity, authorized, finishOnCancel, false);
    }

    /** Sensitive actions obtain a fresh credential without resetting the surrounding role session. */
    public static void authenticate(Activity activity, Runnable authorized, boolean finishOnCancel) {
        KeyholderProvider.State keyholder = keyholderState();
        if (keyholder == KeyholderProvider.State.PENDING || keyholder == KeyholderProvider.State.UNAVAILABLE
                || !ControllerPinManager.hasCredentials(activity)) {
            android.widget.Toast.makeText(activity, R.string.keyholder_remote_code_unavailable,
                    android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        showCredentialPrompt(activity, authorized, finishOnCancel, true);
    }

    private static void showCredentialPrompt(Activity activity, Runnable authorized,
            boolean finishOnCancel, boolean force) {
        KeyholderProvider.State keyholder = keyholderState();
        LinearLayout panel = panel(activity);
        boolean remote = keyholder != KeyholderProvider.State.NONE;
        panel.addView(title(activity, activity.getString(remote
                ? R.string.keyholder_remote_unlock : R.string.controller_pin_unlock_title)));
        panel.addView(explainer(activity, activity.getString(remote
                ? R.string.keyholder_remote_unlock_help : R.string.controller_pin_unlock_body)));
        EditText pin = pinInput(activity, remote ? R.string.authenticator_code : R.string.controller_pin_label);
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
            bindSubmit(dialog, pin);
            com.subhub.app.util.InputFocusPolicy.focusController(dialog, pin);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                    if (credential.verify()) {
                        dialog.dismiss();
                        authorized.run();
                    }
                });
        });
        dialog.show();
        watchAuthority(activity, dialog, credential::isCurrentAuthority,
                () -> {
                    if (force) authenticate(activity, authorized, finishOnCancel);
                    else unlock(activity, authorized, finishOnCancel);
                });
    }

    private static void watchAuthority(Activity activity, AlertDialog dialog,
            java.util.function.BooleanSupplier current, Runnable changed) {
        android.os.Handler clock = new android.os.Handler(android.os.Looper.getMainLooper());
        Runnable observe = new Runnable() {
            @Override public void run() {
                if (!dialog.isShowing()) return;
                if (activity.isFinishing() || activity.isDestroyed()) { dialog.dismiss(); return; }
                if (!current.getAsBoolean()) { dialog.dismiss(); changed.run(); return; }
                clock.postDelayed(this, 250);
            }
        };
        dialog.setOnDismissListener(ignored -> clock.removeCallbacks(observe));
        clock.postDelayed(observe, 250);
    }

    /** Keyboard submission follows the existing validated button action. */
    public static void submitOnEnter(EditText input, Runnable submit) {
        input.setImeOptions((input.getImeOptions() & ~EditorInfo.IME_MASK_ACTION)
                | EditorInfo.IME_ACTION_DONE);
        input.setOnEditorActionListener((view, action, event) -> {
            if (event != null && (event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    || event.getKeyCode() == KeyEvent.KEYCODE_NUMPAD_ENTER)) {
                if (event.getAction() == KeyEvent.ACTION_UP && event.getRepeatCount() == 0)
                    submit.run();
                return true;
            }
            if (action == EditorInfo.IME_ACTION_DONE || action == EditorInfo.IME_ACTION_GO
                    || action == EditorInfo.IME_ACTION_SEND) {
                submit.run();
                return true;
            }
            return false;
        });
    }

    private static void bindSubmit(AlertDialog dialog, EditText... inputs) {
        for (EditText input : inputs) submitOnEnter(input, () -> {
            if (dialog.isShowing()) dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        });
    }

    private static LinearLayout panel(Activity activity) {
        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        int horizontal = dp(activity, 24);
        panel.setPadding(horizontal, dp(activity, 20), horizontal, dp(activity, 4));
        return panel;
    }

    private static TextView title(Activity activity, String text) {
        TextView view = new TextView(activity);
        view.setText(text);
        view.setTextColor(activity.getColor(R.color.text_primary));
        com.subhub.app.util.UiIdentity.textSize(view, R.dimen.ui_text_title);
        view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        view.setLetterSpacing(0.02f);
        view.setIncludeFontPadding(false);
        view.setPadding(0, 0, 0, dp(activity, 10));
        return view;
    }

    private static TextView explainer(Activity activity, String text) {
        TextView view = new TextView(activity);
        view.setText(text);
        view.setTextColor(activity.getColor(R.color.text_secondary));
        com.subhub.app.util.UiIdentity.textSize(view, R.dimen.ui_text_caption);
        view.setIncludeFontPadding(false);
        view.setPadding(0, 0, 0, dp(activity, 10));
        return view;
    }

    private static EditText pinInput(Activity activity, int hint) {
        EditText input = new EditText(activity);
        input.setHint(hint);
        input.setSaveEnabled(false);
        input.setSingleLine(true);
        input.setMaxLines(1);
        com.subhub.app.util.UiIdentity.inputType(input, InputType.TYPE_CLASS_NUMBER
                | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        input.setBackgroundTintList(null);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = activity.getResources().getDimensionPixelSize(R.dimen.ui_gap_control);
        input.setLayoutParams(params);
        return input;
    }

    private static void styleDialog(Activity activity, AlertDialog dialog) {
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(R.drawable.bg_native_dialog);
            window.setDimAmount(0.72f);
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
        }
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(activity.getColor(R.color.accent_text));
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
                .setTextColor(activity.getColor(R.color.text_secondary));
    }

    private static int dp(Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
