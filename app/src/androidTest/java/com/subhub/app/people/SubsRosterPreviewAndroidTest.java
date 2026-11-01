package com.subhub.app.people;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.longClick;
import static androidx.test.espresso.matcher.ViewMatchers.withTagValue;
import static com.subhub.app.NativeUiActions.revealAboveNavigation;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.*;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
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
import com.subhub.app.settings.SettingsRepository;
import com.subhub.app.settings.SharedPreferenceTestRestore;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public final class SubsRosterPreviewAndroidTest {
    @Rule public final KeyholderPresentationAndroidTest.IntegrationFixture integration =
            new KeyholderPresentationAndroidTest.IntegrationFixture();
    @Rule public final UiAutomationStateRule automation = new UiAutomationStateRule();
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private Map<String, ?> favoritesBefore, profilesBefore;
    private static final int AVATAR_COLOR = Color.rgb(67, 135, 208);

    @Before public void prepare() {
        assertFalse(LocalSessionControls.isActive(context));
        favoritesBefore = new LinkedHashMap<>(context.getSharedPreferences(FavoriteSubStore.PREFERENCES_NAME, 0).getAll());
        profilesBefore = new LinkedHashMap<>(context.getSharedPreferences(ProfileNames.STORE, 0).getAll());
        assertTrue(context.getSharedPreferences(FavoriteSubStore.PREFERENCES_NAME, 0).edit().clear().commit());
        assertTrue(new SettingsRepository(context).preferences().edit().putString(DeviceRolePreferences.KEY_MODE,
                DeviceRolePreferences.Mode.DOM_ONLY.name()).commit());
        assertTrue(ControllerPinManager.setPin(context, "2468")); ControllerPinManager.enterDomMode();
        OnboardingState.complete(context);
    }

    @After public void restore() {
        SharedPreferenceTestRestore.restore(context.getSharedPreferences(FavoriteSubStore.PREFERENCES_NAME, 0), favoritesBefore);
        SharedPreferenceTestRestore.restore(context.getSharedPreferences(ProfileNames.STORE, 0), profilesBefore);
    }

    @Test @SdkSuppress(minSdkVersion = 29)
    public void actualRosterCardKeepsIdentityFavoriteAndReadOnlyTapThroughWithLongProfileData() throws Exception {
        PersonId id = new PersonId("remote_" + "a".repeat(64) + "_roster1");
        String gender = "Non-binary / gender nonconforming person";
        assertEquals(ProfileData.MAX_GENDER_LENGTH, gender.length());
        ProfileData profile = new ProfileData("Alexandra Morgan with a long display name",
                "I value clear communication, shared plans, and a steady routine. ".repeat(5).trim(), image(), gender, 32);
        PersonSnapshot person = person(id, profile, PersonSnapshot.Connection.CONNECTED);
        install(new AtomicReference<>(person));
        try (ActivityScenario<PeopleActivity> scenario = ActivityScenario.launch(PeopleActivity.class)) {
            AtomicReference<PeopleActivity> host = new AtomicReference<>(); scenario.onActivity(host::set);
            await(() -> tagged(host.get(), "person:" + id.value()) != null);
            await(() -> ((ViewGroup) tagged(host.get(), prefix(id) + "_avatar")).getChildAt(1).getVisibility() == View.VISIBLE);
            scenario.onActivity(a -> {
                View header = tagged(a, prefix(id) + "_identity"); android.widget.ScrollView scroll = scroll(header);
                Rect target = new Rect(); header.getDrawingRect(target); scroll.offsetDescendantRectToMyCoords(header, target);
                scroll.scrollTo(0, Math.max(0, target.top - scroll.getHeight() / 3));
            });
            capture(host.get(), "subs-roster-header-before-clearance").recycle();
            onView(tag(prefix(id) + "_identity")).perform(revealWholeIdentity());
            scenario.onActivity(a -> {
                ViewGroup card = tagged(a, "person:" + id.value());
                assertTrue(card.findViewWithTag("profile_preview:sub") instanceof ProfilePreview);
                assertEquals(profile.name, ((TextView) card.findViewWithTag(prefix(id) + "_name")).getText().toString());
                assertTrue(((TextView) card.findViewWithTag(prefix(id) + "_details")).getText().toString().contains("Non-binary"));
                assertDetailsBelowName(card, prefix(id));
                assertEquals(2, ((TextView) card.findViewWithTag(prefix(id) + "_about")).getMaxLines());
                assertNull(card.findViewWithTag(prefix(id) + "_about_expand"));
                assertNull(card.findViewWithTag("profile_journal_preview"));
                assertFalse(card.findViewWithTag(prefix(id) + "_name").isClickable());
                assertFalse(card.findViewWithTag(prefix(id) + "_about").isClickable());
                assertTrue(card.isClickable()); assertTrue(card.isLongClickable());
                assertNull(card.findViewWithTag(prefix(id) + "_target"));
                assertNull(card.findViewWithTag(prefix(id) + "_contract"));
                assertNoFiller(card);
            });
            Bitmap pixels = capture(host.get(), "subs-roster-long-top");
            Rect avatar = new Rect();
            scenario.onActivity(a -> {
                View image = tagged(a, prefix(id) + "_avatar"); assertTrue(image.getGlobalVisibleRect(avatar));
                assertEquals(image.getWidth(), avatar.width()); assertEquals(image.getHeight(), avatar.height());
            });
            try {
                assertTrue(distance(AVATAR_COLOR, pixels.getPixel(avatar.centerX(), avatar.centerY())) < 18);
                assertTrue(distance(AVATAR_COLOR, pixels.getPixel(avatar.left + avatar.width() / 12,
                        avatar.top + avatar.height() / 12)) > 60);
            } finally { pixels.recycle(); }
            onView(tag(prefix(id) + "_connection")).perform(revealAboveNavigation());
            capture(host.get(), "subs-roster-long-summary").recycle();
            onView(tag(prefix(id) + "_name")).perform(revealAboveNavigation(), longClick());
            await(() -> tagged(host.get(), "people_favorite:" + id.value()) != null);
            assertEquals(FavoriteSubStore.identity(id), new FavoriteSubStore(context).identity());
            scenario.recreate(); scenario.onActivity(host::set);
            await(() -> tagged(host.get(), "people_favorite:" + id.value()) != null);
            onView(tag("people_favorite:" + id.value())).perform(revealAboveNavigation());
            capture(host.get(), "subs-roster-favorite-restored").recycle();
            Instrumentation.ActivityMonitor monitor = InstrumentationRegistry.getInstrumentation()
                    .addMonitor(PersonActivity.class.getName(), null, false);
            PersonActivity opened = null;
            try {
                onView(tag(prefix(id) + "_name")).perform(revealAboveNavigation(), click());
                opened = (PersonActivity) InstrumentationRegistry.getInstrumentation().waitForMonitorWithTimeout(monitor, 5000);
                assertNotNull(opened); PersonActivity selected = opened;
                await(() -> tagged(selected, "person_name") != null);
                InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                    assertEquals(id, PersonRoutes.requireId(selected.getIntent()));
                    assertEquals(profile.name, ((TextView) tagged(selected, "person_name")).getText().toString());
                    assertEquals(profile.about, ((TextView) tagged(selected, "person_about:" + id.value())).getText().toString());
                    assertDetailsBelowName(selected.findViewById(android.R.id.content), "person");
                    assertNull(tagged(selected, "person_target:" + id.value()));
                    assertNull(tagged(selected, "person_allowed_features"));
                    assertNoFiller(selected.findViewById(android.R.id.content));
                    assertNull(tagged(selected, "profile_edit"));
                    assertNull(selected.findViewById(R.id.profile_gender_input));
                    assertNull(selected.findViewById(R.id.profile_age_input));
                });
                capture(opened, "subs-roster-tap-person").recycle();
                onView(tag("person_details")).perform(revealAboveNavigation());
                capture(opened, "subs-roster-tap-person-complete-details").recycle();
                onView(tag("person_about_expand")).perform(revealAboveNavigation(), click());
                onView(tag("person_about_expand")).perform(revealAboveNavigation());
                capture(opened, "subs-roster-tap-person-expanded-end").recycle();
            } finally {
                if (opened != null) { PersonActivity selected = opened;
                    InstrumentationRegistry.getInstrumentation().runOnMainSync(selected::finish); }
                InstrumentationRegistry.getInstrumentation().removeMonitor(monitor);
            }
        }
    }

    @Test @SdkSuppress(minSdkVersion = 29)
    public void legacyOfflineRosterKeepsFallbackAndUnknownHistoryAndHidesAfterAuthorityLoss() throws Exception {
        PersonId id = new PersonId("remote_" + "b".repeat(64) + "_legacy1");
        AtomicReference<PersonSnapshot> current = new AtomicReference<>(person(id, ProfileData.empty("Sam Taylor"), PersonSnapshot.Connection.OFFLINE));
        install(current);
        try (ActivityScenario<PeopleActivity> scenario = ActivityScenario.launch(PeopleActivity.class)) {
            AtomicReference<PeopleActivity> host = new AtomicReference<>(); scenario.onActivity(host::set);
            await(() -> tagged(host.get(), "person:" + id.value()) != null);
            scenario.onActivity(a -> {
                ViewGroup card = tagged(a, "person:" + id.value());
                assertNull(card.findViewWithTag(prefix(id) + "_about"));
                assertEquals(a.getString(R.string.role_sub), ((TextView) card.findViewWithTag(prefix(id) + "_details")).getText().toString());
                assertDetailsBelowName(card, prefix(id)); assertNoFiller(card);
                assertEquals(a.getString(R.string.role_offline_last_online_unknown),
                        ((TextView) card.findViewWithTag(prefix(id) + "_connection")).getText().toString());
                assertNotEquals(View.VISIBLE, ((ViewGroup) card.findViewWithTag(prefix(id) + "_avatar")).getChildAt(1).getVisibility());
            });
            onView(tag(prefix(id) + "_name")).perform(revealAboveNavigation());
            capture(host.get(), "subs-roster-legacy-offline").recycle();
            onView(tag(prefix(id) + "_connection")).perform(revealAboveNavigation());
            capture(host.get(), "subs-roster-legacy-offline-context").recycle();
            Instrumentation.ActivityMonitor monitor = InstrumentationRegistry.getInstrumentation()
                    .addMonitor(PersonActivity.class.getName(), null, false);
            PersonActivity opened = null;
            try {
                onView(tag(prefix(id) + "_name")).perform(revealAboveNavigation(), click());
                opened = (PersonActivity) InstrumentationRegistry.getInstrumentation().waitForMonitorWithTimeout(monitor, 5000);
                assertNotNull(opened); PersonActivity selected = opened;
                await(() -> tagged(selected, "person_name") != null);
                InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                    assertEquals(id, PersonRoutes.requireId(selected.getIntent()));
                    assertNull(tagged(selected, "person_about:" + id.value()));
                    assertNull(tagged(selected, "profile_edit"));
                });
                capture(opened, "subs-roster-legacy-offline-person").recycle();
            } finally {
                if (opened != null) { PersonActivity selected = opened;
                    InstrumentationRegistry.getInstrumentation().runOnMainSync(selected::finish); }
                InstrumentationRegistry.getInstrumentation().removeMonitor(monitor);
            }
            assertTrue(ControllerPinManager.isDomModeActive());
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED);
            await(() -> androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                    .getLifecycleStageOf(host.get()) == androidx.test.runner.lifecycle.Stage.RESUMED);
            current.set(person(id, ProfileData.empty("Sam Taylor"), PersonSnapshot.Connection.REVOKED));
            scenario.recreate(); scenario.onActivity(host::set);
            await(() -> tagged(host.get(), "person:" + id.value()) != null);
            scenario.onActivity(a -> {
                assertFalse(tagged(a, "person:" + id.value()).isLongClickable());
                assertEquals(a.getString(R.string.role_connection_revoked),
                        ((TextView) tagged(a, prefix(id) + "_connection")).getText().toString());
            });
            onView(tag(prefix(id) + "_connection")).perform(revealAboveNavigation());
            capture(host.get(), "subs-roster-revoked").recycle();
            View roster = host.get().findViewById(R.id.people_roster_scroll);
            ControllerPinManager.enterSubMode(); await(() -> !roster.isShown());
        }
    }

    @Test @SdkSuppress(minSdkVersion = 29)
    public void bothOwnedProfileModesKeepEnteredDetailsImmediatelyBelowTheirNames() throws Exception {
        assertTrue(new SettingsRepository(context).preferences().edit().putString(DeviceRolePreferences.KEY_MODE,
                DeviceRolePreferences.Mode.BOTH.name()).commit());
        for (boolean dom : new boolean[]{false, true}) {
            if (dom) ControllerPinManager.enterDomMode(); else ControllerPinManager.enterSubMode();
            String role = context.getString(dom ? R.string.role_dom : R.string.role_sub);
            assertTrue(ProfileStore.save(context, dom, role, "Clear communication and shared plans.", "", dom ? "Woman" : "Non-binary", dom ? 34 : 32));
            try (ActivityScenario<ProfileActivity> scenario = ActivityScenario.launch(ProfileActivity.class)) {
                AtomicReference<ProfileActivity> host = new AtomicReference<>(); scenario.onActivity(host::set);
                await(() -> tagged(host.get(), "self_profile_details") != null);
                scenario.onActivity(a -> {
                    assertDetailsBelowName(a.findViewById(android.R.id.content), "self_profile");
                    String details = ((TextView) tagged(a, "self_profile_details")).getText().toString();
                    assertTrue(details.startsWith(role + " · ")); assertTrue(details.contains(dom ? "34" : "32"));
                    assertTrue(details.contains(dom ? "Woman" : "Non-binary"));
                    assertNoFiller(tagged(a, "self_profile"));
                });
                capture(host.get(), dom ? "owned-dom-name-details" : "owned-sub-name-details").recycle();
            }
        }
    }

    private static String prefix(PersonId id) { return "roster:" + id.value(); }
    private void install(AtomicReference<PersonSnapshot> value) {
        People.installRemoteRepository(new PersonRepository() {
            public List<PersonId> people() { return List.of(value.get().id); }
            public PersonSnapshot load(PersonId id) { assertEquals(value.get().id, id); return value.get(); }
        });
    }
    private PersonSnapshot person(PersonId id, ProfileData profile, PersonSnapshot.Connection connection) {
        return new PersonSnapshot(id, profile.name, "Pixel 8 Pro", "Current configuration", "Until released",
                connection, connection == PersonSnapshot.Connection.CONNECTED ? PersonSnapshot.Freshness.CURRENT : PersonSnapshot.Freshness.STALE,
                0, EnumSet.of(PersonSnapshot.Capability.VIEW_CONTRACT, PersonSnapshot.Capability.VIEW_STATS,
                        PersonSnapshot.Capability.MANAGE_CONNECTION), Collections.emptyList(), Collections.emptyList(),
                "EUR", 0, 0, 0, 0, false).withProfile(profile, Collections.emptyMap());
    }
    private static String image() throws java.io.IOException {
        Bitmap bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888); bitmap.eraseColor(AVATAR_COLOR);
        try { return ProfileImages.encode(bitmap); } finally { bitmap.recycle(); }
    }
    private static void assertDetailsBelowName(View root, String prefix) {
        TextView name = root.findViewWithTag(prefix + "_name"), details = root.findViewWithTag(prefix + "_details");
        assertNotNull(name); assertNotNull(details); assertSame(name.getParent(), details.getParent());
        assertEquals(name.getBottom(), details.getTop());
        assertEquals(PersonUi.dp(root.getContext(), 4), details.getPaddingTop());
        assertNotNull(details.getLayout());
        int width = details.getWidth() - details.getCompoundPaddingLeft() - details.getCompoundPaddingRight();
        for (int line = 0; line < details.getLineCount(); line++) {
            assertEquals(0, details.getLayout().getEllipsisCount(line));
            assertTrue(details.getLayout().getLineMax(line) <= width + 1);
        }
    }
    private static void assertNoFiller(View view) {
        if (view instanceof TextView) {
            String text = ((TextView) view).getText().toString();
            for (String filler : new String[]{"Pixel 8 Pro", "On this device", "Current configuration"})
                assertFalse(text, text.contains(filler));
        }
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
            assertNoFiller(((ViewGroup) view).getChildAt(i));
    }
    @SuppressWarnings("unchecked") private static <T extends View> T tagged(Activity activity, String tag) {
        return (T) activity.findViewById(android.R.id.content).findViewWithTag(tag);
    }
    private static org.hamcrest.Matcher<View> tag(String value) { return withTagValue(is((Object) value)); }
    private static android.widget.ScrollView scroll(View view) {
        android.view.ViewParent parent = view.getParent();
        while (!(parent instanceof android.widget.ScrollView)) parent = parent.getParent();
        return (android.widget.ScrollView) parent;
    }
    private androidx.test.espresso.ViewAction revealWholeIdentity() {
        return new androidx.test.espresso.ViewAction() {
            public org.hamcrest.Matcher<View> getConstraints() {
                return androidx.test.espresso.matcher.ViewMatchers.isDescendantOfA(org.hamcrest.Matchers.instanceOf(android.widget.ScrollView.class));
            }
            public String getDescription() { return "fit the complete identity block above navigation before sampling or input"; }
            public void perform(androidx.test.espresso.UiController ui, View view) {
                android.widget.ScrollView parent = scroll(view); Rect viewport = new Rect(), nav = new Rect(), before = new Rect();
                assertTrue(parent.getGlobalVisibleRect(viewport)); assertTrue(view.getGlobalVisibleRect(before));
                assertTrue(view.getRootView().findViewById(R.id.bottom_navigation).getGlobalVisibleRect(nav));
                int bottom = Math.min(viewport.bottom, nav.top);
                try {
                    String run = InstrumentationRegistry.getArguments().getString("evidence_run", "subs-roster");
                    File directory = new File(context.getFilesDir(), "ui-v3/" + run);
                    org.json.JSONObject record = new org.json.JSONObject().put("tag", view.getTag()).put("before", before.toShortString())
                            .put("viewport", viewport.toShortString()).put("navigation", nav.toShortString())
                            .put("identityHeight", view.getHeight()).put("availableHeight", bottom - viewport.top)
                            .put("contentPaddingBottom", parent.getChildAt(0).getPaddingBottom()).put("navigationOverlap", viewport.bottom - bottom);
                    java.nio.file.Files.write(new File(directory, "identity-clearance-before.json").toPath(),
                            record.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                } catch (Exception failure) { throw new AssertionError(failure); }
                assertTrue("Roster tail must reserve the measured navigation overlap",
                        parent.getChildAt(0).getPaddingBottom() >= viewport.bottom - bottom);
                assertTrue("Identity must fit the actual unobscured viewport", view.getHeight() <= bottom - viewport.top);
                Rect target = new Rect(); view.getDrawingRect(target); parent.offsetDescendantRectToMyCoords(view, target);
                parent.scrollTo(0, target.top); ui.loopMainThreadUntilIdle();
                Rect actual = new Rect(); assertTrue(view.getGlobalVisibleRect(actual));
                assertEquals(view.getWidth(), actual.width()); assertEquals(view.getHeight(), actual.height());
                assertTrue(actual.top >= viewport.top); assertTrue("Complete identity must clear navigation", actual.bottom <= bottom);
            }
        };
    }
    private static void await(BooleanSupplier condition) {
        long end = SystemClock.uptimeMillis() + 10_000;
        while (SystemClock.uptimeMillis() < end) { AtomicBoolean ready = new AtomicBoolean();
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> ready.set(condition.getAsBoolean()));
            if (ready.get()) return; SystemClock.sleep(40); }
        fail("Subs roster did not settle");
    }
    @androidx.annotation.RequiresApi(29)
    private Bitmap capture(Activity activity, String label) throws Exception {
        for (int i = 0; i < 3; i++) {
            CountDownLatch frame = new CountDownLatch(1);
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> { View root = activity.getWindow().getDecorView();
                assertTrue(root.isHardwareAccelerated()); root.getViewTreeObserver().registerFrameCommitCallback(frame::countDown); root.invalidate(); });
            assertTrue(frame.await(5, TimeUnit.SECONDS));
        }
        Bitmap image = InstrumentationRegistry.getInstrumentation().getUiAutomation(android.app.UiAutomation.FLAG_DONT_USE_ACCESSIBILITY).takeScreenshot();
        assertNotNull(image); String run = InstrumentationRegistry.getArguments().getString("evidence_run", "subs-roster");
        assertTrue(run.matches("[a-zA-Z0-9-]+")); File directory = new File(context.getFilesDir(), "ui-v3/" + run);
        assertTrue(directory.isDirectory() || directory.mkdirs());
        try (FileOutputStream output = new FileOutputStream(new File(directory, label + ".png"))) {
            assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, output));
        }
        return image;
    }
    private static int distance(int a, int b) {
        return Math.abs(Color.red(a) - Color.red(b)) + Math.abs(Color.green(a) - Color.green(b)) + Math.abs(Color.blue(a) - Color.blue(b));
    }
}
