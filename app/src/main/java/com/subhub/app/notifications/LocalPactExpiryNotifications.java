package com.subhub.app.notifications;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import com.subhub.app.appmode.RuntimeSessionIdentity;
import com.subhub.app.remote.RemoteSessionClock;
import com.subhub.app.service.ScreenCaptureService;
import com.subhub.app.settings.SettingsRepository;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import org.json.JSONException;
import org.json.JSONObject;

/** Local expiry identity survives persistence; its runtime fence rejects replacement start/end cycles. */
public final class LocalPactExpiryNotifications {
    static final String RECEIPT = "notification_local_pact_expiry";
    static final String FENCE = "notification_local_pact_expiry_fence";
    private LocalPactExpiryNotifications() { }
    public static void append(SharedPreferences.Editor edit, String pact, String statistics, long sequence) {
        try {
            edit.remove(RECEIPT).remove(FENCE);
            edit.putString(RECEIPT, new JSONObject().put("sequence", sequence).put("pact", pact)
                    .put("statistics", statistics).put("token", UUID.randomUUID().toString()).toString());
        } catch (JSONException | RuntimeException unavailable) { /* Optional presentation only. */ }
    }
    public static void schedule(Context context, ScreenCaptureService.StopTarget capture, long sequence) {
        afterCaptureClosure(context, () -> capture == null || capture.isClosed(), sequence);
    }
    /** Wait for the historical capture rather than the state of a replacement service. */
    static CompletableFuture<Void> afterCaptureClosure(Context context, BooleanSupplier closed, long sequence) {
        CompletableFuture<Void> done = new CompletableFuture<>();
        try {
            final String notificationEpoch;
            final boolean notificationAllowed;
            synchronized (NotificationPreferences.DELIVERY_LOCK) {
                notificationEpoch = NotificationPreferences.sessionEpoch(context);
                notificationAllowed = NotificationPreferences.enabled(context, NotificationPreferences.Role.SUB,
                        NotificationPreferences.Event.SESSION_STOPPED);
            }
            final SharedPreferences main = context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME, 0);
            final String receipt, fence;
            final RuntimeSessionIdentity.ComponentObservation scope;
            synchronized (RuntimeSessionIdentity.LOCK) {
                receipt = main.getString(RECEIPT, "");
                if (receipt.isEmpty() || new JSONObject(receipt).getLong("sequence") != sequence) {
                    done.complete(null); return done;
                }
                scope = RuntimeSessionIdentity.componentObservation(context);
                fence = RuntimeSessionIdentity.observe(context, RemoteSessionClock.local()).idleFence;
                if (!main.edit().putString(FENCE, fence).commit()) {
                    main.edit().remove(FENCE).commit(); done.complete(null); return done;
                }
            }
            Handler handler = new Handler(Looper.getMainLooper());
            Runnable check = new Runnable() {
                private int attempts;
                @Override public void run() {
                    try {
                        if (!closed.getAsBoolean()) {
                            if (++attempts < 100 && handler.postDelayed(this, 100)) return;
                            done.complete(null); return;
                        }
                        synchronized (RuntimeSessionIdentity.LOCK) {
                            if (!scope.isCurrent() || !receipt.equals(main.getString(RECEIPT, ""))
                                    || !fence.equals(main.getString(FENCE, ""))
                                    || !fence.equals(RuntimeSessionIdentity.observe(context, RemoteSessionClock.local()).idleFence)
                                    || RuntimeSessionIdentity.observe(context, RemoteSessionClock.local()).active) {
                                done.complete(null); return;
                            }
                            // Keep admission and posting atomic with a local disable/re-enable transition.
                            synchronized (NotificationPreferences.DELIVERY_LOCK) {
                                if (!notificationAllowed || !notificationEpoch.equals(NotificationPreferences.sessionEpoch(context))) {
                                    PeerNotifications.consume(context, NotificationPreferences.Role.SUB,
                                            NotificationPreferences.Event.SESSION_STOPPED, "local-pact-expiry", sequence);
                                    done.complete(null); return;
                                }
                                PeerNotifications.applied(context, NotificationPreferences.Role.SUB,
                                        NotificationPreferences.Event.SESSION_STOPPED, "local-pact-expiry", sequence);
                            }
                        }
                    } catch (RuntimeException unavailable) { /* Optional presentation cannot fail expiry dispatch. */ }
                    done.complete(null);
                }
            };
            if (!handler.post(check)) done.complete(null);
        } catch (JSONException | RuntimeException unavailable) { done.complete(null); }
        return done;
    }
}
