package com.subhub.app.runtime;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * One process-local RESERVED start, including its permission prompts. Not reconstructible from an
 * Intent, persisted identity, role or profile ID. This class never starts enforcement or writes the
 * owner journal. The gateway checks coordinator freshness on its worker before accepting results.
 */
public final class RuntimeStartAttempt {
    public enum Permission { NOTIFICATIONS, ACCESSIBILITY, OVERLAY, PROJECTION }
    private final RuntimeLease lease;
    private final OwnedResourceRegistry registry;
    private final Runnable changed;
    private final Supplier<OwnedResourceRegistry.PermissionObligation> permissionIssuer;
    private final Object lock = new Object();
    private final Set<Prompt> prompts = new HashSet<>();
    private final AtomicBoolean sealed = new AtomicBoolean();
    private boolean transferred;

    RuntimeStartAttempt(RuntimeLease lease, OwnedResourceRegistry registry,
            Supplier<OwnedResourceRegistry.PermissionObligation> permissionIssuer, Runnable changed) {
        this.lease = Objects.requireNonNull(lease); this.registry = Objects.requireNonNull(registry);
        this.permissionIssuer = Objects.requireNonNull(permissionIssuer);
        this.changed = Objects.requireNonNull(changed);
    }
    public RuntimeLease.Identity identity() { return lease.identity; }
    public boolean revoked() { return sealed.get(); }
    RuntimeLease lease() { return lease; }

    /** Gateway worker only, after exact coordinator acceptsPermissionResult validation. */
    Prompt issue(Permission permission) {
        Objects.requireNonNull(permission);
        synchronized (lock) { requireIssueOpen(); }
        // Issuance can acquire RuntimeMutex; NEVER hold the UI metadata lock across journal IO.
        OwnedResourceRegistry.PermissionObligation obligation = permissionIssuer.get();
        synchronized (lock) {
            try { requireIssueOpen(); }
            catch (RuntimeException closed) { obligation.cancelBeforeLaunch(); throw closed; }
            Prompt prompt = new Prompt(permission, obligation); prompts.add(prompt); return prompt;
        }
    }
    private void requireIssueOpen() {
        if (sealed.get() || transferred || !prompts.isEmpty())
            throw new SecurityException("Permission attempt is closed or already has a prompt");
    }
    /** Called only after beginStarting ACK, before handing the exact lease to runtime construction. */
    boolean transferToStarting() {
        synchronized (lock) {
            if (sealed.get() || transferred || !prompts.isEmpty()) return false;
            transferred = true; return true;
        }
    }
    boolean promptsReturned() { synchronized (lock) { return prompts.isEmpty(); } }
    boolean transferred() { synchronized (lock) { return transferred; } }

    /** Immediate no-IO fence. A queued prompt becomes inert; an admitted prompt remains owned. */
    boolean revoke() {
        synchronized (lock) {
            // An old permission host cannot stop the session after ownership was transferred.
            if (transferred) return false;
            sealed.set(true);
            prompts.removeIf(prompt -> {
                if (prompt.started) return false;
                prompt.terminal = true; prompt.obligation.cancelBeforeLaunch(); return true;
            });
        }
        registry.fence(lease);
        changed.run(); // Gateway schedules journal/reconciliation work; never performs it inline.
        return true;
    }

    /** Failed dispatcher publication cannot hand a STARTING capability to a consumer. No journal IO. */
    void fenceUnreported() {
        synchronized (lock) {
            sealed.set(true);
            prompts.removeIf(prompt -> {
                if (prompt.started) return false;
                prompt.terminal = true; prompt.obligation.cancelBeforeLaunch(); return true;
            });
        }
        registry.fence(lease);
    }

    public final class Prompt {
        public final Permission permission;
        private boolean started, launchReturned, resultReturned, terminal;
        private final OwnedResourceRegistry.PermissionObligation obligation;
        private Prompt(Permission permission, OwnedResourceRegistry.PermissionObligation obligation) {
            this.permission = permission; this.obligation = obligation;
        }
        public RuntimeLease.Identity identity() { return lease.identity; }
        boolean belongsTo(RuntimeStartAttempt attempt) { return RuntimeStartAttempt.this == attempt; }

        /**
         * UI-thread prompt launch, only. The action cannot arm/capture/start services. True means
         * it ran, not that the user granted permission. Exceptions may follow a real launch.
         */
        public boolean launch(Runnable promptAction) {
            Objects.requireNonNull(promptAction);
            final boolean admitted;
            synchronized (lock) {
                if (sealed.get() || transferred || terminal || started || !prompts.contains(this)) return false;
                admitted = obligation.tryLaunch();
                if (admitted) started = true;
                else { terminal = true; prompts.remove(this); obligation.cancelBeforeLaunch(); }
            }
            if (!admitted) { revoke(); return false; }
            boolean returnedNormally = false;
            try { promptAction.run(); returnedNormally = true; return true; }
            finally {
                synchronized (lock) { launchReturned = true; obligation.launchReturned(); retireIfReturned(); }
                if (!returnedNormally) revoke(); else changed.run();
            }
        }

        /**
         * Exact Activity-result callback terminal. A stale/revoked result still releases only its
         * own prompt, but returns false and grants no authority. Call in the callback's finally,
         * after transferring any approved one-use projection data to the gateway worker.
         */
        public boolean resultReturned() {
            final boolean usable;
            synchronized (lock) {
                if (!started || terminal || resultReturned || !prompts.contains(this)) return false;
                resultReturned = true;
                boolean current = obligation.resultReturned();
                usable = current && !sealed.get() && !transferred;
                retireIfReturned();
            }
            if (!usable) revoke(); else changed.run();
            return usable;
        }
        private void retireIfReturned() {
            if (launchReturned && resultReturned) { terminal = true; prompts.remove(this); }
        }
    }
}
