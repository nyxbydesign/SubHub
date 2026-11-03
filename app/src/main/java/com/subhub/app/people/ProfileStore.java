package com.subhub.app.people;

import android.content.Context;
import android.content.SharedPreferences;
import com.subhub.app.R;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.security.DeviceRolePreferences;

/** Role profiles extend the existing name store, preserving names and saving one complete edit. */
public final class ProfileStore {
    private ProfileStore() { }

    public static boolean mayEdit(Context context, boolean dom) {
        return dom ? DeviceRolePreferences.supportsDom(context)
                && DeviceRolePreferences.isDomWorkspace(context) && (ControllerPinManager.isDomWorkspaceUnlocked()
                        || DeviceRolePreferences.get(context) == DeviceRolePreferences.Mode.DOM_ONLY)
                : DeviceRolePreferences.supportsSub(context) && !DeviceRolePreferences.isDomWorkspace(context);
    }

    public static ProfileData load(Context context, boolean dom) {
        return prepareRead(context,dom).load();
    }

    /** Capture preferences/resources and finish any lazy disk load before a caller's session-read boundary. */
    public static PreparedRead prepareRead(Context context,boolean dom){
        SharedPreferences preferences=context.getSharedPreferences(ProfileNames.STORE,0);
        preferences.contains("__subhub_profile_read_warmup__");
        String fallback=context.getString(dom?R.string.header_subtitle_dom:R.string.header_subtitle_sub);
        return new PreparedRead(preferences,dom,fallback,DeviceRolePreferences.get(context)==DeviceRolePreferences.Mode.BOTH);
    }
    public static final class PreparedRead {
        private final SharedPreferences preferences;private final boolean dom,isSwitch;private final String fallback;
        private PreparedRead(SharedPreferences preferences,boolean dom,String fallback,boolean isSwitch){
            this.preferences=preferences;this.dom=dom;this.fallback=fallback;this.isSwitch=isSwitch;
        }
        /** Exact role keys only. This never copies the other profile's map or resolves Context resources. */
        public ProfileData load(){
            String key=dom?"dom":"sub";
            String name=ProfileNames.clean(string(preferences,key));if(name.isEmpty())name=fallback;
            String about=ProfileData.cleanAbout(string(preferences,key+"_about"));
            String gender=ProfileData.cleanGender(string(preferences,key+"_gender"));
            int age;try{age=preferences.getInt(key+"_age",0);}catch(ClassCastException malformed){age=0;}
            if(age<1||age>ProfileData.MAX_AGE)age=0;
            String avatar=string(preferences,key+"_avatar");
            try{return new ProfileData(name,about,avatar,gender,age,isSwitch);}
            catch(IllegalArgumentException invalid){return new ProfileData(name,about,"",gender,age,isSwitch);}
        }
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

    private static String string(SharedPreferences preferences,String key){
        try{String value=preferences.getString(key,"");return value==null?"":value;}catch(ClassCastException malformed){return "";}
    }
}
