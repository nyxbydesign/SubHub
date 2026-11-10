package com.subhub.app.stats;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.*;
import static androidx.test.espresso.assertion.ViewAssertions.*;
import static androidx.test.espresso.matcher.ViewMatchers.*;

import static org.junit.Assert.*;
import static org.junit.Assume.*;

import android.content.*;
import android.graphics.*;
import android.view.*;
import android.widget.*;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.subhub.app.MainActivity;
import com.subhub.app.R;
import com.subhub.app.security.ControllerPinManager;

import org.junit.*;
import org.junit.runner.RunWith;

import java.io.*;
import java.time.*;

@RunWith(AndroidJUnit4.class)
public class StatisticsQualityAndroidTest {
    @Rule public final com.subhub.app.UiAutomationStateRule automation = new com.subhub.app.UiAutomationStateRule();
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();

    @Test
    public void singlePageShowsTotalsImmediatelyAndPagesUndatedHistoryInPlace() throws Exception {
        assumeTrue(android.os.Build.MODEL.contains("sdk_gphone"));
        DailyStatsStore.awaitWrites();
        DailyStatsStore store = DailyStatsStore.get(context);
        store.getWritableDatabase().execSQL("DELETE FROM daily");
        store.getWritableDatabase()
                .execSQL(
                        "UPDATE metadata SET value=? WHERE name='since'",
                        new Object[] {LocalDate.now().toString()});
        StringBuilder history = new StringBuilder();
        long start = System.currentTimeMillis() - 600000;
        for (int i = 0; i < 8; i++) {
            if (i > 0) history.append(';');
            history.append(start + i * 60000).append(",30,").append(i + 1);
        }
        context.getSharedPreferences(StatsRepository.PREFS_NAME, 0)
                .edit()
                .putLong("total_blocks_all_time", 6031)
                .putString("session_history", history.toString())
                .commit();
        // Saved records are within the collection boundary here. They must not become dated totals.
        store.getWritableDatabase()
                .execSQL(
                        "INSERT OR REPLACE INTO metadata VALUES('legacy_cutoff_ms',?)",
                        new Object[] {Long.toString(start - 1)});
        store.getWritableDatabase()
                .execSQL(
                        "INSERT OR REPLACE INTO metadata VALUES('legacy_zone',?)",
                        new Object[] {ZoneId.systemDefault().getId()});
        try (ActivityScenario<StatsActivity> stats = ActivityScenario.launch(StatsActivity.class)) {
            await(stats, R.id.stats_empty_activity);
            stats.onActivity(
                    a -> {
                        assertNotNull(a.findViewById(R.id.stats_lifetime_summary));
                        assertNotNull(a.findViewById(R.id.daily_statistics_panel));
                        assertEquals(3, countSessions(a.findViewById(android.R.id.content)));
                        assertNull(a.findViewById(R.id.stats_totals_toggle));
                        assertEquals(View.VISIBLE, a.findViewById(R.id.stats_totals_details).getVisibility());
                        assertTrue(
                                allText(a.findViewById(R.id.stats_lifetime_summary))
                                        .contains(
                                                java.text.NumberFormat.getIntegerInstance()
                                                        .format(
                                                                new StatsRepository(context)
                                                                        .load()
                                                                        .getTotalBlocks())));
                        capture(a, "stats-compact-empty.png");
                        assertEquals(6, assertMetricsReadable(a.findViewById(R.id.stats_lifetime_summary)));
                    });
            onView(withId(R.id.stats_sessions_more)).perform(com.subhub.app.NativeUiActions.revealAboveNavigation(), click());
            stats.onActivity(
                    a -> {
                        capture(a, "stats-expanded-after-action.png");
                        assertEquals(6, countSessions(a.findViewById(android.R.id.content)));
                        assertEquals(
                                View.VISIBLE,
                                a.findViewById(R.id.stats_totals_details).getVisibility());
                        capture(a, "stats-compact-expanded.png");
                    });
            stats.recreate();
            await(stats, R.id.stats_empty_activity);
            stats.onActivity(
                    a -> {
                        assertEquals(6, countSessions(a.findViewById(android.R.id.content)));
                        assertEquals(
                                View.VISIBLE,
                                a.findViewById(R.id.stats_totals_details).getVisibility());
                    });
        }
    }

    static void await(ActivityScenario<? extends android.app.Activity> scenario, int id)
            throws Exception {
        long until = android.os.SystemClock.uptimeMillis() + 5000;
        java.util.concurrent.atomic.AtomicBoolean found =
                new java.util.concurrent.atomic.AtomicBoolean();
        while (android.os.SystemClock.uptimeMillis() < until) {
            scenario.onActivity(a -> found.set(a.findViewById(id) != null));
            if (found.get()) return;
            android.os.SystemClock.sleep(25);
        }
        fail("Async page did not publish view " + id);
    }

    @Test
    public void subOverviewKeepsServiceBeforeContractAndStatisticsSummary() {
        ControllerPinManager.enterSubMode();
        android.content.SharedPreferences settings = new com.subhub.app.settings.SettingsRepository(context).preferences();
        java.util.Map<String, ?> before = settings.getAll();
        java.util.Set<String> apps = java.util.Set.of("com.android.chrome", "com.android.settings");
        for (String app : apps) {
            try { context.getPackageManager().getApplicationInfo(app, 0); }
            catch (android.content.pm.PackageManager.NameNotFoundException missing) {
                throw new AssertionError("Capture fixture app missing: " + app, missing);
            }
        }
        com.subhub.app.appmode.AppModeManager assigned = new com.subhub.app.appmode.AppModeManager(context);
        assigned.setAllAppsEnabled(false);
        assigned.saveIncludedPackages(apps);
        com.subhub.app.appmode.AppTimerManager timers = new com.subhub.app.appmode.AppTimerManager(context);
        timers.saveSettings(true, 30, true, 60);
        timers.saveAllowances(apps, java.util.Map.of("com.android.chrome", 15, "com.android.settings", 40));
        assertEquals(apps, assigned.getIncludedPackages());
        assertEquals(15, timers.allowanceMinutes("com.android.chrome"));
        assertEquals(40, timers.allowanceMinutes("com.android.settings"));
        try (ActivityScenario<MainActivity> home =
                ActivityScenario.launch(
                        new Intent(context, MainActivity.class).setAction(Intent.ACTION_MAIN))) {
            long until = android.os.SystemClock.uptimeMillis() + 5000;
            java.util.concurrent.atomic.AtomicBoolean rendered = new java.util.concurrent.atomic.AtomicBoolean();
            while (!rendered.get() && android.os.SystemClock.uptimeMillis() < until) {
                home.onActivity(activity -> rendered.set(allText(activity.findViewById(R.id.home_sub_summary))
                        .contains(activity.getString(R.string.role_stats))));
                if (!rendered.get()) android.os.SystemClock.sleep(25);
            }
            assertTrue("Sub overview did not render", rendered.get());
            home.onActivity(
                    a -> {
                        LinearLayout service = a.findViewById(R.id.home_service_widget),
                                content = a.findViewById(R.id.page_content);
                        assertTrue(service.isShown());
                        assertEquals(
                                content.indexOfChild(service) + 1,
                                content.indexOfChild(a.findViewById(R.id.home_sub_summary)));
                        assertEquals(service, a.findViewById(R.id.protection_status_row).getParent());
                        assertEquals(service, a.findViewById(R.id.sub_wallet_pay).getParent());
                        assertEquals(service, a.findViewById(R.id.sub_wallet_pause).getParent());
                        assertTrue(allText(a.findViewById(R.id.home_sub_summary)).contains(a.getString(R.string.role_stats)));
                        assertTrue(allText(a.findViewById(R.id.home_sub_summary)).contains(a.getString(R.string.role_active_contract)));
                        assertFalse(a.findViewById(R.id.sub_dashboard).isShown());
                        assertTrue(a.findViewById(R.id.nav_stats).isShown());
                        capture(a, "home-service-contract-summary.png");
                    });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        } finally {
            com.subhub.app.settings.SharedPreferenceTestRestore.restore(settings, before);
        }
    }

    private static int assertMetricsReadable(View view) {
        int count = 0;
        if ("stats:metric-value".equals(view.getTag()) || "stats:metric-label".equals(view.getTag())) {
            android.widget.TextView text = (android.widget.TextView) view;
            assertNotNull(text.getLayout());
            assertEquals("Metric must remain on one readable line: " + text.getText(), 1,
                    text.getLayout().getLineCount());
            assertTrue(text.getLayout().getLineMax(0) <= text.getWidth()
                    - text.getCompoundPaddingLeft() - text.getCompoundPaddingRight() + 1);
            count++;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) count += assertMetricsReadable(group.getChildAt(i));
        }
        return count;
    }

    private static int countSessions(View view) {
        int count = "stats:session".equals(view.getTag()) ? 1 : 0;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++)
                count += countSessions(group.getChildAt(i));
        }
        return count;
    }

    private static String allText(View view) {
        String text = view instanceof TextView ? ((TextView) view).getText().toString() : "";
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++)
                text += "\n" + allText(group.getChildAt(i));
        }
        return text;
    }

    private static void capture(android.app.Activity activity, String name) {
        View root = activity.getWindow().getDecorView();
        root.post(
                () -> {
                    Bitmap image =
                            Bitmap.createBitmap(
                                    root.getWidth(), root.getHeight(), Bitmap.Config.ARGB_8888);
                    root.draw(new Canvas(image));
                    try (FileOutputStream out =
                            new FileOutputStream(new File(activity.getFilesDir(), name))) {
                        image.compress(Bitmap.CompressFormat.PNG, 100, out);
                    } catch (IOException error) {
                        throw new AssertionError(error);
                    } finally {
                        image.recycle();
                    }
                });
    }
}
