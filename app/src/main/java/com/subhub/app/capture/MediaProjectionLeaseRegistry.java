package com.subhub.app.capture;

/** Process-local guard preventing two SubHub services from consuming MediaProjection at once. */
public final class MediaProjectionLeaseRegistry {
    public enum Owner { PROTECTION, CENSOR_LAB }

    private static Owner owner;

    private MediaProjectionLeaseRegistry() {}

    public static synchronized boolean acquire(Owner requested) {
        if (requested == null || owner != null) return false;
        owner = requested;
        return true;
    }

    public static synchronized void release(Owner releasing) {
        if (owner == releasing) owner = null;
    }

    public static synchronized Owner owner() { return owner; }
}
