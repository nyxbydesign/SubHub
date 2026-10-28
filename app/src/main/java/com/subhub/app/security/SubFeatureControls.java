package com.subhub.app.security;

import android.app.Activity;
import android.widget.LinearLayout;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.subhub.app.R;
import com.subhub.app.people.PersonUi;

/** A Sub can change participation locally even when configuration belongs to a paired Dom. */
public final class SubFeatureControls {
    private SubFeatureControls() { }
    public static void bind(Activity activity, LinearLayout parent) {
        parent.removeAllViews();
        if (!DeviceRolePreferences.supportsSub(activity) || DeviceRolePreferences.isDomWorkspace(activity)) return;
        PersonUi.text(parent, activity.getString(R.string.sub_features_title), true);
        PersonUi.text(parent, activity.getString(SubFeaturePolicy.hardcoreOverride(activity)
                ? R.string.sub_features_hardcore : R.string.sub_features_help), false);
        String[] keys = {"censor", "limits", "wallet", "subliminal", "popup"};
        int[] labels = {R.string.sub_censor_title, R.string.sub_limits_title, R.string.sub_wallet_title,
                R.string.sub_feature_whispers, R.string.popup_title};
        for (int index = 0; index < keys.length; index++) {
            String key = keys[index];
            SwitchMaterial control = new com.subhub.app.util.StateToggle(activity);
            control.setText(labels[index]);
            control.setTag("sub_feature:" + key);
            control.setMinHeight(PersonUi.dp(activity, 48));
            control.setChecked(SubFeaturePolicy.chosen(activity, key));
            control.setOnCheckedChangeListener((view, checked) -> {
                if (!SubFeaturePolicy.setAllowed(activity, key, checked))
                    control.setChecked(SubFeaturePolicy.chosen(activity, key));
                com.subhub.app.popup.PopupStormManager.get().reloadSettings(activity);
                com.subhub.app.remote.RemoteFeature.get(activity).changed();
            });
            parent.addView(control, new LinearLayout.LayoutParams(-1, -2));
        }
    }
}
