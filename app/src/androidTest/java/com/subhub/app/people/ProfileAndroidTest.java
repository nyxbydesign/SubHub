package com.subhub.app.people;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.*;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static com.subhub.app.NativeUiActions.revealAboveNavigation;
import static org.junit.Assert.*;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.filters.SdkSuppress;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.R;
import com.subhub.app.UiAutomationStateRule;
import com.subhub.app.atmosphere.GagActivity;
import com.subhub.app.atmosphere.GagParticipation;
import com.subhub.app.onboarding.OnboardingState;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.security.DeviceRolePreferences;
import com.subhub.app.security.KeyholderPresentationAndroidTest;
import com.subhub.app.security.SubFeaturePolicy;
import com.subhub.app.settings.SettingsRepository;
import com.subhub.app.settings.SharedPreferenceTestRestore;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public final class ProfileAndroidTest {
    @Rule public final KeyholderPresentationAndroidTest.IntegrationFixture integration =
            new KeyholderPresentationAndroidTest.IntegrationFixture();
    @Rule public final UiAutomationStateRule automation = new UiAutomationStateRule();
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final Map<String, Map<String, ?>> before = new LinkedHashMap<>();

    @Before public void prepare() {
        assertFalse(com.subhub.app.people.LocalSessionControls.isActive(context));
        for (String store : new String[]{"subhub_profile_names", SubFeaturePolicy.STORE,
                GagParticipation.STORE, "betablocker_achievements"})
            before.put(store, new LinkedHashMap<>(context.getSharedPreferences(store, 0).getAll()));
        new SettingsRepository(context).preferences().edit().putString(DeviceRolePreferences.KEY_MODE,
                DeviceRolePreferences.Mode.BOTH.name()).commit();
        assertTrue(ControllerPinManager.setPin(context, "2468"));
        ControllerPinManager.enterSubMode();
        OnboardingState.complete(context);
        context.getSharedPreferences(SubFeaturePolicy.STORE, 0).edit().putBoolean("censor", true).commit();
    }

    @After public void restore() {
        for (Map.Entry<String, Map<String, ?>> entry : before.entrySet())
            SharedPreferenceTestRestore.restore(context.getSharedPreferences(entry.getKey(), 0), entry.getValue());
    }

    @Test public void profileNavigationRetainsStatisticsAndAchievementsInTheSameHost() {
        try (ActivityScenario<ProfileActivity> scenario = ActivityScenario.launch(ProfileActivity.class)) {
            AtomicReference<Activity> host = new AtomicReference<>();
            AtomicReference<View> daily = new AtomicReference<>();
            scenario.onActivity(a -> {
                host.set(a);
                assertEquals(a.getString(R.string.role_profile), ((TextView) a.findViewById(R.id.nav_stats_label)).getText());
                assertTrue(a.findViewById(R.id.nav_stats).isSelected());
                ViewGroup root = a.findViewById(android.R.id.content);
                assertTrue(root.findViewWithTag("self_profile:sub").isShown());
                assertNull(a.findViewById(R.id.stats_section_statistics));
                View profile = root.findViewWithTag("self_profile");
                View stats = a.findViewById(R.id.stats_lifetime_summary);
                assertNotNull(stats);
                ViewGroup shell = (ViewGroup) profile.getParent();
                assertEquals(shell.indexOfChild(profile) + 1, shell.indexOfChild((View) stats.getParent()));
            });
            for (int repeat = 0; repeat < 3; repeat++) {
                onView(withId(R.id.stats_section_profile)).perform(revealAboveNavigation(), click());
                await(scenario, a -> a.findViewById(R.id.daily_range_7) != null && a.findViewById(R.id.daily_range_7).isShown());
                if (repeat == 0) {
                    scenario.onActivity(a -> daily.set(a.findViewById(R.id.daily_statistics_panel)));
                    onView(withId(R.id.daily_range_7)).perform(revealAboveNavigation(), click());
                    await(scenario, a -> a.findViewById(R.id.daily_range_7) != null && a.findViewById(R.id.daily_range_7).isSelected());
                }
                scenario.onActivity(a -> {
                    assertSame(host.get(), a); assertTrue(a.findViewById(R.id.stats_lifetime_summary).isShown());
                    assertSame(daily.get(), a.findViewById(R.id.daily_statistics_panel));
                    assertTrue(a.findViewById(R.id.daily_range_7).isSelected());
                    assertEquals(a.getString(R.string.role_profile), ((TextView) a.findViewById(R.id.primary_header_title)).getText());
                });
                onView(withId(R.id.stats_section_achievements)).perform(revealAboveNavigation(), click());
                scenario.onActivity(a -> {
                    assertSame(host.get(), a); assertTrue(a.findViewById(R.id.stats_section_achievements).isSelected());
                    assertEquals(a.getString(R.string.achievements_title), ((TextView) a.findViewById(R.id.primary_header_title)).getText());
                });
                onView(withId(R.id.nav_stats)).perform(click());
                scenario.onActivity(a -> {
                    assertSame(host.get(), a); assertTrue(a.findViewById(R.id.stats_section_profile).isSelected());
                    assertTrue(a.findViewById(android.R.id.content).findViewWithTag("self_profile:sub").isShown());
                });
            }
            scenario.recreate();
            scenario.onActivity(a -> assertTrue(a.findViewById(R.id.stats_section_profile).isSelected()));
            onView(withId(R.id.stats_section_profile)).perform(revealAboveNavigation(), click());
            await(scenario, a -> a.findViewById(R.id.daily_range_7) != null && a.findViewById(R.id.daily_range_7).isSelected());
        }
    }

    @Test public void editsSaveAtomicallyAndCancelledDraftsLeaveBothRoleProfilesUnchanged() {
        assertTrue(ProfileStore.save(context, false, "Saved Sub", "Original Sub bio", ""));
        ControllerPinManager.enterDomMode();
        assertTrue(ProfileStore.save(context, true, "Saved Dom", "Original Dom bio", ""));
        ControllerPinManager.enterSubMode();
        Intent edit = new Intent(context, ProfileEditorActivity.class).putExtra(ProfileEditorActivity.EXTRA_DOM, false);
        try (ActivityScenario<ProfileEditorActivity> scenario = ActivityScenario.launch(edit)) {
            scenario.onActivity(a -> assertFalse(a.getCurrentFocus() instanceof EditText));
            scenario.recreate();
            scenario.onActivity(a -> assertFalse(a.getCurrentFocus() instanceof EditText));
            onView(tag("profile_name:sub")).perform(revealAboveNavigation(), click(), replaceText("Updated Sub"), closeSoftKeyboard());
            onView(tag("profile_about")).perform(revealAboveNavigation(), replaceText("A new bio\nSecond paragraph"), closeSoftKeyboard());
            scenario.recreate();
            scenario.onActivity(a -> assertEquals("Updated Sub", ((EditText) a.findViewById(android.R.id.content)
                    .findViewWithTag("profile_name:sub")).getText().toString()));
            assertEquals("Saved Sub", ProfileStore.load(context, false).name);
            onView(tag("profile_save")).perform(revealAboveNavigation(), click());
        }
        assertEquals("Updated Sub", ProfileStore.load(context, false).name);
        assertEquals("A new bio\nSecond paragraph", ProfileStore.load(context, false).about);
        assertEquals("Saved Dom", ProfileStore.load(context, true).name);
        assertEquals("Original Dom bio", ProfileStore.load(context, true).about);
        assertFalse(ControllerPinManager.isDomModeActive());
        try (ActivityScenario<ProfileEditorActivity> ignored = ActivityScenario.launch(edit)) {
            onView(tag("profile_name:sub")).perform(revealAboveNavigation(), replaceText("Discarded"), closeSoftKeyboard());
            onView(tag("profile_cancel")).perform(revealAboveNavigation(), click());
        }
        assertEquals("Updated Sub", ProfileStore.load(context, false).name);
        assertFalse(ProfileStore.save(context, true, "Wrong owner", "", ""));
        ControllerPinManager.enterDomMode();
        assertFalse(ProfileStore.save(context, false, "Wrong owner", "", ""));
    }

    @Test public void emptyAboutMeIsOptionalAndOnlyChangesOnSaveForEitherRole() throws Exception {
        String avatar = profileImage(Color.rgb(67, 135, 208));
        for (boolean dom : new boolean[]{false, true}) {
            if (dom) ControllerPinManager.enterDomMode(); else ControllerPinManager.enterSubMode();
            assertTrue(ProfileStore.save(context, dom, "Keep this name", "Original biography", avatar, "Fixture gender", 29));
            ProfileData original = ProfileStore.load(context, dom), other = ProfileStore.load(context, !dom);
            Intent edit = new Intent(context, ProfileEditorActivity.class).putExtra(ProfileEditorActivity.EXTRA_DOM, dom);
            try (ActivityScenario<ProfileEditorActivity> scenario = ActivityScenario.launch(edit)) {
                scenario.onActivity(a -> {
                    assertProfileFieldLabels(a, dom);
                    java.util.ArrayList<View> obsolete = new java.util.ArrayList<>();
                    a.findViewById(android.R.id.content).findViewsWithText(obsolete,
                            a.getString(R.string.profile_optional_visibility), View.FIND_VIEWS_WITH_TEXT);
                    assertTrue("Removed sharing subtext must stay absent", obsolete.isEmpty());
                });
                onView(tag("profile_about")).perform(revealAboveNavigation(), replaceText(""), closeSoftKeyboard());
                scenario.recreate();
                scenario.onActivity(a -> {
                    assertProfileFieldLabels(a, dom);
                    assertFalse(a.getCurrentFocus() instanceof EditText);
                    assertEquals("", ((EditText) a.findViewById(android.R.id.content)
                            .findViewWithTag("profile_about")).getText().toString());
                });
                assertEquals(original, ProfileStore.load(context, dom));
                onView(tag("profile_cancel")).perform(revealAboveNavigation(), click());
            }
            assertEquals(original, ProfileStore.load(context, dom));
            try (ActivityScenario<ProfileEditorActivity> scenario = ActivityScenario.launch(edit)) {
                onView(tag("profile_about")).perform(revealAboveNavigation(), replaceText(""), closeSoftKeyboard());
                onView(tag("profile_save")).perform(revealAboveNavigation(), click());
            }
            ProfileData saved = ProfileStore.load(context, dom);
            assertEquals("", saved.about);
            assertEquals(original.name, saved.name); assertEquals(original.avatar, saved.avatar);
            assertEquals(original.gender, saved.gender); assertEquals(original.age, saved.age);
            assertEquals(other, ProfileStore.load(context, !dom));
            try (ActivityScenario<ProfileEditorActivity> scenario = ActivityScenario.launch(edit)) {
                scenario.onActivity(a -> assertEquals("", ((TextView) a.findViewById(android.R.id.content)
                        .findViewWithTag("profile_about")).getText().toString()));
            }
        }
    }

    private static void assertProfileFieldLabels(ProfileEditorActivity activity, boolean dom) {
        View root = activity.findViewById(android.R.id.content);
        TextView name = root.findViewWithTag("profile_name_label");
        TextView about = root.findViewWithTag("profile_about_label");
        assertEquals(activity.getString(dom ? R.string.profile_dom_name : R.string.profile_sub_name),
                name.getText().toString());
        assertEquals(activity.getString(R.string.profile_about_optional), about.getText().toString());
        assertProfileFieldLabel(name, root.findViewWithTag(dom ? "profile_name:dom" : "profile_name:sub"), root);
        assertProfileFieldLabel(about, root.findViewWithTag("profile_about"), root);
    }

    private static void assertProfileFieldLabel(TextView label, EditText input, View root) {
        assertNotEquals(View.NO_ID, input.getId());
        assertSame(input, root.findViewById(label.getLabelFor()));
        assertNull(input.getHint());
        assertNull(input.getContentDescription());
        assertFalse(input.isSaveEnabled());
    }

    @Test @SdkSuppress(minSdkVersion = 29)
    public void profileEditorPairsActionsAndKeepsAvatarRemovalAsDraft() throws Exception {
        String avatar = profileImage(Color.rgb(67, 135, 208));
        assertTrue(ProfileStore.save(context, false, "Layout fixture", "", avatar));
        Intent edit = new Intent(context, ProfileEditorActivity.class).putExtra(ProfileEditorActivity.EXTRA_DOM, false);
        try (ActivityScenario<ProfileEditorActivity> scenario = ActivityScenario.launch(edit)) {
            onView(tag("profile_avatar_actions")).perform(revealAboveNavigation());
            settleNativeFrame(scenario);
            scenario.onActivity(a -> assertProfileActionPair(a, "profile_avatar_actions",
                    "profile_avatar_change", "profile_avatar_remove"));
            capture(scenario, "editor-avatar-action-row").recycle();
            onView(tag("profile_form_actions")).perform(revealAboveNavigation());
            settleNativeFrame(scenario);
            scenario.onActivity(a -> assertProfileActionPair(a, "profile_form_actions", "profile_save", "profile_cancel"));
            capture(scenario, "editor-save-cancel-row").recycle();
            onView(tag("profile_avatar_remove")).perform(revealAboveNavigation(), click());
            assertEquals(avatar, ProfileStore.load(context, false).avatar);
            scenario.recreate();
            await(scenario, a -> {
                View change = a.findViewById(android.R.id.content).findViewWithTag("profile_avatar_change");
                return change.isLaidOut() && change.getWidth() > 0;
            });
            scenario.onActivity(a -> {
                View root = a.findViewById(android.R.id.content);
                View change = root.findViewWithTag("profile_avatar_change"), remove = root.findViewWithTag("profile_avatar_remove");
                assertEquals(View.GONE, remove.getVisibility());
                assertEquals(((View) change.getParent()).getWidth(), change.getWidth());
            });
            onView(tag("profile_cancel")).perform(revealAboveNavigation(), click());
        }
        assertEquals(avatar, ProfileStore.load(context, false).avatar);
    }

    private static void assertProfileActionPair(Activity activity, String rowTag, String topTag, String bottomTag) {
        View root = activity.findViewById(android.R.id.content);
        ProfileActionRow row = root.findViewWithTag(rowTag);
        TextView top = root.findViewWithTag(topTag), bottom = root.findViewWithTag(bottomTag);
        assertSame(row, top.getParent()); assertSame(row, bottom.getParent());
        if (row.getOrientation() == android.widget.LinearLayout.HORIZONTAL) {
            assertSame(bottom, row.getChildAt(0)); assertSame(top, row.getChildAt(1));
            assertTrue(Math.abs(top.getWidth() - bottom.getWidth()) <= 1);
            assertEquals(1, top.getLineCount()); assertEquals(1, bottom.getLineCount());
        } else {
            assertSame(top, row.getChildAt(0)); assertSame(bottom, row.getChildAt(1));
        }
        assertTrue(top.getHeight() >= PersonUi.dp(activity, 48));
        assertTrue(bottom.getHeight() >= PersonUi.dp(activity, 48));
        textFits(top); textFits(bottom);
    }

    @Test public void subParticipationRemainsAccessibleWithoutDomUnlockAndSurvivesRecreation() {
        java.util.Map<String, ?> baseline = new LinkedHashMap<>(context.getSharedPreferences(SubFeaturePolicy.STORE, 0).getAll());
        try (ActivityScenario<ProfileActivity> scenario = ActivityScenario.launch(ProfileActivity.class)) {
            scenario.onActivity(a -> {
                assertReadOnly(a.findViewById(android.R.id.content).findViewWithTag("self_profile_features"));
                assertNull(a.findViewById(android.R.id.content).findViewWithTag("sub_feature:censor"));
            });
            for (boolean back : new boolean[]{false, true}) {
                onView(tag("profile_edit")).perform(revealAboveNavigation(), click());
                resumed(ProfileEditorActivity.class);
                onView(tag("sub_feature:censor")).perform(revealAboveNavigation(), click());
                assertEquals(baseline, context.getSharedPreferences(SubFeaturePolicy.STORE, 0).getAll());
                if (back) androidx.test.espresso.Espresso.pressBack();
                else onView(tag("profile_cancel")).perform(revealAboveNavigation(), click());
                resumed(ProfileActivity.class);
                assertEquals(baseline, context.getSharedPreferences(SubFeaturePolicy.STORE, 0).getAll());
            }
            onView(tag("profile_edit")).perform(revealAboveNavigation(), click());
            ProfileEditorActivity editor = resumed(ProfileEditorActivity.class);
            onView(tag("sub_feature:censor")).perform(revealAboveNavigation(), click());
            InstrumentationRegistry.getInstrumentation().runOnMainSync(editor::recreate);
            ProfileEditorActivity recreated = resumed(ProfileEditorActivity.class);
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> assertFalse(((CompoundButton)
                    recreated.findViewById(android.R.id.content).findViewWithTag("sub_feature:censor")).isChecked()));
            assertEquals(baseline, context.getSharedPreferences(SubFeaturePolicy.STORE, 0).getAll());
            onView(tag("profile_save")).perform(revealAboveNavigation(), click());
            resumed(ProfileActivity.class);
            assertFalse(SubFeaturePolicy.chosen(context, "censor"));
            assertFalse(ControllerPinManager.isDomModeActive());
            scenario.recreate();
            scenario.onActivity(a -> assertReadOnly(a.findViewById(android.R.id.content).findViewWithTag("self_profile_features")));
        }
    }

    @Test @SdkSuppress(minSdkVersion = 29)
    public void subProfilePlacesFeatureChoicesBeforeJournalAndKeepsTasksConsentLocal() throws Exception {
        assertTrue(ProfileStore.save(context, false, "Milo", "A steady routine and clear communication.", ""));
        assertTrue(context.getSharedPreferences(SubFeaturePolicy.STORE, 0).edit().putBoolean("tasks", true).commit());
        try (ActivityScenario<ProfileActivity> scenario = ActivityScenario.launch(ProfileActivity.class)) {
            scenario.onActivity(a -> {
                View root = a.findViewById(android.R.id.content); ViewGroup hero = root.findViewWithTag("self_profile:sub");
                View features = root.findViewWithTag("self_profile_features"); View preview = root.findViewWithTag("profile_preview:sub");
                View journal = root.findViewWithTag("profile_journal");
                while (journal.getParent() != hero) journal = (View) journal.getParent();
                assertTrue(hero.indexOfChild(preview) < hero.indexOfChild(features));
                assertTrue(hero.indexOfChild(features) < hero.indexOfChild(journal));
                assertReadOnly(features);
                assertEquals(7, ((ViewGroup) root.findViewWithTag("feature_consent_grid")).getChildCount());
                assertNotNull(root.findViewWithTag("person_allowance:gag"));
                assertNull(root.findViewWithTag("profile_gag_setup"));
            });
            onView(tag("person_allowance:censor")).perform(revealAboveNavigation()); capture(scenario,"profile-readonly-choices").recycle();
            onView(tag("profile_edit")).perform(revealAboveNavigation(), click()); resumed(ProfileEditorActivity.class);
            onView(tag("sub_feature:tasks")).perform(revealAboveNavigation(), click());
            assertTrue(SubFeaturePolicy.chosen(context, "tasks"));
            onView(tag("profile_save")).perform(revealAboveNavigation(), click()); resumed(ProfileActivity.class);
            assertFalse(SubFeaturePolicy.chosen(context, "tasks"));
            assertFalse(new com.subhub.app.tasks.TaskRepository(context, PersonId.LOCAL).allowed());
            onView(tag("profile_edit")).perform(revealAboveNavigation(), click()); resumed(ProfileEditorActivity.class);
            onView(tag("sub_feature:tasks")).perform(revealAboveNavigation(), click());
            long beforeSave = System.currentTimeMillis();
            assertFalse(SubFeaturePolicy.chosen(context, "tasks"));
            onView(tag("profile_save")).perform(revealAboveNavigation(), click()); resumed(ProfileActivity.class);
            assertTrue(SubFeaturePolicy.chosen(context, "tasks"));
            assertTrue(context.getSharedPreferences(SubFeaturePolicy.STORE, 0).getLong("tasks_resume_after", 0) >= beforeSave);
            onView(tag("profile_edit")).perform(revealAboveNavigation(), click());
            ProfileEditorActivity editor = resumed(ProfileEditorActivity.class);
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                CompoundButton stale = editor.findViewById(android.R.id.content).findViewWithTag("sub_feature:tasks");
                ControllerPinManager.enterDomMode(); stale.performClick();
                editor.findViewById(android.R.id.content).findViewWithTag("profile_save").performClick();
                assertTrue(SubFeaturePolicy.chosen(context, "tasks")); ControllerPinManager.enterSubMode();
            });
            onView(tag("profile_cancel")).perform(revealAboveNavigation(), click()); resumed(ProfileActivity.class);
        }
    }

    @Test public void gagParticipationUsesThePreviousChoiceAndSaveOpensOnlyKeyboardSetup() {
        assertTrue(context.getSharedPreferences(GagParticipation.STORE, 0).edit()
                .putBoolean("enabled", false).putString("replacements", "old local rule").commit());
        assertFalse(GagParticipation.allowed(context));
        try (ActivityScenario<ProfileActivity> profile = ActivityScenario.launch(ProfileActivity.class)) {
            onView(tag("profile_edit")).perform(revealAboveNavigation(), click());
            resumed(ProfileEditorActivity.class);
            onView(tag("sub_feature:gag")).check(androidx.test.espresso.assertion.ViewAssertions.matches(isNotChecked()));
            onView(tag("sub_feature:gag")).perform(revealAboveNavigation(), click());
            assertFalse(GagParticipation.allowed(context));
            onView(tag("profile_save")).perform(revealAboveNavigation(), click());
            GagActivity setup = resumed(GagActivity.class);
            assertTrue(GagParticipation.allowed(context));
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                assertTrue(setup.findViewById(R.id.gag_keyboard_settings).isClickable());
                assertNull(setup.findViewById(R.id.gag_modes));
                assertNull(setup.findViewById(R.id.gag_replacements));
                setup.finish();
            });
            resumed(ProfileActivity.class);
            onView(tag("profile_edit")).perform(revealAboveNavigation(), click());
            resumed(ProfileEditorActivity.class);
            onView(tag("sub_feature:gag")).perform(revealAboveNavigation(), click());
            onView(tag("profile_save")).perform(revealAboveNavigation(), click());
            resumed(ProfileActivity.class);
            assertFalse(GagParticipation.allowed(context));
            assertEquals("old local rule", context.getSharedPreferences(GagParticipation.STORE, 0)
                    .getString("replacements", ""));
        }
    }

    @Test public void gagSetupHasOneAndroidActionAndNoRuleControlsInTheSubWorkspace() {
        try (ActivityScenario<com.subhub.app.atmosphere.GagActivity> setup =
                     ActivityScenario.launch(com.subhub.app.atmosphere.GagActivity.class)) {
            setup.onActivity(activity -> {
                assertTrue(activity.findViewById(R.id.gag_keyboard_settings).isClickable());
                assertNull(activity.findViewById(R.id.gag_enabled));
                assertNull(activity.findViewById(R.id.gag_modes));
                assertNull(activity.findViewById(R.id.gag_replacements));
            });
        }
        ControllerPinManager.enterDomMode();
        boolean prior = GagParticipation.allowed(context);
        assertFalse(GagParticipation.setAllowed(context, !prior));
        assertEquals(prior, GagParticipation.allowed(context));
        try (ActivityScenario<com.subhub.app.atmosphere.GagActivity> denied =
                     ActivityScenario.launch(com.subhub.app.atmosphere.GagActivity.class)) {
            assertEquals(androidx.lifecycle.Lifecycle.State.DESTROYED, denied.getState());
        } finally {
            ControllerPinManager.enterSubMode();
        }
    }

    @Test public void nativeRoleSwitchesKeepDomAndSubIdentitySeparate() {
        assertTrue(ProfileStore.save(context, false, "Fixture Sub", "Sub bio", ""));
        ControllerPinManager.enterDomMode();
        assertTrue(ProfileStore.save(context, true, "Fixture Dom", "Dom bio", ""));
        ControllerPinManager.enterSubMode();
        try (ActivityScenario<ProfileActivity> scenario = ActivityScenario.launch(ProfileActivity.class)) {
            assertSelf(scenario, false, "Fixture Sub");
            onView(withId(R.id.button_edit_lock)).perform(click());
            onView(withHint(R.string.controller_pin_label)).perform(replaceText("2468"), closeSoftKeyboard());
            onView(withText(R.string.controller_pin_unlock)).perform(click());
            assertSelf(scenario, true, "Fixture Dom");
            scenario.recreate(); assertSelf(scenario, true, "Fixture Dom");
            onView(withId(R.id.button_edit_lock)).perform(click());
            assertSelf(scenario, false, "Fixture Sub");
            assertEquals("Dom bio", ProfileStore.load(context, true).about);
            assertEquals("Sub bio", ProfileStore.load(context, false).about);
        }
    }

    @Test public void returningToProfileAfterAnotherWorkspaceChangeUsesTheCurrentRoleOnFirstResume() {
        assertTrue(ProfileStore.save(context, false, "Returning Sub", "Sub identity", ""));
        ControllerPinManager.enterDomMode();
        assertTrue(ProfileStore.save(context, true, "Returning Dom", "Dom identity", ""));
        ControllerPinManager.enterSubMode();
        try (ActivityScenario<ProfileActivity> scenario = ActivityScenario.launch(ProfileActivity.class)) {
            assertSelf(scenario, false, "Returning Sub");
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.STARTED);
            ControllerPinManager.enterDomMode();
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED);
            assertSelf(scenario, true, "Returning Dom");
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.STARTED);
            ControllerPinManager.enterSubMode();
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED);
            assertSelf(scenario, false, "Returning Sub");
        }
    }

    @Test @SdkSuppress(minSdkVersion = 29) public void domOnlyOwnProfileStaysEditableWhileStatisticsRequireTheExistingPin() throws Exception {
        new SettingsRepository(context).preferences().edit().putString(DeviceRolePreferences.KEY_MODE,
                DeviceRolePreferences.Mode.DOM_ONLY.name()).commit();
        ControllerPinManager.enterSubMode();
        assertTrue(ProfileStore.save(context, true, "My Dom profile", "Own profile while locked", ""));
        try (ActivityScenario<ProfileActivity> scenario = ActivityScenario.launch(ProfileActivity.class)) {
            assertSelf(scenario, true, "My Dom profile");
            assertFalse(ControllerPinManager.isDomModeActive());
            capture(scenario, "dom-only-locked-profile").recycle();
            onView(tag("profile_edit")).perform(revealAboveNavigation(), click());
            onView(withId(R.id.header_role_badge)).check(
                    androidx.test.espresso.assertion.ViewAssertions.matches(withText(R.string.role_dom)));
            onView(tag("profile_name:dom")).perform(revealAboveNavigation(), replaceText("Updated own Dom"), closeSoftKeyboard());
            onView(tag("profile_save")).perform(revealAboveNavigation(), click());
            assertSelf(scenario, true, "Updated own Dom");
            onView(tag("profile_stats_unlock")).perform(revealAboveNavigation(), click());
            onView(withHint(R.string.controller_pin_label)).check(
                    androidx.test.espresso.assertion.ViewAssertions.matches(isDisplayed()));
            assertFalse(ControllerPinManager.isDomModeActive());
            onView(withHint(R.string.controller_pin_label)).perform(closeSoftKeyboard());
            onView(withText(android.R.string.cancel)).perform(click());
            onView(withHint(R.string.controller_pin_label)).check(
                    androidx.test.espresso.assertion.ViewAssertions.doesNotExist());
            scenario.onActivity(a -> {
                assertTrue(a.findViewById(R.id.stats_section_profile).isSelected());
                assertNull(a.findViewById(R.id.stats_lifetime_summary));
                assertNull(a.findViewById(R.id.daily_statistics_panel));
                assertNull(a.findViewById(R.id.achievement_list));
            });
            onView(tag("profile_stats_unlock")).perform(revealAboveNavigation(), click());
            onView(withHint(R.string.controller_pin_label)).perform(replaceText("2468"), closeSoftKeyboard());
            onView(withText(R.string.controller_pin_unlock)).perform(click());
            await(scenario, a -> a.findViewById(R.id.daily_range_7) != null && a.findViewById(R.id.daily_range_7).isShown());
            scenario.onActivity(a -> {
                assertTrue(a.findViewById(R.id.stats_lifetime_summary).isShown());
                assertTrue(a.findViewById(R.id.button_edit_lock).isSelected());
                assertEquals(a.getString(R.string.device_role_lock), a.findViewById(R.id.button_edit_lock).getContentDescription());
            });
            onView(withId(R.id.stats_section_profile)).perform(revealAboveNavigation(), click());
            onView(withId(R.id.button_edit_lock)).perform(click());
            assertSelf(scenario, true, "Updated own Dom");
            assertFalse(ControllerPinManager.isDomModeActive());
        }
    }

    @Test @SdkSuppress(minSdkVersion = 29) public void pairedSubContractStartsWithReadOnlyAllowancesAndProfileHasNoDeviceFiller() throws Exception {
        ControllerPinManager.enterDomMode();
        PersonId id = new PersonId("remote_" + String.join("", Collections.nCopies(64, "a"))
                + "_" + String.join("", Collections.nCopies(32, "b")));
        Map<String, Boolean> consent = new LinkedHashMap<>();
        for (String key : SubFeaturePolicy.FEATURES) consent.put(key, !"censor".equals(key));
        Bitmap pairedImage = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888);
        pairedImage.eraseColor(Color.rgb(112, 79, 160));
        android.graphics.Paint pairedPaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        pairedPaint.setColor(Color.rgb(129, 187, 214));
        new android.graphics.Canvas(pairedImage).drawCircle(120, 112, 66, pairedPaint);
        String pairedAvatar;
        try { pairedAvatar = ProfileImages.encode(pairedImage); } finally { pairedImage.recycle(); }
        PersonSnapshot snapshot = new PersonSnapshot(id, "Paired Sub", "Fixture device", "Fixture contract", "Inactive",
                PersonSnapshot.Connection.OFFLINE, PersonSnapshot.Freshness.STALE, System.currentTimeMillis() - TimeUnit.DAYS.toMillis(1),
                EnumSet.noneOf(PersonSnapshot.Capability.class), Collections.emptyList(), Collections.emptyList(),
                "EUR", 0, 0, 0, 0, false).withProfile(new ProfileData("Paired Sub", "Their own bio", pairedAvatar), consent);
        People.installRemoteRepository(new PersonRepository() {
            public java.util.List<PersonId> people() { return Collections.singletonList(id); }
            public PersonSnapshot load(PersonId requested) { assertEquals(id, requested); return snapshot; }
        });
        Map<String, ?> choices = new LinkedHashMap<>(context.getSharedPreferences(SubFeaturePolicy.STORE, 0).getAll());
        try (ActivityScenario<PersonActivity> scenario = ActivityScenario.launch(new Intent(context, PersonActivity.class)
                .putExtra(PersonRoutes.EXTRA_PERSON_ID, id.value()))) {
            await(scenario, a -> a.findViewById(android.R.id.content).findViewWithTag("person_name") != null);
            await(scenario, a -> avatarReady(a.findViewById(android.R.id.content)));
            scenario.onActivity(a -> {
                View root = a.findViewById(android.R.id.content);
                assertNull(root.findViewWithTag("person_allowed_features"));
                assertNull(root.findViewWithTag("person_target:" + id.value()));
                assertNotNull(root.findViewWithTag("person_about:" + id.value()));
                for (String key : SubFeaturePolicy.FEATURES) assertNull(root.findViewWithTag("sub_feature:" + key));
            });
            capture(scenario, "paired-sub-profile").recycle();
            onView(tag("person_tab:contracts")).perform(revealAboveNavigation(), click());
            await(scenario, a -> a.findViewById(android.R.id.content).findViewWithTag("person_allowed_features") != null);
            scenario.onActivity(a -> {
                View card = a.findViewById(android.R.id.content).findViewWithTag("person_allowed_features");
                assertReadOnly(card); ViewGroup parent = (ViewGroup) card.getParent();
                for (int i = 0; i < parent.getChildCount(); i++) if (parent.getChildAt(i) instanceof ViewGroup) {
                    assertSame(card, parent.getChildAt(i)); break;
                }
            });
            capture(scenario, "paired-sub-contract-first-allowances").recycle();
            onView(tag("person_allowance:censor")).perform(revealAboveNavigation(), click());
            capture(scenario, "paired-sub-allowances").recycle();
            assertEquals(choices, context.getSharedPreferences(SubFeaturePolicy.STORE, 0).getAll());
        }
    }

    @Test @SdkSuppress(minSdkVersion = 29) public void avatarSelectionChangeRemovalAndCancellationKeepRoundedNativePixels() throws Exception {
        assertTrue(ProfileStore.save(context, false, "Fixture Sub", "Avatar fixture", ""));
        File pink = imageFile(Color.rgb(227, 82, 166)), blue = imageFile(Color.rgb(67, 135, 208));
        Intent edit = new Intent(context, ProfileEditorActivity.class).putExtra(ProfileEditorActivity.EXTRA_DOM, false);
        try {
            int iteration = 0;
            for (File source : new File[]{pink, blue}) {
                int expected = iteration == 0 ? Color.rgb(227, 82, 166) : Color.rgb(67, 135, 208);
                androidx.test.espresso.intent.Intents.init();
                try {
                    androidx.test.espresso.intent.Intents.intending(androidx.test.espresso.intent.matcher.IntentMatchers
                            .hasAction(Intent.ACTION_GET_CONTENT)).respondWith(new android.app.Instrumentation.ActivityResult(
                            Activity.RESULT_OK, new Intent().setData(android.net.Uri.fromFile(source))));
                    try (ActivityScenario<ProfileEditorActivity> scenario = ActivityScenario.launch(edit)) {
                        AtomicReference<View> previousPreview = new AtomicReference<>();
                        scenario.onActivity(a -> previousPreview.set(a.findViewById(android.R.id.content)
                                .findViewWithTag("profile_avatar_preview")));
                        onView(tag("profile_avatar_change")).perform(revealAboveNavigation(), click());
                        await(scenario, a -> {
                            ViewGroup preview = a.findViewById(android.R.id.content).findViewWithTag("profile_avatar_preview");
                            if (preview == previousPreview.get() || !preview.isAttachedToWindow()
                                    || !preview.isLaidOut() || preview.isLayoutRequested()) return false;
                            android.widget.ImageView image = (android.widget.ImageView) preview.getChildAt(1);
                            if (image.getVisibility() != View.VISIBLE
                                    || !(image.getDrawable() instanceof android.graphics.drawable.BitmapDrawable)) return false;
                            Bitmap decoded = ((android.graphics.drawable.BitmapDrawable) image.getDrawable()).getBitmap();
                            View root = a.findViewById(android.R.id.content);
                            return root.findViewWithTag("profile_save").isEnabled()
                                    && root.findViewWithTag("profile_avatar_change").isEnabled()
                                    && distance(expected, decoded.getPixel(decoded.getWidth() / 2, decoded.getHeight() / 2)) < 18;
                        });
                        onView(tag("profile_avatar_preview")).perform(revealAboveNavigation());
                        Rect bounds = new Rect();
                        scenario.onActivity(a -> {
                            View preview = a.findViewById(android.R.id.content).findViewWithTag("profile_avatar_preview");
                            assertTrue(preview.getGlobalVisibleRect(bounds));
                            assertEquals(preview.getWidth(), bounds.width()); assertEquals(preview.getHeight(), bounds.height());
                        });
                        Bitmap pixels = capture(scenario, iteration == 0 ? "avatar-pink" : "avatar-blue");
                        try {
                            org.json.JSONObject witness = new org.json.JSONObject().put("view", "profile_avatar_preview")
                                    .put("role", "SUB").put("bounds", bounds.toShortString()).put("expected", expected)
                                    .put("center", pixels.getPixel(bounds.centerX(), bounds.centerY()))
                                    .put("opaqueInterior", pixels.getPixel(bounds.centerX(), bounds.top + Math.max(2, bounds.height() / 12)))
                                    .put("outsideRoundedClip", pixels.getPixel(bounds.left + bounds.width() / 12, bounds.top + bounds.height() / 12));
                            File evidence = new File(evidenceDirectory(), iteration == 0 ? "avatar-pink.witness.json" : "avatar-blue.witness.json");
                            try (FileOutputStream stream = new FileOutputStream(evidence)) {
                                stream.write(witness.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                            }
                            assertTrue(distance(expected, pixels.getPixel(bounds.centerX(), bounds.centerY())) < 18);
                            assertTrue(distance(expected, pixels.getPixel(bounds.centerX(), bounds.top + Math.max(2, bounds.height() / 12))) < 18);
                            assertTrue(distance(expected, pixels.getPixel(bounds.left + bounds.width() / 12,
                                    bounds.top + bounds.height() / 12)) > 60);
                        } finally { pixels.recycle(); }
                        onView(tag("profile_save")).perform(revealAboveNavigation(), click());
                    }
                    assertFalse(ProfileStore.load(context, false).avatar.isEmpty());
                    iteration++;
                } finally { androidx.test.espresso.intent.Intents.release(); }
            }
            String saved = ProfileStore.load(context, false).avatar;
            androidx.test.espresso.intent.Intents.init();
            try {
                androidx.test.espresso.intent.Intents.intending(androidx.test.espresso.intent.matcher.IntentMatchers
                        .hasAction(Intent.ACTION_GET_CONTENT)).respondWith(new android.app.Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null));
                try (ActivityScenario<ProfileEditorActivity> scenario = ActivityScenario.launch(edit)) {
                    onView(tag("profile_avatar_change")).perform(revealAboveNavigation(), click());
                    onView(tag("profile_avatar_remove")).perform(revealAboveNavigation(), click());
                    onView(tag("profile_cancel")).perform(revealAboveNavigation(), click());
                }
            } finally { androidx.test.espresso.intent.Intents.release(); }
            assertEquals(saved, ProfileStore.load(context, false).avatar);
            try (ActivityScenario<ProfileEditorActivity> scenario = ActivityScenario.launch(edit)) {
                onView(tag("profile_avatar_remove")).perform(revealAboveNavigation(), click());
                scenario.recreate();
                assertEquals(saved, ProfileStore.load(context, false).avatar);
                onView(tag("profile_save")).perform(revealAboveNavigation(), click());
            }
            assertEquals("", ProfileStore.load(context, false).avatar);
            try (ActivityScenario<ProfileActivity> scenario = ActivityScenario.launch(ProfileActivity.class)) {
                await(scenario, a -> {
                    View avatar = a.findViewById(android.R.id.content).findViewWithTag("self_profile_avatar");
                    return avatar instanceof ProfileAvatarView && avatar.isAttachedToWindow()
                            && avatar.isLaidOut() && !avatar.isLayoutRequested();
                });
                onView(tag("self_profile_avatar")).perform(revealAboveNavigation());
                capture(scenario, "avatar-removed-fallback").recycle();
                scenario.onActivity(a -> {
                    ViewGroup hero = a.findViewById(android.R.id.content).findViewWithTag("self_profile:sub");
                    View candidate = hero.findViewWithTag("self_profile_avatar");
                    assertTrue(candidate instanceof ProfileAvatarView);
                    ProfileAvatarView avatar = (ProfileAvatarView) candidate;
                    assertEquals(2, avatar.getChildCount());
                    assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, avatar.getImportantForAccessibility());
                    assertTrue(avatar.getChildAt(0) instanceof TextView);
                    TextView fallback = (TextView) avatar.getChildAt(0);
                    assertEquals("FS", fallback.getText().toString());
                    assertTrue(fallback.isShown());
                    assertNotNull(fallback.getLayout());
                    textFits(fallback);
                    assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, fallback.getImportantForAccessibility());
                    assertEquals("Fixture Sub", ((TextView) hero.findViewWithTag("self_profile_name")).getText().toString());
                    assertTrue(avatar.getChildAt(1) instanceof android.widget.ImageView);
                    android.widget.ImageView image = (android.widget.ImageView) avatar.getChildAt(1);
                    assertEquals(View.INVISIBLE, image.getVisibility());
                    assertNull(image.getDrawable());
                });
            }
        } finally { assertTrue(pink.delete()); assertTrue(blue.delete()); }
    }

    @Test public void avatarDecoderRejectsBrokenOrOversizedImageDimensionsBeforeSaving() throws Exception {
        Bitmap large = Bitmap.createBitmap(768, 768, Bitmap.Config.ARGB_8888);
        large.eraseColor(Color.BLUE);
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try { assertTrue(large.compress(Bitmap.CompressFormat.JPEG, 50, bytes)); }
        finally { large.recycle(); }
        String oversized = java.util.Base64.getEncoder().encodeToString(bytes.toByteArray());
        assertNull(ProfileImages.decode(oversized));
        ProfileData original = ProfileStore.load(context, false);
        assertThrows(IllegalArgumentException.class, () -> ProfileStore.save(context, false, "Invalid", "", oversized));
        assertEquals(original, ProfileStore.load(context, false));
        assertNull(ProfileImages.decode(java.util.Base64.getEncoder().encodeToString(new byte[]{(byte) 255, (byte) 216, 0, 0})));
    }

    @Test @SdkSuppress(minSdkVersion = 29) public void renderedRoleProfilesRetainReadableNavigationStatisticsAndOriginalAchievements() throws Exception {
        Bitmap portrait = Bitmap.createBitmap(240, 240, Bitmap.Config.ARGB_8888);
        android.graphics.Canvas canvas = new android.graphics.Canvas(portrait);
        android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        canvas.drawColor(Color.rgb(57, 41, 79));
        paint.setColor(Color.rgb(188, 133, 211)); canvas.drawCircle(82, 93, 78, paint);
        paint.setColor(Color.rgb(233, 162, 193)); canvas.drawCircle(171, 174, 64, paint);
        String avatar;
        try { avatar = ProfileImages.encode(portrait); } finally { portrait.recycle(); }
        assertTrue(ProfileStore.save(context, false, "Mara Sinclair", "Books, walks and quiet evenings.\nTaking things one day at a time.", avatar));
        ControllerPinManager.enterDomMode();
        assertTrue(ProfileStore.save(context, true, "Alex Morgan", "Clear expectations and good communication.", avatar));
        context.getSharedPreferences(SubFeaturePolicy.STORE, 0).edit().putBoolean("censor", false)
                .putBoolean("limits", true).putBoolean("wallet", true).putBoolean("subliminal", false).putBoolean("popup", true).commit();
        String[] cases = {"sub-only", "both-sub", "dom-only", "both-dom"};
        String selectedCase = InstrumentationRegistry.getArguments().getString("visual_case", "all");
        assertTrue("all".equals(selectedCase) || java.util.Arrays.asList(cases).contains(selectedCase));
        for (String name : cases) {
            if (!"all".equals(selectedCase) && !name.equals(selectedCase)) continue;
            boolean dom = name.startsWith("dom") || name.endsWith("dom");
            DeviceRolePreferences.Mode mode = name.startsWith("both") ? DeviceRolePreferences.Mode.BOTH
                    : dom ? DeviceRolePreferences.Mode.DOM_ONLY : DeviceRolePreferences.Mode.SUB_ONLY;
            new SettingsRepository(context).preferences().edit().putString(DeviceRolePreferences.KEY_MODE, mode.name()).commit();
            if (dom) ControllerPinManager.enterDomMode(); else ControllerPinManager.enterSubMode();
            try (ActivityScenario<ProfileActivity> scenario = ActivityScenario.launch(ProfileActivity.class)) {
                assertSelf(scenario, dom, dom ? "Alex Morgan" : "Mara Sinclair");
                await(scenario, a -> avatarReady(a.findViewById(android.R.id.content)));
                capture(scenario, name + "-profile").recycle();
                java.util.concurrent.atomic.AtomicBoolean expandable = new java.util.concurrent.atomic.AtomicBoolean();
                scenario.onActivity(a -> {
                    View more = a.findViewById(android.R.id.content).findViewWithTag("self_profile_about_expand");
                    expandable.set(more != null && more.getVisibility() == View.VISIBLE);
                });
                if (expandable.get()) {
                    onView(tag("self_profile_about_expand")).perform(revealAboveNavigation(), click());
                    settleNativeFrame(scenario);
                    capture(scenario, name + "-profile-expanded").recycle();
                }
                scenario.onActivity(a -> textFits(a.findViewById(android.R.id.content)));
                if (!dom) {
                    onView(tag("person_allowance:popup")).perform(revealAboveNavigation());
                    capture(scenario, name + "-allowances").recycle();
                }
                alignCardTop(scenario, a -> a.findViewById(R.id.stats_lifetime_summary));
                await(scenario, a -> a.findViewById(R.id.daily_range_7) != null && a.findViewById(R.id.daily_range_7).isShown());
                capture(scenario, name + "-stats").recycle();
                onView(withId(R.id.daily_metric_filter)).perform(revealAboveNavigation());
                capture(scenario, name + "-activity").recycle();
                alignCardTop(scenario, a -> a.findViewById(R.id.stats_totals_details));
                capture(scenario, name + "-totals").recycle();
                alignCardTop(scenario, a -> a.findViewById(R.id.stats_achievements));
                capture(scenario, name + "-achievement-preview").recycle();
                scenario.onActivity(a -> textFits(a.findViewById(android.R.id.content)));
                onView(withId(R.id.stats_section_achievements)).perform(revealAboveNavigation(), click());
                await(scenario, a -> {
                    ViewGroup list = a.findViewById(R.id.achievement_list);
                    return list != null && list.isShown() && list.getChildCount() > 0;
                });
                capture(scenario, name + "-achievements").recycle();
                onView(withId(R.id.stats_section_profile)).perform(revealAboveNavigation(), click());
                assertSelf(scenario, dom, dom ? "Alex Morgan" : "Mara Sinclair");
                scenario.recreate(); assertSelf(scenario, dom, dom ? "Alex Morgan" : "Mara Sinclair");
            }
            try (ActivityScenario<com.subhub.app.settings.GlobalSettingsActivity> settings =
                    ActivityScenario.launch(com.subhub.app.settings.GlobalSettingsActivity.class)) {
                capture(settings, name + "-settings").recycle();
                settings.onActivity(a -> {
                    View root = a.findViewById(android.R.id.content); textFits(root);
                    assertNull(root.findViewWithTag("profile_name:dom")); assertNull(root.findViewWithTag("profile_name:sub"));
                    assertNull(root.findViewWithTag("sub_feature:censor"));
                });
            }
            try (ActivityScenario<ProfileEditorActivity> editor = ActivityScenario.launch(new Intent(context, ProfileEditorActivity.class)
                    .putExtra(ProfileEditorActivity.EXTRA_DOM, dom))) {
                await(editor, a -> avatarReady(a.findViewById(android.R.id.content)));
                capture(editor, name + "-editor").recycle();
                editor.onActivity(a -> textFits(a.findViewById(android.R.id.content)));
            }
        }
    }

    @Test @SdkSuppress(minSdkVersion = 29) public void contractDomPreviewKeepsItsOwnIdentityBioAndRoundedAvatar() throws Exception {
        AtomicReference<ProfileData> owner = new AtomicReference<>(new ProfileData("Alexandra Morgan the contract owner",
                String.join("", Collections.nCopies(12, "Clear expectations and a steady routine. ")), profileImage(Color.rgb(67, 135, 208)), "Woman", 34));
        People.installLocalProjection(person -> person.withContractDom(owner.get()));
        try (ActivityScenario<ContractActivity> scenario = ActivityScenario.launch(ContractActivity.class)) {
            await(scenario, a -> a.findViewById(android.R.id.content).findViewWithTag("contract_dom_name") != null);
            await(scenario, a -> avatarReady(a.findViewById(android.R.id.content)));
            capture(scenario, "contract-owner-long").recycle();
            scenario.onActivity(a -> {
                View root = a.findViewById(android.R.id.content);
                assertEquals(owner.get().name, ((TextView) root.findViewWithTag("contract_dom_name")).getText().toString());
                TextView name = root.findViewWithTag("contract_dom_name"), details = root.findViewWithTag("contract_dom_details");
                assertSame(name.getParent(), details.getParent()); assertEquals(name.getBottom(), details.getTop());
                assertTrue(details.getText().toString().contains("34")); assertTrue(details.getText().toString().contains("Woman"));
                assertEquals(owner.get().about, ((TextView) root.findViewWithTag("contract_dom_about")).getText().toString());
                assertEquals(2, ((TextView) root.findViewWithTag("contract_dom_about")).getMaxLines());
                assertNull(root.findViewWithTag("contract_name"));
            });
            onView(tag("contract_dom_about_expand")).perform(revealAboveNavigation(), click()); scenario.recreate();
            await(scenario, a -> a.findViewById(android.R.id.content).findViewWithTag("contract_dom_about") != null);
            scenario.onActivity(a -> assertEquals(Integer.MAX_VALUE,
                    ((TextView) a.findViewById(android.R.id.content).findViewWithTag("contract_dom_about")).getMaxLines()));
            capture(scenario, "contract-owner-expanded").recycle();
            owner.set(new ProfileData("Renamed owner", "", profileImage(Color.rgb(227, 82, 166)))); scenario.recreate();
            await(scenario, a -> a.findViewById(android.R.id.content).findViewWithTag("contract_dom_name") != null);
            await(scenario, a -> avatarReady(a.findViewById(android.R.id.content)));
            Bitmap pixels = capture(scenario, "contract-owner-avatar-changed"); Rect bounds = new Rect();
            scenario.onActivity(a -> {
                View root = a.findViewById(android.R.id.content);
                assertEquals("Renamed owner", ((TextView) root.findViewWithTag("contract_dom_name")).getText().toString());
                assertNull(root.findViewWithTag("contract_dom_about")); assertNull(root.findViewWithTag("contract_dom_about_expand"));
                assertTrue(root.findViewWithTag("contract_dom_avatar").getGlobalVisibleRect(bounds));
            });
            try {
                assertTrue(distance(Color.rgb(227, 82, 166), pixels.getPixel(bounds.centerX(), bounds.centerY())) < 18);
                assertTrue(distance(Color.rgb(227, 82, 166), pixels.getPixel(bounds.left + bounds.width() / 12,
                        bounds.top + bounds.height() / 12)) > 60);
            } finally { pixels.recycle(); }
            owner.set(new ProfileData("Renamed owner", "Short bio", "")); scenario.recreate();
            await(scenario, a -> a.findViewById(android.R.id.content).findViewWithTag("contract_dom_about") != null);
            capture(scenario, "contract-owner-avatar-fallback").recycle();
            scenario.onActivity(a -> {
                View root = a.findViewById(android.R.id.content); ViewGroup avatar = root.findViewWithTag("contract_dom_avatar");
                assertNotEquals(View.VISIBLE, avatar.getChildAt(1).getVisibility());
                TextView details = root.findViewWithTag("contract_dom_details");
                assertEquals("", details.getText().toString()); assertEquals(View.GONE, details.getVisibility());
                assertEquals(View.GONE, root.findViewWithTag("contract_dom_about_expand").getVisibility()); textFits(root);
            });
        }
    }

    @Test @SdkSuppress(minSdkVersion = 29) public void compactAllowancesRetainNativeTargetsExplicitStatesAndPersistence() throws Exception {
        context.getSharedPreferences(SubFeaturePolicy.STORE, 0).edit().putBoolean("censor", false).putBoolean("subliminal", false).commit();
        try (ActivityScenario<ProfileEditorActivity> scenario = ActivityScenario.launch(new Intent(context, ProfileEditorActivity.class)
                .putExtra(ProfileEditorActivity.EXTRA_DOM, false))) {
            onView(tag("sub_feature:popup")).perform(revealAboveNavigation());
            captureEditorFeatures(scenario, "compact-editable-allowances");
            scenario.onActivity(a -> {
                View root = a.findViewById(android.R.id.content); ViewGroup grid = root.findViewWithTag("feature_consent_grid");
                View card = root.findViewWithTag("profile_edit_features");
                assertTrue(card.getPaddingStart() > 0 && card.getPaddingEnd() > 0);
                int[] cardPoint = new int[2], gridPoint = new int[2];
                card.getLocationOnScreen(cardPoint); grid.getLocationOnScreen(gridPoint);
                assertTrue(gridPoint[0] >= cardPoint[0] + card.getPaddingLeft());
                assertTrue(gridPoint[0] + grid.getWidth() <= cardPoint[0] + card.getWidth() - card.getPaddingRight());
                assertEquals(7, grid.getChildCount());
                assertEquals(GagParticipation.allowed(context),
                        ((CompoundButton) root.findViewWithTag("sub_feature:gag")).isChecked());
                for (String key : SubFeaturePolicy.FEATURES) {
                    CompoundButton choice = root.findViewWithTag("sub_feature:" + key);
                    assertNotNull(choice); assertTrue(choice.getHeight() >= PersonUi.dp(a, 48));
                    assertEquals(SubFeaturePolicy.chosen(context, key), choice.isChecked());
                }
                textFits(grid);
            });
            onView(tag("sub_feature:censor")).perform(revealAboveNavigation(), click());
            assertFalse(SubFeaturePolicy.chosen(context, "censor")); scenario.recreate();
            scenario.onActivity(a -> assertTrue(((CompoundButton) a.findViewById(android.R.id.content)
                    .findViewWithTag("sub_feature:censor")).isChecked()));
            assertFalse(ControllerPinManager.isDomModeActive());
        }
        assertFalse(SubFeaturePolicy.chosen(context, "censor"));
    }

    @Test @SdkSuppress(minSdkVersion = 29) public void compactReadOnlyAllowancesKeepUnknownAndHardcoreContextWithoutEditControls() throws Exception {
        ControllerPinManager.enterDomMode();
        PersonId id = new PersonId("remote_" + String.join("", Collections.nCopies(64, "a")) + "_" + String.join("", Collections.nCopies(32, "b")));
        AtomicReference<PersonSnapshot> current = new AtomicReference<>();
        PersonSnapshot base = new PersonSnapshot(id, "Paired Sub", "Fixture device", "Contract", "Inactive",
                PersonSnapshot.Connection.OFFLINE, PersonSnapshot.Freshness.STALE, System.currentTimeMillis() - 86400000L,
                EnumSet.noneOf(PersonSnapshot.Capability.class), Collections.emptyList(), Collections.emptyList(),
                "EUR", 0, 0, 0, 0, false);
        current.set(base.withProfile(ProfileData.empty("Paired Sub"), Collections.emptyMap()));
        People.installRemoteRepository(new PersonRepository() {
            public java.util.List<PersonId> people() { return Collections.singletonList(id); }
            public PersonSnapshot load(PersonId requested) { assertEquals(id, requested); return current.get(); }
        });
        Map<String, ?> beforeChoices = new LinkedHashMap<>(context.getSharedPreferences(SubFeaturePolicy.STORE, 0).getAll());
        try (ActivityScenario<PersonActivity> scenario = ActivityScenario.launch(new Intent(context, PersonActivity.class)
                .putExtra(PersonRoutes.EXTRA_PERSON_ID, id.value()).putExtra("person_section", "contracts"))) {
            await(scenario, a -> a.findViewById(android.R.id.content).findViewWithTag("person_allowed_features") != null);
            onView(tag("person_allowance:popup")).perform(revealAboveNavigation());
            capture(scenario, "compact-readonly-unknown").recycle();
            scenario.onActivity(a -> { View root = a.findViewById(android.R.id.content); assertReadOnly(root.findViewWithTag("person_allowed_features")); textFits(root); });
            Map<String, Boolean> consent = new LinkedHashMap<>(); for (String key : SubFeaturePolicy.FEATURES) consent.put(key, !"censor".equals(key));
            current.set(base.withProfile(ProfileData.empty("Paired Sub"), consent, true)); scenario.recreate();
            await(scenario, a -> a.findViewById(android.R.id.content).findViewWithTag("person_allowed_features") != null);
            onView(tag("person_allowance:popup")).perform(revealAboveNavigation());
            capture(scenario, "compact-readonly-hardcore").recycle();
            scenario.onActivity(a -> { View root = a.findViewById(android.R.id.content); assertReadOnly(root.findViewWithTag("person_allowed_features")); textFits(root); });
            onView(tag("person_allowance:censor")).perform(revealAboveNavigation(), click());
            assertEquals(beforeChoices, context.getSharedPreferences(SubFeaturePolicy.STORE, 0).getAll());
        }
    }

    @Test @SdkSuppress(minSdkVersion = 29) public void largeTextCaptureCoverageShowsWholeControlsAndExpandedBioEnd() throws Exception {
        context.getSharedPreferences(SubFeaturePolicy.STORE, 0).edit().putBoolean("censor", false).putBoolean("subliminal", false).commit();
        try (ActivityScenario<ProfileActivity> scenario = ActivityScenario.launch(ProfileActivity.class)) {
            settleNativeFrame(scenario);
            alignCardTop(scenario, a -> (View) a.findViewById(android.R.id.content).findViewWithTag("feature_consent_grid").getParent());
            capture(scenario, "large-editable-complete").recycle();
            scenario.onActivity(a -> assertAboveNavigation(a, (View) a.findViewById(android.R.id.content)
                    .findViewWithTag("feature_consent_grid").getParent(), true));
        }
        ControllerPinManager.enterDomMode();
        PersonId id = new PersonId("remote_" + String.join("", Collections.nCopies(64, "a")) + "_" + String.join("", Collections.nCopies(32, "b")));
        Map<String, Boolean> consent = new LinkedHashMap<>();
        for (String key : SubFeaturePolicy.FEATURES) consent.put(key, !"censor".equals(key));
        PersonSnapshot paired = new PersonSnapshot(id, "Paired Sub", "Fixture device", "Contract", "Inactive",
                PersonSnapshot.Connection.OFFLINE, PersonSnapshot.Freshness.STALE, System.currentTimeMillis() - 86400000L,
                EnumSet.noneOf(PersonSnapshot.Capability.class), Collections.emptyList(), Collections.emptyList(),
                "EUR", 0, 0, 0, 0, false).withProfile(ProfileData.empty("Paired Sub"), consent, true);
        People.installRemoteRepository(new PersonRepository() {
            public java.util.List<PersonId> people() { return Collections.singletonList(id); }
            public PersonSnapshot load(PersonId requested) { assertEquals(id, requested); return paired; }
        });
        try (ActivityScenario<PersonActivity> scenario = ActivityScenario.launch(new Intent(context, PersonActivity.class)
                .putExtra(PersonRoutes.EXTRA_PERSON_ID, id.value()).putExtra("person_section", "contracts"))) {
            await(scenario, a -> a.findViewById(android.R.id.content).findViewWithTag("person_allowed_features") != null);
            settleNativeFrame(scenario); alignCardTop(scenario, a -> a.findViewById(android.R.id.content).findViewWithTag("person_allowed_features"));
            capture(scenario, "large-readonly-hardcore-context").recycle();
            onView(tag("person_allowance:popup")).perform(revealAboveNavigation()); settleNativeFrame(scenario);
            capture(scenario, "large-readonly-hardcore-end").recycle();
            scenario.onActivity(a -> assertAboveNavigation(a, a.findViewById(android.R.id.content).findViewWithTag("person_allowance:popup"), true));
        }
        ControllerPinManager.enterSubMode();
        ProfileData owner = new ProfileData("Alexandra Morgan the contract owner",
                String.join("", Collections.nCopies(12, "Clear expectations and a steady routine. ")), profileImage(Color.rgb(67, 135, 208)));
        People.installLocalProjection(person -> person.withContractDom(owner));
        try (ActivityScenario<ContractActivity> scenario = ActivityScenario.launch(ContractActivity.class)) {
            await(scenario, a -> a.findViewById(android.R.id.content).findViewWithTag("contract_dom_about_expand") != null);
            settleNativeFrame(scenario); onView(tag("contract_dom_about_expand")).perform(revealAboveNavigation(), click());
            settleNativeFrame(scenario); onView(tag("contract_dom_about_expand")).perform(revealAboveNavigation()); settleNativeFrame(scenario);
            capture(scenario, "large-expanded-bio-end").recycle();
            scenario.onActivity(a -> assertAboveNavigation(a, a.findViewById(android.R.id.content).findViewWithTag("contract_dom_about_expand"), true));
            onView(tag("contract_dom_about_expand")).perform(click());
            scenario.onActivity(a -> assertEquals(2, ((TextView) a.findViewById(android.R.id.content).findViewWithTag("contract_dom_about")).getMaxLines()));
        }
    }

    private void captureEditorFeatures(ActivityScenario<ProfileEditorActivity> scenario, String name) throws Exception {
        alignCardTop(scenario, a -> a.findViewById(android.R.id.content).findViewWithTag("profile_edit_features"));
        Bitmap previous = null;
        long deadline = SystemClock.uptimeMillis() + 7000, stableSince = 0;
        try {
            do {
                Bitmap current = capture(scenario, name);
                long now = SystemClock.uptimeMillis();
                boolean same = previous != null && previous.sameAs(current);
                if (!same) stableSince = now;
                if (previous != null) previous.recycle();
                previous = current;
                if (same && now - stableSince >= 200) return;
            } while (SystemClock.uptimeMillis() < deadline);
            throw new AssertionError("Editor feature card compositor did not settle");
        } finally { if (previous != null) previous.recycle(); }
    }

    private <A extends Activity> void settleNativeFrame(ActivityScenario<A> scenario) throws Exception {
        CountDownLatch frame = new CountDownLatch(1);
        scenario.onActivity(a -> {
            View root = a.getWindow().getDecorView();
            if (android.os.Build.VERSION.SDK_INT >= 29) root.getViewTreeObserver().registerFrameCommitCallback(frame::countDown);
            else root.post(frame::countDown);
            root.invalidate();
        });
        assertTrue(frame.await(5, TimeUnit.SECONDS)); InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    private <A extends Activity> void alignCardTop(ActivityScenario<A> scenario, java.util.function.Function<A, View> target) throws Exception {
        scenario.onActivity(a -> {
            View card = target.apply(a); android.view.ViewParent parent = card.getParent();
            while (!(parent instanceof android.widget.ScrollView)) parent = parent.getParent();
            android.widget.ScrollView scroll = (android.widget.ScrollView) parent;
            Rect bounds = new Rect(); card.getDrawingRect(bounds); scroll.offsetDescendantRectToMyCoords(card, bounds);
            scroll.scrollTo(0, Math.max(0, bounds.top - PersonUi.dp(a, 4)));
        });
        settleNativeFrame(scenario);
    }

    private void assertAboveNavigation(Activity activity, View target, boolean whole) {
        int[] point = new int[2]; target.getLocationOnScreen(point);
        Rect bounds = new Rect(point[0], point[1], point[0] + target.getWidth(), point[1] + target.getHeight());
        View nav = activity.findViewById(R.id.bottom_navigation); Rect navigation = new Rect(); assertTrue(nav.getGlobalVisibleRect(navigation));
        assertTrue("Complete target above navigation: " + bounds, bounds.bottom <= navigation.top);
        assertTrue("Target below system status: " + bounds, bounds.top >= activity.getWindow().getDecorView().getRootWindowInsets()
                .getSystemWindowInsetTop());
    }

    private String profileImage(int color) throws Exception {
        Bitmap image = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888); image.eraseColor(color);
        try { return ProfileImages.encode(image); } finally { image.recycle(); }
    }

    private static boolean avatarReady(View view) {
        if (view instanceof ProfileAvatarView) {
            View image = ((ViewGroup) view).getChildAt(1);
            return image.getVisibility() == View.VISIBLE && ((android.widget.ImageView) image).getDrawable() != null;
        }
        if (view instanceof ViewGroup) for (int index = 0; index < ((ViewGroup) view).getChildCount(); index++)
            if (avatarReady(((ViewGroup) view).getChildAt(index))) return true;
        return false;
    }

    private File imageFile(int color) throws Exception {
        File target = File.createTempFile("profile-fixture-", ".png", context.getCacheDir());
        Bitmap image = Bitmap.createBitmap(256, 192, Bitmap.Config.ARGB_8888); image.eraseColor(color);
        try (FileOutputStream stream = new FileOutputStream(target)) { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, stream)); }
        finally { image.recycle(); }
        return target;
    }

    @androidx.annotation.RequiresApi(29)
    private <A extends Activity> Bitmap capture(ActivityScenario<A> scenario, String name) throws Exception {
        CountDownLatch frame = new CountDownLatch(1);
        scenario.onActivity(a -> {
            View root = a.getWindow().getDecorView(); assertTrue(root.isHardwareAccelerated());
            root.getViewTreeObserver().registerFrameCommitCallback(frame::countDown); root.invalidate();
        });
        assertTrue("A new hardware frame must commit before capture", frame.await(5, TimeUnit.SECONDS));
        File directory = evidenceDirectory();
        Bitmap pixels = InstrumentationRegistry.getInstrumentation().getUiAutomation(android.app.UiAutomation.FLAG_DONT_USE_ACCESSIBILITY).takeScreenshot();
        assertNotNull(pixels);
        try (FileOutputStream stream = new FileOutputStream(new File(directory, name + ".png"))) {
            assertTrue(pixels.compress(Bitmap.CompressFormat.PNG, 100, stream));
        }
        return pixels;
    }

    private File evidenceDirectory() {
        String run = InstrumentationRegistry.getArguments().getString("evidence_run", "profile-native");
        assertTrue(run.matches("[a-zA-Z0-9-]+"));
        File directory = new File(context.getFilesDir(), "ui-v3/" + run);
        assertTrue(directory.isDirectory() || directory.mkdirs());
        return directory;
    }

    @Test @SdkSuppress(minSdkVersion = 29)
    public void bothRoleProfileCardsUseSwitchAndSingleRolesKeepTheirIdentity() throws Exception {
        for(DeviceRolePreferences.Mode mode:new DeviceRolePreferences.Mode[]{DeviceRolePreferences.Mode.BOTH,DeviceRolePreferences.Mode.DOM_ONLY,DeviceRolePreferences.Mode.SUB_ONLY}) {
            ControllerPinManager.enterDomMode();assertTrue(DeviceRolePreferences.set(context,mode));
            for(boolean dom:new boolean[]{false,true}) {
                if(dom&&mode==DeviceRolePreferences.Mode.SUB_ONLY || !dom&&mode==DeviceRolePreferences.Mode.DOM_ONLY)continue;
                if(dom)ControllerPinManager.enterDomMode();else ControllerPinManager.enterSubMode();
                assertTrue(ProfileStore.save(context,dom,dom?"Own Dom":"Own Sub","Clear communication.",""));
                try(ActivityScenario<ProfileActivity> scenario=ActivityScenario.launch(ProfileActivity.class)) {
                    scenario.onActivity(a->{
                        TextView details=a.findViewById(android.R.id.content).findViewWithTag("self_profile_details");
                        ProfileData profile=ProfileStore.load(a,dom);java.util.List<String> metadata=new java.util.ArrayList<>();
                        if(mode==DeviceRolePreferences.Mode.BOTH)metadata.add(a.getString(R.string.profile_role_switch));
                        if(profile.age!=0)metadata.add(a.getResources().getQuantityString(R.plurals.profile_age_value,profile.age,profile.age));
                        if(!profile.gender.isEmpty())metadata.add(profile.gender);
                        assertEquals(String.join(" · ",metadata),details.getText().toString());
                        assertEquals(metadata.isEmpty()?View.GONE:View.VISIBLE,details.getVisibility());
                        assertEquals(dom,DeviceRolePreferences.isDomWorkspace(a));
                    });
                    if(mode==DeviceRolePreferences.Mode.BOTH) {
                        java.util.concurrent.atomic.AtomicReference<ProfileActivity> activity=new java.util.concurrent.atomic.AtomicReference<>();scenario.onActivity(activity::set);
                        ProfileStatsActivityAndroidTest.captureNativeProfile(activity.get(),dom?"switch-own-dom":"switch-own-sub","profile_preview:"+(dom?"dom":"sub"));
                    }
                    scenario.recreate();
                    scenario.onActivity(a->{
                        TextView details=a.findViewById(android.R.id.content).findViewWithTag("self_profile_details");
                        ProfileData profile=ProfileStore.load(a,dom);java.util.List<String> metadata=new java.util.ArrayList<>();
                        if(mode==DeviceRolePreferences.Mode.BOTH)metadata.add(a.getString(R.string.profile_role_switch));
                        if(profile.age!=0)metadata.add(a.getResources().getQuantityString(R.plurals.profile_age_value,profile.age,profile.age));
                        if(!profile.gender.isEmpty())metadata.add(profile.gender);
                        assertEquals(String.join(" · ",metadata),details.getText().toString());
                        assertEquals(metadata.isEmpty()?View.GONE:View.VISIBLE,details.getVisibility());
                    });
                }
            }
        }
    }

    @Test @SdkSuppress(minSdkVersion = 29) public void profileSelectorsUseEqualHalvesOrAReadableStack() throws Exception {
        for (boolean dom : new boolean[]{false, true}) {
            if (dom) ControllerPinManager.enterDomMode(); else ControllerPinManager.enterSubMode();
            try (ActivityScenario<ProfileActivity> scenario = ActivityScenario.launch(ProfileActivity.class)) {
                capture(scenario, dom ? "equal-selectors-dom" : "equal-selectors-sub").recycle();
                scenario.onActivity(a -> {
                    TextView profile = a.findViewById(R.id.stats_section_profile), catalog = a.findViewById(R.id.stats_section_achievements);
                    assertTrue(Math.abs(profile.getWidth() - catalog.getWidth()) <= 1);
                    assertTrue(profile.getHeight() >= PersonUi.dp(a,48)); assertTrue(catalog.getHeight() >= PersonUi.dp(a,48));
                    textFits(profile); textFits(catalog);
                });
                onView(withId(R.id.stats_section_achievements)).perform(revealAboveNavigation(),click());
                onView(withId(R.id.stats_section_profile)).perform(revealAboveNavigation(),click());
                scenario.recreate();
                scenario.onActivity(a -> assertTrue(a.findViewById(R.id.stats_section_profile).isSelected()));
            }
        }
    }

    private static <T extends Activity> T resumed(Class<T> type) {
        long end = SystemClock.uptimeMillis() + 10000;
        java.util.concurrent.atomic.AtomicReference<T> found = new java.util.concurrent.atomic.AtomicReference<>();
        while (SystemClock.uptimeMillis() < end) {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                for (Activity a : androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                        .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED)) if (type.isInstance(a)) found.set(type.cast(a));
            });
            if (found.get() != null) return found.get(); SystemClock.sleep(20);
        }
        throw new AssertionError("Expected resumed " + type.getSimpleName());
    }

    private static int distance(int left, int right) {
        return Math.abs(Color.red(left) - Color.red(right)) + Math.abs(Color.green(left) - Color.green(right))
                + Math.abs(Color.blue(left) - Color.blue(right));
    }

    private static void textFits(View view) {
        if (view.getVisibility() != View.VISIBLE) return;
        if (view instanceof TextView) {
            TextView text = (TextView) view;
            if (text.getLayout() != null && text.getWidth() > 0) {
                for (int line = 0; line < text.getLineCount(); line++) {
                    assertEquals(text.getText().toString(), 0, text.getLayout().getEllipsisCount(line));
                    assertTrue(text.getText().toString(), text.getLayout().getLineMax(line)
                            <= text.getWidth() - text.getCompoundPaddingLeft() - text.getCompoundPaddingRight() + 1);
                }
                assertTrue(text.getText().toString(), text.getLayout().getHeight()
                        <= text.getHeight() - text.getCompoundPaddingTop() - text.getCompoundPaddingBottom() + 1);
            }
        }
        if (view instanceof ViewGroup) for (int index = 0; index < ((ViewGroup) view).getChildCount(); index++)
            textFits(((ViewGroup) view).getChildAt(index));
    }

    private static org.hamcrest.Matcher<View> tag(String value) {
        return withTagValue(org.hamcrest.Matchers.is((Object) value));
    }
    private static void assertSelf(ActivityScenario<ProfileActivity> scenario, boolean dom, String name) {
        scenario.onActivity(a -> {
            View root = a.findViewById(android.R.id.content);
            assertTrue(root.findViewWithTag(dom ? "self_profile:dom" : "self_profile:sub").isShown());
            assertNull(root.findViewWithTag(dom ? "self_profile:sub" : "self_profile:dom"));
            assertEquals(name, ((TextView) root.findViewWithTag("self_profile_name")).getText().toString());
            if (dom) assertNull(root.findViewWithTag("sub_feature:censor"));
        });
    }
    private static void assertReadOnly(View view) {
        assertFalse(view instanceof CompoundButton); assertFalse(view.hasOnClickListeners());
        if (view instanceof ViewGroup) for (int index = 0; index < ((ViewGroup) view).getChildCount(); index++)
            assertReadOnly(((ViewGroup) view).getChildAt(index));
    }
    private static <A extends Activity> void await(ActivityScenario<A> scenario, java.util.function.Predicate<A> predicate) {
        long deadline = SystemClock.uptimeMillis() + 10_000;
        while (SystemClock.uptimeMillis() < deadline) {
            java.util.concurrent.atomic.AtomicBoolean done = new java.util.concurrent.atomic.AtomicBoolean();
            scenario.onActivity(a -> done.set(predicate.test(a)));
            if (done.get()) return;
            SystemClock.sleep(30);
        }
        fail("Profile UI did not settle");
    }
}
