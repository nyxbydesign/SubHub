package com.subhub.app.settings;

import com.subhub.app.R;

/** Canonical UI grouping, deep-link keys and controller requirements. */
enum SettingsSection {
    APPS("apps", R.string.settings_apps, R.drawable.ic_settings_apps, true),
    PRIVACY_PERMISSIONS("privacy", R.string.settings_privacy_permissions, R.drawable.ic_ux_lock, false),
    HELP("help", R.string.settings_help, R.drawable.ic_tab_help, false);

    final String key;
    final int title, icon;
    final boolean requiresController;

    SettingsSection(String key, int title, int icon, boolean requiresController) {
        this.key = key;
        this.title = title;
        this.icon = icon;
        this.requiresController = requiresController;
    }

    static SettingsSection fromKey(String key) {
        for (SettingsSection section : values()) if (section.key.equals(key)) return section;
        return null;
    }
}
