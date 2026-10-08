package com.subhub.app.privacy;

import android.os.Bundle;
import android.widget.LinearLayout;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import com.subhub.app.R;
import com.subhub.app.security.ControllerPinGate;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.util.PreferencePage;

public final class PrivacyActivity extends PreferencePage {
    private BiometricPrompt prompt;
    @Override protected void onCreate(Bundle state) { super.onCreate(state); page(R.string.privacy_title); ControllerPinGate.require(this, this::render, true); }
    private void render() {
        page(R.string.privacy_title); PrivacyManager privacy = new PrivacyManager(this);
        LinearLayout discreet = card(page); text(discreet, getString(R.string.privacy_discreet_heading), 18, false);
        text(discreet, getString(R.string.privacy_discreet_help), 14, true);
        toggle(discreet, R.string.privacy_discreet_mode, privacy.isDiscreet(), value -> {
            if (!ControllerPinManager.isDomModeActive() || !privacy.setDiscreet(value)) notice(getString(R.string.privacy_save_failed));
            render();
        }).setId(R.id.privacy_discreet_toggle);
        text(discreet, getString(R.string.privacy_system_identity), 12, true);
        LinearLayout lock = card(page); text(lock, getString(R.string.privacy_app_lock), 18, false);
        text(lock, getString(R.string.privacy_lock_help), 14, true);
        toggle(lock, R.string.privacy_app_lock, privacy.isAppLockEnabled(), value -> {
            if (!ControllerPinManager.isDomModeActive()) { render(); return; }
            if (!value) { privacy.setAppLock(false); render(); return; }
            render();
            if (BiometricManager.from(this).canAuthenticate(AppUnlockActivity.AUTHENTICATORS) != BiometricManager.BIOMETRIC_SUCCESS) {
                notice(getString(R.string.privacy_no_device_lock)); return;
            }
            prompt = new BiometricPrompt(this, ContextCompat.getMainExecutor(this), new BiometricPrompt.AuthenticationCallback() {
                @Override public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                    if (ControllerPinManager.isDomModeActive()) {
                        PrivacyLifecycle.unlock(); privacy.setAppLock(true); render();
                    }
                }
                @Override public void onAuthenticationError(int error, CharSequence message) { notice(message.toString()); render(); }
            });
            prompt.authenticate(new BiometricPrompt.PromptInfo.Builder().setTitle(getString(R.string.privacy_enable_lock))
                    .setAllowedAuthenticators(AppUnlockActivity.AUTHENTICATORS).build());
        }).setId(R.id.privacy_lock_toggle);
        text(lock, getString(R.string.privacy_dom_separate), 12, true);
    }
}
