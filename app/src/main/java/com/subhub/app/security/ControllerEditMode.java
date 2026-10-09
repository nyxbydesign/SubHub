package com.subhub.app.security;

import android.app.Activity;
import android.content.Intent;
import android.view.View;
import android.widget.TextView;

import com.subhub.app.MainActivity;
import com.subhub.app.R;
import com.subhub.app.commitment.CommitmentActivity;
import com.subhub.app.commitment.CommitmentManager;

/** Shared Dom/Sub role toggle for settings-changing surfaces. */
public final class ControllerEditMode {
    public interface Listener {
        void onEditStateChanged(boolean editing);
    }

    private final Activity activity;
    private final TextView button;
    private final Listener listener;

    private ControllerEditMode(Activity activity, TextView button, Listener listener) {
        this.activity = activity;
        this.button = button;
        this.listener = listener;
        boolean activePactSurface = supportsSubSurface();
        if (!ControllerPinManager.isDomModeActive() && !(activity instanceof MainActivity)
                && !activePactSurface) {
            button.setVisibility(View.GONE);
            listener.onEditStateChanged(false);
            enterSubMode(activity);
            return;
        }
        button.setOnClickListener(view -> toggle());
        refresh();
    }

    public static ControllerEditMode bind(
            Activity activity, TextView button, Listener listener) {
        return new ControllerEditMode(activity, button, listener);
    }

    public static void renderButton(Activity activity, TextView button) {
        boolean editing = ControllerPinManager.isDomModeActive();
        button.setText(editing
                ? R.string.controller_edit_unlocked : R.string.controller_edit_locked);
        button.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, 0, 0);
        button.setContentDescription(activity.getString(editing
                ? R.string.controller_edit_unlocked_description
                : R.string.controller_edit_locked_description));
        button.setSelected(editing);
    }

    public boolean isEditing() {
        return ControllerPinManager.isDomModeActive();
    }

    public void refresh() {
        renderButton(activity, button);
        listener.onEditStateChanged(isEditing());
    }

    public void setEditable(View... views) {
        boolean editing = isEditing();
        for (View view : views) {
            if (view != null) view.setEnabled(editing);
        }
    }

    private void toggle() {
        if (isEditing()) {
            if (supportsSubSurface()) {
                ControllerPinManager.enterSubMode();
                refresh();
            } else {
                enterSubMode(activity);
            }
        } else {
            ControllerPinGate.unlock(activity, this::refresh, false);
        }
    }

    private boolean supportsSubSurface() {
        // The popup editor retains its read-only safety stop in Sub space.
        return activity instanceof com.subhub.app.popup.PopupStormActivity
                || activity instanceof CommitmentActivity && CommitmentManager.isActive(activity);
    }

    /** Leaves all configuration surfaces and returns to the single Sub dashboard. */
    public static void enterSubMode(Activity activity) {
        ControllerPinManager.enterSubMode();
        if (activity instanceof MainActivity) return;
        activity.startActivity(new Intent(activity, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        activity.finish();
        activity.overridePendingTransition(R.anim.subhub_page_pop_enter,
                R.anim.subhub_page_pop_exit);
    }
}
