package com.subhub.app.runtime;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * In-process legacy quiescence only, NOT a runtime lease, durable journal or OS-close proof.
 * Every legacy prompt, queued service delivery and mutation must participate before this can be
 * used for installation. Existing sessions keep their original identities and behavior while the
 * barrier is LEGACY. The installer still needs independent exact resource/persisted-state proof.
 */
final class RuntimeInstallationBarrier {
    private enum Mode { LEGACY, PREPARING, IRREVERSIBLE, INSTALLED, FAILED }
    private final RuntimeMutex mutex;
    private final Set<LegacyWork> work = new HashSet<>();
    private Mode mode = Mode.LEGACY;
    private Installation installation;
    RuntimeInstallationBarrier(RuntimeMutex mutex) { this.mutex = Objects.requireNonNull(mutex); }

    /** Main-safe short metadata operation, always BEFORE the legacy action/dispatch. */
    synchronized LegacyWork legacyWork() {
        LegacyWork ticket = tryLegacyWork();
        if (ticket == null) throw new SecurityException("Legacy admission is closed");
        return ticket;
    }

    /** OS deliveries may decline a sealed route without racing a separate open-state check. */
    synchronized LegacyWork tryLegacyWork() {
        if (mode != Mode.LEGACY) return null;
        LegacyWork ticket = new LegacyWork(); work.add(ticket); return ticket;
    }
    /**
     * Hold the shared runtime lock, seal first, then inspect actual quiescence. No callback is
     * accepted as proof here. If inspection fails, abort BEFORE provisioning; do not clear state.
     */
    Installation prepareInstallation() {
        mutex.requireHeld();
        synchronized (this) {
            if (mode != Mode.LEGACY || !work.isEmpty()) return null;
            mode = Mode.PREPARING; installation = new Installation(); return installation;
        }
    }
    synchronized boolean legacyAdmissionOpen() { return mode == Mode.LEGACY; }
    synchronized int outstandingLegacyWork() { return work.size(); }

    final class LegacyWork implements AutoCloseable {
        private boolean returned;
        private LegacyWork() { }
        /** Transfer child ownership BEFORE returning the parent; no uncounted async gap. */
        LegacyWork fork() {
            synchronized (RuntimeInstallationBarrier.this) {
                if (returned || mode != Mode.LEGACY || !work.contains(this))
                    throw new SecurityException("Legacy work has retired");
                return legacyWork();
            }
        }
        /** Only actual return, or a proven never-dispatched action. An executor throw is not proof. */
        @Override public void close() {
            synchronized (RuntimeInstallationBarrier.this) {
                if (!returned) { returned = true; work.remove(this); }
            }
        }
    }
    final class Installation {
        private Installation() { }
        /** No durable/platform work occurred; a failed quiescence observation may preserve legacy. */
        void abortBeforeProvisioning() {
            mutex.requireHeld();
            synchronized (RuntimeInstallationBarrier.this) {
                require(Mode.PREPARING); mode = Mode.LEGACY; installation = null;
            }
        }
        /** Call before the first write/OS transition. Failure thereafter never restores legacy. */
        void beginProvisioning() {
            mutex.requireHeld();
            synchronized (RuntimeInstallationBarrier.this) { require(Mode.PREPARING); mode = Mode.IRREVERSIBLE; }
        }
        /** Only after real acknowledged provisioning/open/reconciliation and full adapter readiness. */
        void installed() {
            mutex.requireHeld();
            synchronized (RuntimeInstallationBarrier.this) { require(Mode.IRREVERSIBLE); mode = Mode.INSTALLED; }
        }
        void failed() {
            synchronized (RuntimeInstallationBarrier.this) {
                if (installation != this || mode == Mode.LEGACY || mode == Mode.INSTALLED)
                    throw new SecurityException("Stale installation failure");
                mode = Mode.FAILED;
            }
        }
        private void require(Mode expected) {
            if (installation != this || mode != expected || !work.isEmpty())
                throw new SecurityException("Installation barrier changed");
        }
    }
}
