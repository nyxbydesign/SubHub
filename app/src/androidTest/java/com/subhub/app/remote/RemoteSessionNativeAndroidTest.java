package com.subhub.app.remote;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.appmode.AppModeManager;
import com.subhub.app.appmode.RuntimeSessionIdentity;
import com.subhub.app.people.LocalSessionControls;
import com.subhub.app.people.PersonId;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.settings.SettingsRepository;
import com.subhub.app.stats.RemoteSessionStatsFixture;
import com.subhub.app.stats.StatsRepository;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Real main/statistics persistence and local transitions; no projection or Accessibility permission. */
@RunWith(AndroidJUnit4.class)
public class RemoteSessionNativeAndroidTest {
    private Context target, context;
    private String prefix;
    private RemoteSessionNative nativeSession;
    private StatsRepository stats;
    private boolean wasDom;
    private final AtomicBoolean refuseStats = new AtomicBoolean(), refuseMain = new AtomicBoolean(), uncertainMain = new AtomicBoolean();
    @Before public void setup() {
        target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        prefix = "native-session-marker-" + UUID.randomUUID() + "-";
        context = new ContextWrapper(target) {
            @Override public Context getApplicationContext() { return this; }
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                SharedPreferences saved = target.getSharedPreferences(prefix + name, mode);
                return (SharedPreferences) Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(),
                        new Class<?>[]{SharedPreferences.class}, (proxy, method, args) -> {
                    if (method.getName().equals("edit")) return editor(saved.edit(), name);
                    try { return method.invoke(saved, args); } catch (InvocationTargetException failed) { throw failed.getCause(); }
                });
            }
        };
        stats = RemoteSessionStatsFixture.create(context);
        nativeSession = RemoteSessionNative.installFixture(context, () -> stats);
        wasDom = ControllerPinManager.isDomModeActive(); ControllerPinManager.enterDomMode();
        RemoteSessionClock.observe(context);
    }
    private SharedPreferences.Editor editor(SharedPreferences.Editor saved, String name) {
        return (SharedPreferences.Editor) Proxy.newProxyInstance(SharedPreferences.Editor.class.getClassLoader(),
                new Class<?>[]{SharedPreferences.Editor.class}, (proxy, method, args) -> {
            boolean commit = method.getName().equals("commit");
            if (commit && (StatsRepository.PREFS_NAME.equals(name) && refuseStats.get()
                    || SettingsRepository.PREFERENCES_NAME.equals(name) && refuseMain.get())) return false;
            Object value;
            try { value = method.invoke(saved, args); } catch (InvocationTargetException failed) { throw failed.getCause(); }
            if (commit && SettingsRepository.PREFERENCES_NAME.equals(name) && uncertainMain.getAndSet(false)) return false;
            return value == saved ? proxy : value;
        });
    }
    @After public void cleanup() {
        refuseStats.set(false); refuseMain.set(false); uncertainMain.set(false); nativeSession.close();
        for (String name : new String[]{SettingsRepository.PREFERENCES_NAME, StatsRepository.PREFS_NAME,
                com.subhub.app.penance.PenanceManager.PREFS_NAME}) target.getSharedPreferences(prefix + name, 0).edit().clear().commit();
        stats.sessionStamp();
        if (wasDom) ControllerPinManager.enterDomMode(); else ControllerPinManager.enterSubMode();
    }
    private SharedPreferences main() { return context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME, 0); }
    private int count() { return context.getSharedPreferences(StatsRepository.PREFS_NAME, 0).getInt("sessions_count", 0); }
    private RemoteSessionNative.PreparedStart prepare(boolean random) throws Exception {
        RemoteSessionClock.Sample now = RemoteSessionClock.observe(context);
        RemoteSessionCommands.Request request = RemoteSessionCommands.Request.start(UUID.randomUUID().toString(),
                String.join("", Collections.nCopies(64, "a")), String.join("", Collections.nCopies(64, "b")),
                "native_generation", 1, 0, now.wall, now.wall + 120000,
                RuntimeSessionIdentity.observe(context, now).idleFence,
                new RemoteSessionCommands.Duration(random ? -2 : -1, 1800000, 3600000, random));
        return RemoteSessionNative.prepare(request, now);
    }
    private boolean apply(RemoteSessionNative.PreparedStart prepared) throws Exception {
        return nativeSession.commitPreparedStart(prepared, RemoteSessionClock.observe(context));
    }
    private void endLocally() { assertTrue(LocalSessionControls.end(context, PersonId.LOCAL)); }

    @Test public void chosenDurationAndIdentitiesSurviveSerializationAndReplay() throws Exception {
        RemoteSessionNative.PreparedStart prepared = prepare(true);
        RemoteSessionNative.PreparedStart decoded = RemoteSessionNative.PreparedStart.parse(prepared.json());
        assertEquals(prepared.selectedDuration, decoded.selectedDuration);
        assertEquals(prepared.statistics.identity, decoded.statistics.identity);
        assertEquals(prepared.pactIdentity, decoded.pactIdentity);
        assertTrue(apply(decoded)); assertTrue(apply(prepared));
        assertEquals(1, count()); assertEquals(prepared.selectedDuration, main().getLong("commitment_duration", 0));
        assertEquals(prepared.pactIdentity, main().getString("commitment_session_identity", ""));
        assertEquals(prepared.statistics.identity, stats.activeSessionIdentity());
        endLocally(); assertFalse(apply(prepared)); assertFalse(new AppModeManager(context).isArmed());
        assertEquals(1, stats.getSessionHistory().size());
    }
    @Test public void mainCommittedStatisticsPendingThenLocalEndCreatesOnlyClosedHistory() throws Exception {
        RemoteSessionNative.PreparedStart prepared = prepare(false);
        refuseStats.set(true); assertFalse(apply(prepared));
        assertTrue(main().getBoolean(AppModeManager.KEY_ARMED, false)); assertEquals(0, count());
        endLocally(); assertFalse(main().getBoolean(AppModeManager.KEY_ARMED, false));
        assertTrue(nativeSession.marker().ended); assertEquals("", stats.activeSessionIdentity());
        refuseStats.set(false); assertTrue(RemoteSessionNative.reconcile(context));
        assertEquals(1, count()); assertEquals(1, stats.getSessionHistory().size());
        assertEquals("", stats.activeSessionIdentity()); assertFalse(apply(prepared));
    }
    @Test public void failedStatisticsMustBeReconciledBeforeAReplacementLocalStart() throws Exception {
        RemoteSessionNative.PreparedStart prepared = prepare(false);
        refuseStats.set(true); assertFalse(apply(prepared)); endLocally();
        new AppModeManager(context).setArmed(true); assertFalse(main().getBoolean(AppModeManager.KEY_ARMED, false));
        refuseStats.set(false); new AppModeManager(context).setArmed(true);
        String replacement = stats.activeSessionIdentity(); assertFalse(replacement.isEmpty());
        assertNotEquals(prepared.statistics.identity, replacement); assertEquals(2, count());
        assertFalse(apply(prepared)); assertTrue(RemoteSessionNative.reconcile(context));
        assertEquals(replacement, stats.activeSessionIdentity()); assertTrue(new AppModeManager(context).isArmed());
        endLocally(); assertEquals(2, stats.getSessionHistory().size());
    }
    @Test public void reopeningBookkeepingNeverRearmsAnEndedSession() throws Exception {
        RemoteSessionNative.PreparedStart prepared = prepare(false);
        assertTrue(apply(prepared)); refuseStats.set(true); endLocally();
        nativeSession.close(); stats = RemoteSessionStatsFixture.create(context);
        nativeSession = RemoteSessionNative.installFixture(context, () -> stats);
        refuseStats.set(false); assertTrue(RemoteSessionNative.reconcile(context));
        assertFalse(new AppModeManager(context).isArmed()); assertFalse(apply(prepared));
        assertEquals(1, count()); assertEquals(1, stats.getSessionHistory().size());
    }
    @Test public void oldProcessPreparationWithoutMainMarkerCannotStart() throws Exception {
        JSONObject saved = prepare(false).json().put("process", UUID.randomUUID().toString());
        assertFalse(apply(RemoteSessionNative.PreparedStart.parse(saved)));
        assertFalse(main().getBoolean(AppModeManager.KEY_ARMED, false)); assertEquals(0, count());
    }
    @Test public void localTransitionInvalidatesPreviouslyObservedIdleFence() throws Exception {
        RemoteSessionNative.PreparedStart prepared = prepare(false);
        new AppModeManager(context).setArmed(true); endLocally();
        assertFalse(apply(prepared)); assertEquals(1, count());
        assertFalse(main().getBoolean(AppModeManager.KEY_ARMED, false));
    }
    @Test public void mainCommitRefusalDoesNotCreateStatisticsOrAnApplication() throws Exception {
        RemoteSessionNative.PreparedStart prepared = prepare(false);
        refuseMain.set(true); assertFalse(apply(prepared)); refuseMain.set(false);
        assertNull(nativeSession.marker()); assertEquals(0, count());
        // A failed application invalidates the observed idle fence, even before a successful main write.
        assertFalse(apply(prepared));
    }
    @Test public void unacknowledgedMainWriteReconcilesExactlyOnceWithoutFreshArming() throws Exception {
        RemoteSessionNative.PreparedStart prepared = prepare(false);
        uncertainMain.set(true); assertFalse(apply(prepared)); assertEquals(0, count());
        assertTrue(apply(prepared)); assertEquals(1, count()); endLocally();
        assertFalse(apply(prepared)); assertEquals(1, stats.getSessionHistory().size());
    }
    @Test public void changedPreparationForTheSameCommandIsRejected() throws Exception {
        RemoteSessionNative.PreparedStart prepared = prepare(true); assertTrue(apply(prepared));
        JSONObject changed = prepared.json().put("statistics", UUID.randomUUID().toString());
        try { apply(RemoteSessionNative.PreparedStart.parse(changed)); fail("Reused command identity accepted"); }
        catch (SecurityException expected) { }
        assertEquals(1, count()); assertEquals(prepared.statistics.identity, stats.activeSessionIdentity()); endLocally();
    }
    @Test public void corruptMarkerDeniesNewStartsWhileAllowingExplicitLocalEnd() throws Exception {
        main().edit().putBoolean(AppModeManager.KEY_ARMED, true).putString(RemoteSessionNative.MAIN_KEY, "{").commit();
        assertFalse(RemoteSessionNative.beforeLocalStart(context)); endLocally();
        assertFalse(main().getBoolean(AppModeManager.KEY_ARMED, false));
        new AppModeManager(context).setArmed(true); assertFalse(main().getBoolean(AppModeManager.KEY_ARMED, false));
        assertEquals(0, count());
    }
}
