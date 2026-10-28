package com.subhub.app.people;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import com.subhub.app.appmode.AppModeActivity;
import com.subhub.app.atmosphere.AtmosphereActivity;
import com.subhub.app.penance.PenanceActivity;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.settings.SettingsActivity;

/** All person-scoped routes retain the identity through navigation and action dispatch. */
public final class PersonRoutes {
    public static final String EXTRA_PERSON_ID = "subhub.person.id";
    private static String pendingSessionStart;
    private static final String EXTRA_SESSION_START_TOKEN = "subhub.person.session.start";
    private PersonRoutes() {}

    public static void open(Context context, PersonId id) {
        context.startActivity(new Intent(context, PersonActivity.class).putExtra(EXTRA_PERSON_ID, id.value()));
    }

    public static synchronized void openLocalSession(Context context, PersonId id) {
        if (id == null || !id.isLocal() || !ControllerPinManager.isDomModeActive()) return;
        pendingSessionStart = java.util.UUID.randomUUID().toString();
        context.startActivity(new Intent(context, com.subhub.app.MainActivity.class)
                .setAction(Intent.ACTION_MAIN)
                .putExtra(EXTRA_PERSON_ID, id.value())
                .putExtra(EXTRA_SESSION_START_TOKEN, pendingSessionStart)
                .putExtra(com.subhub.app.MainActivity.EXTRA_LOCAL_SESSION, true));
    }

    public static synchronized boolean consumeLocalSessionStart(Intent intent) {
        String token = intent.getStringExtra(EXTRA_SESSION_START_TOKEN);
        intent.removeExtra(EXTRA_SESSION_START_TOKEN);
        if (token == null || !token.equals(pendingSessionStart)) return false;
        pendingSessionStart = null;
        return isLocalTarget(intent) && ControllerPinManager.isDomModeActive();
    }

    public static PersonId requireId(Intent intent) {
        return new PersonId(intent.getStringExtra(EXTRA_PERSON_ID));
    }

    public static boolean canEditLocal(Context context, PersonId id) {
        return id != null && id.isLocal() && ControllerPinManager.canEditConfiguration()
                && People.isLocalConfigurationAllowed();
    }

    /** Legacy entry points are explicitly local, but never accept a remote target. */
    public static boolean isLocalTarget(Intent intent) {
        String id = intent.getStringExtra(EXTRA_PERSON_ID);
        return id == null || PersonId.LOCAL.value().equals(id);
    }

    public static void editSection(Activity activity, PersonId id, String section) {
        if (!canEditLocal(activity, id)) return;
        activity.startActivity(new Intent(activity, sectionActivity(section)).putExtra(EXTRA_PERSON_ID, id.value()));
    }

    public static Class<? extends Activity> sectionActivity(String section) {
        Class<? extends Activity> target;
        switch (section) {
            case "apps": target = LocalAppsActivity.class; break;
            case "duration": target = ServiceDurationActivity.class; break;
            case "censor": target = SettingsActivity.class; break;
            case "limits": target = AppModeActivity.class; break;
            case "wallet": target = PenanceActivity.class; break;
            case "atmosphere": target = AtmosphereActivity.class; break;
            default: throw new IllegalArgumentException("Unknown contract section");
        }
        return target;
    }
}
