package com.subhub.app.settings;

import com.subhub.app.R;

/** Canonical UI grouping, deep-link keys and controller requirements. */
enum SettingsSection {
    FEATURES("features", R.string.settings_features, R.drawable.ic_tab_settings, true),
    APPS("apps", R.string.settings_apps, R.drawable.ic_settings_apps, true),
    PERMISSIONS(
            "permissions", R.string.settings_permissions, R.drawable.ic_settings_permissions, true),
    PRIVACY("privacy", R.string.privacy_title, R.drawable.ic_ux_lock, false),
    ARRANGEMENTS("pacts", R.string.settings_pacts, R.drawable.ic_nav_studio, true),
    WALLET("services", R.string.settings_services, R.drawable.ic_settings_wallet, true),
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
