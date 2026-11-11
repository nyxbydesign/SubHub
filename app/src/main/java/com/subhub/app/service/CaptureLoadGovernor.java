package com.subhub.app.service;

import android.content.Context;
import android.os.Build;
import android.os.PowerManager;

/** Keeps normal capture unconstrained, backing off only for explicit system thermal/power signals. */
final class CaptureLoadGovernor implements AutoCloseable {
    private final PowerManager powerManager;
    private final PowerManager.OnThermalStatusChangedListener thermalListener;
    private final boolean owned;
    private final OwnedThermal ownedThermal;
    private volatile boolean sealed, closeStarted;
    private boolean startAttempted;
    private volatile int thermalStatus = PowerManager.THERMAL_STATUS_NONE;
    private long lastCaptureUptimeMillis = Long.MIN_VALUE;

    CaptureLoadGovernor(Context context) { this(context, false); }

    /** Prepare before STARTUP registration; caller retains this object even if startOwned throws. */
    static CaptureLoadGovernor prepareOwned(Context context) { return new CaptureLoadGovernor(context, true); }

    private CaptureLoadGovernor(Context context, boolean owned) {
        this.owned = owned;
        powerManager = context.getSystemService(PowerManager.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && powerManager != null) {
            if (owned) {
                java.util.concurrent.Executor main = context.getMainExecutor();
                ownedThermal = new OwnedThermal(status -> thermalStatus = status, new OwnedThermal.Platform() {
                    @Override public void register(PowerManager.OnThermalStatusChangedListener listener) {
                        powerManager.addThermalStatusListener(main, listener);
                    }
                    @Override public void unregister(PowerManager.OnThermalStatusChangedListener listener) {
                        powerManager.removeThermalStatusListener(listener);
                    }
                });
                thermalListener = null;
            } else {
                ownedThermal = null;
                thermalListener = status -> thermalStatus = status;
                powerManager.addThermalStatusListener(context.getMainExecutor(), thermalListener);
            }
        } else { thermalListener = null; ownedThermal = null; }
    }

    void startOwned() {
        synchronized (this) {
            if (!owned || sealed || startAttempted) throw new IllegalStateException("Owned governor start unavailable");
            startAttempted = true;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && ownedThermal != null) ownedThermal.start();
    }
    /** Nonblocking owner-reference fence; accepted callback bodies still owe actual return. */
    void sealOwned() {
        if (!owned) throw new IllegalStateException("Unbound governor has no owned retirement");
        sealed = true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && ownedThermal != null) ownedThermal.seal();
    }
    boolean managedResourcesDrained() {
        return owned && closeStarted && (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || ownedThermal == null || ownedThermal.drained());
    }

    synchronized boolean shouldCapture(long nowUptimeMillis, long requestedIntervalMillis) {
        if (owned && sealed) return false;
        boolean powerSave = powerManager != null && powerManager.isPowerSaveMode();
        long interval = intervalFor(requestedIntervalMillis, thermalStatus, powerSave);
        if (lastCaptureUptimeMillis != Long.MIN_VALUE
                && nowUptimeMillis - lastCaptureUptimeMillis < interval) return false;
        lastCaptureUptimeMillis = nowUptimeMillis;
        return true;
    }

    static long intervalFor(long requested, int thermalStatus, boolean powerSave) {
        long interval = Math.max(16L, requested);
        if (powerSave) interval = Math.max(interval, 50L);
        if (thermalStatus >= PowerManager.THERMAL_STATUS_EMERGENCY) {
            interval = Math.max(interval, 250L);
        } else if (thermalStatus >= PowerManager.THERMAL_STATUS_CRITICAL) {
            interval = Math.max(interval, 125L);
        } else if (thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE) {
            interval = Math.max(interval, 66L);
        } else if (thermalStatus >= PowerManager.THERMAL_STATUS_MODERATE) {
            interval = Math.max(interval, 33L);
        }
        return interval;
    }

    @Override
    public void close() {
        if (owned) {
            sealOwned(); closeStarted = true;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && ownedThermal != null) ownedThermal.close();
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                && powerManager != null && thermalListener != null) {
            powerManager.removeThermalStatusListener(thermalListener);
        }
    }

    /** API29 callback ownership only; ordinary cadence/power policy is unchanged. */
    @androidx.annotation.RequiresApi(29)
    static final class OwnedThermal implements AutoCloseable {
        interface Platform {
            void register(PowerManager.OnThermalStatusChangedListener listener);
            void unregister(PowerManager.OnThermalStatusChangedListener listener);
        }
        private final Platform platform;
        final ThermalCallback callback;
        private boolean started, registering, sealed, closing, unregistering, unregistered, failed;
        OwnedThermal(java.util.function.IntConsumer status, Platform platform) {
            callback = new ThermalCallback(java.util.Objects.requireNonNull(status));
            this.platform = java.util.Objects.requireNonNull(platform);
        }
        void start() {
            synchronized (this) {
                if (started || sealed) throw new IllegalStateException("Thermal ownership retired or already started");
                started = true; registering = true;
            }
            try { platform.register(callback); }
            catch (RuntimeException | Error uncertainty) { synchronized (this) { failed = true; } throw uncertainty; }
            finally {
                synchronized (this) { registering = false; }
                finishClose();
            }
        }
        void seal() {
            synchronized (this) { sealed = true; }
            callback.retire();
        }
        @Override public void close() {
            seal();
            synchronized (this) { closing = true; }
            finishClose();
            synchronized (this) { if (failed) throw new IllegalStateException("Thermal registration/closure uncertain"); }
        }
        private void finishClose() {
            synchronized (this) {
                if (!closing || registering || unregistering || unregistered) return;
                if (!started) { unregistered = true; return; }
                unregistering = true;
            }
            try { platform.unregister(callback); }
            catch (RuntimeException | Error uncertainty) { synchronized (this) { failed = true; } throw uncertainty; }
            finally { synchronized (this) { unregistering = false; unregistered = true; } }
        }
        boolean drained() {
            synchronized (this) {
                if (!closing || registering || unregistering || !unregistered || failed) return false;
            }
            return callback.drained();
        }
    }

    /** Static platform target: after retirement only inert data and the detached relay remain. */
    @androidx.annotation.RequiresApi(29)
    static final class ThermalCallback implements PowerManager.OnThermalStatusChangedListener {
        private final OwnedProjectionBinding.CallbackRelay relay;
        private int status;
        ThermalCallback(java.util.function.IntConsumer target) {
            relay = new OwnedProjectionBinding.CallbackRelay(() -> target.accept(status));
        }
        // Serialize status with its invocation. retire never takes this monitor or waits for a body.
        @Override public synchronized void onThermalStatusChanged(int value) { status = value; relay.run(); }
        void retire() { relay.retire(); }
        boolean drained() { return relay.drained(); }
    }
}
