package com.subhub.app.penance;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Restores the protected state after a purchased pause expires. */
public final class PaidPauseExpiryReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        com.subhub.app.appmode.SessionReceiverLifetime.Work work = com.subhub.app.appmode.SessionReceiverLifetime.begin(this);
        try { receive(context, intent); work.afterQueues(); }
        catch (RuntimeException | Error failure) { work.failed(); throw failure; }
    }
    private void receive(Context context, Intent intent) {
        String target = com.subhub.app.appmode.SessionExpiryAlarms.target(intent,
                com.subhub.app.appmode.SessionExpiryAlarms.Kind.PAUSE);
        if (target == null || !target.isEmpty()) new PaidPauseManager(context).finishIfDue(target);
    }
}
