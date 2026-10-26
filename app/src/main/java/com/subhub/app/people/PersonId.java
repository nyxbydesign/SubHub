package com.subhub.app.people;

import java.util.Objects;

/** Stable selection identity, independent of a display name or transport address. */
public final class PersonId {
    public static final PersonId LOCAL = new PersonId("local");
    private final String value;

    public PersonId(String value) {
        if (value == null || value.trim().isEmpty() || value.length() > 256) {
            throw new IllegalArgumentException("Missing person identity");
        }
        this.value = value;
    }

    public String value() { return value; }
    public boolean isLocal() { return equals(LOCAL); }
    @Override public boolean equals(Object other) {
        return other instanceof PersonId && value.equals(((PersonId) other).value);
    }
    @Override public int hashCode() { return Objects.hash(value); }
    @Override public String toString() { return value; }
}
