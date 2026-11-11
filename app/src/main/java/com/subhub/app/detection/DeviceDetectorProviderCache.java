package com.subhub.app.detection;

import android.content.Context;
import android.content.SharedPreferences;

/** One legacy DEVICE preference handle, retained for this initialization/model attempt only. */
final class DeviceDetectorProviderCache implements DetectorProviderCache {
    private final SharedPreferences preferences;

    /** The engine already normalized this Context; acquisition stays at the original read point. */
    DeviceDetectorProviderCache(Context applicationContext) {
        preferences = applicationContext.getSharedPreferences("detector_provider_cache", Context.MODE_PRIVATE);
    }

    @Override public String get(String key) { return preferences.getString(key, null); }
    @Override public void put(String key, String provider) { preferences.edit().putString(key, provider).apply(); }
    @Override public void remove(String key) { preferences.edit().remove(key).apply(); }
}
