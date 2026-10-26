package com.subhub.app.security;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
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
        super.onCreate(state);
        if (state != null) expandedMethod = state.getString("keyholder_method", "pin");
        else if ("remote".equals(getIntent().getStringExtra("keyholder_method"))) expandedMethod = "remote";
        page(R.string.authenticator_title);
        getOnBackPressedDispatcher().addCallback(this, new androidx.activity.OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (pendingSecret != null) { clearPending(); render(); } else finish();
            }
        });
    }
    private void render() {
        if (isFinishing() || isDestroyed() || !ControllerPinManager.isDomModeActive()) return;
        page(R.string.authenticator_title);
        ControllerAuthenticator authenticator = new ControllerAuthenticator(this);
        methodBodies.clear(); methodArrows.clear(); methodHeaders.clear();
        LinearLayout pinCard = method("pin", R.string.role_pin, R.string.keyholder_pin_subtitle, R.id.keyholder_pin_header);
        button(pinCard, getString(ControllerPinManager.isConfigured(this) ? R.string.keyholder_pin_change : R.string.controller_pin_set),
                () -> ControllerPinGate.changePin(this, this::render)).setId(R.id.keyholder_pin_change_button);
        if (ControllerPinManager.isConfigured(this)) button(pinCard, getString(R.string.keyholder_remove_pin), () ->
                ControllerPinGate.require(this, () -> {
                    if (ControllerPinManager.removePin(this)) render();
                    else notice(getString(R.string.authenticator_unavailable));
                }, false)).setId(R.id.keyholder_remove_pin);
        LinearLayout section = method("remote", R.string.role_authenticator, R.string.keyholder_remote_subtitle, R.id.keyholder_remote_header);
        TextView status = text(section, getString(authenticator.isPaired() ? R.string.authenticator_paired : R.string.authenticator_unpaired), 18, false);
        status.setTypeface(null, android.graphics.Typeface.BOLD);
        Button pair = button(section, getString(authenticator.isPaired() ? R.string.role_replace_authenticator : R.string.role_pair_authenticator), () ->
                ControllerPinGate.require(this, this::beginPairing, false));
        pair.setId(R.id.keyholder_pair_button); pair.setBackgroundResource(R.drawable.bg_primary_button);
        pair.setTextColor(getColor(R.color.text_primary));
        updateMethods();
        pairedDevices();
        if (authenticator.isPaired()) button(section, getString(R.string.authenticator_remove), () ->
                ControllerPinGate.require(this, () -> {
                            if (new ControllerAuthenticator(this).remove()) render();
                            else notice(getString(R.string.authenticator_unavailable));
                        }, false)).setId(R.id.keyholder_remove_authenticator);
    }
    private LinearLayout method(String key, int title, int subtitle, int id) {
        LinearLayout outer = card(page);
        LinearLayout header = new LinearLayout(this); header.setId(id); header.setGravity(android.view.Gravity.CENTER_VERTICAL);
        header.setMinimumHeight(dp(64)); header.setPadding(0, dp(6), 0, dp(6)); outer.addView(header);
        ImageView icon = new ImageView(this); icon.setImageResource(R.drawable.ic_keyholder); icon.setBackgroundResource(R.drawable.bg_header_icon);
        icon.setPadding(dp(10), dp(10), dp(10), dp(10)); icon.setImportantForAccessibility(android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        header.addView(icon, new LinearLayout.LayoutParams(dp(44), dp(44)));
        LinearLayout labels = new LinearLayout(this); labels.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams copy = new LinearLayout.LayoutParams(0, -2, 1); copy.leftMargin = dp(12); header.addView(labels, copy);
        TextView heading = text(labels, getString(title), 17, false); heading.setTypeface(null, android.graphics.Typeface.BOLD);
        if ("remote".equals(key)) text(labels, getString(new ControllerAuthenticator(this).isPaired()
                ? R.string.authenticator_paired : R.string.authenticator_unpaired), 13, true);
        else text(labels, getString(ControllerPinManager.isConfigured(this)
                ? R.string.keyholder_status_pin : R.string.keyholder_status_none), 13, true);
        TextView arrow = new TextView(this); com.subhub.app.util.UiIdentity.textSize(arrow, R.dimen.ui_icon_text); arrow.setTextColor(getColor(R.color.accent_hot)); arrow.setGravity(android.view.Gravity.CENTER);
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

    private void pairedDevices() {
        LinearLayout devices = card(page);
        com.subhub.app.people.PersonUi.text(devices, getString(R.string.role_paired_devices), true);
        com.subhub.app.people.PersonRepository repository = com.subhub.app.people.People.repository(getApplicationContext());
        com.subhub.app.util.AsyncUiScope.forPage(this).load("paired-devices", () -> {
            java.util.List<com.subhub.app.people.PersonSnapshot> values = new java.util.ArrayList<>();
            for (com.subhub.app.people.PersonId id : repository.people()) {
                if (!id.isLocal()) values.add(repository.load(id));
            }
            return values;
        }, people -> {
            if (pendingSecret != null || !ControllerPinManager.isDomModeActive()) return;
            if (people.isEmpty()) text(devices, getString(R.string.role_no_paired_devices), 14, true);
            for (com.subhub.app.people.PersonSnapshot person : people) {
                com.subhub.app.people.PersonUi.row(devices, R.drawable.ic_nav_people, person.name,
                        com.subhub.app.people.PersonUi.connection(this, person),
                        com.subhub.app.people.People.connectionUi() == null ? null : () -> {
                            com.subhub.app.people.ConnectionUi ui = com.subhub.app.people.People.connectionUi();
                            if (ui != null && ControllerPinManager.isDomModeActive()) ui.openConnection(this, person.id);
                        });
            }
        }, failure -> text(devices, getString(R.string.role_connection_error), 14, true));
        if (com.subhub.app.people.People.connectionUi() != null) {
            button(devices, getString(R.string.role_pair_device), () -> {
                com.subhub.app.people.ConnectionUi ui = com.subhub.app.people.People.connectionUi();
                if (ui != null) ControllerPinGate.require(this, () -> ui.openPairing(this), false);
            });
        }
    }
    @Override protected void onSaveInstanceState(Bundle state) { state.putString("keyholder_method", expandedMethod); super.onSaveInstanceState(state); }
    @Override protected void onResume() {
        super.onResume();
        if (!ControllerPinManager.isDomModeActive()) {
            clearPending();
            page(R.string.authenticator_title);
            ControllerPinGate.require(this, this::render, true);
        } else if (pendingSecret == null) render();
    }
    private void beginPairing() {
        clearPending(); pendingSecret = Totp.newSecret();
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
        page(R.string.keyholder_handover_title);
        com.subhub.app.util.PrimaryHeader.backButton(page).setOnClickListener(v -> { clearPending(); render(); });
        LinearLayout setup = (LinearLayout) getLayoutInflater().inflate(R.layout.view_authenticator_setup, page, false);
        page.addView(setup);
        ImageView image = setup.findViewById(R.id.authenticator_pairing_qr);
        try {
            BitMatrix matrix = new QRCodeWriter().encode("otpauth://totp/SubHub:Dom?secret=" + pendingSecret
                    + "&issuer=SubHub&algorithm=SHA1&digits=6&period=30", BarcodeFormat.QR_CODE, 600, 600);
            int[] pixels = new int[600 * 600];
            for (int y = 0; y < 600; y++) for (int x = 0; x < 600; x++) pixels[y * 600 + x] = matrix.get(x, y) ? Color.BLACK : Color.WHITE;
            qr = Bitmap.createBitmap(pixels, 600, 600, Bitmap.Config.ARGB_8888);
            image.setImageBitmap(qr);
        } catch (Exception failure) { notice(getString(R.string.authenticator_manual_help)); }
        TextView manual = setup.findViewById(R.id.authenticator_manual_key);
        manual.setText(pendingSecret); manual.setSaveEnabled(false);
        TextView manualToggle = setup.findViewById(R.id.authenticator_manual_toggle);
        manualToggle.setOnClickListener(v -> {
            boolean showing = manual.getVisibility() == android.view.View.VISIBLE;
            manual.setVisibility(showing ? android.view.View.GONE : android.view.View.VISIBLE);
            manualToggle.setText(showing ? R.string.keyholder_manual : R.string.keyholder_manual_hide);
        });
        EditText code = setup.findViewById(R.id.authenticator_confirmation);
        code.setFilters(new android.text.InputFilter[] {new android.text.InputFilter.LengthFilter(6)});
        Button confirm = setup.findViewById(R.id.authenticator_confirm_button);
        confirm.setEnabled(false);
        code.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence value,int start,int count,int after) { }
            public void onTextChanged(CharSequence value,int start,int before,int count) {
                confirm.setEnabled(code.isEnabled() && value.length() == 6);
            }
            public void afterTextChanged(android.text.Editable value) { }
        });
        confirm.setOnClickListener(view -> {
            String secret = pendingSecret; String entered = code.getText().toString();
            if (secret == null || !ControllerPinManager.isDomModeActive()) { clearPending(); render(); return; }
            code.setEnabled(false); confirm.setEnabled(false);
            worker.execute(() -> {
                ControllerAuthenticator.Result result = new ControllerAuthenticator(this).pair(secret, entered);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    code.setEnabled(true); confirm.setEnabled(code.length() == 6);
                    if (result == ControllerAuthenticator.Result.SUCCESS) { clearPending(); notice(getString(R.string.authenticator_pair_success)); render(); }
                    else code.setError(getString(result == ControllerAuthenticator.Result.THROTTLED
                            ? R.string.authenticator_throttled : result == ControllerAuthenticator.Result.INVALID
                            ? R.string.authenticator_invalid : R.string.authenticator_unavailable));
                });
            });
        });
        ControllerPinGate.submitOnEnter(code, () -> {
            if (confirm.isEnabled()) confirm.performClick();
        });
        setup.findViewById(R.id.authenticator_pairing_cancel).setOnClickListener(v -> { clearPending(); render(); });
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
    private void clearPending() {
        pendingSecret = null;
        getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
        if (qr != null) { qr.recycle(); qr = null; }
    }
    @Override protected void onDestroy() { clearPending(); worker.shutdownNow(); super.onDestroy(); }
}
