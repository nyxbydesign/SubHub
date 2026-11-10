package com.subhub.app.remote;

import static org.junit.Assert.*;
import android.content.Context;
import java.lang.reflect.Field;
import java.util.UUID;

/** An unpaired UI review reads an owned store while the application's endpoint remains intact. */
public final class RemoteUnpairedUiFixture implements AutoCloseable {
    private final Context context;
    private final RemoteFeature original, fixture;
    private final String database = "unpaired-ui-" + UUID.randomUUID() + ".db";
    private final Field singleton;

    public RemoteUnpairedUiFixture(Context context) throws Exception {
        this.context = context;
        original = RemoteFeature.get(context);
        fixture = new RemoteFeature(context, new RemoteStore(context, database));
        assertTrue("Unpaired fixture requires completed configuration recovery", fixture.recoveryReady());
        assertTrue(fixture.store.peers(false).isEmpty());
        singleton = RemoteFeature.class.getDeclaredField("instance"); singleton.setAccessible(true);
        synchronized (RemoteFeature.class) {
            assertSame("Preserve the application endpoint", original, singleton.get(null));
            singleton.set(null, fixture);
        }
    }

    @Override public void close() throws Exception {
        synchronized (RemoteFeature.class) {
            assertSame("Restore only this fixture's replacement", fixture, singleton.get(null));
            singleton.set(null, original);
        }
        fixture.shutdownForTest();
        assertTrue(context.deleteDatabase(database));
        assertSame(original, RemoteFeature.get(context));
        Field executor = RemoteFeature.class.getDeclaredField("sessions"); executor.setAccessible(true);
        assertFalse("The application endpoint remains live",
                ((java.util.concurrent.ThreadPoolExecutor) executor.get(original)).isShutdown());
    }
}
