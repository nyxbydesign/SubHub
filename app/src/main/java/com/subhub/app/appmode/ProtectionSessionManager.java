package com.subhub.app.appmode;

import android.content.Context;
import android.content.SharedPreferences;

import com.subhub.app.runtime.AppRuntimeGateway;
import com.subhub.app.settings.SettingsRepository;

/** Persists user intent without attempting to persist Android's one-use MediaProjection token. */
public final class ProtectionSessionManager {
    private static final String KEY_MEDIA_PROJECTION_DESIRED =
            "media_projection_session_desired";

    private ProtectionSessionManager() {}

    public static void markMediaProjectionStarted(Context context) {
        AppRuntimeGateway.DeviceMutationPermit permit = AppRuntimeGateway.admitDeviceMutation(
                context, AppRuntimeGateway.Mutation.PROJECTION_STARTED);
        try {
            synchronized (RuntimeSessionIdentity.LOCK) {
                if (!com.subhub.app.remote.RemoteSessionNative.beforeLocalStart(context)) return;
                preferences(context).edit().putBoolean(KEY_MEDIA_PROJECTION_DESIRED, true).apply();
                RuntimeSessionIdentity.changed();
            }
        } finally { permit.close(); }
    }

    public static void markMediaProjectionExplicitlyStopped(Context context) {
        AppRuntimeGateway.DeviceMutationPermit permit = AppRuntimeGateway.admitDeviceMutation(
                context, AppRuntimeGateway.Mutation.PROJECTION_STOPPED);
        try {
            synchronized (RuntimeSessionIdentity.LOCK) {
                SharedPreferences.Editor edit = preferences(context).edit()
                        .putBoolean(KEY_MEDIA_PROJECTION_DESIRED, false);
                com.subhub.app.remote.RemoteSessionNative.appendLocalEnd(context, edit);
                edit.commit();
                RuntimeSessionIdentity.changed();
            }
        } finally { permit.close(); }
    }

    /** Backend-owned durable intent only. This never supplies/reuses a MediaProjection grant. */
    public static ManagedProjection prepareManaged(Context context, com.subhub.app.runtime.RuntimeLease.Identity owner) {
        if (owner == null || owner.target.kind != com.subhub.app.runtime.RuntimeTarget.Kind.DEVICE)
            throw new SecurityException("Managed projection needs DEVICE evidence");
        return new ManagedProjection(context.getApplicationContext(), owner);
    }
    public static final class ManagedProjection {
        private final Context context;
        private final com.subhub.app.runtime.RuntimeLease.Identity fullOwner;
        private final String owner;
        private boolean attempted, acknowledged, finished;
        private ManagedProjection(Context context, com.subhub.app.runtime.RuntimeLease.Identity owner) { this.context = context; this.fullOwner = owner; this.owner = owner.session; }
        public synchronized void start() {
            requireManagedWorker(context);
            if (attempted || finished) throw new IllegalStateException("Managed projection already attempted");
            try (AppRuntimeGateway.DeviceMutationPermit permit = AppRuntimeGateway.admitManagedDeviceMutation(context, fullOwner, AppRuntimeGateway.Mutation.PROJECTION_STARTED, AppRuntimeGateway.ManagedDevicePhase.STARTUP)) {
                java.util.Objects.requireNonNull(permit);
                synchronized (RuntimeSessionIdentity.LOCK) {
                    SharedPreferences values = preferences(context);
                    if (!owner.equals(values.getString(AppModeManager.KEY_MANAGED_DEVICE_SESSION, ""))
                            || !values.getBoolean(AppModeManager.KEY_ARMED, false)
                            || values.getBoolean(KEY_MEDIA_PROJECTION_DESIRED, false))
                        throw new SecurityException("Physical projection intent is not available");
                    attempted = true;
                    if (!values.edit().putBoolean(KEY_MEDIA_PROJECTION_DESIRED, true).commit())
                        throw new IllegalStateException("Projection intent start was not acknowledged");
                    RuntimeSessionIdentity.changed(); acknowledged = true;
                }
            }
        }
        public synchronized void finish() {
            requireManagedWorker(context);
            if (finished) return;
            try (AppRuntimeGateway.DeviceMutationPermit permit = AppRuntimeGateway.admitManagedDeviceMutation(context, fullOwner, AppRuntimeGateway.Mutation.PROJECTION_STOPPED, AppRuntimeGateway.ManagedDevicePhase.CLEANUP)) {
                java.util.Objects.requireNonNull(permit);
                synchronized (RuntimeSessionIdentity.LOCK) {
                    if (!attempted) { finished = true; return; }
                    SharedPreferences values = preferences(context);
                    if (!owner.equals(values.getString(AppModeManager.KEY_MANAGED_DEVICE_SESSION, "")))
                        throw new SecurityException("Physical projection owner changed before cleanup");
                    if (!values.edit().putBoolean(KEY_MEDIA_PROJECTION_DESIRED, false).commit())
                        throw new IllegalStateException("Projection intent end was not acknowledged");
                    RuntimeSessionIdentity.changed(); acknowledged = false; finished = true;
                }
            }
        }
        public synchronized boolean startAcknowledged() { return acknowledged && !finished; }
        public synchronized boolean finishAcknowledged() { return finished; }
    }
    private static void requireManagedWorker(Context context) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper())
            throw new IllegalStateException("Managed projection writes require the owner worker");
        if (AppRuntimeGateway.current(context) == null)
            throw new SecurityException("Managed projection writes require installed physical ownership");
    }

    public static boolean needsMediaProjectionResume(Context context) {
        return preferences(context).getBoolean(KEY_MEDIA_PROJECTION_DESIRED, false);
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(
                SettingsRepository.PREFERENCES_NAME, Context.MODE_PRIVATE);
    }
}
