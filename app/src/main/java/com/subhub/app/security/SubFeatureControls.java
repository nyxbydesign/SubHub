package com.subhub.app.security;

import android.app.Activity;
import android.widget.LinearLayout;
import com.subhub.app.R;
import com.subhub.app.atmosphere.GagActivity;
import com.subhub.app.fullaccess.FullAccessGrant;
import com.subhub.app.people.FeatureConsentView;
import com.subhub.app.people.PersonUi;
import com.subhub.app.util.AsyncUiScope;

/** A Sub can change participation locally even when configuration belongs to a paired Dom. */
public final class SubFeatureControls {
    private SubFeatureControls() { }
    private static final String GAG = "gag";
    private static final String[] KEYS = {"censor", "limits", "wallet", "subliminal", "popup", "tasks", GAG};
    private static final int[] LABELS = {R.string.sub_censor_title, R.string.sub_limits_title,
            R.string.sub_wallet_title, R.string.sub_feature_whispers, R.string.popup_title, R.string.tasks_title,
            R.string.gag_title};

    private static boolean chosen(android.content.Context context, String key) {
        return GAG.equals(key) ? GagActivity.isEnabled(context) : SubFeaturePolicy.chosen(context, key);
    }

    public static void bindReadOnly(Activity activity, LinearLayout parent) {
        bindChoices(activity, parent, null);
    }

    public static FeatureConsentView bindDraft(Activity activity, LinearLayout parent, Draft draft) {
        return bindChoices(activity, parent, draft);
    }

    private static FeatureConsentView bindChoices(Activity activity, LinearLayout parent, Draft draft) {
        parent.removeAllViews();
        if (!DeviceRolePreferences.supportsSub(activity) || DeviceRolePreferences.isDomWorkspace(activity)) return null;
        parent.setPadding(0, PersonUi.dp(activity, 10), 0, 0);
        android.widget.TextView heading = PersonUi.text(parent, activity.getString(R.string.sub_features_title), true);
        heading.setPadding(0, 0, 0, PersonUi.dp(activity, 4));
        FeatureConsentView grid = new FeatureConsentView(activity);
        parent.addView(grid, new LinearLayout.LayoutParams(-1, -2));
        for (int index = 0; index < KEYS.length; index++) {
            String key = KEYS[index];
            if (draft == null) grid.readOnly(key, LABELS[index], chosen(activity, key));
            else {
                android.widget.CompoundButton control = grid.editable(key, LABELS[index], draft.values.get(key));
                control.setOnCheckedChangeListener((view, checked) -> draft.values.put(key, checked));
            }
        }
        if (draft == null && new HardcoreModeManager(activity).isEnabled()) {
            android.content.Context app = activity.getApplicationContext();
            AsyncUiScope.forContext(activity).load("self-profile-blackmail", () -> FullAccessGrant.isLocallyEnabled(app),
                    enabled -> {
                        if (grid.isAttachedToWindow() && DeviceRolePreferences.supportsSub(app)
                                && !DeviceRolePreferences.isDomWorkspace(app)
                                && new HardcoreModeManager(app).isEnabled())
                            grid.readOnly("blackmail", R.string.full_access_title, enabled);
                    }, failure -> { });
        }
        return grid;
    }

    /** Only explicit Save applies changed choices through their existing local policy APIs. */
    public static final class Draft {
        private final java.util.Map<String, Boolean> original = new java.util.LinkedHashMap<>();
        private final java.util.Map<String, Boolean> values = new java.util.LinkedHashMap<>();
        public Draft(android.content.Context context, android.os.Bundle state) {
            for (String key : KEYS) {
                boolean chosen = chosen(context, key);
                original.put(key, state == null ? chosen : state.getBoolean("feature_original:" + key, chosen));
                values.put(key, state == null ? chosen : state.getBoolean("feature_draft:" + key, chosen));
            }
        }
        public void saveState(android.os.Bundle state) {
            for (String key : KEYS) {
                state.putBoolean("feature_original:" + key, original.get(key));
                state.putBoolean("feature_draft:" + key, values.get(key));
            }
        }
        public boolean apply(Activity activity) {
            if (!com.subhub.app.people.ProfileStore.mayEdit(activity, false)) return false;
            boolean changed = false, effectsChanged = false;
            try {
                for (String key : KEYS) {
                    boolean value = values.get(key);
                    if (value == original.get(key)) continue;
                    if (!com.subhub.app.people.ProfileStore.mayEdit(activity, false)) return false;
                    if (GAG.equals(key)) {
                        if (GagActivity.isEnabled(activity) != value && !GagActivity.setSubEnabled(activity, value))
                            return false;
                    } else if (chosen(activity, key) != value) {
                        if ("tasks".equals(key)) new com.subhub.app.tasks.TaskRepository(activity,
                                com.subhub.app.people.PersonId.LOCAL).setAllowed(value);
                        else {
                            if (!SubFeaturePolicy.setAllowed(activity, key, value)) return false;
                            effectsChanged = true;
                        }
                        changed = true;
                    }
                    original.put(key, value);
                }
                return true;
            } catch (IllegalStateException | SecurityException unavailable) { return false; }
            finally {
                if (effectsChanged) com.subhub.app.popup.PopupStormManager.get().reloadSettings(activity);
                if (changed) com.subhub.app.remote.RemoteFeature.get(activity).changed();
            }
        }
    }
}
