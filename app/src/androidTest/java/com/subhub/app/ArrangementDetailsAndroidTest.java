package com.subhub.app;

import static org.junit.Assert.*;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.matcher.ViewMatchers.withTagValue;
import static com.subhub.app.NativeUiActions.revealAboveNavigation;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.penance.PenanceInfraction;
import com.subhub.app.penance.PenanceManager;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.settings.FeatureModuleManager;
import com.subhub.app.settings.SettingsRepository;
import com.subhub.app.settings.SharedPreferenceTestRestore;
import java.io.File;
import java.io.FileOutputStream;
import java.util.EnumMap;
import java.util.Map;
import org.junit.*;

/** Every arrangement stays visible; summaries describe configuration without an activity claim. */
public class ArrangementDetailsAndroidTest {
    @Rule public final UiAutomationStateRule automation = new UiAutomationStateRule();
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private SharedPreferences settings, wallet;
    private Map<String, ?> settingsBefore, walletBefore;

    @Before public void fixture() {
        settings = context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME, 0);
        wallet = context.getSharedPreferences(PenanceManager.PREFS_NAME, 0);
        settingsBefore = settings.getAll(); walletBefore = wallet.getAll();
        new FeatureModuleManager(context).save(false, false, false, false);
        settings.edit().putBoolean(com.subhub.app.popup.PopupStormSettings.K_ENABLED, false).commit();
        Map<PenanceInfraction, Integer> rules = new EnumMap<>(PenanceInfraction.class);
        for (PenanceInfraction rule : PenanceInfraction.values())
            if (rule != PenanceInfraction.PAID_PAUSE) rules.put(rule, 125);
        new PenanceManager(context).configure(true, rules, 1000, 5000, 10, 15);
        new com.subhub.app.penance.PaidPauseManager(context).configure(false, 375, 12);
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
        ControllerPinManager.enterSubMode();
    }

    @After public void restore() {
        SharedPreferenceTestRestore.restore(settings, settingsBefore);
        SharedPreferenceTestRestore.restore(wallet, walletBefore);
        ControllerPinManager.enterSubMode();
    }

    @Test public void allContractSectionsRemainWithFiveOrZeroTributesAndReadableDetails() {
        try (ActivityScenario<com.subhub.app.people.ContractActivity> page =
                ActivityScenario.launch(com.subhub.app.people.ContractActivity.class)) {
            awaitContract(page);
            page.onActivity(a -> assertExpanded(a, ""));
            verifyRenderedDetails(page);
            page.onActivity(a -> assertSections(a, 5));
            new PenanceManager(context).configure(false, java.util.Collections.emptyMap(), 1000, 5000, 10, 15);
            page.recreate();
            awaitContract(page);
            page.onActivity(a -> assertExpanded(a, "atmosphere"));
            verifyRenderedDetails(page);
            page.onActivity(a -> assertSections(a, 0));
        }
    }

    private void verifyRenderedDetails(ActivityScenario<com.subhub.app.people.ContractActivity> page) {
        com.subhub.app.people.PersonSnapshot snapshot = new com.subhub.app.people.LocalPersonRepository(context)
                .load(com.subhub.app.people.PersonId.LOCAL);
        for (com.subhub.app.people.PersonSnapshot.Section section : snapshot.sections) {
            if (section.details.isEmpty()) continue;
            openSection(section.key);
            page.onActivity(activity -> {
                assertExpanded(activity, section.key);
                assertDetailBlocks(activity, section);
                if ("limits".equals(section.key)) {
                    String detail = text(activity.findViewById(R.id.role_page_body)
                            .findViewWithTag("contract_details:limits"));
                    assertTrue(detail.contains("Chrome · 15 min"));
                    assertTrue(detail.contains("Settings · 40 min"));
                }
                assertFalse(ControllerPinManager.isDomModeActive());
            });
        }
    }

    private void assertSections(com.subhub.app.people.ContractActivity activity, int expectedRules) {
        View content = activity.findViewById(R.id.role_page_body);
        String visible = text(content);
        com.subhub.app.people.PersonSnapshot snapshot = new com.subhub.app.people.LocalPersonRepository(activity)
                .load(com.subhub.app.people.PersonId.LOCAL);
        assertEquals(5, snapshot.sections.size());
        java.util.Map<String, com.subhub.app.people.PersonSnapshot.Section> sections = new java.util.LinkedHashMap<>();
        for (com.subhub.app.people.PersonSnapshot.Section value : snapshot.sections) sections.put(value.key, value);
        assertEquals(new java.util.LinkedHashSet<>(java.util.Arrays.asList("apps", "censor", "limits", "wallet", "atmosphere")), sections.keySet());
        for (com.subhub.app.people.PersonSnapshot.Section section : snapshot.sections) {
            assertTrue(visible.contains(section.title));
            assertTrue(visible.contains(section.summary));
            assertNotNull(content.findViewWithTag("contract_section:" + section.key));
            assertFalse(section.summary.contains(activity.getString(R.string.role_service_active)));
        }
        assertEquals(activity.getString(R.string.role_disabled), sections.get("censor").summary);
        assertEquals(activity.getResources().getQuantityString(R.plurals.sub_limits_selected, 3, 3),
                sections.get("limits").summary);
        assertEquals(expectedRules == 0 ? activity.getString(R.string.role_disabled)
                : activity.getString(R.string.role_wallet_rule_count, expectedRules), sections.get("wallet").summary);
        assertEquals(activity.getResources().getQuantityString(R.plurals.atmosphere_effects_active, 0, 0),
                sections.get("atmosphere").summary);
        View navigation = activity.findViewById(R.id.nav_contract);
        assertTrue(navigation.isShown());
        assertTrue(navigation.getHeight() >= Math.round(48 * activity.getResources().getDisplayMetrics().density));
        assertEquals(15, new com.subhub.app.appmode.AppTimerManager(context).allowanceMinutes("com.android.chrome"));
        assertEquals(40, new com.subhub.app.appmode.AppTimerManager(context).allowanceMinutes("com.android.settings"));
        Bitmap image = Bitmap.createBitmap(content.getWidth(), content.getHeight(), Bitmap.Config.ARGB_8888);
        content.draw(new Canvas(image));
        try (FileOutputStream out = new FileOutputStream(new File(context.getFilesDir(),
                "followup-contract-" + expectedRules + "-tributes.png"))) {
            assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, out));
        } catch (Exception failure) { throw new AssertionError(failure); }
        finally { image.recycle(); }
    }

    private void awaitContract(ActivityScenario<com.subhub.app.people.ContractActivity> page) {
        long until = android.os.SystemClock.uptimeMillis() + 5000;
        java.util.concurrent.atomic.AtomicBoolean rendered = new java.util.concurrent.atomic.AtomicBoolean();
        while (!rendered.get() && android.os.SystemClock.uptimeMillis() < until) {
            page.onActivity(activity -> rendered.set(text(activity.findViewById(R.id.role_page_body))
                    .contains(activity.getString(R.string.atmosphere_title))));
            if (!rendered.get()) android.os.SystemClock.sleep(25);
        }
        assertTrue("Contract did not render", rendered.get());
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    private static void openSection(String key) {
        onView(withTagValue(org.hamcrest.Matchers.is("contract_section:" + key)))
                .perform(revealAboveNavigation(), click());
    }
    private static void assertExpanded(com.subhub.app.people.ContractActivity activity, String selected) {
        View body = activity.findViewById(R.id.role_page_body);
        for (String key : new String[] {"apps", "censor", "limits", "wallet", "atmosphere"}) {
            View detail = body.findViewWithTag("contract_details:" + key);
            if (detail != null) assertEquals("Expansion for " + key,
                    selected.equals(key) ? View.VISIBLE : View.GONE, detail.getVisibility());
        }
    }
    private static void assertDetailBlocks(com.subhub.app.people.ContractActivity activity,
            com.subhub.app.people.PersonSnapshot.Section section) {
        ViewGroup detail = activity.findViewById(R.id.role_page_body).findViewWithTag("contract_details:" + section.key);
        assertNotNull("Detail for " + section.key, detail);
        assertTrue("Expanded detail is rendered", detail.isShown());
        String[] expected = section.details.split("\\n\\s*\\n");
        assertEquals("Every label/value paragraph is rendered", expected.length, detail.getChildCount());
        for (int index = 0; index < expected.length; index++) {
            assertTrue(detail.getChildAt(index) instanceof TextView);
            assertEquals("Paragraph " + index + " in " + section.key,
                    expected[index], ((TextView) detail.getChildAt(index)).getText().toString());
        }
    }
    private static String text(View view) {
        String copy = view instanceof TextView ? ((TextView) view).getText().toString() : "";
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) copy += "\n" + text(group.getChildAt(index));
        }
        return copy;
    }
}
