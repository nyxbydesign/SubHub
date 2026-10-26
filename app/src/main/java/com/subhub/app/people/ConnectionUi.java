package com.subhub.app.people;

import android.app.Activity;
import android.widget.LinearLayout;

/** Transport integration supplies identity-confirmed pairing and peer management screens. */
public interface ConnectionUi {
    void openPairing(Activity activity);
    void openConnection(Activity activity, PersonId personId);
    default void openPairing(Activity activity, boolean asDom) { openPairing(activity); }
    default void bindSessionDuration(Activity activity, LinearLayout parent, PersonSnapshot person) {}
    default void openStats(Activity activity, PersonId personId) {}
    default void editSection(Activity activity, PersonId personId, String section) {}
    default void endSession(Activity activity, PersonId personId, String expectedSessionIdentity) {}
}
