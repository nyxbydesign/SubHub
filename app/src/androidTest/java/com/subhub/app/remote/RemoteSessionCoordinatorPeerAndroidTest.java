package com.subhub.app.remote;

import static org.junit.Assert.*;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import com.subhub.app.appmode.AppModeManager;
import com.subhub.app.people.People;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.security.DeviceRolePreferences;
import com.subhub.app.security.KeyholderProvider;
import com.subhub.app.settings.SettingsRepository;
import com.subhub.app.stats.StatsRepository;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Two actual direct Iroh endpoints; actual local PIN in private prefs, no production hook or permission activation. */
public final class RemoteSessionCoordinatorPeerAndroidTest {
    private RemoteSessionControlPeerAndroidTest fixture;
    private Context target, domContext, subContext;
    private RemoteStore dom, sub;
    private PeerTransport domTransport, subTransport;
    private String prefix, generation;
    private KeyholderProvider originalProvider;
    private boolean originalMode;
    private Field applicationField;
    private Object originalApplication;
    @Before public void setup() throws Exception {
        originalProvider = People.keyholder(); originalMode = ControllerPinManager.isDomModeActive();
        applicationField = ControllerPinManager.class.getDeclaredField("application"); applicationField.setAccessible(true); originalApplication = applicationField.get(null);
        fixture = new RemoteSessionControlPeerAndroidTest(); fixture.setup(); target = (Context) field(fixture, "target");
        dom = (RemoteStore) field(fixture, "dom"); sub = (RemoteStore) field(fixture, "sub"); subContext = (Context) field(fixture, "subContext");
        domTransport = (PeerTransport) field(fixture, "dt"); subTransport = (PeerTransport) field(fixture, "st"); generation = (String) field(fixture, "generation");
        prefix = "session-coordinator-peer-" + UUID.randomUUID() + "-";
        domContext = new ContextWrapper(target) {
            @Override public Context getApplicationContext() { return this; }
            @Override public SharedPreferences getSharedPreferences(String name, int mode) { return target.getSharedPreferences(prefix + name, mode); }
        };
        RemoteKeyholder keyholder = new RemoteKeyholder(domContext, dom, new RemoteSecrets(domContext, "secrets", prefix + "wrap"));
        People.installKeyholder(keyholder); domContext.getSharedPreferences(SettingsRepository.PREFERENCES_NAME, 0).edit()
                .putString(DeviceRolePreferences.KEY_MODE, DeviceRolePreferences.Mode.DOM_ONLY.name()).commit();
        assertTrue(ControllerPinManager.setPin(domContext, "7351"));
        RemoteSessionControlCoordinator receiver = new RemoteSessionControlCoordinator(subContext, sub, subTransport.localIdentity());
        set(fixture, "subWire", new RemoteSessionControlPeer(subContext, sub, subTransport.localIdentity(), receiver::receiverState));
    }
    @After public void cleanup() throws Exception {
        try {
            if (originalProvider != null) People.installKeyholder(originalProvider);
            if (originalMode) ControllerPinManager.enterDomMode(); else ControllerPinManager.enterSubMode();
            if (applicationField != null) applicationField.set(null, originalApplication);
        }
        finally {
            try { if (fixture != null) fixture.cleanup(); }
            finally {
                if (prefix != null) for (String name : new String[]{SettingsRepository.PREFERENCES_NAME, "subhub_controller_auth", "secrets"})
                    target.getSharedPreferences(prefix + name, 0).edit().clear().commit();
            }
        }
    }
    private static Object field(Object object, String name) throws Exception { Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(object); }
    private static void set(Object object, String name, Object value) throws Exception { Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); field.set(object, value); }
    private void exchange() throws Exception {
        Method method = RemoteSessionControlPeerAndroidTest.class.getDeclaredMethod("exchange", UnaryOperator.class, UnaryOperator.class); method.setAccessible(true);
        Object result;
        try { result = method.invoke(fixture, null, null); }
        catch (InvocationTargetException failure) { throw new AssertionError(failure.getCause()); }
        assertNull(String.valueOf(field(result, "client")), field(result, "client")); assertNull(String.valueOf(field(result, "server")), field(result, "server"));
        assertTrue((Boolean) field(result, "directClient")); assertTrue((Boolean) field(result, "directServer"));
    }
    @Test public void actualAdviceReviewConfirmAndStopProofComposeOverAuthenticatedDirectIroh() throws Exception {
        new AppModeManager(subContext).setArmed(true); StatsRepository stats = (StatsRepository) field(fixture, "stats");
        exchange(); RemoteStore.Peer peer = dom.peer(subTransport.localIdentity(), generation);
        RemoteSessionControlCoordinator coordinator = new RemoteSessionControlCoordinator(domContext, dom, domTransport.localIdentity());
        RemoteSessionRuntime.Snapshot clicked = coordinator.snapshot(peer); assertTrue(clicked.supported); assertTrue(clicked.canStop); assertFalse(clicked.canStart);
        RemoteSessionRuntime.Review review = coordinator.review(peer, RemoteSessionCommands.Action.STOP); assertEquals(clicked.sourceWitness, review.sourceWitness);
        assertEquals(review.request.id, coordinator.confirm(review));
        assertEquals(RemoteSessionCommands.State.QUEUED, coordinator.snapshot(peer).latestResult.state);
        exchange(); RemoteSessionRuntime.Snapshot applied = coordinator.snapshot(peer);
        assertEquals(review.request.id, applied.latestResult.id); assertEquals(RemoteSessionCommands.State.APPLIED, applied.latestResult.state);
        assertNotNull(applied.latestResult.proof); assertFalse(applied.latestResult.proof.active); assertFalse(applied.canStop);
        assertFalse(new AppModeManager(subContext).isArmed()); assertEquals(1, stats.getSessionHistory().size());
        assertEquals(review.request.id, coordinator.confirm(review)); exchange(); assertEquals(1, stats.getSessionHistory().size());
        assertTrue(RemoteSessionRuntime.applicationEnabled());
    }
}
