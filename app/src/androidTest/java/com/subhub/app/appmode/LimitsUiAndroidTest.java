package com.subhub.app.appmode;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;

import com.subhub.app.R;
import com.subhub.app.onboarding.OnboardingState;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.settings.FeatureModuleManager;
import com.subhub.app.settings.SettingsRepository;
import com.subhub.app.settings.SharedPreferenceTestRestore;

import org.junit.*;

import java.io.File;
import java.io.FileOutputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public final class LimitsUiAndroidTest {
    private static final String FIRST = "com.android.chrome", SECOND = "com.android.settings";
    private Context context;
    private AppModeManager apps;
    private AppTimerManager timers;
    private final Map<SharedPreferences, Map<String, ?>> original = new LinkedHashMap<>();

    @Before
    public void fixture() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        context.getPackageManager().getApplicationInfo(FIRST, 0);
        for (String name :
                new String[] {
                    SettingsRepository.PREFERENCES_NAME,
                    "subhub_app_timer_usage",
                    "subhub_onboarding",
                    "subhub_stats",
                    "subhub_daily_stats"
                }) {
            SharedPreferences preferences = context.getSharedPreferences(name, 0);
            original.put(preferences, new LinkedHashMap<>(preferences.getAll()));
        }
        ControllerPinManager.enterDomMode();
        OnboardingState.complete(context);
        new FeatureModuleManager(context).save(true, true, true);
        apps = new AppModeManager(context);
        apps.setArmed(false);
        apps.saveIncludedPackages(Set.of(FIRST, SECOND));
        timers = new AppTimerManager(context);
        timers.clearUsageForTesting();
        timers.saveSettings(true, 30, true, 45);
        timers.saveAllowances(Set.of(FIRST, SECOND), Map.of(FIRST, 15, SECOND, 40));
        long now = System.currentTimeMillis();
        timers.recordUsage(FIRST, 11 * 60_000L, Set.of(FIRST, SECOND), now);
        timers.recordUsage(SECOND, 8 * 60_000L, Set.of(FIRST, SECOND), now);
    }

    @After
    public void restore() {
        for (Map.Entry<SharedPreferences, Map<String, ?>> entry : original.entrySet())
            SharedPreferenceTestRestore.restore(entry.getKey(), entry.getValue());
        ControllerPinManager.enterSubMode();
    }

    @Test
    public void recordedUsageAndDistinctAllowancesHaveReadableGeometry() throws Exception {
        try (ActivityScenario<AppModeActivity> scenario =
                ActivityScenario.launch(AppModeActivity.class)) {
            settle();
            scenario.onActivity(
                    a -> {
                        assertEquals("15", field(a, FIRST).getText().toString());
                        assertEquals("40", field(a, SECOND).getText().toString());
                        assertEquals(
                                "19m",
                                ((TextView) a.findViewById(R.id.limits_today_amount))
                                        .getText()
                                        .toString());
                        assertEquals(
                                a.getString(R.string.limits_usage_remaining, "19m", "26m"),
                                ((TextView) a.findViewById(R.id.limits_combined_usage))
                                        .getText()
                                        .toString());
                        assertTrue(a.findViewById(R.id.limits_manage_apps).isClickable());
                        readable(a.findViewById(android.R.id.content));
                        capture(a, "limits-validation.png");
                    });
        }
    }

    @Test
    public void invalidDraftSurvivesTicksAndRecreationThenPersistsWhenValid() throws Exception {
        try (ActivityScenario<AppModeActivity> scenario =
                ActivityScenario.launch(AppModeActivity.class)) {
            settle();
            scenario.onActivity(a -> field(a, FIRST).setText(""));
            settle();
            scenario.onActivity(a -> assertEquals("", field(a, FIRST).getText().toString()));
            scenario.recreate();
            settle();
            scenario.onActivity(
                    a -> {
                        assertEquals("", field(a, FIRST).getText().toString());
                        field(a, FIRST).setText("25");
                    });
            settle();
            assertEquals(25, timers.allowanceMinutes(FIRST));
            assertEquals(40, timers.allowanceMinutes(SECOND));
        }
    }

    @Test
    public void scopeRefreshRetainsFocusedDraftAndInactiveAllowance() throws Exception {
        try (ActivityScenario<AppModeActivity> scenario =
                ActivityScenario.launch(AppModeActivity.class)) {
            settle();
            scenario.onActivity(
                    a -> {
                        field(a, FIRST).requestFocus();
                        field(a, FIRST).setText("25");
                        apps.saveIncludedPackages(Set.of(FIRST));
                    });
            settle();
            scenario.onActivity(
                    a -> {
                        assertEquals("25", field(a, FIRST).getText().toString());
                        field(a, FIRST).setText("50");
                    });
            settle();
            assertEquals(50, timers.allowanceMinutes(FIRST));
            assertEquals(40, timers.allowanceMinutes(SECOND));
            assertEquals(
                    19 * 60_000L,
                    timers.snapshots(Set.of(FIRST), System.currentTimeMillis())
                            .get("")
                            .totalUsedMillis);
            apps.saveIncludedPackages(Set.of(FIRST, SECOND));
            settle();
            scenario.onActivity(a -> assertEquals("40", field(a, SECOND).getText().toString()));
        }
    }

    @Test
    public void delayedSaveRechecksDomAccess() throws Exception {
        try (ActivityScenario<AppModeActivity> scenario =
                ActivityScenario.launch(AppModeActivity.class)) {
            settle();
            scenario.onActivity(
                    a -> {
                        field(a, FIRST).setText("99");
                        ControllerPinManager.enterSubMode();
                    });
            settle();
            assertEquals(15, timers.allowanceMinutes(FIRST));
            scenario.onActivity(
                    a -> {
                        assertFalse(field(a, FIRST).isEnabled());
                        assertEquals("15", field(a, FIRST).getText().toString());
                    });
        }
    }

    @Test
    public void storedRowsSaveOnBlurAndPauseWhileCatalogIsPendingOrFailed() throws Exception {
        String third = "com.example.subhub.metadata.fixture";
        Set<String> requested = Set.of(FIRST, third);
        java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(2);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        try (ActivityScenario<AppModeActivity> scenario = ActivityScenario.launch(AppModeActivity.class)) {
            try {
                awaitMetadata(scenario);
                scenario.onActivity(a -> {
                    com.subhub.app.util.AsyncUiScope work = com.subhub.app.util.AsyncUiScope.forPage(a);
                    assertFalse(work.isPending("limits-apps"));
                    for (int index = 0; index < 2; index++) work.load("metadata-gate:" + index, () -> {
                        started.countDown();
                        if (!release.await(30, java.util.concurrent.TimeUnit.SECONDS))
                            throw new IllegalStateException("Fixture gate timed out");
                        return true;
                    }, ignored -> { }, failure -> { });
                });
                assertTrue("Both metadata workers are held", started.await(5, java.util.concurrent.TimeUnit.SECONDS));
                scenario.onActivity(a -> {
                    apps.saveIncludedPackages(requested);
                    timers.saveAllowances(requested, Map.of(FIRST, 15, third, 33));
                    invoke(a, "renderPerAppAllowances", new Class<?>[0]);
                    assertTrue(com.subhub.app.util.AsyncUiScope.forPage(a).isPending("limits-apps"));
                    assertEquals("33", field(a, third).getText().toString());
                    assertTrue(field(a, FIRST).isEnabled());
                    field(a, FIRST).requestFocus(); field(a, FIRST).setText("25");
                    field(a, third).requestFocus(); // Blur must save before package metadata arrives.
                    assertEquals(25, timers.allowanceMinutes(FIRST));
                    assertEquals("25", field(a, FIRST).getText().toString());
                    field(a, FIRST).setText("35");
                });
                scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED);
                assertEquals("Pause persists a draft while metadata is still queued", 35, timers.allowanceMinutes(FIRST));
                assertEquals(1L, release.getCount());
                scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED);
                scenario.onActivity(a -> {
                    // Deliver the metadata failure handler while the real queued lookup is cancelled.
                    // No private loader hook or permission bypass is added to production code.
                    com.subhub.app.util.AsyncUiScope.forPage(a).cancel("limits-apps");
                    invoke(a, "appMetadataUnavailable", new Class<?>[]{Set.class}, requested);
                    assertEquals("35", field(a, FIRST).getText().toString());
                    assertTrue(field(a, FIRST).isEnabled());
                    field(a, FIRST).requestFocus(); field(a, FIRST).setText("45");
                    field(a, third).requestFocus();
                    assertEquals("Failed labels cannot prevent allowance saves", 45, timers.allowanceMinutes(FIRST));
                    assertEquals("45", field(a, FIRST).getText().toString());
                    assertEquals(33, timers.allowanceMinutes(third));
                });
            } finally { release.countDown(); }
        }
    }

    @Test
    public void scopeChurnRestoresSavedRowsDespiteEarlierMetadataSuccessOrFailure() throws Exception {
        String third = "com.example.subhub.churn.fixture";
        Set<String> firstScope = Set.of(FIRST, SECOND), secondScope = Set.of(FIRST, third);
        java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(2);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        try (ActivityScenario<AppModeActivity> scenario = ActivityScenario.launch(AppModeActivity.class)) {
            try {
                awaitMetadata(scenario); // A has a successful catalog observation.
                scenario.onActivity(a -> {
                    com.subhub.app.util.AsyncUiScope work = com.subhub.app.util.AsyncUiScope.forPage(a);
                    for (int index = 0; index < 2; index++) work.load("churn-gate:" + index, () -> {
                        started.countDown();
                        if (!release.await(30, java.util.concurrent.TimeUnit.SECONDS))
                            throw new IllegalStateException("Fixture gate timed out");
                        return true;
                    }, ignored -> { }, failure -> { });
                });
                assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS));
                scenario.onActivity(a -> {
                    timers.updateAllowances(Map.of(third, 33));
                    showScope(a, secondScope);
                    assertNull(field(a, SECOND)); assertEquals("33", field(a, third).getText().toString());
                    assertTrue(com.subhub.app.util.AsyncUiScope.forPage(a).isPending("limits-apps"));
                    showScope(a, firstScope); // B is still queued, and the old successful scope is A.
                    assertFirstScope(a, third);
                    field(a, FIRST).requestFocus(); field(a, FIRST).setText("27");
                    field(a, SECOND).requestFocus(); assertEquals(27, timers.allowanceMinutes(FIRST));
                    com.subhub.app.util.AsyncUiScope work = com.subhub.app.util.AsyncUiScope.forPage(a);
                    assertTrue(work.isPending("limits-apps"));
                    work.cancel("limits-apps");
                    invoke(a, "appMetadataUnavailable", new Class<?>[]{Set.class}, firstScope);
                    showScope(a, secondScope);
                    assertNull(field(a, SECOND)); assertNotNull(field(a, third));
                    showScope(a, firstScope); // A's previous failure must not leave B's rows visible.
                    assertFirstScope(a, third);
                    assertTrue("Returning to a failed scope retries its current metadata", work.isPending("limits-apps"));
                    field(a, FIRST).requestFocus(); field(a, FIRST).setText("37");
                    field(a, SECOND).requestFocus(); assertEquals(37, timers.allowanceMinutes(FIRST));
                    assertEquals(40, timers.allowanceMinutes(SECOND));
                });
                assertEquals(1L, release.getCount());
                release.countDown();
                awaitMetadata(scenario);
                scenario.onActivity(a -> {
                    assertFirstScope(a, third);
                    assertEquals("37", field(a, FIRST).getText().toString());
                    assertEquals(33, timers.allowanceMinutes(third));
                });
            } finally { release.countDown(); }
        }
    }

    private void showScope(AppModeActivity activity, Set<String> packages) {
        apps.saveIncludedPackages(packages);
        invoke(activity, "renderPerAppAllowances", new Class<?>[0]);
    }

    private static void assertFirstScope(AppModeActivity activity, String absent) {
        assertNotNull(field(activity, FIRST)); assertNotNull(field(activity, SECOND));
        assertNull("Rows from the superseded scope must be removed", field(activity, absent));
        assertEquals("40", field(activity, SECOND).getText().toString());
    }

    private static void awaitMetadata(ActivityScenario<AppModeActivity> scenario) {
        long deadline = android.os.SystemClock.uptimeMillis() + 10_000;
        java.util.concurrent.atomic.AtomicBoolean pending = new java.util.concurrent.atomic.AtomicBoolean(true);
        while (pending.get() && android.os.SystemClock.uptimeMillis() < deadline) {
            scenario.onActivity(a -> pending.set(com.subhub.app.util.AsyncUiScope.forPage(a).isPending("limits-apps")));
            if (pending.get()) android.os.SystemClock.sleep(20);
        }
        assertFalse("Initial metadata lookup completed", pending.get());
    }

    private static void invoke(AppModeActivity activity, String name, Class<?>[] types, Object... values) {
        try {
            java.lang.reflect.Method method = AppModeActivity.class.getDeclaredMethod(name, types);
            method.setAccessible(true); method.invoke(activity, values);
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }

    private static EditText field(AppModeActivity a, String name) {
        return a.findViewById(android.R.id.content).findViewWithTag("limit:" + name);
    }

    private static void settle() throws Exception {
        Thread.sleep(1350);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    static void readable(View view) {
        if (view instanceof TextView) {
            TextView text = (TextView) view;
            if (text.getVisibility() == View.VISIBLE && text.getLayout() != null) {
                for (int i = 0; i < text.getLineCount(); i++)
                    assertEquals(
                            "Clipped text: " + text.getText(),
                            0,
                            text.getLayout().getEllipsisCount(i));
                assertTrue(
                        "Vertical clipping: " + text.getText(),
                        text.getLayout().getHeight()
                                <= text.getHeight()
                                        - text.getCompoundPaddingTop()
                                        - text.getCompoundPaddingBottom());
                if (text instanceof EditText)
                    assertTrue(
                            text.getHeight()
                                    >= Math.round(
                                            48 * text.getResources().getDisplayMetrics().density));
            }
        }
        if (view instanceof android.view.ViewGroup)
            for (int i = 0; i < ((android.view.ViewGroup) view).getChildCount(); i++)
                readable(((android.view.ViewGroup) view).getChildAt(i));
    }

    private static void capture(android.app.Activity a, String name) {
        View root = a.getWindow().getDecorView();
        Bitmap bitmap =
                Bitmap.createBitmap(root.getWidth(), root.getHeight(), Bitmap.Config.ARGB_8888);
        root.draw(new Canvas(bitmap));
        try (FileOutputStream output = new FileOutputStream(new File(a.getFilesDir(), name))) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
        } catch (Exception e) {
            throw new AssertionError(e);
        } finally {
            bitmap.recycle();
        }
    }
}
