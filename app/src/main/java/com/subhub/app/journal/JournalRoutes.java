package com.subhub.app.journal;

import android.app.Activity;
import android.content.Intent;
import com.subhub.app.people.PersonId;
import com.subhub.app.security.ControllerPinGate;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.security.DeviceRolePreferences;

public final class JournalRoutes {
    public static final String EXTRA_DOM = "journal_dom";
    private JournalRoutes() { }
    public static void own(Activity activity, boolean dom) {
        Runnable open = () -> start(activity, PersonId.LOCAL, dom);
        if (dom && !ControllerPinManager.isDomModeActive()) ControllerPinGate.unlock(activity, open, false);
        else open.run();
    }
    public static void profile(Activity activity, PersonId target) {
        boolean dom = DeviceRolePreferences.isDomWorkspace(activity);
        if (target.isLocal()) target = new PersonId("journal_local_sub");
        start(activity, target, dom);
    }
    private static void start(Activity activity, PersonId target, boolean dom) {
        activity.startActivity(new Intent(activity, JournalActivity.class)
                .putExtra(com.subhub.app.people.PersonRoutes.EXTRA_PERSON_ID, target.value()).putExtra(EXTRA_DOM, dom));
    }
}
