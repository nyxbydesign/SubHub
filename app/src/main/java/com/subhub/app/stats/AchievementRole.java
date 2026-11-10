package com.subhub.app.stats;

import android.content.Context;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.security.DeviceRolePreferences;

/** A workspace selects a catalog; it never grants access to paired facts. */
public enum AchievementRole {
    SUB, DOM;

    public static AchievementRole current(Context context) {
        return DeviceRolePreferences.supportsDom(context)
                && DeviceRolePreferences.isDomWorkspace(context) ? DOM : SUB;
    }

    public boolean accessible(Context context) {
        return current(context) == this && (this == SUB
                ? DeviceRolePreferences.supportsSub(context)
                : DeviceRolePreferences.supportsDom(context)
                        && ControllerPinManager.isDomWorkspaceUnlocked());
    }

    /** Keep legacy persisted IDs and earned dates when configuration badges move to Dom. */
    static boolean isDomConfiguration(String id) {
        return "first_custom_phrase".equals(id) || "phrase_library".equals(id) || "paypal_vault".equals(id);
    }

    public boolean owns(String id) {
        return id != null && (this == DOM) == (id.startsWith("dom_") || isDomConfiguration(id));
    }
}
