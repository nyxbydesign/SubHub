package com.subhub.app.people;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.security.DeviceRolePreferences;

/** Explicit internal Sub routes. This identity never aliases LOCAL or a remote peer. */
public final class DummySubPreview {
    public static final String PREFERENCES_NAME = "subhub_dummy_preview";
    public static final PersonId ID = PersonId.INTERNAL;
    private static final String AUTHORITY = "subhub.dummy.authority";
    private static final String REVISION = "subhub.dummy.revision";
    private static volatile String token = java.util.UUID.randomUUID().toString();
    private static final java.util.Set<String> SECTIONS = java.util.Set.of(
            "censor", "limits", "wallet", "apps", "duration", "atmosphere");
    private static final String GENERATION = "subhub.dummy.generation";
    // A failed commit can update SharedPreferences' in-memory map. Never trust that map afterward.
    private static volatile boolean persistenceFailed;

    private DummySubPreview() { }

    static boolean authorized(Context context) {
        return ControllerPinManager.isDomWorkspaceUnlocked() && DeviceRolePreferences.supportsDom(context);
    }

    public static boolean isEnabled(Context context) {
        if (!authorized(context) || persistenceFailed) return false;
        InternalSubData data = InternalSubData.peek(context);
        try { return data != null && data.acknowledged().visible; }
        catch (IllegalStateException unavailable) { return false; }
    }

    /** The Add-a-Sub gesture is the only first-provision entry; hiding preserves all owned data. */
    public static boolean toggle(Context context) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper())
            throw new IllegalStateException("Toggle internal data on a serialized worker");
        if (!authorized(context)) throw new SecurityException("Internal Sub requires an unlocked Dom workspace");
        long revision = ControllerPinManager.domWorkspaceRevision();
        InternalSubData data = InternalSubData.provision(context);
        com.subhub.app.internal.InternalSubRecord base = data.store.read();
        synchronized (com.subhub.app.appmode.RuntimeSessionIdentity.LOCK) {
            if (!authorized(context) || revision != ControllerPinManager.domWorkspaceRevision())
                throw new SecurityException("Dom workspace changed");
        }
        com.subhub.app.internal.InternalOwnerDataBroker broker = data.ownerData();
        InternalVisibilityBarrier.Request request = new InternalVisibilityBarrier.Request(
                base, !base.visible, revision, System.currentTimeMillis());
        InternalVisibilityBarrier.Proof permit = data.await(InternalVisibilityBarrier.prepare(context.getApplicationContext(), request));
        if (permit == null) throw new IllegalStateException("Visibility proof unavailable");
        persistenceFailed = true;
        // The broker completes the proof from the actual mutation result, even if this wait is interrupted.
        com.subhub.app.internal.InternalSubRecord saved = data.await(broker.submitVisibility(permit));
        persistenceFailed = false;
        token = java.util.UUID.randomUUID().toString();
        if (!authorized(context) || revision != ControllerPinManager.domWorkspaceRevision())
            throw new SecurityException("Dom workspace changed");
        return saved.visible;
    }

    public static boolean current(Context context, String generation, long controllerRevision) {
        if (!isEnabled(context) || controllerRevision != ControllerPinManager.domWorkspaceRevision()) return false;
        try { return generation != null && generation.equals(InternalSubData.get(context).acknowledged().generation); }
        catch (IllegalStateException unavailable) { return false; }
    }

    /** Reserved IDs or preview markers never fall through to live routes. */
    public static boolean isRequested(Intent intent) {
        return intent != null && (intent.hasExtra(AUTHORITY) || intent.hasExtra(REVISION) || intent.hasExtra(GENERATION)
                || ID.value().equals(intent.getStringExtra(PersonRoutes.EXTRA_PERSON_ID)));
    }

    public static boolean isAllowed(Activity activity) {
        Intent intent = activity.getIntent();
        return isEnabled(activity) && intent != null
                && ID.value().equals(intent.getStringExtra(PersonRoutes.EXTRA_PERSON_ID))
                && routeMatches(activity.getClass(), intent.getStringExtra("section"))
                && authorityFor(activity.getClass(), intent.getStringExtra("section")).equals(intent.getStringExtra(AUTHORITY))
                && current(activity, intent.getStringExtra(GENERATION), intent.getLongExtra(REVISION, -1));
    }

    private static String sectionFor(Class<?> destination) {
        if (destination == PersonActivity.class || destination == ProfileEditorActivity.class
                || destination == com.subhub.app.tasks.TasksActivity.class) return "";
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
                .putExtra(REVISION, ControllerPinManager.domWorkspaceRevision())
                .putExtra(GENERATION, InternalSubData.get(activity).acknowledged().generation);
    }

    public static Intent childIntent(Activity activity, Class<?> destination) {
        if (!isAllowed(activity)) throw new SecurityException("Preview unavailable");
        String section = sectionFor(destination);
        return route(activity, destination, section.isEmpty() ? null : section);
    }

    public static void open(Activity activity) {
        if (isEnabled(activity)) activity.startActivity(route(activity, PersonActivity.class, null));
    }

    /** A top-level Tasks roster supplies the exact generation it displayed, not a newly minted target. */
    public static void openTasks(Activity activity, String generation, long controllerRevision) {
        if (current(activity, generation, controllerRevision))
            activity.startActivity(route(activity, com.subhub.app.tasks.TasksActivity.class, null));
    }

    public static void openSection(Activity activity, String section) {
        if (isAllowed(activity) && SECTIONS.contains(section)) activity.startActivity(
                route(activity, PersonRoutes.sectionActivity(section), section));
    }

    /** Internal presentation reads only acknowledged internal data and the public app catalog. */
    public static PersonSnapshot snapshot(Context context) {
        if (!isEnabled(context)) throw new SecurityException("Internal Sub unavailable");
        InternalSubData data=InternalSubData.get(context);
        PersonSnapshot cached=data.peekPresentation();
        if(cached!=null)return cached;
        return data.loadPresentation(context.getApplicationContext(),data.acknowledged());
    }
}
