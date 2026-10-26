package com.subhub.app.people;

import android.content.Context;

/** App integration point for a transport-owned remote repository. */
public final class People {
    private static volatile PersonRepository remote;
    private static volatile java.util.function.BooleanSupplier remoteAuthority = () -> false;
    private static volatile ConnectionUi connectionUi;
    private People() {}

    public static PersonRepository repository(Context context) {
        return new PersonDirectory(new LocalPersonRepository(context.getApplicationContext()), remote);
    }

    public static void installRemoteRepository(PersonRepository repository) { remote = repository; }
    public static void installConnectionUi(ConnectionUi ui) { connectionUi = ui; }
    public static ConnectionUi connectionUi() { return connectionUi; }

    /** The pairing owner supplies durable authority; UI mode never grants remote permission. */
    public static void installRemoteAuthority(java.util.function.BooleanSupplier authority) {
        remoteAuthority = java.util.Objects.requireNonNull(authority);
    }

    public static boolean isLocalConfigurationAllowed() {
        try { return !remoteAuthority.getAsBoolean(); }
        catch (RuntimeException unavailable) { return false; }
    }
}
