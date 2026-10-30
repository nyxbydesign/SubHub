package com.subhub.app.security;

import android.app.Activity;
import android.widget.LinearLayout;
import com.subhub.app.R;
import com.subhub.app.people.PersonUi;

/** A Sub can change participation locally even when configuration belongs to a paired Dom. */
public final class SubFeatureControls {
    private SubFeatureControls() { }
    public static void bind(Activity activity, LinearLayout parent) {
        parent.removeAllViews();
        if (!DeviceRolePreferences.supportsSub(activity) || DeviceRolePreferences.isDomWorkspace(activity)) return;
        int inset = PersonUi.dp(activity, 12); parent.setPadding(inset, inset, inset, inset);
        android.widget.TextView heading = PersonUi.text(parent, activity.getString(R.string.sub_features_title), true);
        heading.setPadding(0, 0, 0, PersonUi.dp(activity, 4));
        if (SubFeaturePolicy.hardcoreOverride(activity))
            PersonUi.text(parent, activity.getString(R.string.sub_features_hardcore), false);
        com.subhub.app.people.FeatureConsentView grid = new com.subhub.app.people.FeatureConsentView(activity);
        parent.addView(grid, new LinearLayout.LayoutParams(-1, -2));
        String[] keys = {"censor", "limits", "wallet", "subliminal", "popup"};
        int[] labels = {R.string.sub_censor_title, R.string.sub_limits_title, R.string.sub_wallet_title,
                R.string.sub_feature_whispers, R.string.popup_title};
        for (int index = 0; index < keys.length; index++) {
            String key = keys[index];
            android.widget.CompoundButton control = grid.editable(key, labels[index], SubFeaturePolicy.chosen(activity, key));
            control.setOnCheckedChangeListener((view, checked) -> {
                if (!SubFeaturePolicy.setAllowed(activity, key, checked))
                    control.setChecked(SubFeaturePolicy.chosen(activity, key));
                com.subhub.app.popup.PopupStormManager.get().reloadSettings(activity);
                com.subhub.app.remote.RemoteFeature.get(activity).changed();
            });
        }
    }
}
