package com.subhub.app.people;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.*;
import static androidx.test.espresso.matcher.ViewMatchers.withTagValue;
import static com.subhub.app.NativeUiActions.revealAboveNavigation;
import static org.junit.Assert.*;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.view.View;
import android.widget.EditText;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.R;
import com.subhub.app.onboarding.OnboardingState;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.security.DeviceRolePreferences;
import com.subhub.app.security.KeyholderPresentationAndroidTest;
import com.subhub.app.settings.SettingsRepository;
import com.subhub.app.settings.SharedPreferenceTestRestore;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public final class ProfileDetailsAndroidTest {
    @Rule public final KeyholderPresentationAndroidTest.IntegrationFixture integration =
            new KeyholderPresentationAndroidTest.IntegrationFixture();
    @Rule public final com.subhub.app.UiAutomationStateRule automation = new com.subhub.app.UiAutomationStateRule();
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private Map<String, ?> before;

    @Before public void prepare() {
        before = new LinkedHashMap<>(context.getSharedPreferences(ProfileNames.STORE, 0).getAll());
        new SettingsRepository(context).preferences().edit().putString(DeviceRolePreferences.KEY_MODE,
                DeviceRolePreferences.Mode.BOTH.name()).commit();
        assertTrue(ControllerPinManager.setPin(context, "2468")); ControllerPinManager.enterSubMode();
        OnboardingState.complete(context);
    }
    @After public void restore() {
        SharedPreferenceTestRestore.restore(context.getSharedPreferences(ProfileNames.STORE, 0), before);
    }

    @Test public void optionalDetailsAreAtomicRoleSeparatedAndRetainedThroughRecreationAndLegacySaves() {
        context.getSharedPreferences(ProfileNames.STORE, 0).edit().remove("sub_gender").remove("sub_age")
                .remove("dom_gender").remove("dom_age").commit();
        assertTrue(ProfileStore.save(context, false, "Avery", "Saved bio", ""));
        assertEquals("", ProfileStore.load(context, false).gender); assertEquals(0, ProfileStore.load(context, false).age);
        try (ActivityScenario<ProfileEditorActivity> editor = launch(false)) {
            editor.onActivity(a -> {
                assertFalse(a.getCurrentFocus() instanceof EditText);
                assertEquals("", field(a, "profile_gender").getText().toString());
                assertEquals("", field(a, "profile_age").getText().toString());
                assertNull(a.findViewById(android.R.id.content).findViewWithTag("profile_name:dom"));
            });
            onView(tag("profile_gender")).perform(revealAboveNavigation(), click(), replaceText("Non-binary"), closeSoftKeyboard());
            onView(tag("profile_age")).perform(revealAboveNavigation(), click(), replaceText("29"), closeSoftKeyboard());
            editor.recreate();
            editor.onActivity(a -> {
                assertFalse(a.getCurrentFocus() instanceof EditText);
                assertEquals("Non-binary", field(a, "profile_gender").getText().toString());
                assertEquals("29", field(a, "profile_age").getText().toString());
            });
            assertEquals(0, ProfileStore.load(context, false).age);
            tap("profile_save");
        }
        assertEquals(29, ProfileStore.load(context, false).age);
        assertEquals("Non-binary", ProfileStore.load(context, false).gender);
        ControllerPinManager.enterDomMode();
        assertTrue(ProfileStore.save(context, true, "Rowan", "Other role bio", "", "Woman", 34));
        ControllerPinManager.enterSubMode();
        assertTrue(ProfileStore.save(context, false, "Renamed Avery", "Changed bio", ""));
        assertEquals(29, ProfileStore.load(context, false).age);
        assertEquals("Non-binary", ProfileStore.load(context, false).gender);
        try (ActivityScenario<ProfileEditorActivity> editor = launch(false)) {
            onView(tag("profile_gender")).perform(revealAboveNavigation(), replaceText("Discarded"), closeSoftKeyboard());
            onView(tag("profile_age")).perform(revealAboveNavigation(), replaceText("31"), closeSoftKeyboard()); tap("profile_cancel");
        }
        assertEquals(29, ProfileStore.load(context, false).age);
        assertEquals("Non-binary", ProfileStore.load(context, false).gender);
        try (ActivityScenario<ProfileEditorActivity> editor = launch(false)) {
            onView(tag("profile_gender")).perform(revealAboveNavigation(), replaceText(""), closeSoftKeyboard());
            onView(tag("profile_age")).perform(revealAboveNavigation(), replaceText(""), closeSoftKeyboard()); tap("profile_save");
        }
        assertEquals(0, ProfileStore.load(context, false).age); assertEquals("", ProfileStore.load(context, false).gender);
        assertEquals(34, ProfileStore.load(context, true).age); assertEquals("Woman", ProfileStore.load(context, true).gender);
    }

    @Test public void invalidAgeDoesNotPartiallySaveAndBothInputsStayBounded() {
        assertTrue(ProfileStore.save(context, false, "Avery", "Saved bio", "", "Non-binary", 29));
        try (ActivityScenario<ProfileEditorActivity> editor = launch(false)) {
            onView(tag("profile_name:sub")).perform(revealAboveNavigation(), replaceText("Unsaved"), closeSoftKeyboard());
            for (String invalid : new String[]{"0", "131", "999"}) {
                onView(tag("profile_age")).perform(revealAboveNavigation(), replaceText(invalid), closeSoftKeyboard()); tap("profile_save");
                editor.onActivity(a -> { assertFalse(a.isFinishing()); assertEquals(a.getString(R.string.profile_age_invalid), field(a, "profile_age").getError().toString()); });
                assertEquals("Avery", ProfileStore.load(context, false).name); assertEquals(29, ProfileStore.load(context, false).age);
            }
            editor.onActivity(a -> {
                field(a, "profile_gender").setText("x".repeat(41)); assertEquals(40, field(a, "profile_gender").length());
                field(a, "profile_age").setText("9999"); assertEquals(3, field(a, "profile_age").length());
            });
            tap("profile_cancel");
        }
        assertEquals("Non-binary", ProfileStore.load(context, false).gender);
        assertEquals(29, ProfileStore.load(context, false).age);
    }

    @Test public void deniedSaveDoesNotReadTheOtherRoleProfileAndSettingsHasNoDuplicateFields() {
        final int[] privateReads = {0};
        Context observed = new android.content.ContextWrapper(context) {
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                if (ProfileNames.STORE.equals(name)) { privateReads[0]++; throw new AssertionError("Forbidden role profile read"); }
                return super.getSharedPreferences(name, mode);
            }
        };
        assertFalse(ProfileStore.save(observed, true, "Forbidden", "", ""));
        assertFalse(ProfileStore.save(observed, true, "Forbidden", "", "", "", 0));
        assertEquals(0, privateReads[0]);
        try (ActivityScenario<com.subhub.app.settings.GlobalSettingsActivity> settings = ActivityScenario.launch(com.subhub.app.settings.GlobalSettingsActivity.class)) {
            settings.onActivity(a -> {
                assertNull(a.findViewById(R.id.profile_gender_input)); assertNull(a.findViewById(R.id.profile_age_input));
                assertNull(a.findViewById(android.R.id.content).findViewWithTag("profile_gender"));
            });
        }
    }

    private ActivityScenario<ProfileEditorActivity> launch(boolean dom) {
        return ActivityScenario.launch(new Intent(context, ProfileEditorActivity.class).putExtra(ProfileEditorActivity.EXTRA_DOM, dom));
    }
    private static EditText field(ProfileEditorActivity activity, String tag) { return (EditText) activity.findViewById(android.R.id.content).findViewWithTag(tag); }
    private static org.hamcrest.Matcher<View> tag(String value) { return withTagValue(org.hamcrest.Matchers.is((Object) value)); }
    private static void tap(String tag) { onView(tag(tag)).perform(revealAboveNavigation(), click()); }
}
