package com.subhub.app.security;

import android.content.Context;
import android.content.SharedPreferences;
import com.subhub.app.appmode.RuntimeSessionIdentity;
import com.subhub.app.onboarding.OnboardingState;
import com.subhub.app.people.LocalSessionControls;
import com.subhub.app.people.People;
import com.subhub.app.settings.SettingsRepository;
import java.util.Objects;

/** Device capabilities are independent of whether the local controller session is unlocked. */
public final class DeviceRolePreferences {
    public enum Mode { DOM_ONLY, SUB_ONLY, BOTH }
    public static final String KEY_MODE = "device_role_mode";
    private DeviceRolePreferences() { }

    public static Mode get(Context context) {
        try { return Mode.valueOf(preferences(context).getString(KEY_MODE, Mode.BOTH.name())); }
        catch (IllegalArgumentException | ClassCastException invalid) { return Mode.BOTH; }
    }
    public static boolean supportsDom(Context context) { return get(context) != Mode.SUB_ONLY; }
    public static boolean supportsSub(Context context) { return get(context) != Mode.DOM_ONLY; }
    public static boolean isDomWorkspace(Context context) {
        return get(context) == Mode.DOM_ONLY || ControllerPinManager.isDomModeActive();
    }

    /** Established devices require controller authentication; pairing and active protection stay authoritative. */
    public static boolean set(Context context, Mode mode) {
        Objects.requireNonNull(mode);
        if (get(context) == mode) return true;
        return ControllerPinManager.withLocalAuthority(() -> {
            synchronized (RuntimeSessionIdentity.LOCK) {
                if (!People.isLocalConfigurationAllowed()) return false;
                if (!OnboardingState.isPristine(context)
                        && (!ControllerPinManager.hasCredentials(context) || !ControllerPinManager.isDomModeActive())) return false;
                if (mode == Mode.DOM_ONLY && hasSubProtection(context)) return false;
                return preferences(context).edit().putString(KEY_MODE, mode.name()).commit();
            }
        });
    }
    public static boolean hasSubProtection(Context context) {
        HardcoreModeManager hardcore = new HardcoreModeManager(context);
        return LocalSessionControls.isActive(context) || hardcore.isRequested() || hardcore.isAdminActive();
    }
    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME, Context.MODE_PRIVATE);
    }
}
