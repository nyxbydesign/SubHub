package com.subhub.app.people;

import android.content.Context;
import android.content.SharedPreferences;
import com.subhub.app.R;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.security.DeviceRolePreferences;
import java.util.Map;

/** Role profiles extend the existing name store, preserving names and saving one complete edit. */
public final class ProfileStore {
    private ProfileStore() { }

    public static boolean mayEdit(Context context, boolean dom) {
        return dom ? DeviceRolePreferences.supportsDom(context)
                && DeviceRolePreferences.isDomWorkspace(context) && (ControllerPinManager.isDomModeActive()
                        || DeviceRolePreferences.get(context) == DeviceRolePreferences.Mode.DOM_ONLY)
                : DeviceRolePreferences.supportsSub(context) && !DeviceRolePreferences.isDomWorkspace(context);
    }

    public static ProfileData load(Context context, boolean dom) {
        Map<String, ?> values = context.getSharedPreferences(ProfileNames.STORE, 0).getAll();
        String key = dom ? "dom" : "sub";
        String name = ProfileNames.clean(string(values, key));
        if (name.isEmpty()) name = context.getString(dom ? R.string.header_subtitle_dom : R.string.header_subtitle_sub);
        String about = ProfileData.cleanAbout(string(values, key + "_about"));
        String gender = ProfileData.cleanGender(string(values, key + "_gender"));
        Object storedAge = values.get(key + "_age");
        int age = storedAge instanceof Integer && (Integer) storedAge >= 1 && (Integer) storedAge <= ProfileData.MAX_AGE
                ? (Integer) storedAge : 0;
        try { return new ProfileData(name, about, string(values, key + "_avatar"), gender, age); }
        catch (IllegalArgumentException invalid) { return new ProfileData(name, about, "", gender, age); }
    }

    public static boolean save(Context context, boolean dom, String name, String about, String avatar) {
        if (!mayEdit(context, dom)) return false;
        ProfileData previous = load(context, dom);
        return save(context, dom, name, about, avatar, previous.gender, previous.age);
    }

    public static boolean save(Context context, boolean dom, String name, String about, String avatar, String gender, int age) {
        if (!mayEdit(context, dom)) return false;
        ProfileData value = new ProfileData(name, about, avatar, gender, age);
        ProfileImages.requireValid(value.avatar);
        String key = dom ? "dom" : "sub";
        SharedPreferences preferences = context.getSharedPreferences(ProfileNames.STORE, 0);
        return preferences.edit().putString(key, value.name).putString(key + "_about", value.about)
                .putString(key + "_avatar", value.avatar).putString(key + "_gender", value.gender)
                .putInt(key + "_age", value.age).commit();
    }

    private static String string(Map<String, ?> values, String key) {
        Object value = values.get(key);
        return value instanceof String ? (String) value : "";
    }
}
