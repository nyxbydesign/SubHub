package com.subhub.app.appmode;

import android.content.Context;
import android.content.SharedPreferences;

import com.subhub.app.settings.SettingsRepository;

/** Persists user intent without attempting to persist Android's one-use MediaProjection token. */
public final class ProtectionSessionManager {
    private static final String KEY_MEDIA_PROJECTION_DESIRED =
            "media_projection_session_desired";

    private ProtectionSessionManager() {}

    public static void markMediaProjectionStarted(Context context) {
        synchronized (com.subhub.app.appmode.RuntimeSessionIdentity.LOCK) {
        preferences(context).edit().putBoolean(KEY_MEDIA_PROJECTION_DESIRED, true).apply();
            }
    }

    public static void markMediaProjectionExplicitlyStopped(Context context) {
        synchronized (com.subhub.app.appmode.RuntimeSessionIdentity.LOCK) {
        preferences(context).edit().putBoolean(KEY_MEDIA_PROJECTION_DESIRED, false).apply();
            }
    }

    public static boolean needsMediaProjectionResume(Context context) {
        return preferences(context).getBoolean(KEY_MEDIA_PROJECTION_DESIRED, false);
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(
                SettingsRepository.PREFERENCES_NAME, Context.MODE_PRIVATE);
    }
}
