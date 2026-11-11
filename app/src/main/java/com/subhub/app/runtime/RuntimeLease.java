package com.subhub.app.runtime;

import java.util.Objects;

/** Current-process capability. Reading its persisted Identity can never recreate this object. */
public final class RuntimeLease {
    public final Identity identity;
    RuntimeLease(Identity identity) { this.identity = Objects.requireNonNull(identity); }

    /** Durable evidence only; not executable start, stop, permission or effect authority. */
    public static final class Identity {
        public final RuntimeTarget target;
        public final RuntimeOrigin origin;
        public final String profileGeneration, session, activation, process;
        public final long initialConfigurationRevision;
        public Identity(RuntimeTarget target, RuntimeOrigin origin, String profileGeneration,
                String session, String activation, String process, long initialConfigurationRevision) {
            this.target = Objects.requireNonNull(target); this.origin = Objects.requireNonNull(origin);
            if (target.kind == RuntimeTarget.Kind.INTERNAL && origin.kind == RuntimeOrigin.Kind.REMOTE)
                throw new IllegalArgumentException("Remote control cannot own an internal Sub");
            this.profileGeneration = RuntimeOrigin.key(profileGeneration);
            this.session = RuntimeOrigin.uuid(session); this.activation = RuntimeOrigin.uuid(activation);
            this.process = RuntimeOrigin.uuid(process);
            if (initialConfigurationRevision < 0) throw new IllegalArgumentException("Invalid configuration revision");
            this.initialConfigurationRevision = initialConfigurationRevision;
        }
        @Override public boolean equals(Object other) {
            if (!(other instanceof Identity)) return false;
            Identity value = (Identity) other;
            return target.equals(value.target) && origin.equals(value.origin)
                    && profileGeneration.equals(value.profileGeneration) && session.equals(value.session)
                    && activation.equals(value.activation) && process.equals(value.process)
                    && initialConfigurationRevision == value.initialConfigurationRevision;
        }
        @Override public int hashCode() { return Objects.hash(target, origin, profileGeneration,
                session, activation, process, initialConfigurationRevision); }
    }
}
