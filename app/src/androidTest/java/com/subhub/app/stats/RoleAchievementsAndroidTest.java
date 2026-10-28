package com.subhub.app.stats;

import static org.junit.Assert.*;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.matcher.ViewMatchers.withTagValue;
import static org.hamcrest.Matchers.is;

import android.app.Instrumentation;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.MainActivity;
import com.subhub.app.onboarding.OnboardingState;
import com.subhub.app.people.People;
import com.subhub.app.remote.RemoteProtocol;
import com.subhub.app.remote.RemoteStore;
import com.subhub.app.remote.RemoteTelemetry;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.security.DeviceRolePreferences;
import com.subhub.app.settings.SettingsRepository;
import com.subhub.app.settings.SharedPreferenceTestRestore;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class RoleAchievementsAndroidTest {
    @Rule public final com.subhub.app.security.KeyholderPresentationAndroidTest.IntegrationFixture integration =
            new com.subhub.app.security.KeyholderPresentationAndroidTest.IntegrationFixture();
    @Rule public final com.subhub.app.UiAutomationStateRule automation = new com.subhub.app.UiAutomationStateRule();
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final Context context = instrumentation.getTargetContext();
    private final Map<String, Map<String, ?>> original = new LinkedHashMap<>();
    private final List<RemoteStore> stores = new ArrayList<>();
    private final List<String> names = new ArrayList<>();
    private final List<String> uiPeers = new ArrayList<>();
    private final String generation = UUID.randomUUID().toString();

    @Before public void prepare() {
        for (String name : Arrays.asList("betablocker_achievements", StatsRepository.PREFS_NAME,
                SettingsRepository.PREFERENCES_NAME, "subhub_onboarding", "subhub_penance"))
            original.put(name, new LinkedHashMap<>(context.getSharedPreferences(name, 0).getAll()));
        context.getSharedPreferences("betablocker_achievements", 0).edit().clear().commit();
        context.getSharedPreferences(StatsRepository.PREFS_NAME, 0).edit().clear().commit();
        new SettingsRepository(context).preferences().edit()
                .putString(DeviceRolePreferences.KEY_MODE, DeviceRolePreferences.Mode.BOTH.name())
                .putBoolean(com.subhub.app.appmode.AppModeManager.KEY_ARMED, false).commit();
        People.installRemoteRepository(null);
        People.installRemoteAuthority(() -> false);
        ControllerPinManager.enterDomMode();
        OnboardingState.complete(context);
        assertCaptureIdle();
    }

    @After public void restore() {
        if (!uiPeers.isEmpty()) try (RemoteStore store = new RemoteStore(context)) {
            for (String identity : uiPeers) store.getWritableDatabase().execSQL(
                    "UPDATE peers SET state='REVOKED' WHERE peer=? AND generation=?", new Object[]{identity, generation});
        }
        for (RemoteStore store : stores) store.close();
        for (String name : names) assertTrue(context.deleteDatabase(name));
        for (Map.Entry<String, Map<String, ?>> entry : original.entrySet())
            SharedPreferenceTestRestore.restore(context.getSharedPreferences(entry.getKey(), 0), entry.getValue());
        assertCaptureIdle();
    }

    @Test public void workspaceCatalogsAndPendingNotificationsStaySeparated() {
        SharedPreferences saved = context.getSharedPreferences("betablocker_achievements", 0);
        saved.edit().putStringSet("unlocked", new LinkedHashSet<>(Collections.singletonList("first_session")))
                .putStringSet("pending_notifications", new LinkedHashSet<>(Collections.singletonList("first_session"))).commit();
        AchievementManager dom = AchievementManager.forWorkspace(context);
        assertEquals(17, dom.all().size());
        List<AchievementManager.Achievement> awarded = dom.checkDomAchievements(facts());
        assertFalse(awarded.isEmpty());
        long timestamp = dom.getUnlockedAt("dom_subs_1");
        assertTrue(dom.checkDomAchievements(facts()).isEmpty());
        assertEquals(timestamp, dom.getUnlockedAt("dom_subs_1"));
        for (AchievementManager.Achievement value : dom.takePendingNotifications()) assertTrue(value.getId().startsWith("dom_"));
        assertFalse(dom.isUnlocked("first_session"));
        ControllerPinManager.enterSubMode();
        assertTrue(dom.all().isEmpty());
        assertTrue(dom.takePendingNotifications().isEmpty());
        AchievementManager sub = AchievementManager.forWorkspace(context);
        assertEquals(57, sub.all().size()); assertTrue(sub.isUnlocked("first_session"));
        assertFalse(sub.isUnlocked("dom_subs_1"));
        assertEquals("first_session", sub.takePendingNotifications().get(0).getId());
        ControllerPinManager.enterDomMode();
        assertTrue(AchievementManager.forWorkspace(context).isUnlocked("dom_subs_1"));
    }

    @Test public void backgroundSubMilestonesKeepTheirOwnCatalogDuringDomUse() {
        context.getSharedPreferences(StatsRepository.PREFS_NAME, 0).edit().putLong("total_blocks_all_time", 1).commit();
        AchievementManager events = new AchievementManager(context);
        events.checkAchievements(new StatsRepository(context).load());
        assertTrue(events.isUnlocked("first_block"));
        AchievementManager dom = AchievementManager.forWorkspace(context);
        assertFalse(dom.isUnlocked("first_block"));
        for (AchievementManager.Achievement value : dom.takePendingNotifications()) assertTrue(value.getId().startsWith("dom_"));
        ControllerPinManager.enterSubMode();
        assertTrue(AchievementManager.forWorkspace(context).isUnlocked("first_block"));
    }

    @Test public void repositoryRejectsSubAndLockedDomAccess() {
        ControllerPinManager.enterSubMode();
        assertThrows(SecurityException.class, () -> new DomStatsRepository(context).load());
        new SettingsRepository(context).preferences().edit()
                .putString(DeviceRolePreferences.KEY_MODE, DeviceRolePreferences.Mode.DOM_ONLY.name()).commit();
        assertEquals(AchievementRole.DOM, AchievementRole.current(context));
        assertThrows(SecurityException.class, () -> new DomStatsRepository(context).load());
    }

    @Test public void replayAndNewerSnapshotsDoNotAccumulateTotals() throws Exception {
        RemoteStore dom = store(), sub = store(); String domId = identity('d'), subId = identity('a');
        peer(dom, subId, generation, RemoteProtocol.Role.DOM, RemoteStore.State.ACTIVE);
        peer(sub, domId, generation, RemoteProtocol.Role.SUB, RemoteStore.State.ACTIVE);
        long now = System.currentTimeMillis();
        sub.publish(domId, generation, RemoteProtocol.Stream.STATS, "summary", summary(3600, 2, 1000, 500), false, now);
        RemoteProtocol.Batch first = sub.nextBatch(domId, generation, RemoteProtocol.Stream.STATS);
        dom.receive(subId, first); dom.receive(subId, first);
        mirror(dom, subId, RemoteProtocol.Stream.RUNTIME, "current", runtime(true, true, true));
        confirmed(dom, subId, now);
        DomStatsSnapshot facts = new DomStatsRepository(context).load(dom, now);
        assertEquals(1, facts.subs); assertEquals(3600, facts.sessionSeconds); assertEquals(2, facts.sessions);
        assertEquals(Long.valueOf(1000), facts.paidCents.get("EUR")); assertEquals(1, facts.hardcoreSubs);
        sub.publish(domId, generation, RemoteProtocol.Stream.STATS, "summary", summary(5400, 3, 1500, 500), false, now + 1);
        dom.receive(subId, sub.nextBatch(domId, generation, RemoteProtocol.Stream.STATS));
        dom.receive(subId, first);
        facts = new DomStatsRepository(context).load(dom, now);
        assertEquals(5400, facts.sessionSeconds); assertEquals(3, facts.sessions);
        assertEquals(Long.valueOf(1500), facts.paidCents.get("EUR"));
    }

    @Test public void inactivePendingAndNonSubRelationshipsAreExcluded() throws Exception {
        RemoteStore store = store(); long now = System.currentTimeMillis();
        for (int i = 0; i < 4; i++) {
            String id = identity((char)('a' + i));
            peer(store, id, generation, i == 3 ? RemoteProtocol.Role.SUB : RemoteProtocol.Role.DOM,
                    i == 1 ? RemoteStore.State.PENDING : i == 2 ? RemoteStore.State.REVOKED : RemoteStore.State.ACTIVE);
            mirror(store, id, RemoteProtocol.Stream.STATS, "summary", summary(100, 2, 500, 0));
            mirror(store, id, RemoteProtocol.Stream.RUNTIME, "current", runtime(true, true, true));
            confirmed(store, id, now);
        }
        DomStatsSnapshot facts = new DomStatsRepository(context).load(store, now);
        assertEquals(1, facts.subs); assertEquals(100, facts.sessionSeconds); assertEquals(1, facts.hardcoreSubs);
        store.getWritableDatabase().execSQL("UPDATE peers SET state='REVOKED' WHERE peer=?", new Object[]{identity('a')});
        assertEquals(0, new DomStatsRepository(context).load(store, now).subs);
    }

    @Test public void walletConsentAndFreshHardcoreStatusAreRequired() throws Exception {
        RemoteStore store = store(); String id = identity('a'); long now = System.currentTimeMillis();
        peer(store, id, generation, RemoteProtocol.Role.DOM, RemoteStore.State.ACTIVE);
        mirror(store, id, RemoteProtocol.Stream.STATS, "summary", summary(100, 2, 5000, 6000));
        JSONObject current = runtime(true, true, false);
        mirror(store, id, RemoteProtocol.Stream.RUNTIME, "current", current); confirmed(store, id, now);
        DomStatsSnapshot facts = new DomStatsRepository(context).load(store, now);
        assertFalse(facts.hasWalletTotals()); assertEquals(0, facts.leadingPaidCents()); assertEquals(1, facts.hardcoreSubs);
        meta(store, id, "confirmed_RUNTIME", Long.toString(now - DomStatsSnapshot.RUNTIME_FRESH_MILLIS - 1));
        assertEquals(0, new DomStatsRepository(context).load(store, now).hardcoreKnown);
        current.remove("hardcoreEnabled"); mirror(store, id, RemoteProtocol.Stream.RUNTIME, "current", current);
        confirmed(store, id, now);
        assertEquals(0, new DomStatsRepository(context).load(store, now).hardcoreKnown);
    }

    @Test public void runtimeFieldIsOptionalAndStrictlyBoolean() throws Exception {
        JSONObject value = runtime(true, false, true);
        assertTrue(RemoteTelemetry.validRuntime(value));
        value.remove("hardcoreEnabled"); assertTrue(RemoteTelemetry.validRuntime(value));
        value.put("hardcoreEnabled", "true"); assertFalse(RemoteTelemetry.validRuntime(value));
    }

    @Test @androidx.test.filters.SdkSuppress(minSdkVersion = 29)
    public void nativeDomStatisticsShowCombinedFactsAndNoSubHistory() throws Exception {
        seedUi();
        try (ActivityScenario<StatsActivity> scenario = launch(StatsActivity.class)) {
            awaitTag(scenario, "dom_stats_paired"); capture(scenario, "dom-statistics");
            scenario.onActivity(activity -> {
                assertNotNull(activity.findViewById(android.R.id.content).findViewWithTag("dom_statistics"));
                assertEquals("3", textAt(activity, "dom_stats_paired").trim().split("\\n")[1]);
                assertTrue(textAt(activity, "dom_stats_hardcore").contains("2"));
                assertTrue(textAt(activity, "dom_paid_EUR").contains(com.subhub.app.penance.WalletCurrency.format("EUR", 1250)));
                assertEquals(context.getString(com.subhub.app.R.string.role_dom),
                        com.subhub.app.util.PrimaryHeader.editLockButton(activity.findViewById(android.R.id.content)).getText().toString());
                assertNull(activity.findViewById(com.subhub.app.R.id.profile_stats_current));
                assertFits(activity.findViewById(android.R.id.content));
            });
        }
    }

    @Test @androidx.test.filters.SdkSuppress(minSdkVersion = 29)
    public void nativeCatalogsRenderOnlyTheSelectedRole() throws Exception {
        seedUi();
        try (ActivityScenario<AchievementsActivity> scenario = launch(AchievementsActivity.class)) {
            awaitText(scenario, context.getString(com.subhub.app.R.string.dom_subs_1));
            capture(scenario, "dom-achievements");
            scenario.onActivity(activity -> {
                String visible = texts(activity.findViewById(android.R.id.content));
                assertTrue(visible.contains(context.getString(com.subhub.app.R.string.dom_subs_1)));
                assertFalse(visible.contains(context.getString(com.subhub.app.R.string.achievement_first_session)));
                assertFits(activity.findViewById(android.R.id.content));
            });
        }
        ControllerPinManager.enterSubMode();
        try (ActivityScenario<AchievementsActivity> scenario = launch(AchievementsActivity.class)) {
            awaitText(scenario, context.getString(com.subhub.app.R.string.achievement_first_session));
            capture(scenario, "sub-achievements");
            scenario.onActivity(activity -> assertFalse(texts(activity.findViewById(android.R.id.content))
                    .contains(context.getString(com.subhub.app.R.string.dom_subs_1))));
        }
    }

    @Test public void domHomeRoutesToItsStatisticsAndAchievements() throws Exception {
        seedUi();
        try (ActivityScenario<MainActivity> scenario = launch(MainActivity.class)) {
            awaitTag(scenario, "home_dom_statistics");
            onView(withTagValue(is((Object)"home_dom_statistics")))
                    .perform(com.subhub.app.NativeUiActions.revealAboveNavigation(), click());
            awaitScreen(StatsActivity.class, "dom_stats_paired");
            androidx.test.espresso.Espresso.pressBack();
            awaitTag(scenario, "home_dom_achievements");
            onView(withTagValue(is((Object)"home_dom_achievements")))
                    .perform(com.subhub.app.NativeUiActions.revealAboveNavigation(), click());
            awaitScreen(AchievementsActivity.class, null);
            androidx.test.espresso.Espresso.pressBack();
            awaitTag(scenario, "home_dom_statistics");
        }
    }

    private void awaitScreen(Class<?> expected, String tag) throws Exception {
        long end = android.os.SystemClock.elapsedRealtime() + 15_000;
        while (android.os.SystemClock.elapsedRealtime() < end) {
            java.util.concurrent.atomic.AtomicBoolean found = new java.util.concurrent.atomic.AtomicBoolean();
            instrumentation.runOnMainSync(() -> {
                for (android.app.Activity activity : androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                        .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED))
                    if (expected.isInstance(activity) && (tag == null
                            || activity.findViewById(android.R.id.content).findViewWithTag(tag) != null)) found.set(true);
            });
            if (found.get()) return;
            Thread.sleep(40);
        }
        throw new AssertionError("Expected screen did not resume: " + expected.getName());
    }

    private DomStatsSnapshot facts() {
        return DomStatsSnapshot.aggregate(Collections.singletonList(new DomStatsSnapshot.Sub("fixture", generation, 1,
                System.currentTimeMillis(), System.currentTimeMillis(), new DomStatsSnapshot.Totals(3600, 10, 1000, 0, 1000, 0),
                new DomStatsSnapshot.Observation(true, true, true))), System.currentTimeMillis());
    }
    private RemoteStore store() {
        String name = "role-achievement-" + UUID.randomUUID() + ".db";
        RemoteStore store = new RemoteStore(context, name); names.add(name); stores.add(store); return store;
    }
    private void peer(RemoteStore store, String identity, String generation, RemoteProtocol.Role role, RemoteStore.State state) {
        ContentValues row = new ContentValues(); row.put("peer", identity); row.put("generation", generation);
        row.put("name", "Fixture Sub " + identity.charAt(0)); row.put("local_role", role.name()); row.put("state", state.name());
        store.getWritableDatabase().insertOrThrow("peers", null, row);
    }
    private void mirror(RemoteStore store, String id, RemoteProtocol.Stream stream, String key, JSONObject value) {
        ContentValues row = new ContentValues(); row.put("peer", id); row.put("generation", generation);
        row.put("stream", stream.name()); row.put("item", key); row.put("seq", 1); row.put("body", value.toString());
        row.put("deleted", 0); row.put("observed", System.currentTimeMillis());
        store.getWritableDatabase().insertWithOnConflict("remote_objects", null, row, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE);
    }
    private void meta(RemoteStore store, String id, String key, String value) {
        ContentValues row = new ContentValues(); row.put("peer", id); row.put("generation", generation);
        row.put("name", key); row.put("value", value);
        store.getWritableDatabase().insertWithOnConflict("remote_metadata", null, row, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE);
    }
    private void confirmed(RemoteStore store, String id, long now) {
        meta(store, id, "confirmed_STATS", Long.toString(now)); meta(store, id, "confirmed_RUNTIME", Long.toString(now));
    }
    private void seedUi() throws Exception {
        try (RemoteStore store = new RemoteStore(context)) {
            for (int i = 0; i < 3; i++) {
                String id = identity((char)('a' + i)); uiPeers.add(id);
                peer(store, id, generation, RemoteProtocol.Role.DOM, RemoteStore.State.ACTIVE);
                mirror(store, id, RemoteProtocol.Stream.STATS, "summary", summary((i + 1) * 3600, i + 1, i == 0 ? 1250 : 0, i == 1 ? 750 : 0));
                mirror(store, id, RemoteProtocol.Stream.RUNTIME, "current", runtime(i != 2, i != 2, true));
                confirmed(store, id, System.currentTimeMillis());
            }
        }
    }
    private static String identity(char value) { return String.join("", Collections.nCopies(64, String.valueOf(value))); }
    private static JSONObject summary(long seconds, long sessions, long eur, long usd) throws Exception {
        JSONObject result = new JSONObject().put("schema", 1).put("currency", "EUR");
        for (String key : ("protectedSeconds serviceSeconds sessions activityEvents censors limitMillis limitStops whispers popups tamperEvents tributes assessedEUR assessedUSD paidEUR paidUSD currentSeconds currentEvents currentCensors currentLimitMillis currentLimitStops currentWhispers currentPopups currentTributes currentAssessedEUR currentAssessedUSD").split(" ")) result.put(key, 0);
        return result.put("serviceSeconds", seconds).put("protectedSeconds", seconds).put("sessions", sessions)
                .put("paidEUR", eur).put("paidUSD", usd).put("assessedEUR", eur).put("assessedUSD", usd);
    }
    private static JSONObject runtime(boolean active, boolean hardcore, boolean wallet) throws Exception {
        JSONObject result = new JSONObject().put("schema", 1).put("device", "Synthetic fixture");
        for (String key : new String[]{"observedAt", "sessionStart", "sessionEnd", "appliedRevision"}) result.put(key, 0);
        for (String key : new String[]{"serviceActive", "screenCapture", "recognitionActive", "accessibilityBound", "accessibilityEnabled", "overlayPermission", "notifications", "armed", "paused"}) result.put(key, false);
        JSONObject consent = new JSONObject();
        for (String feature : com.subhub.app.security.SubFeaturePolicy.FEATURES) consent.put(feature, "wallet".equals(feature) ? wallet : true);
        return result.put("serviceActive", active).put("hardcoreEnabled", hardcore).put("featureConsent", consent);
    }
    private <T extends android.app.Activity> ActivityScenario<T> launch(Class<T> type) {
        return ActivityScenario.launch(new Intent(context, type).setAction(Intent.ACTION_MAIN));
    }
    private void awaitTag(ActivityScenario<?> scenario, String tag) throws Exception { await(scenario, root -> root.findViewWithTag(tag) != null); }
    private void awaitText(ActivityScenario<?> scenario, String text) throws Exception { await(scenario, root -> texts(root).contains(text) && root.getVisibility() == View.VISIBLE); }
    private void await(ActivityScenario<?> scenario, java.util.function.Predicate<View> ready) throws Exception {
        long end = android.os.SystemClock.elapsedRealtime() + 15_000;
        while (android.os.SystemClock.elapsedRealtime() < end) {
            java.util.concurrent.atomic.AtomicBoolean found = new java.util.concurrent.atomic.AtomicBoolean();
            scenario.onActivity(activity -> found.set(ready.test(activity.findViewById(android.R.id.content))));
            if (found.get()) { instrumentation.waitForIdleSync(); return; }
            Thread.sleep(40);
        }
        throw new AssertionError("Role page did not finish loading");
    }
    @androidx.annotation.RequiresApi(29)
    private void capture(ActivityScenario<?> scenario, String name) throws Exception {
        CountDownLatch frame = new CountDownLatch(1);
        scenario.onActivity(activity -> {
            View root = activity.getWindow().getDecorView();
            root.getViewTreeObserver().registerFrameCommitCallback(frame::countDown); root.invalidate();
        });
        assertTrue("No committed frame", frame.await(5, TimeUnit.SECONDS));
        Bitmap bitmap = instrumentation.getUiAutomation().takeScreenshot(); assertNotNull(bitmap);
        File directory = new File(context.getFilesDir(), "role-achievements"); assertTrue(directory.isDirectory() || directory.mkdirs());
        try (FileOutputStream output = new FileOutputStream(new File(directory, name + ".png"))) { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)); }
        bitmap.recycle();
    }
    private static String textAt(android.app.Activity activity, String tag) { return texts(activity.findViewById(android.R.id.content).findViewWithTag(tag)); }
    private static String texts(View view) {
        if (view == null || view.getVisibility() != View.VISIBLE) return "";
        StringBuilder text = new StringBuilder();
        if (view instanceof TextView) text.append(((TextView)view).getText()).append('\n');
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup)view).getChildCount(); i++) text.append(texts(((ViewGroup)view).getChildAt(i)));
        return text.toString();
    }
    private static void assertFits(View view) {
        if (view.getVisibility() != View.VISIBLE) return;
        if (view instanceof TextView) {
            TextView text = (TextView)view;
            android.text.Layout layout = text.getLayout();
            if (layout != null && text.getEllipsize() == null)
                for (int line = 0; line < layout.getLineCount(); line++)
                    assertTrue("Text overflows: " + text.getText() + " line=" + line
                            + " extent=" + layout.getLineMax(line) + " width=" + text.getWidth(),
                            layout.getLineMax(line) <= text.getWidth() - text.getCompoundPaddingLeft() - text.getCompoundPaddingRight() + 1);
        }
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup)view).getChildCount(); i++) assertFits(((ViewGroup)view).getChildAt(i));
    }
    private static void assertCaptureIdle() {
        assertFalse(com.subhub.app.service.ScreenCaptureService.isRunning());
        assertFalse(com.subhub.app.service.ScreenshotAccessibilityService.isRunning());
    }
}
