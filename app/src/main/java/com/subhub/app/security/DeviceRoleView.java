package com.subhub.app.security;

import android.app.Activity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import com.subhub.app.R;
import com.subhub.app.onboarding.OnboardingState;
import com.subhub.app.people.People;
import com.subhub.app.people.PersonUi;

/** One role selector shared by first setup and device Settings. */
public final class DeviceRoleView extends LinearLayout {
    private final Activity activity;
    private final Runnable changed;
    private final RadioGroup choices;
    private final TextView status;
    private boolean binding;
    private final com.subhub.app.util.AsyncUiScope work;
    private final String authorityJob = "device-role-authority:" + View.generateViewId();

    public DeviceRoleView(Activity activity, Runnable changed) {
        super(activity);
        this.activity = activity;
        this.changed = changed;
        work = com.subhub.app.util.AsyncUiScope.forContext(activity);
        setOrientation(VERTICAL);
        setTag("device_role_selector");
        PersonUi.text(this, activity.getString(R.string.device_role_title), true);
        choices = new RadioGroup(activity);
        choices.setOrientation(VERTICAL);
        addView(choices, new LinearLayout.LayoutParams(-1, -2));
        int[] labels = {R.string.device_role_dom_only, R.string.device_role_sub_only, R.string.device_role_both};
        int index = 0;
        for (DeviceRolePreferences.Mode mode : DeviceRolePreferences.Mode.values()) {
            RadioButton choice = new RadioButton(activity);
            choice.setId(View.generateViewId());
            choice.setTag("device_role:" + mode.name());
            choice.setText(labels[index++]);
            choice.setMinHeight(PersonUi.dp(activity, 48));
            choices.addView(choice, new RadioGroup.LayoutParams(-1, -2));
        }
        status = PersonUi.text(this, "", false);
        status.setId(R.id.device_role_status);
        refresh();
        choices.setOnCheckedChangeListener((group, id) -> {
            if (binding) return;
            View selected = group.findViewById(id);
            if (selected == null) return;
            String name = selected.getTag().toString().substring("device_role:".length());
            DeviceRolePreferences.Mode requested = DeviceRolePreferences.Mode.valueOf(name);
            refresh();
            if (requested == DeviceRolePreferences.get(activity)) return;
            if (!localAuthority()) return;
            if (requested == DeviceRolePreferences.Mode.DOM_ONLY && DeviceRolePreferences.hasSubProtection(activity)) {
                showStatus(R.string.device_role_end_session); return;
            }
            if (OnboardingState.isPristine(activity)) apply(requested);
            else if (!ControllerPinManager.hasCredentials(activity)) {
                showStatus(R.string.device_role_pin_required);
                ControllerPinGate.setupLocalPin(activity, this::refresh);
            } else ControllerPinGate.authenticate(activity, () -> apply(requested), false);
        });
    }

    public void refresh() {
        // A witness read can decrypt credentials through Binder. Rendering must never wait for it.
        if (work.isPending(authorityJob)) return;
        DeviceRolePreferences.Mode selected = DeviceRolePreferences.get(activity);
        KeyholderProvider provider = People.keyholder();
        render(selected, false, true);
        work.load(authorityJob, () -> {
            KeyholderProvider.State state = provider.state();
            boolean local = state == KeyholderProvider.State.NONE && People.isLocalConfigurationAllowed();
            return new AuthorityDisplay(local, state == KeyholderProvider.State.UNAVAILABLE);
        }, display -> {
            if (!isAttachedToWindow()) return;
            if (People.keyholder() != provider || DeviceRolePreferences.get(activity) != selected) {
                refresh(); return;
            }
            render(selected, display.local, display.unavailable);
        }, failure -> { if (isAttachedToWindow()) render(DeviceRolePreferences.get(activity), false, true); });
    }
    private void render(DeviceRolePreferences.Mode selected, boolean local, boolean unavailable) {
        binding = true;
        try {
            for (int index = 0; index < choices.getChildCount(); index++) {
                RadioButton choice = (RadioButton) choices.getChildAt(index);
                choice.setEnabled(local);
                if (choice.getTag().equals("device_role:" + selected.name())) choices.check(choice.getId());
            }
            status.setText(local ? "" : activity.getString(unavailable
                    ? R.string.device_role_unavailable : R.string.device_role_managed));
            status.setVisibility(local ? GONE : VISIBLE);
        } finally { binding = false; }
    }
    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        refresh();
    }
    @Override protected void onDetachedFromWindow() {
        work.cancel(authorityJob);
        super.onDetachedFromWindow();
    }
    private static final class AuthorityDisplay {
        final boolean local, unavailable;
        AuthorityDisplay(boolean local, boolean unavailable) {
            this.local = local; this.unavailable = unavailable;
        }
    }
    private boolean localAuthority() {
        try { return People.keyholder().state() == KeyholderProvider.State.NONE && People.isLocalConfigurationAllowed(); }
        catch (RuntimeException unavailable) { return false; }
    }
    private void apply(DeviceRolePreferences.Mode mode) {
        if (DeviceRolePreferences.set(activity, mode)) {
            refresh(); changed.run();
        } else {
            refresh(); showStatus(R.string.device_role_change_unavailable);
        }
    }
    private void showStatus(int text) { status.setText(text); status.setVisibility(VISIBLE); }
}
