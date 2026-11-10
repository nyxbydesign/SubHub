package com.subhub.app.security;

import android.app.Activity;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import com.subhub.app.R;

/** One input accepts a local PIN or legacy code; an assigned Dom remains the sole authority. */
public final class ControllerCredentialChoice {
    private final Activity activity;
    private final EditText input;
    private final KeyholderProvider.State authority;
    private final KeyholderProvider provider;
    private final boolean workspaceOnly;
    public ControllerCredentialChoice(Activity activity, LinearLayout panel, EditText input) {
        this(activity, panel, input, false);
    }
    ControllerCredentialChoice(Activity activity, LinearLayout panel, EditText input, boolean workspaceOnly) {
        this.activity = activity; this.input = input; this.workspaceOnly = workspaceOnly;
        provider = com.subhub.app.people.People.keyholder();
        authority = workspaceOnly ? KeyholderProvider.State.NONE : currentAuthority();
        if (workspaceOnly) { input.setHint(R.string.controller_pin_label); return; }
        if (authority != KeyholderProvider.State.NONE) {
            input.setHint(R.string.authenticator_code);
            input.setInputType(InputType.TYPE_CLASS_NUMBER);
            return;
        }
        if (new ControllerAuthenticator(activity).isPaired())
            input.setHint(ControllerPinManager.isConfigured(activity) ? R.string.keyholder_pin_or_code : R.string.authenticator_code);
    }
    public boolean verify() {
        if (workspaceOnly) {
            if (isCurrentAuthority() && ControllerPinManager.verifyLocalDomWorkspace(activity, input.getText().toString())) return true;
            input.setError(activity.getString(R.string.controller_pin_wrong));
            return false;
        }
        if (!isCurrentAuthority() || authority==KeyholderProvider.State.PENDING || authority==KeyholderProvider.State.UNAVAILABLE) {
            input.setError(activity.getString(R.string.keyholder_remote_code_unavailable));
            return false;
        }
        String value = input.getText().toString();
        if (authority == KeyholderProvider.State.NONE && ControllerPinManager.verify(activity, value)) return true;
        if (!isCurrentAuthority()) {
            input.setError(activity.getString(R.string.keyholder_remote_code_unavailable));return false;
        }
        boolean remote = authority != KeyholderProvider.State.NONE;
        ControllerAuthenticator authenticator = new ControllerAuthenticator(activity);
        if (!remote && !authenticator.isPaired()) {
            com.subhub.app.penance.TamperTributeReporter.record(activity);
            input.setError(activity.getString(R.string.controller_pin_wrong));return false;
        }
        ControllerAuthenticator.Result result;
        try { result = remote ? provider.verifyCode(value) : authenticator.verify(value); }
        catch (RuntimeException unavailable) { result = ControllerAuthenticator.Result.UNAVAILABLE; }
        if (result == ControllerAuthenticator.Result.SUCCESS) return true;
        if (result == ControllerAuthenticator.Result.INVALID) com.subhub.app.penance.TamperTributeReporter.record(activity);
        input.setError(activity.getString(result == ControllerAuthenticator.Result.THROTTLED
                ? remote ? R.string.keyholder_remote_throttled : R.string.authenticator_throttled
                : result == ControllerAuthenticator.Result.INVALID ? R.string.authenticator_invalid
                : remote ? R.string.keyholder_remote_code_unavailable : R.string.authenticator_unavailable));
        return false;
    }

    public boolean isCurrentAuthority() { return provider == com.subhub.app.people.People.keyholder()
            && (workspaceOnly ? ControllerPinManager.canUnlockLocalDomWorkspace(activity) : authority == currentAuthority()); }

    private static KeyholderProvider.State currentAuthority() {
        try { return com.subhub.app.people.People.keyholder().state(); }
        catch (RuntimeException unavailable) { return KeyholderProvider.State.UNAVAILABLE; }
    }
}
