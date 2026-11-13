package com.subhub.app.fullaccess;

import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;

import com.subhub.app.R;
import com.subhub.app.capture.MediaProjectionLeaseRegistry;

import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** A process-local screen request owned by one authenticated viewer connection. */
public final class FullAccessScreen {
    static final String REQUESTING = "requesting_consent";
    static final String AWAITING = "awaiting_consent";
    static final String STREAMING = "streaming";
    static final String DECLINED = "declined";
    static final String UNAVAILABLE = "unavailable";
    static final String IN_USE = "capture_in_use";
    static final String ALERTS_REQUIRED = "screen_alerts_required";
    static final String EXTRA_SESSION = "screen_session";
    static final String ACTIVE_CHANNEL = "full_access_screen_active";
    private static final String REQUEST_CHANNEL = "full_access_screen_requests";
    private static final int REQUEST_ID = 15031;
    private static final long CONSENT_WINDOW_MS = 60_000;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Object LOCK = new Object();
    private static Session active;

    private FullAccessScreen() {}

    public static Session open(Context context, BooleanSupplier grantCurrent) {
        return open(context, grantCurrent, () -> false);
    }

    public static Session open(Context context, BooleanSupplier grantCurrent, BooleanSupplier appForeground) {
        return open(context, grantCurrent, appForeground, false);
    }

    public static Session open(Context context, BooleanSupplier grantCurrent, BooleanSupplier appForeground,
            boolean videoRequested) {
        Session session = new Session(context.getApplicationContext(), grantCurrent, videoRequested);
        if (!session.revalidate()) return session;
        synchronized (LOCK) {
            if (active != null || MediaProjectionLeaseRegistry.owner() != null) {
                session.phase = IN_USE;
                return session;
            }
            active = session;
        }
        MAIN.post(() -> requestConsent(session, appForeground));
        return session;
    }

    /** Use the Android approval returned to the local viewer without sending a peer request. */
    static Session startLocal(android.app.Activity host, Intent approval, BooleanSupplier grantCurrent) {
        Session session = new Session(host.getApplicationContext(), grantCurrent, false);
        if (!session.revalidate() || !session.claimPrompt(null) || !session.acceptApproval(approval)) {
            session.close();
            return session;
        }
        synchronized (LOCK) {
            if (active != null || MediaProjectionLeaseRegistry.owner() != null) {
                session.end(IN_USE);
                return session;
            }
            active = session;
        }
        try {
            host.startForegroundService(new Intent(host, FullAccessScreenService.class)
                    .putExtra(EXTRA_SESSION, session.id));
        } catch (RuntimeException unavailable) {
            session.close();
        }
        return session;
    }

    static Session find(String id) {
        synchronized (LOCK) {
            return active != null && active.id.equals(id) ? active : null;
        }
    }

    static boolean displayAvailable(Context context) {
        PowerManager power = context.getSystemService(PowerManager.class);
        KeyguardManager keyguard = context.getSystemService(KeyguardManager.class);
        return power != null && power.isInteractive() && keyguard != null && !keyguard.isKeyguardLocked();
    }

    private static void requestConsent(Session session, BooleanSupplier appForeground) {
        if (!session.revalidate()) return;
        if (MediaProjectionLeaseRegistry.owner() != null) {
            session.end(IN_USE);
            return;
        }
        MAIN.postDelayed(session.expire, CONSENT_WINDOW_MS);
        if (displayAvailable(session.context) && current(appForeground)) {
            try {
                session.context.startActivity(consentIntent(session));
                MAIN.postDelayed(session.notifyIfPending, 1_500);
                return;
            } catch (RuntimeException unavailable) {
                // Use the same request through the notification if Activity launch was refused.
            }
        }
        session.notifyIfPending.run();
    }

    private static Intent consentIntent(Session session) {
        return new Intent(session.context, FullAccessScreenActivity.class)
                .setData(Uri.parse("subhub://screen-consent/" + session.id))
                .putExtra(EXTRA_SESSION, session.id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    private static boolean current(BooleanSupplier witness) {
        try { return witness.getAsBoolean(); }
        catch (RuntimeException unavailable) { return false; }
    }

    static boolean screenAlertsAvailable(Context context) {
        NotificationManager notifications = context.getSystemService(NotificationManager.class);
        if (notifications == null || !notifications.areNotificationsEnabled()) return false;
        NotificationChannel active = notifications.getNotificationChannel(ACTIVE_CHANNEL);
        NotificationChannel request = notifications.getNotificationChannel(REQUEST_CHANNEL);
        return (active == null || active.getImportance() != NotificationManager.IMPORTANCE_NONE)
                && (request == null || request.getImportance() != NotificationManager.IMPORTANCE_NONE);
    }

    static NotificationManager activeNotifications(Context context) {
        NotificationManager notifications = context.getSystemService(NotificationManager.class);
        if (notifications == null || !notifications.areNotificationsEnabled()) return null;
        notifications.createNotificationChannel(new NotificationChannel(ACTIVE_CHANNEL,
                context.getString(R.string.full_access_screen_channel), NotificationManager.IMPORTANCE_LOW));
        return sharingIndicatorEnabled(notifications) ? notifications : null;
    }

    static boolean sharingIndicatorEnabled(NotificationManager notifications) {
        if (notifications == null || !notifications.areNotificationsEnabled()) return false;
        NotificationChannel channel = notifications.getNotificationChannel(ACTIVE_CHANNEL);
        return channel != null && channel.getImportance() != NotificationManager.IMPORTANCE_NONE;
    }

    public static final class Session implements AutoCloseable {
        final Context context;
        final String id = UUID.randomUUID().toString();
        final FullAccessScreenFrames frames = new FullAccessScreenFrames();
        final FullAccessVideo.Queue video;
        private volatile boolean videoStarted;
        private final BooleanSupplier grantCurrent;
        private volatile String phase = AWAITING;
        private volatile boolean requestIssued;
        private boolean promptClaimed;
        private boolean approvalReceived;
        private Runnable onEnd;
        private Intent approval;
        private final Runnable expire = this::expireConsent;
        private final Runnable notifyIfPending = this::postRequestNotification;

        private Session(Context context, BooleanSupplier grantCurrent, boolean videoRequested) {
            this.context = context;
            video = videoRequested ? new FullAccessVideo.Queue() : null;
            this.grantCurrent = Objects.requireNonNull(grantCurrent);
        }

        public String status() {
            revalidate();
            String current = phase;
            return AWAITING.equals(current) && !requestIssued ? REQUESTING : current;
        }

        public String statusForViewer(boolean detailed) {
            String current = status();
            if (!detailed && REQUESTING.equals(current)) return AWAITING;
            if (!detailed && ALERTS_REQUIRED.equals(current)) return UNAVAILABLE;
            return current;
        }

        void markRequestIssued() { requestIssued = true; }

        public byte[] takeLatest() {
            if (!captureAllowed()) return null;
            synchronized (this) {
                return STREAMING.equals(phase) ? frames.takeLatest(SystemClock.elapsedRealtime()) : null;
            }
        }

        boolean revalidate() {
            if (!isLive()) return false;
            if (!current(grantCurrent)) {
                end(UNAVAILABLE);
                return false;
            }
            return isLive();
        }

        boolean captureAllowed() {
            if (!STREAMING.equals(phase) || !revalidate()) return false;
            if (!displayAvailable(context)) {
                end(UNAVAILABLE);
                return false;
            }
            return STREAMING.equals(phase);
        }

        public boolean videoReady() { return videoStarted && captureAllowed(); }

        boolean videoCanSample() { return STREAMING.equals(phase) && displayAvailable(context); }

        public FullAccessVideo.Packet nextVideoPacket() throws InterruptedException {
            FullAccessVideo.Packet packet = video.next();
            return videoReady() ? packet : FullAccessVideo.signal(FullAccessVideo.END);
        }

        private boolean isLive() { return AWAITING.equals(phase) || STREAMING.equals(phase); }

        synchronized boolean claimPrompt(Runnable cancel) {
            if (promptClaimed || !AWAITING.equals(phase)) return false;
            promptClaimed = true;
            onEnd = cancel;
            cancelRequestNotification();
            return true;
        }

        synchronized boolean acceptApproval(Intent result) {
            if (!AWAITING.equals(phase) || !promptClaimed || approvalReceived || result == null) return false;
            approvalReceived = true;
            requestIssued = true;
            approval = result;
            return true;
        }

        synchronized Intent consumeApproval() {
            Intent result = AWAITING.equals(phase) ? approval : null;
            approval = null;
            return result;
        }

        synchronized boolean startStreaming(boolean videoStarted) {
            if (!AWAITING.equals(phase)) return false;
            this.videoStarted = videoStarted;
            phase = STREAMING;
            MAIN.removeCallbacks(expire);
            return true;
        }

        synchronized boolean reattachPrompt(Runnable callback) {
            if (!AWAITING.equals(phase) || !promptClaimed || approvalReceived) return false;
            onEnd = callback;
            return true;
        }

        synchronized boolean attachCapture(Runnable callback) {
            if (!AWAITING.equals(phase) || !approvalReceived) return false;
            onEnd = callback;
            return true;
        }

        synchronized void clearOnEnd(Runnable callback) {
            if (onEnd == callback) onEnd = null;
        }

        private synchronized void expireConsent() {
            if (AWAITING.equals(phase)) end(UNAVAILABLE);
        }

        private synchronized void postRequestNotification() {
            if (promptClaimed || !AWAITING.equals(phase)) return;
            try {
                NotificationManager notifications = activeNotifications(context);
                if (notifications == null) {
                    end(ALERTS_REQUIRED);
                    return;
                }
                String title = context.getString(R.string.full_access_screen_request);
                // Existing channels keep their Android settings. New requests are time-sensitive.
                notifications.createNotificationChannel(new NotificationChannel(REQUEST_CHANNEL, title,
                        NotificationManager.IMPORTANCE_HIGH));
                NotificationChannel channel = notifications.getNotificationChannel(REQUEST_CHANNEL);
                if (channel == null || channel.getImportance() == NotificationManager.IMPORTANCE_NONE) {
                    end(ALERTS_REQUIRED);
                    return;
                }
                PendingIntent consent = PendingIntent.getActivity(context, 0, consentIntent(this),
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_ONE_SHOT);
                notifications.notify(id, REQUEST_ID, new Notification.Builder(context, REQUEST_CHANNEL)
                        .setContentTitle(title)
                        .setSmallIcon(android.R.drawable.ic_menu_view)
                        .setContentIntent(consent)
                        .setAutoCancel(true)
                        .setLocalOnly(true)
                        .setVisibility(Notification.VISIBILITY_PRIVATE)
                        .setTimeoutAfter(CONSENT_WINDOW_MS)
                        .build());
                markRequestIssued();
            } catch (SecurityException denied) {
                end(ALERTS_REQUIRED);
            } catch (RuntimeException unavailable) {
                end(UNAVAILABLE);
            }
        }

        private void cancelRequestNotification() {
            try {
                NotificationManager notifications = context.getSystemService(NotificationManager.class);
                if (notifications != null) notifications.cancel(id, REQUEST_ID);
            } catch (RuntimeException unavailable) {
                // The exact request ticket still expires locally.
            }
        }

        @Override public void close() { end(UNAVAILABLE); }

        void end(String status) {
            Runnable callback;
            synchronized (this) {
                if (!isLive()) return;
                phase = status;
                approval = null;
                frames.close();
                if (video != null) video.close();
                callback = onEnd;
                onEnd = null;
            }
            synchronized (LOCK) {
                if (active == this) active = null;
            }
            MAIN.removeCallbacks(expire);
            MAIN.removeCallbacks(notifyIfPending);
            MAIN.post(this::cancelRequestNotification);
            if (callback != null) MAIN.post(callback);
        }
    }
}
