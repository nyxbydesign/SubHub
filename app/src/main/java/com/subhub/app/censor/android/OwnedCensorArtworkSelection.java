package com.subhub.app.censor.android;

import com.subhub.app.runtime.RuntimeLease;
import java.util.Objects;
import java.util.Set;

/** Exact immutable artwork data token; never runtime admission or permission to access a file. */
final class OwnedCensorArtworkSelection {
    static final class Snapshot {
        final long artworkRevision;
        final Set<String> enabled, disabled;
        private Snapshot(long revision, Set<String> enabled, Set<String> disabled) {
            if (revision < 0) throw new IllegalArgumentException("Invalid artwork revision");
            artworkRevision = revision; this.enabled = Set.copyOf(enabled); this.disabled = Set.copyOf(disabled);
        }
        private boolean same(long revision, Set<String> enabled, Set<String> disabled) {
            return artworkRevision == revision && this.enabled.equals(enabled) && this.disabled.equals(disabled);
        }
    }
    private final RuntimeLease.Identity owner;
    private volatile Snapshot selected;
    private long latestConfigurationRevision;

    OwnedCensorArtworkSelection(RuntimeLease.Identity owner, long configurationRevision, long artworkRevision,
            Set<String> enabled, Set<String> disabled) {
        this.owner = Objects.requireNonNull(owner);
        if (configurationRevision < 0) throw new IllegalArgumentException("Invalid configuration revision");
        latestConfigurationRevision = configurationRevision; selected = new Snapshot(artworkRevision, enabled, disabled);
    }
    synchronized void update(RuntimeLease.Identity owner, long configurationRevision, long artworkRevision,
            Set<String> enabled, Set<String> disabled) {
        if (!this.owner.equals(owner) || configurationRevision < latestConfigurationRevision)
            throw new SecurityException("Artwork update belongs to a stale runtime/configuration");
        Snapshot before = selected;
        boolean unchanged = before.same(artworkRevision, enabled, disabled);
        if (!unchanged && configurationRevision == latestConfigurationRevision)
            throw new SecurityException("Conflicting artwork at the same acknowledged configuration revision");
        // Reuse only the artwork token, never an old configuration image or accounting facts.
        if (!unchanged) selected = new Snapshot(artworkRevision, enabled, disabled);
        latestConfigurationRevision = configurationRevision;
    }
    Snapshot snapshot() { return selected; }
    boolean current(Snapshot snapshot) { return selected == snapshot; }
}
