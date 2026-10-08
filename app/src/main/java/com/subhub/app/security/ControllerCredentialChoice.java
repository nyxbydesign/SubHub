package com.subhub.app.security;

import android.app.Activity;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import com.subhub.app.R;

/** Explicit credential selection avoids treating an existing six-digit PIN as a TOTP code. */
public final class ControllerCredentialChoice {
    private final Activity activity;
    private final EditText input;
    private boolean useAuthenticator;
    public ControllerCredentialChoice(Activity activity, LinearLayout panel, EditText input) {
        this.activity = activity; this.input = input;
        if (!new ControllerAuthenticator(activity).isPaired()) return;
        RadioGroup methods = new RadioGroup(activity); methods.setOrientation(LinearLayout.HORIZONTAL);
        RadioButton pin = new RadioButton(activity); pin.setId(android.view.View.generateViewId()); pin.setText(R.string.authenticator_method_pin);
        RadioButton code = new RadioButton(activity); code.setId(android.view.View.generateViewId()); code.setText(R.string.authenticator_method_code);
        pin.setTextColor(activity.getColor(R.color.text_primary)); code.setTextColor(activity.getColor(R.color.text_primary));
        methods.addView(pin, new LinearLayout.LayoutParams(0, -2, 1)); methods.addView(code, new LinearLayout.LayoutParams(0, -2, 1));
        methods.check(pin.getId()); panel.addView(methods);
        methods.setOnCheckedChangeListener((group, id) -> {
            useAuthenticator = id == code.getId(); input.setText(""); input.setError(null);
            input.setHint(useAuthenticator ? R.string.authenticator_code : R.string.controller_pin_label);
            input.setInputType(InputType.TYPE_CLASS_NUMBER | (useAuthenticator ? 0 : InputType.TYPE_NUMBER_VARIATION_PASSWORD));
        });
    }
    public boolean verify() {
        if (!useAuthenticator) {
            boolean valid = ControllerPinManager.verify(activity, input.getText().toString());
            if (!valid) {
                com.subhub.app.penance.TamperTributeReporter.record(activity);
                input.setError(activity.getString(R.string.controller_pin_wrong));
            }
            return valid;
        }
        ControllerAuthenticator.Result result = new ControllerAuthenticator(activity).verify(input.getText().toString());
        if (result == ControllerAuthenticator.Result.SUCCESS) return true;
        if (result == ControllerAuthenticator.Result.INVALID) com.subhub.app.penance.TamperTributeReporter.record(activity);
        input.setError(activity.getString(result == ControllerAuthenticator.Result.THROTTLED
                ? R.string.authenticator_throttled : result == ControllerAuthenticator.Result.INVALID
                ? R.string.authenticator_invalid : R.string.authenticator_unavailable));
        return false;
    }
}
