package com.subhub.app.security;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.view.WindowManager;
import android.widget.*;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.common.BitMatrix;
import com.subhub.app.R;
import com.subhub.app.util.PreferencePage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Pairing is explicit and local; setup keys never enter saved instance state or logs. */
public final class AuthenticatorActivity extends PreferencePage {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private String pendingSecret;
    private Bitmap qr;
    private String expandedMethod = "pin";
    private final java.util.Map<String, LinearLayout> methodBodies = new java.util.LinkedHashMap<>();
    private final java.util.Map<String, TextView> methodArrows = new java.util.LinkedHashMap<>();
    private final java.util.Map<String, android.view.View> methodHeaders = new java.util.LinkedHashMap<>();
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state); getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        if (state != null) expandedMethod = state.getString("keyholder_method", "pin");
        page(R.string.authenticator_title);
        render();
    }
    private void render() {
        if (isFinishing() || isDestroyed()) return;
        page(R.string.authenticator_title);
        ControllerAuthenticator authenticator = new ControllerAuthenticator(this);
        methodBodies.clear(); methodArrows.clear(); methodHeaders.clear();
        LinearLayout pinCard = method("pin", R.string.keyholder_pin_heading, R.string.keyholder_pin_subtitle, R.id.keyholder_pin_header);
        button(pinCard, getString(ControllerPinManager.isConfigured(this) ? R.string.keyholder_pin_change : R.string.controller_pin_set),
                () -> ControllerPinGate.changePin(this, this::render)).setId(R.id.keyholder_pin_change_button);
        if (ControllerPinManager.isConfigured(this)) button(pinCard, getString(R.string.keyholder_remove_pin), () ->
                ControllerPinGate.require(this, () -> {
                    if (ControllerPinManager.removePin(this)) render();
                    else notice(getString(R.string.authenticator_unavailable));
                }, false)).setId(R.id.keyholder_remove_pin);
        LinearLayout pinSteps = card(pinCard);
        step(pinSteps, "1", R.string.keyholder_pin_step_one, R.string.keyholder_pin_step_one_help);
        step(pinSteps, "2", R.string.keyholder_pin_step_two, R.string.keyholder_pin_step_two_help);
        step(pinSteps, "3", R.string.keyholder_pin_step_three, R.string.keyholder_pin_step_three_help);
        LinearLayout section = method("remote", R.string.keyholder_remote_title, R.string.keyholder_remote_subtitle, R.id.keyholder_remote_header);
        TextView status = text(section, getString(authenticator.isPaired() ? R.string.authenticator_paired : R.string.authenticator_unpaired), 18, false);
        status.setTypeface(null, android.graphics.Typeface.BOLD);
        Button pair = button(section, getString(authenticator.isPaired() ? R.string.authenticator_replace : R.string.authenticator_pair), () ->
                ControllerPinGate.require(this, this::beginPairing, false));
        pair.setId(R.id.keyholder_pair_button); pair.setBackgroundResource(R.drawable.bg_primary_button);
        pair.setTextColor(getColor(R.color.text_primary));
        LinearLayout steps = card(section);
        step(steps, "1", R.string.keyholder_step_one, R.string.keyholder_step_one_help);
        step(steps, "2", R.string.keyholder_step_two, R.string.keyholder_step_two_help);
        step(steps, "3", R.string.keyholder_step_three, R.string.keyholder_step_three_help);
        updateMethods();
        LinearLayout lock = card(page);
        TextView lockTitle = text(lock, getString(R.string.keyholder_your_lock), 16, false); lockTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        lockTitle.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_ux_lock, 0, 0, 0); lockTitle.setCompoundDrawablePadding(dp(8));
        boolean active = com.subhub.app.commitment.CommitmentManager.isActive(this);
        text(lock, active ? com.subhub.app.commitment.CommitmentManager.countdownLabel(this) : getString(R.string.keyholder_no_lock), 14, true);
        button(lock, getString(active ? R.string.keyholder_view_lock : R.string.keyholder_choose_lock), () -> startActivity(new android.content.Intent(this,
                active ? com.subhub.app.commitment.CommitmentActivity.class : com.subhub.app.MainActivity.class).setAction(android.content.Intent.ACTION_MAIN)));
        if (authenticator.isPaired()) button(section, getString(R.string.authenticator_remove), () ->
                ControllerPinGate.require(this, () -> {
                            if (new ControllerAuthenticator(this).remove()) render();
                            else notice(getString(R.string.authenticator_unavailable));
                        }, false)).setId(R.id.keyholder_remove_authenticator);
    }
    private LinearLayout method(String key, int title, int subtitle, int id) {
        LinearLayout outer = card(page); outer.setBackgroundResource(R.drawable.bg_sub_hero);
        LinearLayout header = new LinearLayout(this); header.setId(id); header.setGravity(android.view.Gravity.CENTER_VERTICAL);
        header.setMinimumHeight(dp(64)); header.setPadding(0, dp(6), 0, dp(6)); outer.addView(header);
        ImageView icon = new ImageView(this); icon.setImageResource(R.drawable.ic_keyholder); icon.setBackgroundResource(R.drawable.bg_header_icon);
        icon.setPadding(dp(10), dp(10), dp(10), dp(10)); icon.setImportantForAccessibility(android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        header.addView(icon, new LinearLayout.LayoutParams(dp(44), dp(44)));
        LinearLayout labels = new LinearLayout(this); labels.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams copy = new LinearLayout.LayoutParams(0, -2, 1); copy.leftMargin = dp(12); header.addView(labels, copy);
        TextView heading = text(labels, getString(title), 17, false); heading.setTypeface(null, android.graphics.Typeface.BOLD);
        TextView arrow = new TextView(this); arrow.setTextSize(23); arrow.setTextColor(getColor(R.color.accent_hot)); arrow.setGravity(android.view.Gravity.CENTER);
        header.addView(arrow, new LinearLayout.LayoutParams(dp(36), dp(48)));
        arrow.setImportantForAccessibility(android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        header.setFocusable(true); header.setContentDescription(getString(title) + ". " + getString(subtitle));
        androidx.core.view.ViewCompat.setScreenReaderFocusable(header, true);
        header.setOnClickListener(view -> { expandedMethod = key.equals(expandedMethod) ? "" : key; updateMethods(); });
        LinearLayout body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL); outer.addView(body, new LinearLayout.LayoutParams(-1, -2));
        methodHeaders.put(key, header); methodBodies.put(key, body); methodArrows.put(key, arrow);
        return body;
    }
    private void updateMethods() {
        for (String key : methodBodies.keySet()) {
            boolean expanded = key.equals(expandedMethod);
            methodBodies.get(key).setVisibility(expanded ? android.view.View.VISIBLE : android.view.View.GONE);
            methodArrows.get(key).setText(expanded ? "⌃" : "⌄");
            androidx.core.view.ViewCompat.setStateDescription(methodHeaders.get(key), getString(expanded ? R.string.keyholder_expanded : R.string.keyholder_collapsed));
        }
    }
    @Override protected void onSaveInstanceState(Bundle state) { state.putString("keyholder_method", expandedMethod); super.onSaveInstanceState(state); }
    @Override protected void onResume() {
        super.onResume();
        if (pendingSecret == null) render();
    }
    private void beginPairing() {
        clearPending(); pendingSecret = Totp.newSecret();
        page(R.string.keyholder_handover_title); LinearLayout setup = card(page);
        TextView scanTitle = text(setup, getString(R.string.keyholder_scan_title), 18, false); scanTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        text(setup, getString(R.string.authenticator_setup_help), 14, true);
        try {
            BitMatrix matrix = new QRCodeWriter().encode("otpauth://totp/SubHub:Controller?secret=" + pendingSecret
                    + "&issuer=SubHub&algorithm=SHA1&digits=6&period=30", BarcodeFormat.QR_CODE, 600, 600);
            int[] pixels = new int[600 * 600];
            for (int y = 0; y < 600; y++) for (int x = 0; x < 600; x++) pixels[y * 600 + x] = matrix.get(x, y) ? Color.BLACK : Color.WHITE;
            qr = Bitmap.createBitmap(pixels, 600, 600, Bitmap.Config.ARGB_8888);
            ImageView image = new ImageView(this); image.setImageBitmap(qr); image.setContentDescription(getString(R.string.authenticator_qr));
            image.setAdjustViewBounds(true); image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            LinearLayout.LayoutParams qrParams = new LinearLayout.LayoutParams(-1, dp(230)); qrParams.topMargin = dp(8); qrParams.bottomMargin = dp(8);
            setup.addView(image, qrParams);
        } catch (Exception failure) { notice(getString(R.string.authenticator_manual_help)); }
        TextView manual = text(setup, pendingSecret, 16, false);
        manual.setSaveEnabled(false); manual.setTextIsSelectable(true);
        manual.setTypeface(android.graphics.Typeface.MONOSPACE);
        manual.setGravity(android.view.Gravity.CENTER); manual.setVisibility(android.view.View.GONE);
        button(setup, getString(R.string.keyholder_manual), () -> manual.setVisibility(manual.getVisibility() == android.view.View.VISIBLE ? android.view.View.GONE : android.view.View.VISIBLE));
        LinearLayout confirmCard = card(page);
        TextView confirmTitle = text(confirmCard, getString(R.string.keyholder_confirm_title), 18, false); confirmTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        text(confirmCard, getString(R.string.keyholder_confirm_help), 14, true);
        EditText code = input(confirmCard, R.string.authenticator_code, InputType.TYPE_CLASS_NUMBER);
        code.setTextSize(24); code.setGravity(android.view.Gravity.CENTER); code.setLetterSpacing(.18f);
        code.setFilters(new android.text.InputFilter[] {new android.text.InputFilter.LengthFilter(6)});
        code.setImportantForAutofill(android.view.View.IMPORTANT_FOR_AUTOFILL_NO);
        code.setBackgroundResource(R.drawable.bg_input);
        code.setSaveEnabled(false); code.setId(R.id.authenticator_confirmation);
        Button confirm = button(confirmCard, getString(R.string.authenticator_confirm), () -> {
            String secret = pendingSecret; String entered = code.getText().toString();
            if (secret == null || !ControllerPinManager.isDomModeActive()) { clearPending(); render(); return; }
            code.setEnabled(false);
            worker.execute(() -> {
                ControllerAuthenticator.Result result = new ControllerAuthenticator(this).pair(secret, entered);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    code.setEnabled(true);
                    if (result == ControllerAuthenticator.Result.SUCCESS) { clearPending(); notice(getString(R.string.authenticator_pair_success)); render(); }
                    else code.setError(getString(result == ControllerAuthenticator.Result.THROTTLED
                            ? R.string.authenticator_throttled : result == ControllerAuthenticator.Result.INVALID
                            ? R.string.authenticator_invalid : R.string.authenticator_unavailable));
                });
            });
        });
        confirm.setId(R.id.authenticator_confirm_button);
        confirm.setBackgroundResource(R.drawable.bg_primary_button); confirm.setTextColor(getColor(R.color.text_primary));
        text(confirmCard, getString(R.string.keyholder_pending_help), 12, true);
        button(page, getString(android.R.string.cancel), () -> { clearPending(); render(); });
    }
    private void step(LinearLayout parent, String number, int title, int description) {
        LinearLayout row = new LinearLayout(this); row.setGravity(android.view.Gravity.TOP); row.setPadding(0, dp(6), 0, dp(6)); parent.addView(row);
        TextView badge = new TextView(this); badge.setText(number); badge.setTextColor(getColor(R.color.accent_hot)); badge.setGravity(android.view.Gravity.CENTER);
        badge.setBackgroundResource(R.drawable.bg_header_icon); badge.setTypeface(null, android.graphics.Typeface.BOLD);
        row.addView(badge, new LinearLayout.LayoutParams(dp(32), dp(32)));
        LinearLayout copy = new LinearLayout(this); copy.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1); params.leftMargin = dp(12); row.addView(copy, params);
        TextView label = text(copy, getString(title), 15, false); label.setTypeface(null, android.graphics.Typeface.BOLD);
        text(copy, getString(description), 13, true);
    }
    private void clearPending() { pendingSecret = null; if (qr != null) { qr.recycle(); qr = null; } }
    @Override protected void onDestroy() { clearPending(); worker.shutdownNow(); super.onDestroy(); }
}
