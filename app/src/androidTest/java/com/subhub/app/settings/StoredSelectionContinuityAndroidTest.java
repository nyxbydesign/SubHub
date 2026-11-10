package com.subhub.app.settings;

import static org.junit.Assert.*;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ListView;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.R;
import com.subhub.app.appmode.AppModeManager;
import com.subhub.app.help.HelpActivity;
import com.subhub.app.util.AsyncUiScope;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

/** Metadata latency cannot replace saved selections or cross an editor binding. */
public final class StoredSelectionContinuityAndroidTest {
    @Test public void pendingCatalogKeepsStoredRowsAndRebindingDropsTheOldSelection() throws Exception {
        Context app = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Set<String> owned = java.util.concurrent.ConcurrentHashMap.newKeySet();
        Context first = source(app, "first." + UUID.randomUUID(), "com.example.first", owned);
        Context second = source(app, "second." + UUID.randomUUID(), "com.example.second", owned);
        CountDownLatch started = new CountDownLatch(2), release = new CountDownLatch(1);
        AtomicReference<AppSelectionPanel> panel = new AtomicReference<>();
        try (ActivityScenario<HelpActivity> page = ActivityScenario.launch(HelpActivity.class)) {
            page.onActivity(activity -> {
                AsyncUiScope jobs = AsyncUiScope.forPage(activity);
                for (int index = 0; index < 2; index++) jobs.load("catalog-gate:" + index, () -> {
                    started.countDown();
                    if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Fixture gate timed out");
                    return true;
                }, ignored -> { }, failure -> { });
            });
            assertTrue("Both UI workers must be gated", started.await(5, TimeUnit.SECONDS));
            page.onActivity(activity -> {
                AppSelectionPanel selection = new AppSelectionPanel(activity); panel.set(selection);
                activity.setContentView(selection);
                selection.bind(activity, first, () -> true, () -> { }); selection.load();
                ListView list = selection.findViewById(R.id.app_list);
                assertEquals(1, list.getAdapter().getCount());
                Object adapter = list.getAdapter();
                assertEquals("com.example.first", ((InstalledAppCatalog.Entry) list.getAdapter().getItem(0)).packageName);
                assertFalse("Never offer select-all from a partial catalog", selection.findViewById(R.id.all_apps).isEnabled());
                assertFalse(text(selection).toLowerCase(java.util.Locale.ROOT).contains("loading"));
                selection.reload();
                assertSame("A pending refresh keeps the adapter", adapter, list.getAdapter());
                assertEquals(1, list.getAdapter().getCount());
                selection.bind(activity, second, () -> true, () -> { }); selection.load();
                assertEquals(1, list.getAdapter().getCount());
                assertEquals("com.example.second", ((InstalledAppCatalog.Entry) list.getAdapter().getItem(0)).packageName);
                assertFalse(text(selection).contains("com.example.first"));
            });
            release.countDown();
            // The actual catalog may exclude the synthetic packages. A current result may replace them,
            // but no delayed result from the first binding may reintroduce its stored selection.
            long deadline = android.os.SystemClock.uptimeMillis() + 8_000;
            java.util.concurrent.atomic.AtomicBoolean pending = new java.util.concurrent.atomic.AtomicBoolean(true);
            while (pending.get() && android.os.SystemClock.uptimeMillis() < deadline) {
                page.onActivity(activity -> pending.set(AsyncUiScope.forPage(activity).isPending("included-app-selector")));
                if (pending.get()) android.os.SystemClock.sleep(20);
            }
            assertFalse("Current catalog read completed", pending.get());
            page.onActivity(activity -> assertFalse(text(panel.get()).contains("com.example.first")));
        } finally {
            release.countDown();
            for (String name : owned) assertTrue(app.deleteSharedPreferences(name));
        }
    }

    private static Context source(Context app, String prefix, String selected, Set<String> owned) {
        Context source = new ContextWrapper(app) {
            @Override public Context getApplicationContext() { return this; }
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                String key = prefix + "." + name; owned.add(key);
                return app.getSharedPreferences(key, mode);
            }
        };
        assertTrue(source.getSharedPreferences(SettingsRepository.PREFERENCES_NAME, 0).edit()
                .putStringSet(AppModeManager.KEY_INCLUDED_PACKAGES, Set.of(selected))
                .putBoolean(AppModeManager.KEY_ALL_APPS, false).commit());
        return source;
    }

    private static String text(View view) {
        StringBuilder result = new StringBuilder(view instanceof TextView ? ((TextView) view).getText() : "");
        if (view instanceof ViewGroup) for (int index = 0; index < ((ViewGroup) view).getChildCount(); index++)
            result.append(text(((ViewGroup) view).getChildAt(index)));
        return result.toString();
    }
}
