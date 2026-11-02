package com.subhub.app.appmode;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import com.subhub.app.commitment.CommitmentExpiryReceiver;
import com.subhub.app.penance.PaidPauseExpiryReceiver;
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
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "session-expiry-alarms"); thread.setDaemon(true); return thread;
    });
    private static final class Command {
        final Context context;
        final String identity;
        final long trigger;
        Command(Context context, String identity, long trigger) {
            this.context = context; this.identity = identity; this.trigger = trigger;
        }
    }
    private static final class Slot {
        Command next;
        String retired, installed;
        boolean running;
    }
    private SessionExpiryAlarms() { }
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
        synchronized (LOCK) {
            Slot slot = SLOTS.get(kind);
            if (slot == null) { slot = new Slot(); SLOTS.put(kind, slot); }
            // Only the first retired generation can already exist in Android before this pending batch.
            // Later coalesced generations have never been scheduled; an in-flight one is tracked below.
            if (slot.next == null) slot.retired = validIdentity(retired) ? retired : "";
            slot.next = new Command(context, identity, trigger);
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
