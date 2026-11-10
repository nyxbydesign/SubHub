package com.subhub.app.security;

import android.app.Activity;
import android.content.Context;
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
import com.subhub.app.util.AsyncUiScope;

/** Onboarding and Settings share the same durable local or remote keyholder presentation. */
public final class KeyholderSetupView extends LinearLayout {
    private final Activity activity;
    private final Context application;
    private final AsyncUiScope work;
    private final String statusJob = "keyholder-status:" + View.generateViewId();
    private final Handler clock = new Handler(Looper.getMainLooper());
    private String renderedState = "";
    private long epoch;
    private KeyholderProvider observedProvider;
    private DeviceRolePreferences.Mode observedRole;
    private boolean observedDomWorkspace;
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
        application = activity.getApplicationContext();
        work = AsyncUiScope.forContext(activity);
        setOrientation(VERTICAL);
        renderUnavailable();
    }

    public void refresh() {
        if (!isAttachedToWindow() || getWindowVisibility() != View.VISIBLE || activity.isFinishing() || activity.isDestroyed()) return;
        KeyholderProvider provider = People.keyholder();
        DeviceRolePreferences.Mode role = DeviceRolePreferences.get(application);
        boolean domWorkspace = DeviceRolePreferences.isDomWorkspace(application);
        boolean sameContext = observedProvider == provider && observedRole == role
                && observedDomWorkspace == domWorkspace;
        if (sameContext && work.isPending(statusJob)) return;
        if (!sameContext) renderUnavailable();
        observedProvider = provider; observedRole = role; observedDomWorkspace = domWorkspace;
        long request = ++epoch;
        Context app = application;
        // These observations can decrypt through KeyStore/Binder; workers retain no Activity or View.
        work.load(statusJob, () -> readStatus(app, provider), status -> {
            if (!current(request, provider, role, domWorkspace)) { refresh(); return; }
            render(status, role);
        }, failure -> {
            if (!current(request, provider, role, domWorkspace)) { refresh(); return; }
            renderUnavailable();
        });
    }

    private boolean current(long request, KeyholderProvider provider, DeviceRolePreferences.Mode role, boolean domWorkspace) {
        return request == epoch && isAttachedToWindow() && getWindowVisibility() == View.VISIBLE
                && !activity.isFinishing() && !activity.isDestroyed() && People.keyholder() == provider
                && DeviceRolePreferences.get(application) == role
                && DeviceRolePreferences.isDomWorkspace(application) == domWorkspace;
    }

    private static Status readStatus(Context app, KeyholderProvider provider) {
        KeyholderProvider.State state = java.util.Objects.requireNonNull(provider.state());
        String dom = state == KeyholderProvider.State.NONE ? "" : provider.assignedDomName();
        boolean pin = ControllerPinManager.isConfigured(app);
        boolean legacyCode = state == KeyholderProvider.State.NONE && new ControllerAuthenticator(app).isPaired();
        return new Status(state, dom == null ? "" : dom, pin, legacyCode);
    }

    private void renderUnavailable() {
        render(new Status(KeyholderProvider.State.UNAVAILABLE, "", false, false), DeviceRolePreferences.get(application));
    }

    private void render(Status status, DeviceRolePreferences.Mode role) {
        KeyholderProvider.State state = status.state;
        String dom = status.dom;
        boolean pin = status.pin, legacyCode = status.legacyCode;
        boolean ownDomPin = role == DeviceRolePreferences.Mode.BOTH && ControllerPinManager.isDomWorkspaceUnlocked()
                && ControllerPinManager.canUnlockLocalDomWorkspace(application);
        String signature = role + ":" + ownDomPin + ":" + state + ":" + dom + ":" + pin + ":" + legacyCode;
        if (signature.equals(renderedState)) return;
        renderedState = signature;
        removeAllViews();
        if (state != KeyholderProvider.State.NONE) {
            if (ownDomPin) {
                LinearLayout workspace = PersonUi.card(this);
                workspace.setId(R.id.keyholder_dom_workspace_status);
                PersonUi.text(workspace, activity.getString(R.string.keyholder_dom_workspace), true);
                PersonUi.text(workspace, activity.getString(R.string.keyholder_status_pin), false);
                PersonUi.text(workspace, activity.getString(R.string.keyholder_dom_workspace_help), false);
            }
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

        if (role == DeviceRolePreferences.Mode.DOM_ONLY) {
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
        renderUnavailable();
        clock.removeCallbacks(observe);
        clock.post(observe);
    }

    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (clock == null || work == null) return;
        clock.removeCallbacks(observe);
        if (visibility == View.VISIBLE && isAttachedToWindow()) clock.post(observe);
        else cancelStatus();
    }

    @Override protected void onDetachedFromWindow() {
        clock.removeCallbacks(observe);
        cancelStatus();
        super.onDetachedFromWindow();
    }

    private void cancelStatus() {
        ++epoch;
        work.cancel(statusJob);
        observedProvider = null;
    }

    private static final class Status {
        final KeyholderProvider.State state;
        final String dom;
        final boolean pin, legacyCode;
        Status(KeyholderProvider.State state, String dom, boolean pin, boolean legacyCode) {
            this.state = state; this.dom = dom; this.pin = pin; this.legacyCode = legacyCode;
        }
    }
}
