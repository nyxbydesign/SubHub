package com.subhub.app.privacy;

import android.widget.*;
import androidx.fragment.app.FragmentActivity;
import androidx.biometric.*;
import androidx.core.content.ContextCompat;
import com.subhub.app.R;
import com.subhub.app.security.*;
import com.subhub.app.util.StateToggle;

/** Inline privacy controls, also reused by the direct privacy screen. */
public final class PrivacyControls {
    private final FragmentActivity activity;
    private final LinearLayout container;
    private final Runnable changed;
    private BiometricPrompt prompt;
    private PrivacyControls(FragmentActivity activity, LinearLayout container, Runnable changed) {
        this.activity=activity; this.container=container; this.changed=changed;
    }
    public static void bind(FragmentActivity activity, LinearLayout container, Runnable changed) {
        PrivacyControls controls=new PrivacyControls(activity,container,changed); container.setTag(controls); controls.render();
    }
    private void render() {
        container.removeAllViews(); PrivacyManager privacy=new PrivacyManager(activity);
        toggle(R.string.privacy_discreet_mode,R.id.privacy_discreet_toggle,privacy.isDiscreet(),value -> {
            changed.run();
            ControllerPinGate.require(activity,()->{ if(!privacy.setDiscreet(value)) notice(activity.getString(R.string.privacy_save_failed)); changed.run(); },false);
        });
        help(R.string.privacy_discreet_help);
        toggle(R.string.privacy_app_lock,R.id.privacy_lock_toggle,privacy.isAppLockEnabled(),value -> {
            changed.run(); ControllerPinGate.require(activity,()->{
                if(!value) { privacy.setAppLock(false); changed.run(); return; }
                if(BiometricManager.from(activity).canAuthenticate(AppUnlockActivity.AUTHENTICATORS)!=BiometricManager.BIOMETRIC_SUCCESS) { notice(activity.getString(R.string.privacy_no_device_lock)); return; }
                prompt=new BiometricPrompt(activity,ContextCompat.getMainExecutor(activity),new BiometricPrompt.AuthenticationCallback(){
                    @Override public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                        if(ControllerPinManager.isDomModeActive()) { PrivacyLifecycle.unlock(); privacy.setAppLock(true); changed.run(); }
                    }
                    @Override public void onAuthenticationError(int error,CharSequence message) { notice(message.toString()); changed.run(); }
                });
                prompt.authenticate(new BiometricPrompt.PromptInfo.Builder().setTitle(activity.getString(R.string.privacy_enable_lock)).setAllowedAuthenticators(AppUnlockActivity.AUTHENTICATORS).build());
            },false);
        });
        help(R.string.privacy_lock_help);
    }
    private void toggle(int label,int id,boolean value,java.util.function.Consumer<Boolean> action) {
        StateToggle control=new StateToggle(activity); control.setId(id); control.setText(label); control.setTextSize(14); control.setTextColor(activity.getColor(R.color.text_primary));
        control.setChecked(value); control.setOnCheckedChangeListener((button,checked)->action.accept(checked)); container.addView(control,new LinearLayout.LayoutParams(-1,-2));
    }
    private void help(int label) {
        TextView text=new TextView(activity); text.setText(label); text.setTextSize(12); text.setTextColor(activity.getColor(R.color.text_secondary));
        int pad=Math.round(8*activity.getResources().getDisplayMetrics().density); text.setPadding(0,0,0,pad); container.addView(text,new LinearLayout.LayoutParams(-1,-2));
    }
    private void notice(String text) { Toast.makeText(activity,text,Toast.LENGTH_LONG).show(); }
}
