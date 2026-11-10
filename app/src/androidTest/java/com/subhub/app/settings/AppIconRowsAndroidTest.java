package com.subhub.app.settings;

import static org.junit.Assert.*;
import android.content.Context;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.R;
import com.subhub.app.activity.*;
import com.subhub.app.help.HelpActivity;
import com.subhub.app.people.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.*;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class AppIconRowsAndroidTest {
    @Rule public final com.subhub.app.security.KeyholderPresentationAndroidTest.IntegrationFixture integration =
            new com.subhub.app.security.KeyholderPresentationAndroidTest.IntegrationFixture();
    @Rule public final com.subhub.app.UiAutomationStateRule automation = new com.subhub.app.UiAutomationStateRule();

    @Test public void selectorContractAllowanceAndActivityUseActualIconsAndUnknownFallback() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String known = context.getPackageName(), unknown = "com.example.remote.unavailable";
        Set<String> selected = new HashSet<>();
        AtomicReference<IncludedAppsAdapter> adapter = new AtomicReference<>(); AtomicReference<IncludedAppRow> selector = new AtomicReference<>();
        AtomicReference<LinearLayout> root = new AtomicReference<>(); AtomicReference<HelpActivity> host = new AtomicReference<>();
        try (ActivityScenario<HelpActivity> page = ActivityScenario.launch(HelpActivity.class)) {
            page.onActivity(activity -> {
                host.set(activity); ScrollView scroll = new ScrollView(activity); root.set(PersonUi.column(activity)); scroll.addView(root.get()); activity.setContentView(scroll);
                adapter.set(new IncludedAppsAdapter(activity, List.of(new InstalledAppCatalog.Entry("SubHub", known, null),
                        new InstalledAppCatalog.Entry("Unknown remote app", unknown, null)), selected,
                        (name, included) -> { if (included) selected.add(name); else selected.remove(name); }));
                adapter.get().setEditing(true); selector.set((IncludedAppRow) adapter.get().getView(0, null, root.get())); root.get().addView(selector.get());
                ContractAppList.add(root.get(), new PersonSnapshot.Section("apps", "", "", "",
                        List.of(new PersonSnapshot.App(known, "SubHub"), new PersonSnapshot.App(unknown, "Unknown remote app"))));
                PersonUi.allowances(root.get(), new PersonSnapshot(PersonId.LOCAL, "Synthetic", "Emulator", "", "",
                        PersonSnapshot.Connection.LOCAL, PersonSnapshot.Freshness.CURRENT, 0, Set.of(), List.of(),
                        List.of(new PersonSnapshot.Allowance(known, "SubHub", 60_000, 45),
                                new PersonSnapshot.Allowance(unknown, "Unknown remote app", -1, 90)), "EUR", 0, 0, 0, 0, false));
                for (String name : List.of(known, unknown)) {
                    ActivityEntry entry = new ActivityEntry("icon_" + name, PersonId.LOCAL, "Synthetic", "Emulator",
                            new ActivityEvent("event_" + name, ActivityEvent.Kind.APP_OPENED, System.currentTimeMillis(), name, 0, "", "", 0),
                            System.currentTimeMillis(), false, name.equals(known) ? "SubHub" : "Unknown remote app");
                    LinearLayout row = PersonUi.row(root.get(), ActivityText.icon(entry), ActivityText.title(activity, entry), "", null);
                    ActivityText.bindIcon((ImageView) row.getChildAt(0), entry);
                }
            });
            long end = SystemClock.uptimeMillis() + 8000; AtomicInteger actual = new AtomicInteger();
            do { page.onActivity(activity -> actual.set((int) images(root.get()).stream().filter(image -> image.getImageTintList() == null).count()));
                if (actual.get() == 4) break; SystemClock.sleep(25);
            } while (SystemClock.uptimeMillis() < end);
            assertEquals(4, actual.get());
            page.onActivity(activity -> {
                assertEquals(7, images(root.get()).size());
                for (ImageView image : images(root.get())) { assertNotNull(image.getDrawable()); assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, image.getImportantForAccessibility()); }
                assertNotNull(selector.get().choice().getContentDescription());
            });
            if (android.os.Build.VERSION.SDK_INT >= 29) com.subhub.app.UiRefreshWitness.capture(host.get(), "app-icons-shared-rows");
            page.onActivity(activity -> {
                assertSame(selector.get(), adapter.get().getView(1, selector.get(), root.get()));
                assertTrue(selector.get().choice().getContentDescription().toString().contains("Unknown remote app"));
                ImageView image = selector.get().findViewWithTag("app_icon:" + unknown); assertNotNull(image); assertNotNull(image.getImageTintList());
            });
            androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withTagValue(org.hamcrest.Matchers.is("included:" + unknown)))
                    .perform(androidx.test.espresso.action.ViewActions.click());
            assertTrue(selected.contains(unknown)); assertFalse(selected.contains(known));
        }
    }

    private static List<ImageView> images(View view) {
        List<ImageView> result = new ArrayList<>();
        if (view instanceof ImageView) result.add((ImageView) view);
        if (view instanceof ViewGroup) { ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) result.addAll(images(group.getChildAt(i)));
        }
        return result;
    }
}
