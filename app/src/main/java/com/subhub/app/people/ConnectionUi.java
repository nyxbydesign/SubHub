package com.subhub.app.people;

import android.app.Activity;

/** Transport integration supplies identity-confirmed pairing and peer management screens. */
public interface ConnectionUi {
    void openPairing(Activity activity);
    void openConnection(Activity activity, PersonId personId);
}
