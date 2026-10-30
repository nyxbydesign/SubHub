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
        for (String store : new String[]{"subhub_profile_names", SubFeaturePolicy.STORE, "betablocker_achievements"})
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
                assertTrue(a.findViewById(android.R.id.content).findViewWithTag("self_profile:sub").isShown());
            });
            for (int repeat = 0; repeat < 3; repeat++) {
                onView(withId(R.id.stats_section_statistics)).perform(revealAboveNavigation(), click());
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
                    assertEquals(a.getString(R.string.role_stats), ((TextView) a.findViewById(R.id.primary_header_title)).getText());
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
            onView(withId(R.id.stats_section_statistics)).perform(revealAboveNavigation(), click());
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

    @Test public void subParticipationRemainsAccessibleWithoutDomUnlockAndSurvivesRecreation() {
        try (ActivityScenario<ProfileActivity> scenario = ActivityScenario.launch(ProfileActivity.class)) {
            onView(tag("sub_feature:censor")).perform(revealAboveNavigation(), click());
            assertFalse(SubFeaturePolicy.chosen(context, "censor"));
            assertFalse(ControllerPinManager.isDomModeActive());
            scenario.recreate();
            scenario.onActivity(a -> {
                View root = a.findViewById(android.R.id.content);
                assertFalse(((CompoundButton) root.findViewWithTag("sub_feature:censor")).isChecked());
                for (String key : SubFeaturePolicy.FEATURES) assertNotNull(root.findViewWithTag("sub_feature:" + key));
            });
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
            onView(withId(R.id.stats_section_statistics)).perform(revealAboveNavigation(), click());
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
            onView(withId(R.id.stats_section_statistics)).perform(revealAboveNavigation(), click());
            onView(withHint(R.string.controller_pin_label)).perform(replaceText("2468"), closeSoftKeyboard());
            onView(withText(R.string.controller_pin_unlock)).perform(click());
            await(scenario, a -> a.findViewById(R.id.daily_range_7) != null && a.findViewById(R.id.daily_range_7).isShown());
            scenario.onActivity(a -> assertTrue(a.findViewById(R.id.stats_lifetime_summary).isShown()));
            onView(withId(R.id.stats_section_profile)).perform(revealAboveNavigation(), click());
            onView(withId(R.id.button_edit_lock)).perform(click());
            assertSelf(scenario, true, "Updated own Dom");
            assertFalse(ControllerPinManager.isDomModeActive());
        }
    }

    @Test @SdkSuppress(minSdkVersion = 29) public void pairedSubProfileShowsAllowancesWithoutGivingDomEditControls() throws Exception {
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
            await(scenario, a -> a.findViewById(android.R.id.content).findViewWithTag("person_allowed_features") != null);
            await(scenario, a -> avatarReady(a.findViewById(android.R.id.content)));
            scenario.onActivity(a -> {
                View root = a.findViewById(android.R.id.content), card = root.findViewWithTag("person_allowed_features");
                assertReadOnly(card);
                assertNotNull(root.findViewWithTag("person_about:" + id.value()));
                for (String key : SubFeaturePolicy.FEATURES) assertNull(root.findViewWithTag("sub_feature:" + key));
            });
            capture(scenario, "paired-sub-profile").recycle();
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
                androidx.test.espresso.intent.Intents.init();
                try {
                    androidx.test.espresso.intent.Intents.intending(androidx.test.espresso.intent.matcher.IntentMatchers
                            .hasAction(Intent.ACTION_GET_CONTENT)).respondWith(new android.app.Instrumentation.ActivityResult(
                            Activity.RESULT_OK, new Intent().setData(android.net.Uri.fromFile(source))));
                    try (ActivityScenario<ProfileEditorActivity> scenario = ActivityScenario.launch(edit)) {
                        onView(tag("profile_avatar_change")).perform(revealAboveNavigation(), click());
                        await(scenario, a -> {
                            ViewGroup preview = a.findViewById(android.R.id.content).findViewWithTag("profile_avatar_preview");
                            android.widget.ImageView image = (android.widget.ImageView) preview.getChildAt(1);
                            return image.getVisibility() == View.VISIBLE && image.getDrawable() != null;
                        });
                        onView(tag("profile_avatar_preview")).perform(revealAboveNavigation());
                        Rect bounds = new Rect();
                        scenario.onActivity(a -> {
                            View preview = a.findViewById(android.R.id.content).findViewWithTag("profile_avatar_preview");
                            assertTrue(preview.getGlobalVisibleRect(bounds));
                            assertEquals(preview.getWidth(), bounds.width()); assertEquals(preview.getHeight(), bounds.height());
                        });
                        Bitmap pixels = capture(scenario, iteration == 0 ? "avatar-pink" : "avatar-blue");
                        int expected = iteration == 0 ? Color.rgb(227, 82, 166) : Color.rgb(67, 135, 208);
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
                scenario.onActivity(a -> {
                    ViewGroup hero = a.findViewById(android.R.id.content).findViewWithTag("self_profile:sub");
                    ViewGroup row = (ViewGroup) hero.getChildAt(0), avatar = (ViewGroup) row.getChildAt(0);
                    assertTrue(((TextView) avatar.getChildAt(0)).getText().length() > 0);
                    assertEquals(View.INVISIBLE, avatar.getChildAt(1).getVisibility());
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
                scenario.onActivity(a -> textFits(a.findViewById(android.R.id.content)));
                if (!dom) {
                    onView(tag("sub_feature:popup")).perform(revealAboveNavigation());
                    capture(scenario, name + "-allowances").recycle();
                }
                onView(withId(R.id.stats_section_statistics)).perform(revealAboveNavigation(), click());
                await(scenario, a -> a.findViewById(R.id.daily_range_7) != null && a.findViewById(R.id.daily_range_7).isShown());
                capture(scenario, name + "-stats").recycle();
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
                String.join("", Collections.nCopies(12, "Clear expectations and a steady routine. ")), profileImage(Color.rgb(67, 135, 208))));
        People.installLocalProjection(person -> person.withContractDom(owner.get()));
        try (ActivityScenario<ContractActivity> scenario = ActivityScenario.launch(ContractActivity.class)) {
            await(scenario, a -> a.findViewById(android.R.id.content).findViewWithTag("contract_dom_name") != null);
            await(scenario, a -> avatarReady(a.findViewById(android.R.id.content)));
            capture(scenario, "contract-owner-long").recycle();
            scenario.onActivity(a -> {
                View root = a.findViewById(android.R.id.content);
                assertEquals(owner.get().name, ((TextView) root.findViewWithTag("contract_dom_name")).getText().toString());
                assertEquals(owner.get().about, ((TextView) root.findViewWithTag("contract_dom_about")).getText().toString());
                assertEquals(2, ((TextView) root.findViewWithTag("contract_dom_about")).getMaxLines());
                assertNotNull(root.findViewWithTag("contract_name"));
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
                assertEquals(View.GONE, root.findViewWithTag("contract_dom_about_expand").getVisibility()); textFits(root);
            });
        }
    }

    @Test @SdkSuppress(minSdkVersion = 29) public void compactAllowancesRetainNativeTargetsExplicitStatesAndPersistence() throws Exception {
        context.getSharedPreferences(SubFeaturePolicy.STORE, 0).edit().putBoolean("censor", false).putBoolean("subliminal", false).commit();
        try (ActivityScenario<ProfileActivity> scenario = ActivityScenario.launch(ProfileActivity.class)) {
            onView(tag("sub_feature:popup")).perform(revealAboveNavigation());
            capture(scenario, "compact-editable-allowances").recycle();
            scenario.onActivity(a -> {
                View root = a.findViewById(android.R.id.content); ViewGroup grid = root.findViewWithTag("feature_consent_grid");
                assertEquals(5, grid.getChildCount());
                for (String key : SubFeaturePolicy.FEATURES) {
                    CompoundButton choice = root.findViewWithTag("sub_feature:" + key);
                    assertNotNull(choice); assertTrue(choice.getHeight() >= PersonUi.dp(a, 48));
                    assertEquals(SubFeaturePolicy.chosen(context, key), choice.isChecked());
                    assertEquals(a.getString(choice.isChecked() ? R.string.control_state_on : R.string.control_state_off),
                            androidx.core.view.ViewCompat.getStateDescription(choice));
                }
                if (a.getResources().getConfiguration().fontScale <= 1.0f)
                    assertEquals(grid.getChildAt(0).getTop(), grid.getChildAt(1).getTop());
                textFits(root);
            });
            onView(tag("sub_feature:censor")).perform(revealAboveNavigation(), click());
            assertTrue(SubFeaturePolicy.chosen(context, "censor")); scenario.recreate();
            scenario.onActivity(a -> assertTrue(((CompoundButton) a.findViewById(android.R.id.content)
                    .findViewWithTag("sub_feature:censor")).isChecked()));
            assertFalse(ControllerPinManager.isDomModeActive());
        }
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
                .putExtra(PersonRoutes.EXTRA_PERSON_ID, id.value()))) {
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
                .putExtra(PersonRoutes.EXTRA_PERSON_ID, id.value()))) {
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
