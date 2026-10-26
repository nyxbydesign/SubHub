package com.subhub.app.security;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.subhub.app.R;
import com.subhub.app.people.ConnectionUi;
import com.subhub.app.people.People;
import com.subhub.app.people.PersonId;
import com.subhub.app.people.PersonUi;

/** Onboarding and Settings share the same durable local or remote keyholder presentation. */
public final class KeyholderSetupView extends LinearLayout {
    private final Activity activity;
    private final Handler clock = new Handler(Looper.getMainLooper());
    private String renderedState = "";
    private final Runnable observe = new Runnable() {
        @Override public void run() {
            if (!isAttachedToWindow() || getWindowVisibility() != View.VISIBLE) return;
            refresh();
            clock.postDelayed(this, 1000);
        }
    };

    public KeyholderSetupView(Activity activity) {
        super(activity);
        this.activity = activity;
        setOrientation(VERTICAL);
        refresh();
    }

    public void refresh() {
        KeyholderProvider.State state;
        String dom;
        try {
            state = People.keyholder().state();
            dom = state == KeyholderProvider.State.NONE ? "" : People.keyholder().assignedDomName();
            if (dom == null) dom = "";
        } catch (RuntimeException unavailable) {
            state = KeyholderProvider.State.UNAVAILABLE;
            dom = "";
        }
        boolean pin = ControllerPinManager.isConfigured(activity);
        boolean legacyCode = state == KeyholderProvider.State.NONE
                && new ControllerAuthenticator(activity).isPaired();
        String signature = DeviceRolePreferences.get(activity) + ":" + state + ":" + dom + ":" + pin + ":" + legacyCode;
        if (signature.equals(renderedState)) return;
        renderedState = signature;
        removeAllViews();
        if (state != KeyholderProvider.State.NONE) {
            remote(state, dom);
            return;
        }

        LinearLayout local = PersonUi.card(this);
        PersonUi.text(local, activity.getString(R.string.keyholder_local_control), true);
        PersonUi.text(local, activity.getString(pin ? R.string.keyholder_status_pin
                : legacyCode ? R.string.keyholder_status_auth : R.string.keyholder_local_help), false);
        TextView configure = PersonUi.action(local,
                pin ? R.string.keyholder_pin_change : R.string.controller_pin_set,
                () -> ControllerPinGate.setupLocalPin(activity, this::refresh), !pin);
        configure.setId(R.id.keyholder_pin_change_button);
        local.setId(R.id.keyholder_setup_local);
        if (pin) PersonUi.action(local, R.string.keyholder_remove_pin, () -> {
            if (!localAvailable()) return;
            ControllerPinGate.unlock(activity, () -> {
                if (localAvailable() && ControllerPinManager.removePin(activity)) refresh();
            }, false);
        }, false).setId(R.id.keyholder_remove_pin);

        if (legacyCode) PersonUi.action(local, R.string.authenticator_remove, () -> {
            if (!localAvailable()) return;
            ControllerPinGate.authenticate(activity, () -> {
                if (localAvailable() && new ControllerAuthenticator(activity).remove()) refresh();
            }, false);
        }, false).setId(R.id.keyholder_remove_authenticator);

        if (DeviceRolePreferences.get(activity) == DeviceRolePreferences.Mode.DOM_ONLY) {
            PersonUi.action(local, R.string.role_add_sub, () -> {
                if (!localAvailable()) return;
                ControllerPinGate.unlock(activity, () -> {
                    ConnectionUi ui = People.connectionUi();
                    if (ui != null && DeviceRolePreferences.supportsDom(activity)) ui.openPairing(activity, true);
                }, false);
            }, false).setId(R.id.keyholder_add_sub);
            return;
        }

        LinearLayout remote = PersonUi.card(this);
        remote.setId(R.id.keyholder_setup_remote);
        PersonUi.text(remote, activity.getString(R.string.keyholder_remote_control), true);
        PersonUi.text(remote, activity.getString(R.string.keyholder_remote_help), false);
        PersonUi.action(remote, R.string.role_pair_with_dom, () -> {
            if (!localAvailable()) return;
            ConnectionUi ui = People.connectionUi();
            if (ui != null) ui.openPairing(activity, false);
            else android.widget.Toast.makeText(activity, R.string.role_pairing_unavailable,
                    android.widget.Toast.LENGTH_SHORT).show();
        }, false).setId(R.id.keyholder_pair_device);
    }

    private boolean localAvailable() {
        try {
            return People.keyholder().state() == KeyholderProvider.State.NONE
                    && People.isLocalConfigurationAllowed();
        } catch (RuntimeException unavailable) { return false; }
    }

    private void remote(KeyholderProvider.State state, String dom) {
        LinearLayout assigned = PersonUi.card(this);
        if (state == KeyholderProvider.State.UNAVAILABLE && dom.isEmpty()) {
            PersonUi.text(assigned, activity.getString(R.string.keyholder_unavailable), true)
                    .setId(R.id.keyholder_setup_status);
            PersonUi.action(assigned, R.string.role_retry, () -> {
                renderedState = "";
                refresh();
            }, false);
            return;
        }
        PersonUi.text(assigned, activity.getString(R.string.role_your_dom), true);
        if (!dom.isEmpty()) PersonUi.text(assigned, dom, true).setId(R.id.keyholder_assigned_dom);
        PersonUi.text(assigned, activity.getString(state == KeyholderProvider.State.READY
                ? R.string.role_connection_paired : state == KeyholderProvider.State.PENDING
                ? R.string.keyholder_pairing_in_progress : R.string.keyholder_unavailable), false)
                .setId(R.id.keyholder_setup_status);
        if (state == KeyholderProvider.State.READY)
            PersonUi.text(assigned, activity.getString(R.string.keyholder_remote_code_help), false);
        PersonUi.action(assigned, R.string.role_connection, () -> {
            ConnectionUi ui = People.connectionUi();
            if (ui != null) ui.openConnection(activity, PersonId.LOCAL);
        }, false).setId(R.id.settings_connection);
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        clock.removeCallbacks(observe);
        clock.post(observe);
    }

    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (clock == null) return;
        clock.removeCallbacks(observe);
        if (visibility == View.VISIBLE && isAttachedToWindow()) clock.post(observe);
    }

    @Override protected void onDetachedFromWindow() {
        clock.removeCallbacks(observe);
        super.onDetachedFromWindow();
    }
}
