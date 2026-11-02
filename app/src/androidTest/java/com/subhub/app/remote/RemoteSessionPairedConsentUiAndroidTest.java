package com.subhub.app.remote;

import static org.junit.Assert.*;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static com.subhub.app.NativeUiActions.revealAboveNavigation;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.Until;
import com.subhub.app.R;
import com.subhub.app.security.*;
import com.subhub.app.settings.GlobalSettingsActivity;
import com.subhub.app.settings.SettingsRepository;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.*;

/** Paired presentation uses a synthetic endpoint identity; Android admin observations remain real. */
public class RemoteSessionPairedConsentUiAndroidTest {
    @Rule public final KeyholderPresentationAndroidTest.IntegrationFixture integration =
            new KeyholderPresentationAndroidTest.IntegrationFixture();
    @Rule public final com.subhub.app.UiAutomationStateRule automation = new com.subhub.app.UiAutomationStateRule();
    private static final String DOM = repeat('a'), SELF = repeat('b'), GENERATION = "paired_ui_generation";
    private Context context;
    private RemoteFeature original, feature;
    private Field singleton;
    private String database;
    private Map<String, ?> consent;
    private java.util.concurrent.atomic.AtomicInteger endpointLeases;
    private boolean authorityReplaced;
    private Map<String, String> originalAcknowledgements;
    private boolean cleaned;

    @Before public void setup() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        HardcoreModeManager hardcore = new HardcoreModeManager(context);
        assertFalse("This owned guest must have no admin grant", hardcore.isAdminActive());
        assertFalse(new com.subhub.app.appmode.AppModeManager(context).isAccessibilityEnabled());
        assertFalse(hardcore.isRequested());
        consent = new LinkedHashMap<>(context.getSharedPreferences(SubFeaturePolicy.STORE, 0).getAll());
        context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME, 0).edit()
                .putString(DeviceRolePreferences.KEY_MODE, DeviceRolePreferences.Mode.BOTH.name()).commit();
        ControllerPinManager.enterSubMode();
        original = RemoteFeature.get(context);
        database = "paired-consent-ui-" + UUID.randomUUID() + ".db";
        feature = new RemoteFeature(context, new RemoteStore(context, database));
        Field leases = RemoteFeature.class.getDeclaredField("leases"); leases.setAccessible(true);
        endpointLeases = (java.util.concurrent.atomic.AtomicInteger) leases.get(feature);
        assertEquals(1, endpointLeases.incrementAndGet());
        feature.store.getWritableDatabase().execSQL(
                "INSERT INTO peers(peer,generation,name,local_role,state) VALUES(?,?,'Fixture Dom','SUB','ACTIVE')",
                new Object[]{DOM, GENERATION});
        synchronized (SubFeaturePolicy.LOCK) {
            originalAcknowledgements = acknowledgements(original.store);
            assertSame(original.store, registeredAuthority());
            SubFeaturePolicy.unregisterHardcoreAuthority(context, original.store);
            SubFeaturePolicy.registerHardcoreAuthority(context, feature.store);
            authorityReplaced = true;
            assertSame(feature.store, registeredAuthority());
        }
        Field endpoint = RemoteFeature.class.getDeclaredField("endpoint"); endpoint.setAccessible(true);
        endpoint.set(feature, new PeerTransport() {
            public String localIdentity() { return SELF; }
            public String inviteRoute() throws IOException { throw new IOException("UI fixture has no route"); }
            public Connection connect(String peer, String route, long timeout) throws IOException { throw new IOException("UI fixture has no network"); }
            public Connection accept(long timeout) throws IOException { throw new IOException("UI fixture has no network"); }
            public void close() { }
        });
        singleton = RemoteFeature.class.getDeclaredField("instance"); singleton.setAccessible(true);
        synchronized (RemoteFeature.class) {
            assertSame(original, singleton.get(null)); singleton.set(null, feature);
        }
    }

    @After public void cleanup() throws Throwable { cleanupOwned(() -> { }); }

    private void cleanupOwned(Runnable receiptWitness) throws Throwable {
        if (cleaned) return;
        cleaned = true;
        List<Throwable> failures = new ArrayList<>();
        clean(failures, () -> {
            synchronized (SubFeaturePolicy.LOCK) {
                if (consent != null) com.subhub.app.settings.SharedPreferenceTestRestore.restore(
                        context.getSharedPreferences(SubFeaturePolicy.STORE, 0), consent);
            }
        });
        clean(failures, () -> {
            synchronized (SubFeaturePolicy.LOCK) {
                if (authorityReplaced) {
                    Object observed = registeredAuthority();
                    if (observed == feature.store) {
                        SubFeaturePolicy.unregisterHardcoreAuthority(context, feature.store);
                        SubFeaturePolicy.registerHardcoreAuthority(context, original.store);
                    }
                    assertSame(feature.store, observed);
                    assertSame(original.store, registeredAuthority());
                    assertEquals("The fixture must preserve the application's consent receipts",
                            originalAcknowledgements, acknowledgements(original.store));
                    receiptWitness.run();
                }
            }
        });
        clean(failures, () -> {
            if (singleton != null) synchronized (RemoteFeature.class) {
                Object observed = singleton.get(null);
                if (observed == feature) singleton.set(null, original);
                assertSame(feature, observed);
            }
        });
        clean(failures, () -> {
            if (feature != null) {
                try {
                    feature.submit(() -> null).get(15, TimeUnit.SECONDS);
                    if (endpointLeases != null) {
                        long deadline = android.os.SystemClock.elapsedRealtime() + 5000;
                        while (endpointLeases.get() != 1 && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(10);
                        assertEquals(0, endpointLeases.decrementAndGet());
                    }
                } finally { feature.shutdownForTest(); }
            }
        });
        clean(failures, () -> {
            if (database != null) assertTrue(context.deleteDatabase(database));
        });
        clean(failures, () -> {
            if (original != null) {
                assertSame(original, RemoteFeature.get(context));
                Field sessions = RemoteFeature.class.getDeclaredField("sessions"); sessions.setAccessible(true);
                assertFalse(((java.util.concurrent.ThreadPoolExecutor) sessions.get(original)).isShutdown());
            }
        });
        if (!failures.isEmpty()) {
            Throwable first = failures.get(0);
            for (int index = 1; index < failures.size(); index++) first.addSuppressed(failures.get(index));
            throw first;
        }
    }

    private interface CleanupAction { void run() throws Throwable; }
    private static void clean(List<Throwable> failures, CleanupAction action) {
        try { action.run(); } catch (Throwable failure) { failures.add(failure); }
    }

    @Test public void failedReceiptWitnessStillRestoresAndClosesOnlyTheOwnedEndpoint() throws Throwable {
        AssertionError rejected = assertThrows(AssertionError.class, () -> cleanupOwned(() -> {
            throw new AssertionError("Deliberate receipt observer refusal");
        }));
        assertEquals("Deliberate receipt observer refusal", rejected.getMessage());
        assertEquals(0, rejected.getSuppressed().length);
        assertSame(original, RemoteFeature.get(context)); assertSame(original.store, registeredAuthority());
        assertEquals(originalAcknowledgements, acknowledgements(original.store));
        assertEquals(consent, context.getSharedPreferences(SubFeaturePolicy.STORE, 0).getAll());
        assertFalse(context.getDatabasePath(database).exists());
        Field sessions = RemoteFeature.class.getDeclaredField("sessions"); sessions.setAccessible(true);
        assertTrue(((java.util.concurrent.ThreadPoolExecutor) sessions.get(feature)).isTerminated());
        assertFalse(((java.util.concurrent.ThreadPoolExecutor) sessions.get(original)).isShutdown());
    }

    private Object registeredAuthority() throws Exception {
        Field authorities = SubFeaturePolicy.class.getDeclaredField("authorities"); authorities.setAccessible(true);
        return ((Map<?, ?>) authorities.get(null)).get(context.getApplicationContext());
    }
    private static Map<String, String> acknowledgements(RemoteStore store) {
        Map<String, String> values = new TreeMap<>();
        try (android.database.Cursor rows = store.getReadableDatabase().query("remote_metadata",
                new String[]{"peer", "generation", "value"}, "name=?",
                new String[]{"hardcore_start_review_ack"}, null, null, null)) {
            while (rows.moveToNext()) values.put(rows.getString(0) + ":" + rows.getString(1), rows.getString(2));
        }
        return values;
    }

    @Test public void pairedReviewSurvivesRecreationAndDeclinedAndroidAccessGrantsNothing() throws Exception {
        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        try (ActivityScenario<GlobalSettingsActivity> page = ActivityScenario.launch(GlobalSettingsActivity.class)) {
            onView(withId(R.id.switch_hardcore_mode)).perform(revealAboveNavigation(), click());
            String scope = context.getString(R.string.hardcore_remote_start_consent, "Fixture Dom");
            assertTrue(device.wait(Until.hasObject(By.textContains(scope)), 15_000));
            assertFalse(ControllerPinManager.isDomModeActive());
            page.recreate();
            assertTrue(device.wait(Until.hasObject(By.textContains(scope)), 15_000));
            onView(withText(android.R.string.cancel)).perform(click());
            assertNoAuthority();
            RemoteSessionRuntime.HardcoreReview reviewed = feature.reviewHardcoreStart().get(15, TimeUnit.SECONDS);
            assertEquals(DOM, reviewed.dom); assertEquals(SELF, reviewed.recipient); assertEquals(GENERATION, reviewed.generation);
            assertFalse("Real Android admin absence must reject even a current paired review",
                    feature.acceptHardcoreStartReview(reviewed).get(15, TimeUnit.SECONDS));
            assertNoAuthority();
            onView(withId(R.id.switch_hardcore_mode)).perform(revealAboveNavigation(), click());
            assertTrue(device.wait(Until.hasObject(By.textContains(scope)), 15_000));
            onView(withText(R.string.hardcore_consent_enable)).perform(click());
            assertTrue("Continue must enter the actual Android access flow",
                    device.wait(Until.hasObject(By.pkg("com.android.settings")), 8000));
            assertNoAuthority();
            device.pressBack();
            assertTrue(device.wait(Until.hasObject(By.pkg("com.subhub.app")), 8000));
            long deadline = android.os.SystemClock.elapsedRealtime() + 8000;
            while (new HardcoreModeManager(context).isRequested() && android.os.SystemClock.elapsedRealtime() < deadline)
                Thread.sleep(50);
            assertFalse(new HardcoreModeManager(context).isRequested()); assertNoAuthority();
            assertFalse(ControllerPinManager.isDomModeActive());
        }
    }

    private void assertNoAuthority() {
        assertFalse(new HardcoreModeManager(context).isAdminActive());
        assertFalse(SubFeaturePolicy.hardcoreOverride(context));
        assertNull(SubFeaturePolicy.hardcoreStartConsent(context, feature.store, SELF));
        assertEquals("", feature.store.metadata(DOM, GENERATION, "hardcore_start_review_ack", ""));
    }
    private static String repeat(char value) { char[] chars = new char[64]; Arrays.fill(chars, value); return new String(chars); }
}
