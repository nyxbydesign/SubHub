package com.subhub.app.appmode;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import com.subhub.app.commitment.CommitmentExpiryReceiver;
import com.subhub.app.penance.PaidPauseExpiryReceiver;
import com.subhub.app.runtime.AppRuntimeGateway;
import java.util.EnumMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Coalesced timer intent, ordered with runtime commits; Android calls never hold Runtime. */
public final class SessionExpiryAlarms {
    public enum Kind {
        PACT(8317, "com.subhub.app.action.PACT_EXPIRED", CommitmentExpiryReceiver.class,
                AlarmManager.ELAPSED_REALTIME_WAKEUP),
        PAUSE(8349, "com.subhub.app.action.PAID_PAUSE_EXPIRED", PaidPauseExpiryReceiver.class,
                AlarmManager.RTC_WAKEUP);
        final int request, clock;
        final String action;
        final Class<?> receiver;
        Kind(int request, String action, Class<?> receiver, int clock) {
            this.request = request; this.action = action; this.receiver = receiver; this.clock = clock;
        }
    }
    private static final Object LOCK = new Object();
    private static final EnumMap<Kind, Slot> SLOTS = new EnumMap<>(Kind.class);
    private static final java.util.List<java.util.concurrent.CompletableFuture<Void>> IDLE_WAITERS = new java.util.ArrayList<>();
    private static boolean idleQueued;
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "session-expiry-alarms"); thread.setDaemon(true); return thread;
    });
    private static final class Command {
        final Context context;
        final String identity;
        final long trigger;
        final AppRuntimeGateway.LegacyWork delivery;
        Command(Context context, String identity, long trigger, AppRuntimeGateway.LegacyWork delivery) {
            this.context = context; this.identity = identity; this.trigger = trigger; this.delivery = delivery;
        }
    }
    private static final class Slot {
        Command next;
        String retired, installed;
        boolean running;
    }
    private SessionExpiryAlarms() { }
    /** FIFO completion follows the currently queued drains without waiting under Runtime or on main. */
    public static java.util.concurrent.CompletableFuture<Void> whenIdle() {
        java.util.concurrent.CompletableFuture<Void> completion = new java.util.concurrent.CompletableFuture<>();
        completion.whenComplete((ignored, failure) -> {
            synchronized (LOCK) { IDLE_WAITERS.remove(completion); }
        });
        java.util.List<java.util.concurrent.CompletableFuture<Void>> rejected = null;
        RuntimeException failure = null;
        synchronized (LOCK) {
            IDLE_WAITERS.removeIf(java.util.concurrent.CompletableFuture::isDone);
            if (IDLE_WAITERS.size() >= 32) {
                completion.completeExceptionally(new IllegalStateException("Timer completion capacity exceeded"));
                return completion;
            }
            IDLE_WAITERS.add(completion);
            if (!idleQueued) {
                idleQueued = true;
                try { WORKER.execute(SessionExpiryAlarms::completeIdle); }
                catch (RuntimeException unavailable) {
                    idleQueued = false; rejected = new java.util.ArrayList<>(IDLE_WAITERS); IDLE_WAITERS.clear(); failure = unavailable;
                }
            }
        }
        if (rejected != null) for (java.util.concurrent.CompletableFuture<Void> waiter : rejected) waiter.completeExceptionally(failure);
        return completion;
    }
    private static void completeIdle() {
        java.util.List<java.util.concurrent.CompletableFuture<Void>> completed;
        synchronized (LOCK) {
            IDLE_WAITERS.removeIf(java.util.concurrent.CompletableFuture::isDone);
            if (!IDLE_WAITERS.isEmpty() && SLOTS.values().stream().anyMatch(slot -> slot.running)) {
                // A later producer can queue a drain behind this shared barrier. Follow that drain too.
                WORKER.execute(SessionExpiryAlarms::completeIdle); return;
            }
            idleQueued = false; completed = new java.util.ArrayList<>(IDLE_WAITERS); IDLE_WAITERS.clear();
        }
        for (java.util.concurrent.CompletableFuture<Void> waiter : completed) waiter.complete(null);
    }
    /**
     * Exact managed timer handle, retained before invoking Android. Unlike the legacy coalesced
     * queue, these worker-only calls report actual return/failure and never swallow Binder errors.
     * Cancellation cannot recall an already delivered receiver: that receiver still revalidates
     * its stored session identity and admitted physical owner before any transition.
     */
    public static final class ManagedAlarm {
        private final Context context;
        private final com.subhub.app.runtime.RuntimeLease.Identity owner;
        private final Kind kind;
        private final String identity;
        private final long trigger;
        private AlarmManager manager;
        private PendingIntent pending;
        private boolean attempted, installed, cancelled;
        private ManagedAlarm(Context context, com.subhub.app.runtime.RuntimeLease.Identity owner, Kind kind, String identity, long trigger) {
            this.owner = java.util.Objects.requireNonNull(owner);
            if (owner.target.kind != com.subhub.app.runtime.RuntimeTarget.Kind.DEVICE) throw new SecurityException("DEVICE timer required");
            this.context = context.getApplicationContext(); this.kind = kind;
            this.identity = identity; this.trigger = trigger;
        }
        public synchronized void install() {
            requireManagedWorker(context);
            if (attempted || cancelled) throw new IllegalStateException("Managed timer already attempted");
            // The installed gateway verifies the exact caller's admitted DEVICE continuation.
            try (com.subhub.app.runtime.AppRuntimeGateway.DeviceMutationPermit permit =
                    com.subhub.app.runtime.AppRuntimeGateway.admitManagedDeviceMutation(context, owner,
                            com.subhub.app.runtime.AppRuntimeGateway.Mutation.ARM,
                            com.subhub.app.runtime.AppRuntimeGateway.ManagedDevicePhase.STARTUP)) {
                java.util.Objects.requireNonNull(permit);
                synchronized (RuntimeSessionIdentity.LOCK) {
                    if (!identity.equals(storedIdentity(context, kind)))
                        throw new SecurityException("Managed timer does not own the persisted session");
                }
                attempted = true; // An exception afterward may have accepted native work.
                manager = context.getSystemService(AlarmManager.class);
                if (manager == null) throw new IllegalStateException("Alarm service unavailable");
                pending = PendingIntent.getBroadcast(context, kind.request,
                        intent(context, kind, identity), PendingIntent.FLAG_IMMUTABLE);
                if (pending == null) throw new IllegalStateException("Timer token unavailable");
                manager.setAndAllowWhileIdle(kind.clock, trigger, pending);
                installed = true;
            }
        }
        /** Returns only after exact native cancellation; failure leaves the handle unreleased. */
        public synchronized void cancel() {
            requireManagedWorker(context);
            if (cancelled) return;
            try (com.subhub.app.runtime.AppRuntimeGateway.DeviceMutationPermit permit =
                    com.subhub.app.runtime.AppRuntimeGateway.admitManagedDeviceMutation(context, owner,
                            com.subhub.app.runtime.AppRuntimeGateway.Mutation.DISARM,
                            com.subhub.app.runtime.AppRuntimeGateway.ManagedDevicePhase.CLEANUP)) {
                java.util.Objects.requireNonNull(permit);
                if (attempted) {
                    AlarmManager exact = manager != null ? manager : context.getSystemService(AlarmManager.class);
                    if (exact == null) throw new IllegalStateException("Alarm service unavailable");
                    // A throwing token creation may still have created the exact PendingIntent.
                    PendingIntent token = pending != null ? pending : PendingIntent.getBroadcast(context,
                            kind.request, intent(context, kind, identity),
                            PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);
                    if (token != null) { exact.cancel(token); token.cancel(); }
                }
                installed = false; cancelled = true; pending = null; manager = null;
            }
        }
        public synchronized boolean installationAcknowledged() { return installed && !cancelled; }
        public synchronized boolean cancellationAcknowledged() { return cancelled; }
    }
    public static ManagedAlarm prepareManaged(Context context, com.subhub.app.runtime.RuntimeLease.Identity owner, Kind kind, String identity, long trigger) {
        java.util.Objects.requireNonNull(context); java.util.Objects.requireNonNull(kind);
        if (!validIdentity(identity) || trigger <= 0) throw new IllegalArgumentException("Invalid managed timer");
        return new ManagedAlarm(context, owner, kind, identity, trigger);
    }
    private static void requireManagedWorker(Context context) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper())
            throw new IllegalStateException("Managed timer work must run off main");
        if (com.subhub.app.runtime.AppRuntimeGateway.current(context) == null)
            throw new SecurityException("Managed timer requires the installed physical owner");
    }

    public static boolean validIdentity(String identity) {
        return identity != null && identity.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }
    public static Intent intent(Context context, Kind kind, String identity) {
        if (!validIdentity(identity)) throw new IllegalArgumentException("Timer identity required");
        return new Intent(context, kind.receiver).setAction(kind.action)
                .setData(Uri.parse("subhub-expiry://" + kind.name().toLowerCase(java.util.Locale.ROOT) + "/" + identity));
    }
    /** Null is a legacy unbound alarm; an empty result is malformed and must be refused. */
    public static String target(Intent intent, Kind kind) {
        if (intent == null || !kind.action.equals(intent.getAction())) return "";
        Uri data = intent.getData();
        if (data == null) return null;
        String identity = data.getLastPathSegment();
        if (!validIdentity(identity)) return "";
        String expected = "subhub-expiry://" + kind.name().toLowerCase(java.util.Locale.ROOT) + "/" + identity;
        return expected.equals(data.toString()) ? identity : "";
    }
    /** Call in the same Runtime transaction as the checked preference commit. No Binder work here. */
    public static void update(Context context, Kind kind, String retired, String identity, long trigger) {
        if (!Thread.holdsLock(RuntimeSessionIdentity.LOCK)) throw new IllegalStateException("Runtime transaction required");
        if (!identity.isEmpty() && !validIdentity(identity)) throw new IllegalArgumentException("Invalid timer identity");
        // A delayed cleanup request is not authority to replace a newer committed timer intent.
        if (!identity.equals(storedIdentity(context, kind))) return;
        AppRuntimeGateway.LegacyWork delivery = AppRuntimeGateway.beginLegacyWork(context);
        synchronized (LOCK) {
            Slot slot = SLOTS.get(kind);
            if (slot == null) { slot = new Slot(); SLOTS.put(kind, slot); }
            // Only the first retired generation can already exist in Android before this pending batch.
            // Later coalesced generations have never been scheduled; an in-flight one is tracked below.
            Command replaced = slot.next;
            if (replaced == null) slot.retired = validIdentity(retired) ? retired : "";
            slot.next = new Command(context, identity, trigger, delivery);
            // The worker claims under this same lock, so the replaced command never reached Android.
            if (replaced != null) replaced.delivery.close();
            if (!slot.running) {
                slot.running = true;
                Slot work = slot;
                WORKER.execute(() -> drain(kind, work));
            }
        }
    }
    private static void drain(Kind kind, Slot slot) {
        for (;;) {
            Command command;
            String retired, installed;
            synchronized (LOCK) {
                command = slot.next;
                if (command == null) { slot.running = false; LOCK.notifyAll(); return; }
                retired = slot.retired; installed = slot.installed;
                slot.next = null; slot.retired = null;
            }
            try {
                // This barrier also covers callers that enqueue while an outer Runtime owner continues.
                boolean current;
                synchronized (RuntimeSessionIdentity.LOCK) {
                    current = command.identity.equals(storedIdentity(command.context, kind));
                }
                AlarmManager alarm = command.context.getSystemService(AlarmManager.class);
                if (alarm == null) throw new IllegalStateException("Alarm service unavailable");
                cancel(command.context, kind, alarm, ""); // Retire the old unbound token without creating it.
                if (retired != null && !retired.isEmpty() && !retired.equals(command.identity))
                    cancel(command.context, kind, alarm, retired);
                if (installed != null && !installed.isEmpty() && !installed.equals(command.identity)
                        && (current || installed.equals(retired)))
                    cancel(command.context, kind, alarm, installed);
                if (current && !command.identity.isEmpty()) {
                    PendingIntent pending = PendingIntent.getBroadcast(command.context, kind.request,
                            intent(command.context, kind, command.identity), PendingIntent.FLAG_IMMUTABLE);
                    alarm.setAndAllowWhileIdle(kind.clock, command.trigger, pending);
                }
                synchronized (LOCK) {
                    if (current) slot.installed = command.identity;
                    else if (installed != null && installed.equals(retired)) slot.installed = "";
                }
            } catch (RuntimeException failure) {
                android.util.Log.w("SessionExpiry", "Timer delivery unavailable", failure);
            } finally {
                // Only the queue delivery returned. A persisted or native alarm is a separate
                // occupancy fact, and a delivered broadcast must acquire its own admission.
                command.delivery.close();
            }
        }
    }
    private static String storedIdentity(Context context, Kind kind) {
        return kind == Kind.PACT ? context.getSharedPreferences(
                com.subhub.app.settings.SettingsRepository.PREFERENCES_NAME, 0).getString("commitment_session_identity", "")
                : context.getSharedPreferences(com.subhub.app.penance.PenanceManager.PREFS_NAME, 0)
                        .getString("paid_pause_session_identity", "");
    }
    private static void cancel(Context context, Kind kind, AlarmManager alarm, String identity) {
        Intent intent = identity.isEmpty() ? new Intent(context, kind.receiver).setAction(kind.action)
                : intent(context, kind, identity);
        PendingIntent pending = PendingIntent.getBroadcast(context, kind.request, intent,
                PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);
        if (pending != null) { alarm.cancel(pending); pending.cancel(); }
    }
}
