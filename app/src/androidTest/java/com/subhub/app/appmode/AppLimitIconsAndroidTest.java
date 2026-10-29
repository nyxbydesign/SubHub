package com.subhub.app.appmode;

import static org.junit.Assert.*;
import android.os.SystemClock;
import android.widget.ImageView;
import android.widget.LinearLayout;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.subhub.app.help.HelpActivity;
import java.util.concurrent.atomic.*;
import org.junit.*;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class AppLimitIconsAndroidTest {
    @Rule public final com.subhub.app.security.KeyholderPresentationAndroidTest.IntegrationFixture integration =
            new com.subhub.app.security.KeyholderPresentationAndroidTest.IntegrationFixture();
    @Rule public final com.subhub.app.UiAutomationStateRule automation = new com.subhub.app.UiAutomationStateRule();

    @Test public void limitRowsKeepNamesBudgetsAndActualOrFallbackIcons() throws Exception {
        AtomicReference<AppLimitRow> known = new AtomicReference<>(), unknown = new AtomicReference<>();
        AtomicReference<HelpActivity> host = new AtomicReference<>();
        try (ActivityScenario<HelpActivity> page = ActivityScenario.launch(HelpActivity.class)) {
            page.onActivity(activity -> {
                host.set(activity); LinearLayout root = new LinearLayout(activity); root.setOrientation(LinearLayout.VERTICAL); activity.setContentView(root);
                known.set(new AppLimitRow(activity, activity.getPackageName(), "SubHub", null, "45"));
                unknown.set(new AppLimitRow(activity, "com.example.remote.unavailable", "Unknown remote app", null, "90"));
                root.addView(known.get()); root.addView(unknown.get());
                known.get().renderUsage("1 / 45 min", 2, true); unknown.get().renderUsage("Usage unavailable", 0, false);
            });
            long end = SystemClock.uptimeMillis() + 8000; AtomicBoolean ready = new AtomicBoolean();
            do { page.onActivity(activity -> ready.set(((ImageView) known.get().findViewWithTag("app_icon:" + activity.getPackageName())).getImageTintList() == null));
                if (ready.get()) break; SystemClock.sleep(25);
            } while (SystemClock.uptimeMillis() < end);
            assertTrue(ready.get());
            page.onActivity(activity -> {
                assertEquals("45", known.get().allowance.getText().toString()); assertEquals("90", unknown.get().allowance.getText().toString());
                assertTrue(known.get().allowance.getContentDescription().toString().contains("SubHub"));
                assertTrue(unknown.get().allowance.getContentDescription().toString().contains("Unknown remote app"));
                ImageView fallback = unknown.get().findViewWithTag("app_icon:com.example.remote.unavailable");
                assertNotNull(fallback.getDrawable()); assertNotNull(fallback.getImageTintList());
            });
            if (android.os.Build.VERSION.SDK_INT >= 29) com.subhub.app.UiRefreshWitness.capture(host.get(), "app-icons-limit-rows");
        }
    }
}
