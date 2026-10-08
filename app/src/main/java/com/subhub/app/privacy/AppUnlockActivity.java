package com.subhub.app.privacy;

import android.os.Bundle;
import android.content.Intent;
import android.provider.Settings;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import com.subhub.app.R;
import com.subhub.app.util.PreferencePage;
import com.subhub.app.util.PrimaryHeader;

/** Device authentication opens the app only; it never invokes controller authorization. */
public final class AppUnlockActivity extends PreferencePage {
    public static final int AUTHENTICATORS = BiometricManager.Authenticators.BIOMETRIC_WEAK | BiometricManager.Authenticators.DEVICE_CREDENTIAL;
    private BiometricPrompt prompt;
    private TextView status;
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state); page(R.string.privacy_discreet_name);
        getOnBackPressedDispatcher().addCallback(this, new androidx.activity.OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { moveTaskToBack(true); }
        });
        PrimaryHeader.backButton(page).setOnClickListener(view -> moveTaskToBack(true));
        LinearLayout panel = card(page); text(panel, getString(R.string.privacy_locked), 22, false);
        status = text(panel, getString(R.string.privacy_unlock_help), 14, true);
        button(panel, getString(R.string.privacy_unlock), this::authenticate).setId(R.id.privacy_unlock_button);
        button(panel, getString(R.string.privacy_security_settings), () -> startActivity(new Intent(Settings.ACTION_SECURITY_SETTINGS)));
        prompt = new BiometricPrompt(this, ContextCompat.getMainExecutor(this), new BiometricPrompt.AuthenticationCallback() {
            @Override public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) { PrivacyLifecycle.unlock(); finish(); }
            @Override public void onAuthenticationError(int code, CharSequence message) { status.setText(message); }
        });
        if (state == null) panel.post(this::authenticate);
    }
    private void authenticate() {
        if (!new PrivacyManager(this).isAppLockEnabled()) { PrivacyLifecycle.unlock(); finish(); return; }
        if (BiometricManager.from(this).canAuthenticate(AUTHENTICATORS) != BiometricManager.BIOMETRIC_SUCCESS) {
            status.setText(R.string.privacy_no_device_lock); return;
        }
        prompt.authenticate(new BiometricPrompt.PromptInfo.Builder().setTitle(getString(R.string.privacy_unlock))
                .setSubtitle(getString(R.string.privacy_unlock_help)).setAllowedAuthenticators(AUTHENTICATORS).build());
    }
}
