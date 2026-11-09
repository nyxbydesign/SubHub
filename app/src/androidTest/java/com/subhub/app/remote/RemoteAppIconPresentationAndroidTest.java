package com.subhub.app.remote;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.closeSoftKeyboard;
import static androidx.test.espresso.action.ViewActions.replaceText;
import static androidx.test.espresso.matcher.ViewMatchers.withTagValue;
import static com.subhub.app.NativeUiActions.revealAboveNavigation;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.*;

import android.app.Activity;
import android.app.UiAutomation;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SdkSuppress;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import com.subhub.app.R;
import com.subhub.app.MainActivity;
import com.subhub.app.activity.ActivityEntry;
import com.subhub.app.activity.ActivityEvent;
import com.subhub.app.activity.ActivityPreview;
import com.subhub.app.activity.ActivityText;
import com.subhub.app.activity.DomActivityActivity;
import com.subhub.app.help.HelpActivity;
import com.subhub.app.people.ContractAppList;
import com.subhub.app.people.FavoriteSubStore;
import com.subhub.app.people.PersonActivity;
import com.subhub.app.people.PersonId;
import com.subhub.app.people.PersonRoutes;
import com.subhub.app.people.PersonSnapshot;
import com.subhub.app.people.PersonUi;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.security.DeviceRolePreferences;
import com.subhub.app.security.KeyholderPresentationAndroidTest;
import com.subhub.app.settings.SettingsRepository;
import com.subhub.app.settings.SharedPreferenceTestRestore;
import com.subhub.app.stats.StatsActivity;
import com.subhub.app.util.AppIcons;
import com.subhub.app.util.AsyncUiScope;
import com.subhub.app.util.RemoteAppIcon;
import com.subhub.app.util.RemoteAppIcons;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.RuleChain;
import org.junit.runner.RunWith;

/** Real recorded DTOs and the owned integration/cache, composed with the actual screen renderers. */
@RunWith(AndroidJUnit4.class)
public final class RemoteAppIconPresentationAndroidTest {
    @Rule public final RuleChain rules = RuleChain.outerRule(new KeyholderPresentationAndroidTest.IntegrationFixture())
            .around(new com.subhub.app.UiAutomationStateRule());
    private final Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final Set<Throwable> captured = Collections.newSetFromMap(new IdentityHashMap<>());
    private Map<String, ?> preferences;

    @Before public void prepareRole() {
        preferences = new LinkedHashMap<>(target.getSharedPreferences(SettingsRepository.PREFERENCES_NAME, 0).getAll());
        assertTrue(target.getSharedPreferences(SettingsRepository.PREFERENCES_NAME, 0).edit()
                .putString(DeviceRolePreferences.KEY_MODE, DeviceRolePreferences.Mode.BOTH.name()).commit());
        assertTrue(ControllerPinManager.setPin(target, "2468")); assertTrue(ControllerPinManager.verify(target, "2468"));
    }
    @After public void restoreRole() {
        if (preferences != null) SharedPreferenceTestRestore.restore(target.getSharedPreferences(SettingsRepository.PREFERENCES_NAME, 0), preferences);
    }

    @Test @SdkSuppress(minSdkVersion = 29)
    public void actualActivityPreviewOpensFullHistoryAndUsesArrivingRemotePixels() throws Exception {
        try (RemoteAppIconIntegrationAndroidTest.Fixture fixture = new RemoteAppIconIntegrationAndroidTest.Fixture()) {
            fixture.enablePages();
            assertThrows(android.content.pm.PackageManager.NameNotFoundException.class,
                    () -> target.getPackageManager().getApplicationInfo(fixture.packageName(), 0));
            List<ActivityEntry> recorded = fixture.feature().activity.recent(fixture.personId(), 10);
            assertEquals(1, recorded.size()); ActivityEntry entry = recorded.get(0);
            assertEquals(fixture.identity(), entry.appIcon); assertNull(entry.appIcon.descriptor);
            page(new Intent(target, HelpActivity.class), (scenario, host) -> {
                scenario.onActivity(activity -> {
                    LinearLayout column = content(activity);
                    ActivityPreview.addOverview(column, AsyncUiScope.forPage((androidx.lifecycle.LifecycleOwner) activity), "icon-presentation-preview");
                });
                String row = "activity_preview_entry:" + entry.key;
                await(() -> tagged(host.get(), row) != null);
                main(() -> assertGeneric(icon(tagged(host.get(), row))));
                fixture.offer();
                await(() -> magenta(icon(tagged(host.get(), row))));
                assertComposedPixel(host.get(), image(host.get(), row), "overview-preview-arrival");
                onView(withTagValue(is("activity_preview_view_all:all"))).perform(revealAboveNavigation(), click());
                AtomicReference<DomActivityActivity> full = new AtomicReference<>();
                await(() -> { full.set(resumed(DomActivityActivity.class)); return full.get() != null; });
                try {
                    String fullRow = "activity_entry:" + entry.key;
                    await(() -> magenta(icon(tagged(full.get(), fullRow))));
                    assertComposedPixel(full.get(), image(full.get(), fullRow), "full-activity-remote");
                } catch (Exception | AssertionError failure) { captureFailure(failure, "full-activity"); throw failure; }
                finally { main(() -> { if (full.get() != null) full.get().finish(); }); }
            });
        }
    }

    @Test @SdkSuppress(minSdkVersion = 29)
    public void actualHomeOverviewReprojectsRecordedActivityAfterDescriptorArrival() throws Exception {
        Map<String, Map<String, ?>> originals = new LinkedHashMap<>();
        for (String name : Arrays.asList("subhub_onboarding", "subhub_home", FavoriteSubStore.PREFERENCES_NAME))
            originals.put(name, new LinkedHashMap<>(target.getSharedPreferences(name, 0).getAll()));
        try {
            com.subhub.app.onboarding.OnboardingState.complete(target);
            assertTrue(target.getSharedPreferences(SettingsRepository.PREFERENCES_NAME, 0).edit()
                    .putString(DeviceRolePreferences.KEY_MODE, DeviceRolePreferences.Mode.DOM_ONLY.name()).commit());
            try (RemoteAppIconIntegrationAndroidTest.Fixture fixture = new RemoteAppIconIntegrationAndroidTest.Fixture()) {
                fixture.enablePages();
                assertThrows(android.content.pm.PackageManager.NameNotFoundException.class,
                        () -> target.getPackageManager().getApplicationInfo(fixture.packageName(), 0));
                List<ActivityEntry> recorded = fixture.feature().activity.recent(fixture.personId(), 10);
                assertEquals(1, recorded.size()); ActivityEntry entry = recorded.get(0);
                assertEquals(fixture.identity(), entry.appIcon); assertNull(entry.appIcon.descriptor);
                page(new Intent(target, MainActivity.class).setAction(Intent.ACTION_MAIN), (scenario, host) -> {
                    assertTrue("enablePages must install the real fixture relationship provider for Home",
                            com.subhub.app.relationships.RelationshipUi.provider() instanceof FormalRelationshipProvider);
                    String identity = fixture.personId().value(), row = "activity_preview_entry:" + entry.key;
                    await(() -> tagged(host.get(), "home_recent_person:" + identity) != null
                            || tagged(host.get(), "home_favorite:" + identity) != null);
                    await(() -> tagged(host.get(), row) != null);
                    onView(withTagValue(is(row))).perform(revealAboveNavigation());
                    AtomicReference<Object> presenter = new AtomicReference<>();
                    AtomicReference<Long> refreshed = new AtomicReference<>();
                    main(() -> {
                        assertEquals(Intent.ACTION_MAIN, host.get().getIntent().getAction());
                        assertEquals(DeviceRolePreferences.Mode.DOM_ONLY, DeviceRolePreferences.get(host.get()));
                        presenter.set(field(host.get(), "roleHome")); assertNotNull(presenter.get());
                        assertNotNull(field(presenter.get(), "appIconObserver"));
                        assertSame(com.subhub.app.relationships.RelationshipUi.provider(), field(presenter.get(), "iconProvider"));
                        assertTrue(host.get().findViewById(R.id.home_dom_overview).isShown());
                        ImageView placeholder = icon(tagged(host.get(), row)); assertGeneric(placeholder);
                        assertTrue(placeholder.isAttachedToWindow()); assertTrue(placeholder.isShown());
                        android.graphics.Rect clip = new android.graphics.Rect(); assertTrue(placeholder.getGlobalVisibleRect(clip));
                        assertEquals(placeholder.getWidth(), clip.width()); assertEquals(placeholder.getHeight(), clip.height());
                        // Leave the actual icon/source callbacks active; Home's periodic timer must not mask them.
                        ((android.os.Handler) field(host.get(), "uiTimer")).removeCallbacks((Runnable) field(host.get(), "uiTick"));
                        refreshed.set((Long) field(presenter.get(), "lastRefresh"));
                    });
                    await(() -> SystemClock.elapsedRealtime() > refreshed.get());
                    fixture.offer();
                    await(() -> (Long) field(presenter.get(), "lastRefresh") > refreshed.get());
                    await(() -> magenta(icon(tagged(host.get(), row))));
                    ImageView displayed = image(host.get(), row);
                    assertComposedPixel(host.get(), displayed, "actual-home-arrival");
                    RemoteAppIcon complete = fixture.identity(); assertNotNull(complete.descriptor);
                    int[] point = new int[2];
                    main(() -> {
                        assertSame(presenter.get(), field(host.get(), "roleHome"));
                        assertTrue(displayed.isAttachedToWindow()); assertTrue(displayed.isShown());
                        displayed.getLocationOnScreen(point); point[0] += displayed.getWidth() / 2; point[1] += displayed.getHeight() / 2;
                        refreshed.set((Long) field(presenter.get(), "lastRefresh"));
                    });
                    await(() -> SystemClock.elapsedRealtime() > refreshed.get());
                    fixture.retire();
                    assertEquals(RemoteStore.State.REVOKED, fixture.store().peer(complete.peer, complete.generation).state);
                    await(() -> (Long) field(presenter.get(), "lastRefresh") > refreshed.get());
                    await(() -> tagged(host.get(), row) == null || icon(tagged(host.get(), row)).getImageTintList() != null);
                    main(() -> {
                        assertSame(presenter.get(), field(host.get(), "roleHome"));
                        assertTrue(host.get() instanceof MainActivity); assertTrue(host.get().hasWindowFocus());
                        assertTrue(host.get().findViewById(R.id.home_dom_overview).isAttachedToWindow());
                        assertTrue(host.get().findViewById(R.id.home_dom_overview).isShown());
                        assertTrue(((androidx.lifecycle.LifecycleOwner) host.get()).getLifecycle().getCurrentState()
                                .isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED));
                        View current = tagged(host.get(), row);
                        if (current != null) assertGeneric(icon(current));
                        else assertFalse(displayed.isAttachedToWindow()); // Revoked records are removed by the actual source.
                    });
                    assertNull(fixture.identity().descriptor);
                    // A fresh composed frame must also remove the synthetic remote pixel, even if its row disappeared.
                    for (int i = 0; i < 2; i++) {
                        CountDownLatch frame = new CountDownLatch(1);
                        main(() -> { View root = host.get().getWindow().getDecorView(); assertTrue(root.isHardwareAccelerated());
                            root.getViewTreeObserver().registerFrameCommitCallback(frame::countDown); root.invalidate(); });
                        assertTrue(frame.await(5, TimeUnit.SECONDS));
                    }
                    Bitmap pixels = InstrumentationRegistry.getInstrumentation().getUiAutomation(UiAutomation.FLAG_DONT_USE_ACCESSIBILITY).takeScreenshot();
                    assertNotNull(pixels);
                    try {
                        assertTrue(point[0] >= 0 && point[0] < pixels.getWidth() && point[1] >= 0 && point[1] < pixels.getHeight());
                        int observed = pixels.getPixel(point[0], point[1]);
                        if (observed == Color.MAGENTA) save(pixels, "actual-home-revocation-pixels-failed");
                        assertNotEquals("Revocation must remove the remote artwork from the open Home page", Color.MAGENTA, observed);
                    } finally { pixels.recycle(); }
                });
            }
        } finally {
            for (Map.Entry<String, Map<String, ?>> original : originals.entrySet())
                SharedPreferenceTestRestore.restore(target.getSharedPreferences(original.getKey(), 0), original.getValue());
        }
    }

    @Test @SdkSuppress(minSdkVersion = 29)
    public void actualPersonContractReprojectsArrivalAndRevocationWithoutManualRefresh() throws Exception {
        try (RemoteAppIconIntegrationAndroidTest.Fixture fixture = new RemoteAppIconIntegrationAndroidTest.Fixture()) {
            fixture.enablePages();
            Intent intent = route(PersonActivity.class, fixture).putExtra("person_section", "contracts");
            page(intent, (scenario, host) -> {
                String name = "contract_app_icon:" + fixture.packageName();
                await(() -> tagged(host.get(), name) instanceof ImageView);
                main(() -> {
                    assertGeneric((ImageView) tagged(host.get(), name));
                    // Isolate the icon notification from the existing two-second profile observer.
                    ((android.os.Handler) field(host.get(), "clock")).removeCallbacks((Runnable) field(host.get(), "observe"));
                    AsyncUiScope.forPage((androidx.lifecycle.LifecycleOwner) host.get()).cancel("person-observe");
                });
                fixture.offer();
                await(() -> magenta((ImageView) tagged(host.get(), name)));
                ImageView displayed = image(host.get(), name);
                assertComposedPixel(host.get(), displayed, "person-contract-arrival");
                fixture.retire();
                await(() -> displayed.getImageTintList() != null);
                main(() -> {
                    assertNotNull(displayed.getImageTintList());
                    View current = tagged(host.get(), name);
                    if (current != null) assertGeneric((ImageView) current);
                    assertNull(fixture.identity().descriptor);
                });
            });
        }
    }

    @Test @SdkSuppress(minSdkVersion = 29)
    public void actualRemoteStatsReprojectsVisibleAllowanceAfterDescriptorArrival() throws Exception {
        try (RemoteAppIconIntegrationAndroidTest.Fixture fixture = new RemoteAppIconIntegrationAndroidTest.Fixture()) {
            fixture.enablePages();
            PersonSnapshot before = fixture.repository().load(fixture.personId());
            assertEquals(47, before.allowances.stream().filter(a -> fixture.packageName().equals(a.packageName)).findFirst().get().dailyMinutes);
            page(route(StatsActivity.class, fixture), (scenario, host) -> {
                String name = "allowance_app_icon:" + fixture.packageName();
                String amount = target.getString(R.string.role_allowance_usage, 2L, 47);
                assertBareAllowance(host, name, amount);
                scenario.recreate(); scenario.onActivity(host::set);
                assertBareAllowance(host, name, amount);
                main(() -> {
                    LinearLayout container = (LinearLayout) host.get().findViewById(R.id.profile_stats_allowances);
                    ViewGroup parent = (ViewGroup) container.getParent(); int index = parent.indexOfChild(container);
                    ViewGroup.LayoutParams layout = container.getLayoutParams();
                    parent.removeView(container); assertFalse(container.isAttachedToWindow());
                    // Empty the detached presentation to prove attachment reloads the real repository, rather than retaining old rows.
                    container.removeAllViews(); parent.addView(container, index, layout);
                });
                assertBareAllowance(host, name, amount);
                assertNull(fixture.identity().descriptor);
                fixture.offer();
                await(() -> magenta((ImageView) tagged(host.get(), name)));
                assertComposedPixel(host.get(), image(host.get(), name), "remote-statistics-arrival");
            });
        }
    }

    @Test @SdkSuppress(minSdkVersion = 29)
    public void actualRemoteEditorRebindsIconsWithoutChangingIncompleteDraft() throws Exception {
        try (RemoteAppIconIntegrationAndroidTest.Fixture fixture = new RemoteAppIconIntegrationAndroidTest.Fixture()) {
            fixture.enablePages();
            page(route(RemoteContractActivity.class, fixture).putExtra("section", "apps"), (scenario, host) -> {
                String name = "app_icon:" + fixture.packageName();
                await(() -> tagged(host.get(), name) instanceof ImageView);
                AtomicReference<ImageView> originalIcon = new AtomicReference<>();
                AtomicReference<EditText> minutes = new AtomicReference<>(); AtomicReference<CheckBox> selected = new AtomicReference<>();
                AtomicReference<Object> draft = new AtomicReference<>(); AtomicReference<String> encoded = new AtomicReference<>();
                main(() -> {
                    originalIcon.set((ImageView) tagged(host.get(), name)); assertGeneric(originalIcon.get());
                    Map<String, View> controls = controls(host.get());
                    minutes.set((EditText) controls.get("budget:" + fixture.packageName()));
                    selected.set((CheckBox) controls.get("app:" + fixture.packageName()));
                    assertEquals("47", minutes.get().getText().toString()); assertTrue(selected.get().isChecked());
                    draft.set(field(host.get(), "draft")); encoded.set(draft.get().toString());
                });
                onView(is((View) minutes.get())).perform(revealAboveNavigation(), replaceText(""), closeSoftKeyboard());
                onView(is((View) selected.get())).perform(revealAboveNavigation(), click());
                fixture.offer();
                await(() -> magenta((ImageView) tagged(host.get(), name)));
                main(() -> {
                    assertSame(originalIcon.get(), tagged(host.get(), name));
                    assertSame(minutes.get(), controls(host.get()).get("budget:" + fixture.packageName()));
                    assertSame(selected.get(), controls(host.get()).get("app:" + fixture.packageName()));
                    assertEquals("", minutes.get().getText().toString()); assertFalse(selected.get().isChecked());
                    assertSame(draft.get(), field(host.get(), "draft")); assertEquals(encoded.get(), field(host.get(), "draft").toString());
                });
                assertComposedPixel(host.get(), originalIcon.get(), "remote-editor-unsaved-arrival");
                fixture.retire(); await(() -> originalIcon.get().getImageTintList() != null);
                main(() -> {
                    assertGeneric(originalIcon.get()); assertEquals("", minutes.get().getText().toString());
                    assertFalse(selected.get().isChecked()); assertSame(draft.get(), field(host.get(), "draft"));
                });
            });
        }
    }

    @Test @SdkSuppress(minSdkVersion = 29)
    public void sharedNativeLimitsAndAppSelectorKeepRemoteIconScope() throws Exception {
        for (Class<? extends Activity> screen : Arrays.<Class<? extends Activity>>asList(com.subhub.app.appmode.AppModeActivity.class,
                com.subhub.app.people.LocalAppsActivity.class)) {
            try (RemoteAppIconIntegrationAndroidTest.Fixture fixture = new RemoteAppIconIntegrationAndroidTest.Fixture()) {
                fixture.enablePages();
                page(route(screen, fixture), (scenario, host) -> {
                    String name = "app_icon:" + fixture.packageName();
                    await(() -> tagged(host.get(), name) instanceof ImageView);
                    main(() -> assertGeneric((ImageView) tagged(host.get(), name)));
                    fixture.offer();
                    await(() -> magenta((ImageView) tagged(host.get(), name)));
                    ImageView displayed = image(host.get(), name);
                    assertComposedPixel(host.get(), displayed, "shared-native-" + screen.getSimpleName());
                    scenario.recreate(); scenario.onActivity(host::set);
                    await(() -> magenta((ImageView) tagged(host.get(), name)));
                    displayed = image(host.get(), name);
                    AtomicReference<ImageView> retained = new AtomicReference<>(displayed);
                    fixture.retire();
                    await(() -> retained.get().getImageTintList() != null);
                });
            }
        }
    }

    @Test @SdkSuppress(minSdkVersion = 29)
    public void realCallerLegacyAndWrongScopesNeverBorrowViewerPackageIcons() throws Exception {
        try (RemoteAppIconIntegrationAndroidTest.Fixture fixture = new RemoteAppIconIntegrationAndroidTest.Fixture()) {
            fixture.enablePages(); fixture.offer(); RemoteAppIcon complete = fixture.identity();
            assertNotNull(complete.descriptor);
            page(new Intent(target, HelpActivity.class), (scenario, host) -> scenario.onActivity(activity -> {
                LinearLayout column = content(activity); ImageView recycled = new ImageView(activity);
                column.addView(recycled, new LinearLayout.LayoutParams(80, 80));
                ActivityEvent installed = opened(target.getPackageName());
                ActivityText.bindIcon(recycled, entry(PersonId.LOCAL, installed, null));
                assertEquals(AppIcons.class, recycled.getTag(R.id.app_icon_request).getClass().getEnclosingClass());
                ActivityText.bindIcon(recycled, entry(fixture.personId(), installed, null)); assertGeneric(recycled);
                ActivityText.bindIcon(recycled, entry(fixture.personId(), opened(fixture.packageName()), complete));
                for (RemoteAppIcon wrong : Arrays.asList(
                        new RemoteAppIcon(new String(new char[64]).replace('\0', 'c'), complete.generation, complete.packageName, complete.descriptor),
                        new RemoteAppIcon(complete.peer, complete.generation + "-other", complete.packageName, complete.descriptor))) {
                    ActivityText.bindIcon(recycled, entry(fixture.personId(), opened(fixture.packageName()), wrong)); assertGeneric(recycled);
                }
                PersonSnapshot.App bare = new PersonSnapshot.App(fixture.packageName(), "Recorded app",
                        new RemoteAppIcon(complete.peer, complete.generation, complete.packageName, null));
                ContractAppList.add(column, new PersonSnapshot.Section("apps", "Apps", "", "", Collections.singletonList(bare)));
                assertGeneric((ImageView) tagged(activity, "contract_app_icon:" + fixture.packageName()));
                PersonSnapshot legacy = new PersonSnapshot(fixture.personId(), "Synthetic", "Device", "Contract", "Offline",
                        PersonSnapshot.Connection.OFFLINE, PersonSnapshot.Freshness.STALE, 0, Collections.emptySet(), Collections.emptyList(),
                        Arrays.asList(new PersonSnapshot.Allowance(target.getPackageName(), "Viewer installed", 0, 30),
                                new PersonSnapshot.Allowance("", "Shared", 0, 60)), "EUR", 0, 0, 0, 0, false);
                PersonUi.allowances(column, legacy);
                assertGeneric((ImageView) tagged(activity, "allowance_app_icon:" + target.getPackageName()));
                assertGeneric((ImageView) tagged(activity, "allowance_app_icon:"));
                ActivityText.bindIcon(recycled, entry(PersonId.LOCAL, installed, null));
                assertEquals(AppIcons.class, recycled.getTag(R.id.app_icon_request).getClass().getEnclosingClass());
            }));
        }
    }

    private Intent route(Class<? extends Activity> type, RemoteAppIconIntegrationAndroidTest.Fixture fixture) {
        return new Intent(target, type).putExtra(PersonRoutes.EXTRA_PERSON_ID, fixture.personId().value());
    }
    private void assertBareAllowance(AtomicReference<Activity> host, String name, String amount) throws Exception {
        await(() -> tagged(host.get(), name) instanceof ImageView);
        ImageView icon = image(host.get(), name);
        AtomicReference<LinearLayout> row = new AtomicReference<>(); main(() -> row.set((LinearLayout) icon.getParent()));
        onView(is((View) row.get())).perform(revealAboveNavigation());
        main(() -> {
            assertGeneric(icon); assertTrue(icon.isAttachedToWindow()); assertTrue(icon.isShown());
            LinearLayout copy = (LinearLayout) row.get().getChildAt(1);
            android.widget.TextView value = (android.widget.TextView) copy.getChildAt(1);
            assertEquals(amount, value.getText().toString()); assertTrue(value.isShown());
            for (View item : Arrays.asList(icon, value)) {
                android.graphics.Rect clip = new android.graphics.Rect(); assertTrue(item.getGlobalVisibleRect(clip));
                assertEquals(item.getWidth(), clip.width()); assertEquals(item.getHeight(), clip.height());
            }
        });
    }
    private interface PageCheck { void run(ActivityScenario<Activity> scenario, AtomicReference<Activity> host) throws Exception; }
    private void page(Intent intent, PageCheck check) throws Exception {
        try (ActivityScenario<Activity> scenario = ActivityScenario.launch(intent)) {
            AtomicReference<Activity> host = new AtomicReference<>(); scenario.onActivity(host::set);
            try { check.run(scenario, host); }
            catch (Exception | AssertionError failure) { captureFailure(failure, intent.getComponent().getShortClassName()); throw failure; }
        }
    }
    private static LinearLayout content(Activity activity) {
        ScrollView scroll = new ScrollView(activity); LinearLayout column = PersonUi.column(activity);
        scroll.addView(column); activity.setContentView(scroll); return column;
    }
    private static ActivityEvent opened(String name) { return new ActivityEvent("synthetic", ActivityEvent.Kind.APP_OPENED, 1, name, 0, "", "", 0); }
    private static ActivityEntry entry(PersonId person, ActivityEvent event, RemoteAppIcon icon) {
        return new ActivityEntry("synthetic", person, "Synthetic", "Device", event, 2, false, "App", icon);
    }
    private static View tagged(Activity activity, String tag) { return activity == null ? null : activity.findViewById(android.R.id.content).findViewWithTag(tag); }
    private static ImageView image(Activity activity, String tag) {
        AtomicReference<ImageView> result = new AtomicReference<>(); main(() -> result.set(icon(tagged(activity, tag)))); return result.get();
    }
    private static ImageView icon(View view) {
        if (view instanceof ImageView && view.getTag(R.id.app_icon_request) != null) return (ImageView) view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            ImageView result = icon(((ViewGroup) view).getChildAt(i)); if (result != null) return result;
        }
        return null;
    }
    private static boolean magenta(ImageView view) {
        return view != null && view.getImageTintList() == null && view.getDrawable() instanceof BitmapDrawable
                && ((BitmapDrawable) view.getDrawable()).getBitmap().getPixel(2, 2) == Color.MAGENTA;
    }
    private static void assertGeneric(ImageView view) {
        assertNotNull(view); assertNotNull(view.getDrawable()); assertNotNull(view.getImageTintList());
        assertNotNull(view.getTag(R.id.app_icon_request));
        assertEquals(RemoteAppIcons.class, view.getTag(R.id.app_icon_request).getClass().getEnclosingClass());
    }
    private static Object field(Object object, String name) {
        try { Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(object); }
        catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
    @SuppressWarnings("unchecked") private static Map<String, View> controls(Activity activity) { return (Map<String, View>) field(activity, "controls"); }
    private static <T extends Activity> T resumed(Class<T> type) {
        for (Activity activity : ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED))
            if (type.isInstance(activity)) return type.cast(activity);
        return null;
    }
    private static void main(Runnable work) { InstrumentationRegistry.getInstrumentation().runOnMainSync(work); }
    private static void await(BooleanSupplier condition) throws Exception {
        long end = SystemClock.uptimeMillis() + 10_000;
        do {
            AtomicReference<Boolean> ready = new AtomicReference<>(false); main(() -> ready.set(condition.getAsBoolean()));
            if (ready.get()) return; Thread.sleep(25);
        } while (SystemClock.uptimeMillis() < end);
        fail("Actual remote icon presentation did not settle");
    }
    private void assertComposedPixel(Activity activity, ImageView view, String label) throws Exception {
        if (android.os.Build.VERSION.SDK_INT < 29) throw new AssertionError("Native frame capture requires API 29");
        assertNotNull(view);
        AtomicReference<Boolean> inScroll = new AtomicReference<>(false);
        main(() -> { android.view.ViewParent parent = view.getParent(); while (parent != null) {
            if (parent instanceof ScrollView) { inScroll.set(true); break; } parent = parent.getParent();
        }});
        if (inScroll.get()) onView(is((View) view)).perform(revealAboveNavigation());
        await(activity::hasWindowFocus);
        for (int i = 0; i < 2; i++) {
            CountDownLatch frame = new CountDownLatch(1);
            main(() -> { View root = activity.getWindow().getDecorView(); assertTrue(root.isHardwareAccelerated());
                root.getViewTreeObserver().registerFrameCommitCallback(frame::countDown); root.invalidate(); });
            assertTrue(frame.await(5, TimeUnit.SECONDS));
        }
        int[] point = new int[2]; main(() -> { assertTrue(magenta(view)); android.graphics.Rect clip = new android.graphics.Rect();
            assertTrue(view.getGlobalVisibleRect(clip)); assertEquals(view.getWidth(), clip.width()); assertEquals(view.getHeight(), clip.height());
            view.getLocationOnScreen(point); point[0] += view.getWidth() / 2; point[1] += view.getHeight() / 2; });
        Bitmap pixels = InstrumentationRegistry.getInstrumentation().getUiAutomation(UiAutomation.FLAG_DONT_USE_ACCESSIBILITY).takeScreenshot();
        assertNotNull(pixels);
        try { int observed = pixels.getPixel(point[0], point[1]);
            if (observed != Color.MAGENTA) save(pixels, label + "-pixels-failed");
            assertEquals(label, Color.MAGENTA, observed);
        } finally { pixels.recycle(); }
    }
    private void captureFailure(Throwable failure, String label) {
        if (!captured.add(failure)) return;
        try {
            Bitmap pixels = InstrumentationRegistry.getInstrumentation().getUiAutomation(UiAutomation.FLAG_DONT_USE_ACCESSIBILITY).takeScreenshot();
            if (pixels != null) try { save(pixels, label.replaceAll("[^A-Za-z0-9_-]", "_") + "-failure"); } finally { pixels.recycle(); }
        } catch (Exception | AssertionError observer) { failure.addSuppressed(observer); }
    }
    private void save(Bitmap pixels, String label) throws Exception {
        String run = InstrumentationRegistry.getArguments().getString("evidence_run", "remote-icon-presentation");
        if (!run.matches("[A-Za-z0-9_-]+")) throw new IllegalArgumentException("Invalid evidence namespace");
        File folder = new File(target.getFilesDir(), "ui-v3/" + run);
        assertTrue(folder.isDirectory() || folder.mkdirs());
        File output = new File(folder, label + "-" + UUID.randomUUID() + ".png"); assertTrue(output.createNewFile());
        try (FileOutputStream stream = new FileOutputStream(output)) { assertTrue(pixels.compress(Bitmap.CompressFormat.PNG, 100, stream)); }
    }
}
