package com.subhub.app.help;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.content.SharedPreferences;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.subhub.app.MainActivity;
import com.subhub.app.R;
import com.subhub.app.appmode.AppModeManager;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.settings.SettingsRepository;
import com.subhub.app.util.AppShortcuts;
import com.subhub.app.util.LocaleHelper;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

@RunWith(AndroidJUnit4.class)
public final class HelpAndLocaleTest {
    private Context context;
    private SharedPreferences preferences;

    @Before public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        preferences = context.getSharedPreferences(
                SettingsRepository.PREFERENCES_NAME, Context.MODE_PRIVATE);
        preferences.edit().remove("has_seen_onboarding")
                .putString(LocaleHelper.KEY_LANGUAGE, "system").commit();
        LocaleHelper.applySaved(context);
    }

    @After public void tearDown() {
        preferences.edit().remove("has_seen_onboarding")
                .putString(LocaleHelper.KEY_LANGUAGE, "system").commit();
        LocaleHelper.applySaved(context);
    }

    @Test public void recoveredLanguageSetIsAvailableAndPersisted() {
        assertEquals(Arrays.asList("system", "en", "fr", "es", "pt", "de", "ja",
                "zh-CN", "zh-TW", "ko", "ru"), LocaleHelper.SUPPORTED);
        preferences.edit().putString(LocaleHelper.KEY_LANGUAGE, "de").commit();
        assertEquals("de", LocaleHelper.getLanguage(context));
        preferences.edit().putString(LocaleHelper.KEY_LANGUAGE, "not-a-locale").commit();
        assertEquals("system", LocaleHelper.getLanguage(context));
    }

    @Test public void partialLocalesKeepMasterActionBrandingAndMatchingHelp() {
        for (String language : LocaleHelper.SUPPORTED) {
            if ("system".equals(language)) continue;
            Configuration configuration = new Configuration(context.getResources()
                    .getConfiguration());
            configuration.setLocale(Locale.forLanguageTag(language));
            Context localized = context.createConfigurationContext(configuration);
            assertEquals("Enter Service", localized.getString(R.string.start_protection));
            assertEquals("Leave Service", localized.getString(R.string.stop_protection));
            assertEquals(localized.getString(R.string.start_protection),
                    localized.getString(R.string.shortcut_start_protection));
            assertTrue(localized.getString(R.string.help_body_started).contains("Enter Service"));
            assertTrue(localized.getString(R.string.help_body_service_timers)
                    .contains("Leave Service"));
            assertTrue(localized.getString(R.string.help_body_app_assignment)
                    .contains("Subliminal"));
        }
    }

    @Test public void helpScreenExposesRepairLanguageAndCurrentGuides() {
        try (ActivityScenario<HelpActivity> scenario =
                     ActivityScenario.launch(HelpActivity.class)) {
            scenario.onActivity(activity -> {
                assertEquals(activity.getString(R.string.help_title),
                        ((TextView) activity.findViewById(R.id.primary_header_title))
                                .getText().toString());
                assertNotNull(activity.findViewById(R.id.button_fix_permissions));
                assertNotNull(activity.findViewById(R.id.button_accessibility));
                assertNotNull(activity.findViewById(R.id.button_language));
                LinearLayout sections = activity.findViewById(R.id.help_sections);
                assertEquals(15, sections.getChildCount());
            });
        }
    }

    @Test public void onboardingIsVisibleInBothModesAndDismissalPersists() {
        Intent home = new Intent(context, MainActivity.class).setAction(Intent.ACTION_MAIN)
                .putExtra(MainActivity.EXTRA_SUPPRESS_PERMISSION_READINESS, true);
        try {
            for (boolean dom : new boolean[] {true, false}) {
                preferences.edit().remove("has_seen_onboarding").commit();
                if (dom) ControllerPinManager.enterDomMode();
                else ControllerPinManager.enterSubMode();
                try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(home)) {
                    scenario.onActivity(activity -> {
                        View card = activity.findViewById(R.id.onboarding_card);
                        assertTrue("First-run setup must actually be shown", card.isShown());
                        View permission = activity.findViewById(R.id.permission_card);
                        assertEquals(permission.getParent(), card.getParent());
                        android.view.ViewGroup command = (android.view.ViewGroup) card.getParent();
                        assertTrue(command.indexOfChild(card) < command.indexOfChild(permission));
                        int minimum = activity.getResources()
                                .getDimensionPixelSize(R.dimen.control_min_height);
                        for (int id : new int[] {R.id.onboarding_help, R.id.onboarding_dismiss}) {
                            View action = activity.findViewById(id);
                            assertTrue(action.isShown());
                            assertTrue(action.getHeight() >= minimum);
                            assertTrue(action.getWidth() >= minimum);
                        }
                        boolean armed = new AppModeManager(context).isArmed();
                        activity.findViewById(R.id.onboarding_dismiss).performClick();
                        assertEquals(View.GONE, card.getVisibility());
                        assertTrue(preferences.getBoolean("has_seen_onboarding", false));
                        assertEquals("Continue must not toggle service", armed,
                                new AppModeManager(context).isArmed());
                    });
                }
                try (ActivityScenario<MainActivity> reopened = ActivityScenario.launch(home)) {
                    reopened.onActivity(activity -> assertEquals(View.GONE,
                            activity.findViewById(R.id.onboarding_card).getVisibility()));
                }
            }
        } finally {
            ControllerPinManager.enterSubMode();
        }
    }

    @Test public void launcherShortcutRoutesToTheMasterServiceControl() {
        AppShortcuts.install(context);
        ShortcutManager manager = context.getSystemService(ShortcutManager.class);
        assertNotNull(manager);
        List<String> ids = manager.getDynamicShortcuts().stream()
                .map(ShortcutInfo::getId).collect(Collectors.toList());
        assertTrue(ids.contains("start_protection"));
        assertFalse(ids.contains("open_browser"));
        assertEquals(1, ids.size());
        assertEquals(AppShortcuts.ACTION_START_PROTECTION,
                manager.getDynamicShortcuts().stream()
                        .filter(item -> "start_protection".equals(item.getId()))
                        .findFirst().orElseThrow().getIntent().getAction());
    }
}
