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
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state); getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        page(R.string.authenticator_title);
        ControllerPinGate.require(this, this::render, true);
    }
    private void render() {
        if (isFinishing() || isDestroyed()) return;
        page(R.string.authenticator_title);
        ControllerAuthenticator authenticator = new ControllerAuthenticator(this);
        LinearLayout section = card(page); section.setBackgroundResource(R.drawable.bg_sub_hero);
        section.setPadding(dp(20), dp(20), dp(20), dp(18));
        LinearLayout identity = new LinearLayout(this); identity.setGravity(android.view.Gravity.CENTER_VERTICAL); section.addView(identity);
        ImageView key = new ImageView(this); key.setImageResource(R.drawable.ic_keyholder); key.setBackgroundResource(R.drawable.bg_header_icon);
        key.setPadding(dp(14), dp(14), dp(14), dp(14)); identity.addView(key, new LinearLayout.LayoutParams(dp(56), dp(56)));
        key.setImportantForAccessibility(android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout words = new LinearLayout(this); words.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams wordParams = new LinearLayout.LayoutParams(0, -2, 1); wordParams.leftMargin = dp(14); identity.addView(words, wordParams);
        TextView eyebrow = text(words, getString(R.string.keyholder_your_key), 11, true); eyebrow.setLetterSpacing(.12f);
        TextView title = text(words, getString(authenticator.isPaired() ? R.string.authenticator_paired : R.string.authenticator_unpaired), 20, false);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        text(section, getString(authenticator.isPaired() ? R.string.keyholder_paired_help : R.string.authenticator_description), 14, true);
        Button pair = button(section, getString(authenticator.isPaired() ? R.string.authenticator_replace : R.string.authenticator_pair), () ->
                ControllerPinGate.require(this, this::beginPairing, false));
        pair.setId(R.id.keyholder_pair_button); pair.setBackgroundResource(R.drawable.bg_primary_button);
        pair.setTextColor(getColor(R.color.text_primary));
        TextView heading = text(page, getString(R.string.keyholder_how), 14, false); heading.setTextColor(getColor(R.color.accent_hot)); heading.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout steps = card(page);
        step(steps, "1", R.string.keyholder_step_one, R.string.keyholder_step_one_help);
        step(steps, "2", R.string.keyholder_step_two, R.string.keyholder_step_two_help);
        step(steps, "3", R.string.keyholder_step_three, R.string.keyholder_step_three_help);
        LinearLayout lock = card(page);
        TextView lockTitle = text(lock, getString(R.string.keyholder_your_lock), 16, false); lockTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        lockTitle.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_ux_lock, 0, 0, 0); lockTitle.setCompoundDrawablePadding(dp(8));
        boolean active = com.subhub.app.commitment.CommitmentManager.isActive(this);
        text(lock, active ? com.subhub.app.commitment.CommitmentManager.countdownLabel(this) : getString(R.string.keyholder_no_lock), 14, true);
        button(lock, getString(active ? R.string.keyholder_view_lock : R.string.keyholder_choose_lock), () -> startActivity(new android.content.Intent(this,
                active ? com.subhub.app.commitment.CommitmentActivity.class : com.subhub.app.MainActivity.class).setAction(android.content.Intent.ACTION_MAIN)));
        text(page, getString(R.string.keyholder_pin_help), 12, true);
        if (authenticator.isPaired()) button(page, getString(R.string.authenticator_remove), () ->
                ControllerPinGate.require(this, () -> com.subhub.app.util.ThemedDialogs.builder(this)
                        .setTitle(R.string.authenticator_remove).setMessage(R.string.authenticator_remove_message)
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(R.string.authenticator_remove, (d, w) -> {
                            if (new ControllerAuthenticator(this).remove()) render();
                            else notice(getString(R.string.authenticator_unavailable));
                        }).show(), false));
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
