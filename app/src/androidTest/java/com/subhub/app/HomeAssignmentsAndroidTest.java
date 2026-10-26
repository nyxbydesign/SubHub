package com.subhub.app;

import static org.junit.Assert.*;
import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.appmode.AppModeManager;
import com.subhub.app.appmode.AppTimerManager;
import com.subhub.app.onboarding.OnboardingState;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.settings.FeatureModuleManager;
import com.subhub.app.settings.SettingsRepository;
import com.subhub.app.settings.SharedPreferenceTestRestore;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

public class HomeAssignmentsAndroidTest {
    @org.junit.Rule public final UiAutomationStateRule automation = new UiAutomationStateRule();

    @Test public void canonicalDetailsAndContractShowAllowancesWithoutChangingAssignments() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Map<SharedPreferences, Map<String, ?>> before = new LinkedHashMap<>();
        for (String name : new String[] {SettingsRepository.PREFERENCES_NAME, "subhub_onboarding", "subhub_privacy"}) {
            SharedPreferences prefs = context.getSharedPreferences(name, 0);
            before.put(prefs, prefs.getAll());
        }
        Set<String> selected = Set.of("com.android.chrome", "com.android.settings");
        for (String app : selected) {
            try { context.getPackageManager().getApplicationInfo(app, 0); }
            catch (android.content.pm.PackageManager.NameNotFoundException missing) {
                throw new AssertionError("Assignment fixture app missing: " + app, missing);
            }
        }
        OnboardingState.complete(context);
        ControllerPinManager.setPin(context, "2468");
        ControllerPinManager.enterSubMode();
        context.getSharedPreferences("subhub_privacy", 0).edit().putBoolean("app_lock", false).commit();
        new FeatureModuleManager(context).save(true, true, true, true);
        AppModeManager apps = new AppModeManager(context);
        apps.setAllAppsEnabled(false);
        apps.save(false, selected);
        new AppTimerManager(context).saveSettings(true, 30, true, 60);
        new AppTimerManager(context).saveAllowances(selected,
                Map.of("com.android.chrome", 15, "com.android.settings", 40));
        try (ActivityScenario<com.subhub.app.people.ContractActivity> page =
                ActivityScenario.launch(com.subhub.app.people.ContractActivity.class)) {
            com.subhub.app.people.LocalContractDetails descriptions = new com.subhub.app.people.LocalContractDetails(context);
            for (String section : new String[] {"censor", "limits", "wallet", "atmosphere"}) {
                String details = descriptions.forSection(section);
                assertFalse(details.contains(context.getString(R.string.arrangement_apps)));
                if ("limits".equals(section)) {
                    assertTrue(details.contains("Chrome · 15 min"));
                    assertTrue(details.contains("Settings · 40 min"));
                } else {
                    assertFalse(details.contains("Chrome"));
                    assertFalse(details.contains("Settings"));
                }
                assertFalse(details.isEmpty());
            }
            long until = android.os.SystemClock.uptimeMillis() + 5000;
            java.util.concurrent.atomic.AtomicBoolean rendered = new java.util.concurrent.atomic.AtomicBoolean();
            while (!rendered.get() && android.os.SystemClock.uptimeMillis() < until) {
                page.onActivity(activity -> rendered.set(text(activity.findViewById(R.id.role_page_body))
                        .contains(activity.getString(R.string.atmosphere_title))));
                if (!rendered.get()) android.os.SystemClock.sleep(25);
            }
            assertTrue("Contract did not render", rendered.get());
            page.onActivity(activity -> {
                String details = text(activity.findViewById(R.id.role_page_body));
                com.subhub.app.people.PersonSnapshot person = new com.subhub.app.people.LocalPersonRepository(activity)
                        .load(com.subhub.app.people.PersonId.LOCAL);
                for (com.subhub.app.people.PersonSnapshot.Section section : person.sections)
                    assertTrue(details.contains(section.details));
                for (String app : selected) {
                    try {
                        String label = context.getPackageManager().getApplicationLabel(
                                context.getPackageManager().getApplicationInfo(app, 0)).toString();
                        assertTrue(details.contains(label));
                    } catch (android.content.pm.PackageManager.NameNotFoundException missing) {
                        throw new AssertionError(missing);
                    }
                }
                assertFalse(containsEditableInput(activity.findViewById(R.id.role_page_body)));
            });
            assertEquals(selected, apps.getSelectedPackages());
            assertEquals(15, new AppTimerManager(context).allowanceMinutes("com.android.chrome"));
            assertEquals(40, new AppTimerManager(context).allowanceMinutes("com.android.settings"));
        } finally {
            for (Map.Entry<SharedPreferences, Map<String, ?>> entry : before.entrySet())
                SharedPreferenceTestRestore.restore(entry.getKey(), entry.getValue());
            ControllerPinManager.enterSubMode();
        }
    }
    private static boolean containsEditableInput(View view) {
        if (view instanceof android.widget.EditText || view instanceof android.widget.CompoundButton
                || view instanceof android.widget.Spinner) return true;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
            if (containsEditableInput(((ViewGroup) view).getChildAt(i))) return true;
        return false;
    }
    private static String text(View view) {
        String value = view instanceof TextView ? ((TextView) view).getText().toString() : "";
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
            value += "\n" + text(((ViewGroup) view).getChildAt(i));
        return value;
    }
}
