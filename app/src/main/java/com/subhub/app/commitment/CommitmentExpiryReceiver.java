package com.subhub.app.commitment;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Ends protection when the selected pact timer expires. */
public final class CommitmentExpiryReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        String target = com.subhub.app.appmode.SessionExpiryAlarms.target(intent,
                com.subhub.app.appmode.SessionExpiryAlarms.Kind.PACT);
        if (target == null || !target.isEmpty()) CommitmentManager.expireIfDue(context, target);
    }
}
