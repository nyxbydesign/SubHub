package com.subhub.app.runtime;

import java.util.Objects;

/** Physical ownership only. Outgoing peers are deliberately not possible runtime targets. */
public final class RuntimeTarget {
    public enum Kind { DEVICE, INTERNAL }
    public static final RuntimeTarget DEVICE = new RuntimeTarget(Kind.DEVICE, "local");
    public final Kind kind;
    public final String id;

    private RuntimeTarget(Kind kind, String id) { this.kind = kind; this.id = id; }

    public static RuntimeTarget internal(String id) {
        if (id == null || !id.matches("[A-Za-z0-9_-]{1,128}") || "local".equals(id) || id.startsWith("remote_"))
            throw new IllegalArgumentException("Invalid internal identity");
        return new RuntimeTarget(Kind.INTERNAL, id);
    }

    @Override public boolean equals(Object other) {
        return other instanceof RuntimeTarget && kind == ((RuntimeTarget) other).kind
                && id.equals(((RuntimeTarget) other).id);
    }
    @Override public int hashCode() { return Objects.hash(kind, id); }
}
