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
        LinearLayout section = card(page);
        text(section, getString(R.string.authenticator_description), 14, true);
        text(section, getString(authenticator.isPaired() ? R.string.authenticator_paired : R.string.authenticator_unpaired), 18, false);
        button(section, getString(authenticator.isPaired() ? R.string.authenticator_replace : R.string.authenticator_pair), () ->
                ControllerPinGate.require(this, this::beginPairing, false));
        if (authenticator.isPaired()) button(section, getString(R.string.authenticator_remove), () ->
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
        page(R.string.authenticator_title); LinearLayout setup = card(page);
        text(setup, getString(R.string.authenticator_setup_help), 14, true);
        try {
            BitMatrix matrix = new QRCodeWriter().encode("otpauth://totp/SubHub:Controller?secret=" + pendingSecret
                    + "&issuer=SubHub&algorithm=SHA1&digits=6&period=30", BarcodeFormat.QR_CODE, 600, 600);
            int[] pixels = new int[600 * 600];
            for (int y = 0; y < 600; y++) for (int x = 0; x < 600; x++) pixels[y * 600 + x] = matrix.get(x, y) ? Color.BLACK : Color.WHITE;
            qr = Bitmap.createBitmap(pixels, 600, 600, Bitmap.Config.ARGB_8888);
            ImageView image = new ImageView(this); image.setImageBitmap(qr); image.setContentDescription(getString(R.string.authenticator_qr));
            image.setAdjustViewBounds(true); setup.addView(image, new LinearLayout.LayoutParams(-1, dp(260)));
        } catch (Exception failure) { notice(getString(R.string.authenticator_manual_help)); }
        TextView manual = text(setup, pendingSecret, 16, false);
        manual.setSaveEnabled(false); manual.setTextIsSelectable(true);
        manual.setTypeface(android.graphics.Typeface.MONOSPACE);
        EditText code = input(setup, R.string.authenticator_code, InputType.TYPE_CLASS_NUMBER);
        code.setSaveEnabled(false); code.setId(R.id.authenticator_confirmation);
        Button confirm = button(setup, getString(R.string.authenticator_confirm), () -> {
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
        button(setup, getString(android.R.string.cancel), () -> { clearPending(); render(); });
    }
    private void clearPending() { pendingSecret = null; if (qr != null) { qr.recycle(); qr = null; } }
    @Override protected void onDestroy() { clearPending(); worker.shutdownNow(); super.onDestroy(); }
}
