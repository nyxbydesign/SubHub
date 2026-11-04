package com.subhub.app.notifications;

import android.content.Context;
import android.widget.LinearLayout;
import android.widget.Toast;
import com.subhub.app.R;
import com.subhub.app.people.PersonUi;
import com.subhub.app.security.DeviceRolePreferences;
import com.subhub.app.util.StateToggle;
import static com.subhub.app.notifications.NotificationPreferences.*;

/** Local choices remain editable without controller authority. */
public final class NotificationControls {
    private NotificationControls() { }
    public static void bind(Context context, LinearLayout container) {
        container.removeAllViews();
        if (DeviceRolePreferences.supportsDom(context)) group(context, container, Role.DOM);
        if (DeviceRolePreferences.supportsSub(context)) group(context, container, Role.SUB);
    }
    private static void group(Context context, LinearLayout container, Role role) {
        PersonUi.text(container, context.getString(role == Role.DOM ? R.string.role_dom : R.string.role_sub), true);
        if (role == Role.DOM) {
            toggle(context, container, role, Event.ACTIVITY, R.string.notification_activity);
            toggle(context, container, role, Event.TASK_REVIEW, R.string.notification_task_reviews);
        }
        toggle(context, container, role, Event.CONTRACT_CHANGED, R.string.notification_contract_changes);
        toggle(context, container, role, Event.SESSION_STARTED, R.string.notification_session_starts);
        toggle(context, container, role, Event.SESSION_STOPPED, R.string.notification_session_stops);
    }
    private static void toggle(Context context, LinearLayout container, Role role, Event event, int label) {
        StateToggle control = new StateToggle(context);
        // Preference state is authoritative across recreation; framework restoration must not write it back.
        control.setSaveEnabled(false);
        control.setTag("notification:" + key(role, event));
        control.setText(label);
        com.subhub.app.util.UiIdentity.textSize(control, R.dimen.ui_text_body);
        control.setChecked(enabled(context, role, event));
        control.setOnCheckedChangeListener((button, checked) -> {
            if (!set(context, role, event, checked)) {
                Toast.makeText(context, R.string.privacy_save_failed, Toast.LENGTH_LONG).show();
            }
        });
        container.addView(control, new LinearLayout.LayoutParams(-1, -2));
    }
}
