package com.subhub.app.privacy;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;

/** Optional local privacy choices, deliberately outside packs and controller credentials. */
public final class PrivacyManager {
    private final Context context;
    private final SharedPreferences prefs;
    public PrivacyManager(Context context) {
        this.context = context.getApplicationContext(); prefs = this.context.getSharedPreferences("subhub_privacy", Context.MODE_PRIVATE);
    }
    public boolean isDiscreet() { return prefs.getBoolean("discreet", false); }
    public boolean isAppLockEnabled() { return prefs.getBoolean("app_lock", false); }
    public boolean setDiscreet(boolean enabled) {
        boolean previous = isDiscreet();
        try {
            aliases(enabled);
            if (!prefs.edit().putBoolean("discreet", enabled).commit()) { aliases(previous); return false; }
            PrivacyLifecycle.refresh(); PrivacyNotifications.refresh(context); return true;
        } catch (RuntimeException unavailable) {
            try { aliases(previous); } catch (RuntimeException ignored) { /* Startup retries the persisted state. */ }
            return false;
        }
    }
    public boolean setAppLock(boolean enabled) {
        if (!prefs.edit().putBoolean("app_lock", enabled).commit()) return false;
        if (!enabled) PrivacyLifecycle.unlock();
        PrivacyLifecycle.refresh(); return true;
    }
    public void reconcileLauncher() { try { aliases(isDiscreet()); } catch (RuntimeException ignored) { } }
    private void aliases(boolean discreet) {
        PackageManager manager = context.getPackageManager();
        ComponentName normal = new ComponentName(context, "com.subhub.app.DefaultLauncher");
        ComponentName neutral = new ComponentName(context, "com.subhub.app.DiscreetLauncher");
        // Enable the target first, so older Android releases never have zero launcher entries.
        manager.setComponentEnabledSetting(discreet ? neutral : normal, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP);
        manager.setComponentEnabledSetting(discreet ? normal : neutral, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
    }
}
