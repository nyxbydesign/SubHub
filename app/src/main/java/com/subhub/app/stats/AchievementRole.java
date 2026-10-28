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
                        && ControllerPinManager.isDomModeActive());
    }

    public boolean owns(String id) {
        return id != null && (this == DOM) == id.startsWith("dom_");
    }
}
