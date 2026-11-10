package com.subhub.app.remote;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import com.subhub.app.R;
import java.util.LinkedHashMap;
import java.util.Map;

/** Visible, user-started pairing work. The backend owns clocks, authorization and transport. */
public final class PairingForegroundService extends Service {
    static final String CHANNEL = "pairing_lifecycle";
    static final String OPEN = "com.subhub.app.remote.OPEN_PAIRING_OPERATION";
    private static final String START = "com.subhub.app.remote.START_PAIRING_OPERATION";
    private static final String CANCEL = "com.subhub.app.remote.CANCEL_PAIRING_OPERATION";
    static final String ID = "pairing_id", GENERATION = "pairing_generation", DIRECTION = "pairing_direction";
    private static final String TICKET = "pairing_start_ticket", CAPABILITY = "pairing_cancel_capability";
    private static final int CREATE_NOTIFICATION = 7401, ACCEPT_NOTIFICATION = 7402;
    private static final long WATCHDOG_INTERVAL_MILLIS = 2000, ACQUIRE_TIMEOUT_MILLIS = 15000;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final Map<String, Boolean> cancellations = new LinkedHashMap<>();
    private final Map<String, Boolean> rejections = new LinkedHashMap<>();
    private RemoteFeature feature;
    private boolean destroyed;

    private static final class Entry {
        final String id, generation, direction, key;
        final boolean asDom;
        RemoteFeature.PairingOperation operation;
        PairingOperationLease lease;
        PendingIntent cancelAction;
        Runnable wake;
        Runnable watchdog;
        long acquireStartedElapsed;
        boolean inFlight;
        RemoteFeature.PairingServiceState state = RemoteFeature.PairingServiceState.STARTING;
        Entry(String id, String generation, String direction) {
            this.id = id; this.generation = generation; this.direction = direction;
            asDom = "CREATE".equals(direction); key = key(id, generation, direction);
        }
        int notificationId() { return asDom ? CREATE_NOTIFICATION : ACCEPT_NOTIFICATION; }
    }

    static Intent startIntent(Context context, RemoteFeature.PairingStart start) {
        return operationIntent(context, start.operation, START).putExtra(TICKET, start.ticket());
    }
    private static Intent operationIntent(Context context, RemoteFeature.PairingOperation op, String action) {
        return new Intent(context, PairingForegroundService.class).setAction(action)
                .putExtra(ID, op.id).putExtra(GENERATION, op.generation).putExtra(DIRECTION, op.direction);
    }
    static Intent pageIntent(Context context, String id, String generation, String direction) {
        return new Intent(context, PairingActivity.class).setAction(OPEN)
                .putExtra(ID, id).putExtra(GENERATION, generation).putExtra(DIRECTION, direction);
    }
    static boolean drawerAvailable(Context context) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = context.getSystemService(NotificationManager.class).getNotificationChannel(CHANNEL);
            return channel == null || channel.getImportance() != NotificationManager.IMPORTANCE_NONE;
        }
        return true;
    }
    private static String key(String id, String generation, String direction) {
        return direction + ":" + id + ":" + generation;
    }
    private static boolean token(String value) {
        return value != null && value.length() <= 128 && value.matches("[A-Za-z0-9._-]+");
    }
    private static Entry entry(Intent intent) {
        String id = intent.getStringExtra(ID), generation = intent.getStringExtra(GENERATION), direction = intent.getStringExtra(DIRECTION);
        return token(id) && token(generation) && ("CREATE".equals(direction) || "ACCEPT".equals(direction))
                ? new Entry(id, generation, direction) : null;
    }
    @Override public void onCreate() {
        super.onCreate(); feature = RemoteFeature.get(this);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL, getString(R.string.pairing_channel), NotificationManager.IMPORTANCE_LOW);
            channel.setShowBadge(false); getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) { stopIfEmpty(); return START_NOT_STICKY; }
        Entry requested = entry(intent);
        if (requested == null) { stopIfEmpty(); return START_NOT_STICKY; }
        if (CANCEL.equals(intent.getAction())) {
            cancel(requested, intent.getStringExtra(CAPABILITY));
        } else if (START.equals(intent.getAction())) {
            String ticket = intent.getStringExtra(TICKET);
            if (ticket == null || ticket.length() > 512) { stopIfEmpty(); return START_NOT_STICKY; }
            if (entries.containsKey(requested.key)) { refreshNotification(entries.get(requested.key)); return START_NOT_STICKY; }
            // An unconsumed ticket cannot displace an incumbent, especially an ACTIVE drain.
            for (Entry previous : entries.values()) if (previous.direction.equals(requested.direction)) {
                reject(requested); poll(previous); return START_NOT_STICKY;
            }
            requested.wake = () -> poll(requested);
            requested.watchdog = () -> watchdog(requested);
            entries.put(requested.key, requested);
            try { notifications(); }
            catch (RuntimeException unavailable) { reject(requested); release(requested, RemoteFeature.PairingServiceState.UNAVAILABLE); return START_NOT_STICKY; }
            acquire(requested, ticket);
        } else stopIfEmpty();
        return START_NOT_STICKY;
    }
    private boolean owns(Entry entry) { return !destroyed && entries.get(entry.key) == entry; }
    private void reject(Entry entry) {
        if (rejections.containsKey(entry.key) || rejections.size() >= 2) return;
        rejections.put(entry.key, true);
        feature.pairingStatus(entry.id, entry.generation, entry.asDom).whenComplete((op, failure) -> main.post(() -> {
            rejections.remove(entry.key);
            if (failure == null && op != null
                    && feature.pairingServiceState(op.id, op.generation, op.asDom) == RemoteFeature.PairingServiceState.STARTING)
                feature.reportPairingServiceState(op, RemoteFeature.PairingServiceState.UNAVAILABLE);
            if (!destroyed) stopIfEmpty();
        }));
    }
    private void acquire(Entry entry, String ticket) {
        entry.inFlight = true;
        entry.acquireStartedElapsed = SystemClock.elapsedRealtime();
        main.postDelayed(entry.watchdog, WATCHDOG_INTERVAL_MILLIS);
        feature.acquirePairingLease(ticket).whenComplete((lease, failure) -> main.post(() -> {
            if (!owns(entry)) { if (lease != null) lease.close(); return; }
            entry.inFlight = false;
            if (acquisitionTimedOut(entry)) {
                if (lease != null) lease.close();
                reject(entry); release(entry, RemoteFeature.PairingServiceState.UNAVAILABLE); return;
            }
            if (failure != null || lease == null) { reject(entry); release(entry, RemoteFeature.PairingServiceState.UNAVAILABLE); return; }
            RemoteFeature.PairingOperation snapshot = lease.operation();
            if (snapshot == null || !entry.id.equals(snapshot.id) || !entry.generation.equals(snapshot.generation)
                    || !entry.direction.equals(snapshot.direction) || entry.asDom != snapshot.asDom) {
                lease.close(); release(entry, RemoteFeature.PairingServiceState.UNAVAILABLE); return;
            }
            entry.lease = lease; entry.operation = snapshot;
            report(entry, RemoteFeature.PairingServiceState.STARTING);
            // The acquisition snapshot is not current validity or a transport-readiness proof.
            poll(entry);
        }));
    }
    private boolean acquisitionTimedOut(Entry entry) {
        // This only bounds presentation/acquisition; it never grants operation lifetime.
        return SystemClock.elapsedRealtime() - entry.acquireStartedElapsed >= ACQUIRE_TIMEOUT_MILLIS;
    }
    private void watchdog(Entry entry) {
        if (!owns(entry)) return;
        // Independent of async completion: even a hung status request cannot retain a closed lease.
        if (entry.lease != null) {
            if (entry.lease.isClosed()) {
                release(entry, RemoteFeature.PairingServiceState.UNAVAILABLE); return;
            }
        } else if (acquisitionTimedOut(entry)) {
            // Acquisition has not started status polling; report this refusal once, then detach.
            reject(entry); release(entry, RemoteFeature.PairingServiceState.UNAVAILABLE); return;
        }
        // The recurring watch never polls; an open ACTIVE lease retains only the backend's drain bound.
        main.postDelayed(entry.watchdog, WATCHDOG_INTERVAL_MILLIS);
    }
    private void report(Entry entry, RemoteFeature.PairingServiceState state) {
        entry.state = state;
        if (entry.operation != null) feature.reportPairingServiceState(entry.operation, state);
    }
    private void poll(Entry entry) {
        if (entry.wake != null) main.removeCallbacks(entry.wake);
        if (!owns(entry) || entry.inFlight) return;
        entry.inFlight = true;
        feature.pairingStatus(entry.id, entry.generation, entry.asDom).whenComplete((operation, failure) -> main.post(() -> {
            if (!owns(entry)) return;
            entry.inFlight = false;
            if (entry.lease == null || entry.lease.isClosed()) {
                release(entry, RemoteFeature.PairingServiceState.UNAVAILABLE); return;
            }
            if (failure != null) {
                // A previously observed ACTIVE completion drains under the backend's bound.
                if (entry.operation != null && entry.operation.active) {
                    report(entry, RemoteFeature.PairingServiceState.UNAVAILABLE); refreshNotification(entry); schedule(entry); return;
                }
                release(entry, RemoteFeature.PairingServiceState.UNAVAILABLE); return;
            }
            if (operation == null || !entry.id.equals(operation.id) || !entry.generation.equals(operation.generation)
                    || !entry.direction.equals(operation.direction) || operation.asDom != entry.asDom) {
                release(entry, RemoteFeature.PairingServiceState.STOPPED); return;
            }
            entry.operation = operation;
            // ACTIVE takes precedence over the public invitation clock. live includes its backend drain bound.
            if (!operation.live || !operation.active && (!operation.clockCertain || operation.remainingMillis <= 0)) {
                release(entry, RemoteFeature.PairingServiceState.STOPPED); return;
            }
            if (entry.lease == null || entry.lease.isClosed()) {
                release(entry, RemoteFeature.PairingServiceState.UNAVAILABLE); return;
            }
            report(entry, RemoteFeature.PairingServiceState.RUNNING);
            refreshNotification(entry); schedule(entry);
        }));
    }
    private void schedule(Entry entry) {
        if (!owns(entry)) return;
        main.removeCallbacks(entry.wake); main.postDelayed(entry.wake, 2000);
    }
    private void refreshNotification(Entry selected) {
        if (!owns(selected)) return;
        try { notifications(); }
        catch (RuntimeException unavailable) { release(selected, RemoteFeature.PairingServiceState.UNAVAILABLE); }
    }
    private void cancel(Entry requested, String capability) {
        if (capability == null || capability.length() > 512 || cancellations.containsKey(requested.key)
                || cancellations.size() >= 2) { stopIfEmpty(); return; }
        cancellations.put(requested.key, true);
        feature.cancelPairingOperation(requested.id, requested.generation, requested.asDom, capability)
                .whenComplete((cancelled, failure) -> main.post(() -> {
                    cancellations.remove(requested.key);
                    if (destroyed) return;
                    Entry current = entries.get(requested.key);
                    // Persist the backend tombstone before releasing any resources.
                    if (current != null) {
                        if (failure == null && Boolean.TRUE.equals(cancelled)) release(current, RemoteFeature.PairingServiceState.STOPPED);
                        else poll(current); // Stale/ACTIVE cancellation cannot stop an operation.
                    }
                    stopIfEmpty();
                }));
    }
    private void release(Entry entry, RemoteFeature.PairingServiceState state) {
        if (entries.get(entry.key) != entry) return;
        if (entry.wake != null) main.removeCallbacks(entry.wake);
        if (entry.watchdog != null) main.removeCallbacks(entry.watchdog);
        entries.remove(entry.key); report(entry, state);
        if (entry.lease != null) entry.lease.close();
        if (!entries.isEmpty()) {
            // A presentation failure must not close another operation's lease.
            try { notifications(); }
            catch (RuntimeException unavailable) {
                for (Entry remaining : entries.values()) report(remaining, RemoteFeature.PairingServiceState.UNAVAILABLE);
            }
        }
        getSystemService(NotificationManager.class).cancel(entry.notificationId());
        stopIfEmpty();
    }
    private void stopIfEmpty() {
        if (!entries.isEmpty()) return;
        // A pending cancellation callback must not retain foreground presentation without a lease.
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }
    private void notifications() {
        boolean first = true;
        for (Entry entry : entries.values()) {
            Notification notification = notification(entry);
            if (first) {
                if (Build.VERSION.SDK_INT >= 29) startForeground(entry.notificationId(), notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
                else startForeground(entry.notificationId(), notification);
                first = false;
            } else getSystemService(NotificationManager.class).notify(entry.notificationId(), notification);
        }
    }
    private Notification notification(Entry entry) {
        Uri identity = new Uri.Builder().scheme("subhub-pairing-operation").authority(entry.direction)
                .appendPath(entry.id).appendPath(entry.generation).build();
        Intent page = pageIntent(this, entry.id, entry.generation, entry.direction);
        // PendingIntent identity contains only operation identifiers, never an invitation or capability.
        page.addCategory(identity.toString());
        PendingIntent open = PendingIntent.getActivity(this, entry.notificationId(), page, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        int text = entry.state == RemoteFeature.PairingServiceState.RUNNING ? R.string.pairing_notification_running
                : entry.state == RemoteFeature.PairingServiceState.UNAVAILABLE ? R.string.pairing_unavailable : R.string.pairing_starting;
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_nav_people).setContentTitle(getString(R.string.remote_pairing))
                .setContentText(getString(text)).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW).setVisibility(NotificationCompat.VISIBILITY_SECRET)
                .setCategory(NotificationCompat.CATEGORY_SERVICE).setShowWhen(false);
        if (entry.lease != null && !entry.lease.isClosed() && entry.operation != null && !entry.operation.active) {
            Intent cancel = operationIntent(this, entry.operation, CANCEL).setData(identity)
                    .putExtra(CAPABILITY, entry.lease.cancelCapability());
            if (entry.cancelAction == null) entry.cancelAction = PendingIntent.getService(this, 0, cancel,
                    PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            builder.addAction(0, getString(android.R.string.cancel), entry.cancelAction);
        }
        return builder.build();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onDestroy() {
        destroyed = true; main.removeCallbacksAndMessages(null);
        for (Entry entry : entries.values()) {
            report(entry, RemoteFeature.PairingServiceState.STOPPED);
            if (entry.lease != null) entry.lease.close();
            getSystemService(NotificationManager.class).cancel(entry.notificationId());
        }
        entries.clear(); super.onDestroy();
    }
}
