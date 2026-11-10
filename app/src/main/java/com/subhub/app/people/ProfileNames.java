package com.subhub.app.people;

import android.content.Context;
import com.subhub.app.R;

/** Display names are local preferences; person and relationship IDs never depend on them. */
public final class ProfileNames {
    public static final int MAX_LENGTH = 80;
    static final String STORE = "subhub_profile_names";
    private ProfileNames() { }

    public static String chosen(Context context, boolean dom) {
        return clean(context.getSharedPreferences(STORE, 0).getString(dom ? "dom" : "sub", ""));
    }
    public static String get(Context context, boolean dom) {
        String name = chosen(context, dom);
        return name.isEmpty() ? context.getString(dom ? R.string.header_subtitle_dom : R.string.header_subtitle_sub) : name;
    }
    public static boolean set(Context context, boolean dom, String value) {
        return context.getSharedPreferences(STORE, 0).edit().putString(dom ? "dom" : "sub", clean(value)).commit();
    }
    public static String clean(String value) {
        if (value == null) return "";
        String result = value.replaceAll("[\\p{Cntrl}\\p{Zl}\\p{Zp}]", " ").trim();
        if (result.length() > MAX_LENGTH) {
            int end = MAX_LENGTH;
            if (Character.isHighSurrogate(result.charAt(end - 1))) end--;
            result = result.substring(0, end);
        }
        return result;
    }
}
