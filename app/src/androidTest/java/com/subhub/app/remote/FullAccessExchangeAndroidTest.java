package com.subhub.app.remote;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import android.content.Context;
import android.content.ContextWrapper;
import android.os.SystemClock;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@RunWith(AndroidJUnit4.class)
public final class FullAccessExchangeAndroidTest {
    @Test public void deniedScreenReturnsOnlyStatusOverAuthenticatedTransport() throws Exception {
        openScreen(false, "disabled");
    }

    @Test public void authorizedHandshakeDoesNotStartScreenConsentBeforeFrameRequest() throws Exception {
        openScreen(true, "ready");
    }

    @Test public void authenticatedScreenRequestReportsBlockedDeliveryBeforeAnyFrame() throws Exception {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Context withoutAlerts = new ContextWrapper(target) {
            @Override public Context getApplicationContext() { return this; }
            @Override public Object getSystemService(String name) {
                return NOTIFICATION_SERVICE.equals(name) ? null : super.getSystemService(name);
            }
        };
        ExecutorService server = Executors.newSingleThreadExecutor();
        try (PeerTransport dom = IrohPeerTransport.open(target, seed(), new String[0], 10_000);
                PeerTransport sub = IrohPeerTransport.open(target, seed(), new String[0], 10_000)) {
            Future<?> served = server.submit(() -> {
                try (PeerTransport.Connection connection = sub.accept(10_000)) {
                    assertEquals(dom.localIdentity(), connection.authenticatedPeerIdentity());
                    FullAccessExchange.serve(withoutAlerts, connection, "screen", () -> true, () -> false, true,
                            source -> { });
                } catch (IOException closedByViewer) {
                    // The fixture closes the authenticated request after observing the blocking state.
                } catch (Exception invalid) {
                    throw new AssertionError(invalid);
                }
            });
            try (PeerTransport.Connection connection = dom.connect(sub.localIdentity(), sub.inviteRoute(), 10_000)) {
                connection.setReadTimeoutMillis(8_000);
                JSONObject opened = RemoteProtocol.read(connection.input());
                FullAccessChannel.require(opened, FullAccessChannel.OPENED);
                try (FullAccessChannel viewer = new FullAccessChannel(connection, "screen", opened, () -> true, () -> { })) {
                    long deadline = SystemClock.elapsedRealtime() + 5_000;
                    String status;
                    do {
                        JSONObject frame = viewer.query(new JSONObject().put("op", "frame"));
                        assertFalse(frame.has("image"));
                        status = frame.getString("status");
                        if (!"requesting_consent".equals(status)) break;
                        SystemClock.sleep(25);
                    } while (SystemClock.elapsedRealtime() < deadline);
                    assertEquals("screen_alerts_required", status);
                }
            }
            served.get(10, TimeUnit.SECONDS);
        } finally {
            server.shutdownNow();
            server.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private void openScreen(boolean granted, String expectedStatus) throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ExecutorService server = Executors.newSingleThreadExecutor();
        AtomicBoolean openedSource = new AtomicBoolean();
        try (PeerTransport dom = IrohPeerTransport.open(context, seed(), new String[0], 10_000);
                PeerTransport sub = IrohPeerTransport.open(context, seed(), new String[0], 10_000)) {
            Future<?> served = server.submit(() -> {
                try (PeerTransport.Connection connection = sub.accept(10_000)) {
                    assertEquals(dom.localIdentity(), connection.authenticatedPeerIdentity());
                    connection.setReadTimeoutMillis(8_000);
                    FullAccessExchange.serve(context, connection, "screen", () -> granted, () -> false, false,
                            source -> openedSource.set(true));
                } catch (IOException closedByViewer) {
                    // The authenticated viewer closes without requesting a frame.
                } catch (Exception invalid) {
                    throw new AssertionError(invalid);
                }
            });
            try (PeerTransport.Connection connection = dom.connect(sub.localIdentity(), sub.inviteRoute(), 10_000)) {
                assertEquals(sub.localIdentity(), connection.authenticatedPeerIdentity());
                connection.setReadTimeoutMillis(8_000);
                JSONObject opened = RemoteProtocol.read(connection.input());
                FullAccessChannel.require(opened, FullAccessChannel.OPENED);
                assertEquals(expectedStatus, opened.getString("status"));
                assertFalse(opened.has("image"));
            }
            served.get(10, TimeUnit.SECONDS);
            assertFalse(openedSource.get());
        } finally {
            server.shutdownNow();
            server.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private static byte[] seed() {
        byte[] seed = new byte[32];
        new SecureRandom().nextBytes(seed);
        return seed;
    }
}
