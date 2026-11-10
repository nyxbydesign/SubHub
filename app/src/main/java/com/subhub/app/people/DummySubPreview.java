package com.subhub.app.people;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.security.DeviceRolePreferences;

/** A local presentation flag, deliberately unrelated to identities or connections. */
public final class DummySubPreview {
    public static final String PREFERENCES_NAME = "subhub_dummy_preview";
    public static final PersonId ID = new PersonId("dummy_sub_preview");
    private static final String AUTHORITY = "subhub.dummy.authority";
    private static final String REVISION = "subhub.dummy.revision";
    private static volatile String token = java.util.UUID.randomUUID().toString();
    private static final java.util.Set<String> SECTIONS = java.util.Set.of(
            "censor", "limits", "wallet", "apps", "duration", "atmosphere");
    private static final String ENABLED = "enabled";
    // A failed commit can update SharedPreferences' in-memory map. Never trust that map afterward.
    private static boolean persistenceFailed;

    private DummySubPreview() { }

    static boolean authorized(Context context) {
        return ControllerPinManager.isDomWorkspaceUnlocked() && DeviceRolePreferences.supportsDom(context);
    }

    public static synchronized boolean isEnabled(Context context) {
        if (!authorized(context) || persistenceFailed) return false;
        try {
            return context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE).getBoolean(ENABLED, false);
        } catch (ClassCastException invalidFlag) {
            return false;
        }
    }

    /** Returns the resulting usable state; failed persistence always closes the preview. */
    public static synchronized boolean toggle(Context context) {
        if (!authorized(context)) throw new SecurityException("Dummy preview requires an unlocked Dom workspace");
        boolean enabled = !isEnabled(context);
        SharedPreferences preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
        persistenceFailed = true;
        if (!preferences.edit().putBoolean(ENABLED, enabled).commit())
            throw new IllegalStateException("Dummy preview could not be persisted");
        persistenceFailed = false;
        token = java.util.UUID.randomUUID().toString();
        if (!authorized(context)) throw new SecurityException("Dom workspace changed");
        return enabled;
    }

    /** Reserved IDs or preview markers never fall through to live routes. */
    public static boolean isRequested(Intent intent) {
        return intent != null && (intent.hasExtra(AUTHORITY) || intent.hasExtra(REVISION)
                || ID.value().equals(intent.getStringExtra(PersonRoutes.EXTRA_PERSON_ID)));
    }

    public static boolean isAllowed(Activity activity) {
        Intent intent = activity.getIntent();
        return isEnabled(activity) && intent != null
                && ID.value().equals(intent.getStringExtra(PersonRoutes.EXTRA_PERSON_ID))
                && routeMatches(activity.getClass(), intent.getStringExtra("section"))
                && authorityFor(activity.getClass(), intent.getStringExtra("section")).equals(intent.getStringExtra(AUTHORITY))
                && intent.getLongExtra(REVISION, -1) == ControllerPinManager.domWorkspaceRevision();
    }

    private static String sectionFor(Class<?> destination) {
        if (destination == PersonActivity.class) return "";
        if (destination == com.subhub.app.settings.CensorPresetsActivity.class) return "censor";
        if (destination == com.subhub.app.subliminal.SubliminalSettingsActivity.class) return "subliminal";
        if (destination == com.subhub.app.popup.PopupStormActivity.class) return "popup";
        for (String section : SECTIONS) if (PersonRoutes.sectionActivity(section) == destination) return section;
        throw new IllegalArgumentException("Not a preview destination");
    }

    private static boolean routeMatches(Class<?> destination, String section) {
        try { return sectionFor(destination).equals(section == null ? "" : section); }
        catch (IllegalArgumentException invalid) { return false; }
    }

    private static String authorityFor(Class<?> destination, String section) {
        String route = token + ":" + destination.getName() + ":" + (section == null ? "" : section);
        return java.util.UUID.nameUUIDFromBytes(route.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
    }

    private static Intent route(Activity activity, Class<?> destination, String section) {
        return new Intent(activity, destination).putExtra(PersonRoutes.EXTRA_PERSON_ID, ID.value())
                .putExtra(AUTHORITY, authorityFor(destination, section)).putExtra("section", section)
                .putExtra(REVISION, ControllerPinManager.domWorkspaceRevision());
    }

    public static Intent childIntent(Activity activity, Class<?> destination) {
        if (!isAllowed(activity)) throw new SecurityException("Preview unavailable");
        String section = sectionFor(destination);
        return route(activity, destination, section.isEmpty() ? null : section);
    }

    public static void open(Activity activity) {
        if (isEnabled(activity)) activity.startActivity(route(activity, PersonActivity.class, null));
    }

    public static void openSection(Activity activity, String section) {
        if (isAllowed(activity) && SECTIONS.contains(section)) activity.startActivity(
                route(activity, PersonRoutes.sectionActivity(section), section));
    }

    /** Transient presentation only: never inserted into People or a relationship store. */
    public static PersonSnapshot snapshot(Context context) {
        if (!isEnabled(context)) throw new SecurityException("Preview unavailable");
        java.util.List<PersonSnapshot.Section> sections = new java.util.ArrayList<>();
        String[] keys = {"apps", "censor", "limits", "wallet", "atmosphere", "duration"};
        int[] titles = {com.subhub.app.R.string.dummy_sub_apps,
                com.subhub.app.R.string.dummy_sub_censor, com.subhub.app.R.string.dummy_sub_limits,
                com.subhub.app.R.string.dummy_sub_wallet, com.subhub.app.R.string.atmosphere_title,
                com.subhub.app.R.string.dummy_sub_duration};
        java.util.List<PersonSnapshot.App> apps = java.util.List.of(
                new PersonSnapshot.App("preview.reader", "Reader (sample)"),
                new PersonSnapshot.App("preview.music", "Music (sample)"));
        for (int i = 0; i < keys.length; i++) sections.add(new PersonSnapshot.Section(keys[i],
                context.getString(titles[i]), context.getString("apps".equals(keys[i]) || "limits".equals(keys[i])
                        ? com.subhub.app.R.string.dummy_sub_apps_examples : com.subhub.app.R.string.dummy_sub_defaults),
                "", "apps".equals(keys[i]) ? apps : java.util.Collections.emptyList()));
        java.util.Map<String, Boolean> consent = new java.util.LinkedHashMap<>();
        for (String key : java.util.List.of("censor", "limits", "wallet", "subliminal", "popup")) consent.put(key, true);
        return new PersonSnapshot(ID, context.getString(com.subhub.app.R.string.dummy_sub_title),
                context.getString(com.subhub.app.R.string.dummy_sub_view_only), "",
                context.getString(com.subhub.app.R.string.dummy_sub_duration_length_value),
                PersonSnapshot.Connection.UNPAIRED, PersonSnapshot.Freshness.CURRENT, 0,
                java.util.EnumSet.of(PersonSnapshot.Capability.VIEW_CONTRACT), sections,
                java.util.List.of(new PersonSnapshot.Allowance("preview.reader", "Reader (sample)", 0, 45),
                        new PersonSnapshot.Allowance("preview.music", "Music (sample)", 0, 60)),
                "EUR", 0, 0, 0, 0, false)
                .withProfile(new ProfileData(context.getString(com.subhub.app.R.string.dummy_sub_title),
                        context.getString(com.subhub.app.R.string.dummy_sub_description), ""), consent);
    }
}
