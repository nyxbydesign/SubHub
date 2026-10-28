package com.subhub.app.activity;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ListView;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.R;
import com.subhub.app.UiAutomationStateRule;
import com.subhub.app.appmode.AppModeManager;
import com.subhub.app.appmode.AppTimerManager;
import com.subhub.app.onboarding.OnboardingState;
import com.subhub.app.people.PersonId;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.security.KeyholderPresentationAndroidTest;
import com.subhub.app.util.AsyncUiScope;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.RuleChain;
import org.junit.runner.RunWith;
import static androidx.test.espresso.Espresso.onData;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.*;
import static androidx.test.espresso.assertion.ViewAssertions.*;
import static androidx.test.espresso.matcher.RootMatchers.isDialog;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static org.hamcrest.Matchers.anything;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class DomActivityAndroidTest {
    private final KeyholderPresentationAndroidTest.IntegrationFixture integration = new KeyholderPresentationAndroidTest.IntegrationFixture();
    @Rule public final RuleChain rules = RuleChain.outerRule(integration).around(new UiAutomationStateRule());
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private ActivityRepository original;
    private FeedRepository repository;
    private static final Set<String> APPS = Set.of("com.android.chrome", "com.android.settings");

    @Before public void prepare() throws Exception {
        new com.subhub.app.settings.SettingsRepository(context).preferences().edit()
                .putString(com.subhub.app.security.DeviceRolePreferences.KEY_MODE,
                        com.subhub.app.security.DeviceRolePreferences.Mode.BOTH.name()).commit();
        original = ActivitySources.repository();
        repository = new FeedRepository();
        ActivitySources.install(repository);
        OnboardingState.complete(context);
        assertTrue(ControllerPinManager.setPin(context, "5729"));
        ControllerPinManager.enterDomMode();
        for (String app : APPS) context.getPackageManager().getApplicationInfo(app, 0);
        AppModeManager apps = new AppModeManager(context);
        apps.setAllAppsEnabled(false); apps.saveIncludedPackages(APPS);
        AppTimerManager timers = new AppTimerManager(context);
        timers.saveSettings(true, 30, true, 60);
        timers.saveAllowances(APPS, Map.of("com.android.chrome", 15, "com.android.settings", 40));
    }
    @After public void restore() {
        repository.releaseRead.countDown();
        ActivitySources.install(original);
    }

    @Test public void fourKindsFitTheConfiguredNativeFontScaleWithoutChangingAssignments() throws Exception {
        repository.latest = fourKinds(false);
        try (ActivityScenario<DomActivityActivity> page = ActivityScenario.launch(intent(null))) {
            awaitUi(page, DomActivityAndroidTest::ready);
            for (int index = 0; index < repository.latest.size(); index++) {
                final ActivityEntry expected = repository.latest.get(index);
                scrollRow(page, index, expected.key, 0);
                page.onActivity(a -> {
                    View row = a.findViewById(R.id.dom_activity_list).findViewWithTag("activity_entry:" + expected.key);
                    String copy = text(row);
                    assertTrue(copy.contains("Synthetic Alex"));
                    assertTrue(copy.contains(ActivityText.title(a, expected)));
                    if (!ActivityText.detail(a, expected).isEmpty()) assertTrue(copy.contains(ActivityText.detail(a, expected)));
                    assertFalse(copy.contains("paid successfully"));
                    assertTextFits(row);
                    assertTextFits(a.findViewById(R.id.primary_header));
                    assertTrue(a.findViewById(R.id.nav_activity).isSelected());
                    assertFalse(hasInput(a.findViewById(R.id.dom_activity_list)));
                });
                captureWholeRow(page, index, expected);
            }
            String scale = InstrumentationRegistry.getArguments().getString("expected_font_scale");
            if (scale != null) page.onActivity(a -> assertEquals(Float.parseFloat(scale),
                    a.getResources().getConfiguration().fontScale, .01f));
            assertEquals(APPS, new AppModeManager(context).getSelectedPackages());
            assertEquals(15, new AppTimerManager(context).allowanceMinutes("com.android.chrome"));
            assertEquals(40, new AppTimerManager(context).allowanceMinutes("com.android.settings"));
        }
    }

    private static void scrollRow(ActivityScenario<DomActivityActivity> page, int position,
                                  String key, int offset) throws Exception {
        page.onActivity(a -> ((ListView) a.findViewById(R.id.dom_activity_list))
                .setSelectionFromTop(position, -offset));
        awaitUi(page, a -> {
            ListView list = a.findViewById(R.id.dom_activity_list);
            View row = list.findViewWithTag("activity_entry:" + key);
            return ready(a) && !list.isLayoutRequested() && row != null
                    && row.isShown() && !row.isLayoutRequested();
        });
    }

    private void captureWholeRow(ActivityScenario<DomActivityActivity> page, int position,
                                 ActivityEntry expected) throws Exception {
        int coveredBottom = 0;
        for (int part = 0; part < 40; part++) {
            capture(page, "event-" + expected.event.kind.name() + (part == 0 ? "" : "-part-" + part));
            final int covered = coveredBottom;
            final int[] next = new int[2];
            page.onActivity(a -> {
                ListView list = a.findViewById(R.id.dom_activity_list);
                View row = list.findViewWithTag("activity_entry:" + expected.key);
                assertNotNull("Selected native row stays attached", row);
                assertTextFits(row);
                Rect visible = new Rect(), viewport = new Rect(), rail = new Rect();
                assertTrue("Row has a visible native portion", row.getGlobalVisibleRect(visible));
                assertTrue(list.getGlobalVisibleRect(viewport));
                assertTrue(a.findViewById(R.id.bottom_navigation).getGlobalVisibleRect(rail));
                viewport.bottom = Math.min(viewport.bottom, rail.top);
                assertTrue("Row portion is above the navigation rail", visible.intersect(viewport));
                int[] origin = new int[2];
                row.getLocationOnScreen(origin);
                assertEquals("Native row is not clipped horizontally", row.getWidth(), visible.width());
                assertTrue("Scrolling leaves no unobserved vertical gap", visible.top - origin[1] <= covered);
                next[0] = visible.bottom - origin[1];
                next[1] = row.getHeight();
                assertTrue("Native scrolling reveals new row content", next[0] > covered);
            });
            coveredBottom = next[0];
            if (coveredBottom >= next[1]) return;
            // Keep a one-pixel overlap so every part of a wrapped row is observed.
            scrollRow(page, position, expected.key, coveredBottom - 1);
        }
        fail("Native scrolling did not expose the complete row: " + expected.key);
    }

    @Test public void activityShowsOneHeadingPerLocalCalendarDay() throws Exception {
        ActivityEntry first = entry("first", ActivityEvent.Kind.APP_OPENED, false);
        ActivityEntry second = entry("second", ActivityEvent.Kind.APP_LIMIT_REACHED, false);
        long previous = first.event.observedAt - 48L * 60 * 60 * 1000;
        ActivityEvent event = new ActivityEvent("older", ActivityEvent.Kind.APP_OPENED, previous,
                "com.android.chrome", 0, "", "", 0);
        ActivityEntry older = new ActivityEntry("older", first.personId, first.personName, first.device,
                event, previous, false, "Chrome");
        repository.latest = List.of(first, second, older);
        try (ActivityScenario<DomActivityActivity> page = ActivityScenario.launch(intent(null))) {
            awaitUi(page, DomActivityAndroidTest::ready);
            for (int index = 0; index < repository.latest.size(); index++) {
                ActivityEntry expected = repository.latest.get(index);
                scrollRow(page, index, expected.key, 0);
                final boolean hasHeading = index != 1;
                page.onActivity(a -> {
                    ListView list = a.findViewById(R.id.dom_activity_list);
                    View heading = list.findViewWithTag("activity_date:" + expected.key);
                    if (hasHeading) assertNotNull(heading);
                    else assertNull(heading);
                    assertNotNull(list.findViewWithTag("activity_entry:" + expected.key));
                });
            }
        }
    }

    @Test public void repeatedEventsExpandWithNativeTapsAndKeepTheirExpansionAfterRecreation() throws Exception {
        repository.latest = List.of(entry("group-first",ActivityEvent.Kind.APP_OPENED,false),
                entry("other-kind",ActivityEvent.Kind.TRIBUTE_TRIGGERED,false),
                entry("group-second",ActivityEvent.Kind.APP_OPENED,false),
                entry("group-third",ActivityEvent.Kind.APP_OPENED,false));
        try(ActivityScenario<DomActivityActivity> page=ActivityScenario.launch(intent(null))) {
            awaitUi(page,DomActivityAndroidTest::ready);
            page.onActivity(a->{ListView list=a.findViewById(R.id.dom_activity_list);
                assertEquals(2,list.getAdapter().getCount()-list.getFooterViewsCount());
                assertNull(list.findViewWithTag("activity_entry:group-second"));
                assertEquals(a.getString(R.string.dom_activity_group_expand,3),((TextView)list.findViewWithTag("activity_group:group-first")).getText().toString());
                assertTextFits(list);});
            capture(page,"group-collapsed");
            onView(withTagValue(org.hamcrest.Matchers.is((Object)"activity_group:group-first"))).perform(click());
            awaitUi(page,a->ready(a)&&a.findViewById(R.id.dom_activity_list).findViewWithTag("activity_entry:group-third")!=null);
            page.onActivity(a->assertTextFits(a.findViewById(R.id.dom_activity_list)));
            capture(page,"group-expanded");
            page.recreate();
            awaitUi(page,a->ready(a)&&a.findViewById(R.id.dom_activity_list).findViewWithTag("activity_entry:group-second")!=null);
            onView(withTagValue(org.hamcrest.Matchers.is((Object)"activity_group:group-first"))).perform(click());
            awaitUi(page,a->ready(a)&&a.findViewById(R.id.dom_activity_list).findViewWithTag("activity_entry:group-second")==null);
            assertEquals(4,repository.latest.size());
        }
    }
    @Test public void aNotificationFocusExpandsItsGroupAndRevealsTheOriginalFact() throws Exception {
        List<ActivityEntry> facts=new ArrayList<>();
        for(int index=0;index<8;index++)facts.add(entry("focus-group-"+index,ActivityEvent.Kind.APP_OPENED,false));
        repository.history=facts;
        try(ActivityScenario<DomActivityActivity> page=ActivityScenario.launch(intent("focus-group-7"))) {
            awaitUi(page,a->{if(!ready(a))return false;
                View row=a.findViewById(R.id.dom_activity_list).findViewWithTag("activity_entry:focus-group-7");
                Rect bounds=new Rect(),rail=new Rect();a.findViewById(R.id.bottom_navigation).getGlobalVisibleRect(rail);
                return row!=null&&row.getGlobalVisibleRect(bounds)&&bounds.top<rail.top&&bounds.bottom>bounds.top;});
            assertEquals(List.of("focus-group-7"),repository.locations);
            page.recreate();awaitUi(page,DomActivityAndroidTest::ready);
            assertEquals(1,repository.locations.size());
            page.onActivity(a->assertNotNull(a.findViewById(R.id.dom_activity_list).findViewWithTag("activity_entry:focus-group-7")));
        }
    }

    @Test public void notificationEntryDoesNotQueryOrShowPrivateFactsUntilPinUnlock() throws Exception {
        repository.latest = fourKinds(false);
        ControllerPinManager.enterSubMode();
        try (ActivityScenario<DomActivityActivity> page = ActivityScenario.launch(intent("fixture-open"))) {
            onView(withHint(R.string.controller_pin_label)).inRoot(isDialog()).check(matches(isDisplayed()));
            assertEquals(0, repository.loads.size());
            assertEquals(0, repository.locations.size());
            page.onActivity(a -> {
                assertFalse(a.findViewById(R.id.dom_activity_list).isShown());
                assertFalse(a.findViewById(R.id.dom_activity_unread_count).isShown());
            });
            onView(withText("Synthetic Alex")).check(doesNotExist());
            onView(withHint(R.string.controller_pin_label)).inRoot(isDialog()).perform(replaceText("5729"), closeSoftKeyboard());
            onView(withText(R.string.controller_pin_unlock)).inRoot(isDialog()).perform(click());
            awaitUi(page, DomActivityAndroidTest::ready);
            assertTrue(ControllerPinManager.isDomModeActive());
            assertEquals(List.of("fixture-open"), repository.locations);
            page.onActivity(a -> assertTrue(text(a.findViewById(R.id.dom_activity_list)).contains("Synthetic Alex")));
        }
    }

    @Test public void olderNotificationLocatesItsPageAndRecreationRetainsCursorWithoutRefocusing() throws Exception {
        repository.latest = List.of(entry("fixture-new", ActivityEvent.Kind.APP_OPENED, false));
        repository.history = fourKinds(false);
        try (ActivityScenario<DomActivityActivity> page = ActivityScenario.launch(intent("fixture-app-limit"))) {
            awaitUi(page, a -> ready(a) && "history-page".equals(new androidx.lifecycle.ViewModelProvider(a)
                    .get(DomActivityActivity.FeedState.class).cursor));
            assertEquals(List.of("fixture-app-limit"), repository.locations);
            page.onActivity(a -> assertFalse(a.getIntent().hasExtra(DomActivityActivity.EXTRA_ENTRY_KEY)));
            // A user's later native scroll must survive recreation instead of replaying the notification focus.
            onData(anything()).inAdapterView(withId(R.id.dom_activity_list)).atPosition(0).check(matches(isDisplayed()));
            final int[] position = new int[1];
            page.onActivity(a -> position[0] = ((ListView) a.findViewById(R.id.dom_activity_list)).getFirstVisiblePosition());
            page.recreate();
            awaitUi(page, DomActivityAndroidTest::ready);
            assertEquals(1, repository.locations.size());
            assertTrue(repository.loads.contains("history-page"));
            page.onActivity(a -> {
                assertEquals("history-page", new androidx.lifecycle.ViewModelProvider(a).get(DomActivityActivity.FeedState.class).cursor);
                assertEquals(position[0], ((ListView) a.findViewById(R.id.dom_activity_list)).getFirstVisiblePosition());
                assertFalse(a.getIntent().hasExtra(DomActivityActivity.EXTRA_ENTRY_KEY));
                assertNull(a.findViewById(R.id.dom_activity_list).findViewWithTag("activity_entry:fixture-new"));
            });
        }
    }

    @Test public void acknowledgementsContainOnlyRowsVisibleAboveTheNavigationRail() throws Exception {
        List<ActivityEntry> many = new ArrayList<>();
        for (int index = 0; index < 50; index++) many.add(entry("fixture-visible-" + index, ActivityEvent.Kind.APP_OPENED, true));
        repository.latest = many;
        repository.blockRead = true;
        try (ActivityScenario<DomActivityActivity> page = ActivityScenario.launch(intent(null))) {
            assertTrue("Visible read submission arrived", repository.readStarted.await(8, TimeUnit.SECONDS));
            Set<String> eligible = new LinkedHashSet<>();
            page.onActivity(a -> {
                ListView list = a.findViewById(R.id.dom_activity_list);
                Rect rail = new Rect(); assertTrue(a.findViewById(R.id.bottom_navigation).getGlobalVisibleRect(rail));
                for (int index = list.getFirstVisiblePosition(); index <= list.getLastVisiblePosition() && index < many.size(); index++) {
                    View row = list.getChildAt(index - list.getFirstVisiblePosition());
                    Rect bounds = new Rect();
                    if (row != null && row.getGlobalVisibleRect(bounds)) {
                        bounds.bottom = Math.min(bounds.bottom, rail.top);
                        if (bounds.height() >= Math.min(row.getHeight(), Math.round(48 * a.getResources().getDisplayMetrics().density)) / 2)
                            eligible.add(many.get(index).key);
                    }
                }
            });
            assertFalse(eligible.isEmpty());
            assertFalse(repository.submitted.isEmpty());
            assertTrue("No offscreen or rail-obscured row is acknowledged", eligible.containsAll(repository.submitted));
            assertFalse(repository.submitted.contains("fixture-visible-49"));
            assertEquals("Collapsed facts are not acknowledged merely because their group is visible",
                    List.of("fixture-visible-0"),repository.submitted);
            repository.releaseRead.countDown();
            awaitUi(page, a -> ready(a) && repository.read.containsAll(repository.submitted));
        }
    }

    @Test public void pausingAndRelockingHideRetainedUnreadMetadataUntilAuthentication() throws Exception {
        repository.latest = fourKinds(true);
        repository.blockRead = true;
        try (ActivityScenario<DomActivityActivity> page = ActivityScenario.launch(intent(null))) {
            assertTrue("Initial visible read was submitted", repository.readStarted.await(8, TimeUnit.SECONDS));
            page.onActivity(a -> assertEquals(View.VISIBLE, a.findViewById(R.id.dom_activity_unread_count).getVisibility()));
            page.moveToState(androidx.lifecycle.Lifecycle.State.CREATED);
            page.onActivity(a -> assertEquals(View.GONE, a.findViewById(R.id.dom_activity_unread_count).getVisibility()));
            ControllerPinManager.enterSubMode();
            page.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED);
            onView(withHint(R.string.controller_pin_label)).inRoot(isDialog()).check(matches(isDisplayed()));
            repository.releaseRead.countDown();
            page.onActivity(a -> {
                assertEquals(View.GONE, a.findViewById(R.id.dom_activity_unread_count).getVisibility());
                assertFalse(a.findViewById(R.id.dom_activity_list).isShown());
            });
            assertFalse(ControllerPinManager.isDomModeActive());
        }
    }

    @Test public void localHistoryPagingRecreationAndLatestNeverSwitchToTheRemoteFeed() throws Exception {
        ActivityEntry opened = entry("local:55", ActivityEvent.Kind.APP_OPENED, false);
        ActivityEntry limit = entry("local:41", ActivityEvent.Kind.SHARED_LIMIT_REACHED, false);
        repository.localLatest = List.of(new ActivityEntry(opened.key, PersonId.LOCAL, "This device", opened.device,
                opened.event, opened.receivedAt, false, opened.appLabel));
        repository.localHistory = List.of(new ActivityEntry(limit.key, PersonId.LOCAL, "This device", limit.device,
                limit.event, limit.receivedAt, false, limit.appLabel));
        repository.latest = fourKinds(true);
        try (ActivityScenario<DomActivityActivity> page = ActivityScenario.launch(
                intent(null).putExtra(DomActivityActivity.EXTRA_LOCAL_HISTORY, true))) {
            awaitUi(page, a -> ready(a) && "local:MAX".equals(new androidx.lifecycle.ViewModelProvider(a)
                    .get(DomActivityActivity.FeedState.class).cursor));
            assertEquals("local:MAX", repository.loads.get(0));
            page.onActivity(a -> {
                assertTrue(new androidx.lifecycle.ViewModelProvider(a).get(DomActivityActivity.FeedState.class).local);
                assertEquals(View.GONE, a.findViewById(R.id.dom_activity_latest_action).getVisibility());
                assertEquals(View.GONE, a.findViewById(R.id.dom_activity_unread_count).getVisibility());
                assertTrue(text(a.findViewById(R.id.dom_activity_list)).contains("This device"));
                assertFalse(text(a.findViewById(R.id.dom_activity_list)).contains("Synthetic Alex"));
            });
            onView(withId(R.id.dom_activity_older_action)).check(matches(isDisplayed())).perform(click());
            awaitUi(page, a -> ready(a) && "local:42".equals(new androidx.lifecycle.ViewModelProvider(a)
                    .get(DomActivityActivity.FeedState.class).cursor));
            assertTrue(repository.loads.contains("local:42"));
            page.recreate();
            awaitUi(page, a -> ready(a) && "local:42".equals(new androidx.lifecycle.ViewModelProvider(a)
                    .get(DomActivityActivity.FeedState.class).cursor));
            page.onActivity(a -> {
                assertTrue(new androidx.lifecycle.ViewModelProvider(a).get(DomActivityActivity.FeedState.class).local);
                assertEquals(View.VISIBLE, a.findViewById(R.id.dom_activity_latest_action).getVisibility());
                assertEquals(View.GONE, a.findViewById(R.id.dom_activity_unread_count).getVisibility());
            });
            onView(withId(R.id.dom_activity_latest_action)).perform(click());
            awaitUi(page, a -> ready(a) && "local:MAX".equals(new androidx.lifecycle.ViewModelProvider(a)
                    .get(DomActivityActivity.FeedState.class).cursor));
            assertEquals("local:MAX", repository.loads.get(repository.loads.size() - 1));
            page.onActivity(a -> {
                assertEquals(View.GONE, a.findViewById(R.id.dom_activity_latest_action).getVisibility());
                assertEquals(View.GONE, a.findViewById(R.id.dom_activity_unread_count).getVisibility());
                assertFalse(text(a.findViewById(R.id.dom_activity_list)).contains("Synthetic Alex"));
            });
            for (String cursor : repository.loads) assertTrue("Every history query stays local: " + cursor,
                    cursor != null && cursor.startsWith("local:"));
            assertTrue(repository.locations.isEmpty());
            awaitUi(page, a -> a.findViewById(R.id.nav_activity_unread).getVisibility() == View.VISIBLE
                    && (a.getString(R.string.dom_activity_title) + ", "
                    + a.getString(R.string.dom_activity_unread_count, 4))
                    .contentEquals(a.findViewById(R.id.nav_activity).getContentDescription()));
            assertTrue("The global Activity badge queries unread remote activity", repository.unreadQueries > 0);
            assertTrue(repository.submitted.isEmpty());
        }
    }

    @Test public void subOnlyDirectDomFeedClosesWithoutQueryingPrivateFacts() {
        assertTrue(com.subhub.app.security.DeviceRolePreferences.set(context,
                com.subhub.app.security.DeviceRolePreferences.Mode.SUB_ONLY));
        repository.latest = fourKinds(true);
        ControllerPinManager.enterSubMode();
        try (ActivityScenario<DomActivityActivity> page = ActivityScenario.launch(intent("fixture-tribute"))) {
            long deadline = SystemClock.uptimeMillis() + 8000;
            while (page.getState() != androidx.lifecycle.Lifecycle.State.DESTROYED && SystemClock.uptimeMillis() < deadline) {
                InstrumentationRegistry.getInstrumentation().waitForIdleSync();
                Thread.yield();
            }
            assertEquals(androidx.lifecycle.Lifecycle.State.DESTROYED, page.getState());
            assertTrue(repository.loads.isEmpty());
            assertTrue(repository.locations.isEmpty());
            assertEquals(0, repository.unreadQueries);
            assertTrue(repository.submitted.isEmpty());
            assertFalse(ControllerPinManager.isDomModeActive());
        }
    }

    private Intent intent(String focus) {
        Intent intent = new Intent(context, DomActivityActivity.class);
        if (focus != null) intent.putExtra(DomActivityActivity.EXTRA_ENTRY_KEY, focus);
        return intent;
    }
    static ActivityEntry entry(String key, ActivityEvent.Kind kind, boolean unread) {
        String app = kind == ActivityEvent.Kind.APP_OPENED ? "com.android.chrome"
                : kind == ActivityEvent.Kind.APP_LIMIT_REACHED ? "com.android.settings" : "";
        ActivityEvent event = new ActivityEvent(key, kind, 1_700_000_000_000L, app,
                kind == ActivityEvent.Kind.TRIBUTE_TRIGGERED ? 125 : 0,
                kind == ActivityEvent.Kind.TRIBUTE_TRIGGERED ? "EUR" : "",
                kind == ActivityEvent.Kind.TRIBUTE_TRIGGERED ? "watched_app_open" : "",
                kind == ActivityEvent.Kind.APP_LIMIT_REACHED ? 40 : kind == ActivityEvent.Kind.SHARED_LIMIT_REACHED ? 60 : 0);
        return new ActivityEntry(key, new PersonId("peer:activity-fixture"), "Synthetic Alex", "Synthetic Android",
                event, event.observedAt + 100, unread, app.equals("com.android.chrome") ? "Chrome" : app.equals("com.android.settings") ? "Settings" : "");
    }
    private static List<ActivityEntry> fourKinds(boolean unread) {
        return List.of(entry("fixture-open", ActivityEvent.Kind.APP_OPENED, unread),
                entry("fixture-tribute", ActivityEvent.Kind.TRIBUTE_TRIGGERED, unread),
                entry("fixture-app-limit", ActivityEvent.Kind.APP_LIMIT_REACHED, unread),
                entry("fixture-shared-limit", ActivityEvent.Kind.SHARED_LIMIT_REACHED, unread));
    }
    private static boolean ready(DomActivityActivity activity) {
        ListView list = activity.findViewById(R.id.dom_activity_list);
        return list.isShown() && !list.isLayoutRequested() && list.getChildCount() > 0
                && !AsyncUiScope.forPage(activity).isPending("activity-page");
    }
    static void awaitUi(ActivityScenario<DomActivityActivity> scenario, Predicate<DomActivityActivity> condition) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        long deadline = SystemClock.uptimeMillis() + 8000;
        scenario.onActivity(activity -> {
            Runnable check = new Runnable() {
                @Override public void run() {
                    try {
                        if (condition.test(activity)) { done.countDown(); return; }
                        if (SystemClock.uptimeMillis() >= deadline) throw new AssertionError("Activity UI did not settle before deadline");
                        activity.getWindow().getDecorView().postOnAnimation(this);
                    } catch (Throwable error) { failure.set(error); done.countDown(); }
                }
            };
            check.run();
        });
        assertTrue("UI observation completed", done.await(9, TimeUnit.SECONDS));
        if (failure.get() != null) throw new AssertionError(failure.get());
    }
    private void capture(ActivityScenario<DomActivityActivity> page, String name) throws Exception {
        float scale = context.getResources().getConfiguration().fontScale;
        File output = new File(context.getFilesDir(), "activity-" + name + "-font-" + scale + ".png");
        Bitmap previous = null;
        long deadline = SystemClock.uptimeMillis() + 7000, stableSince = 0;
        try {
            awaitUi(page, DomActivityAndroidTest::ready);
            do {
                CountDownLatch committed = new CountDownLatch(1);
                page.onActivity(a -> {
                    View root = a.getWindow().getDecorView();
                    if (android.os.Build.VERSION.SDK_INT >= 29) {
                        assertTrue("Native capture uses hardware rendering", root.isHardwareAccelerated());
                        root.getViewTreeObserver().registerFrameCommitCallback(committed::countDown);
                        root.invalidate();
                    } else root.postOnAnimation(committed::countDown);
                });
                assertTrue("No native render frame committed", committed.await(2, TimeUnit.SECONDS));
                Bitmap current = InstrumentationRegistry.getInstrumentation()
                        .getUiAutomation(android.app.UiAutomation.FLAG_DONT_USE_ACCESSIBILITY).takeScreenshot();
                assertNotNull("Native compositor capture", current);
                long now = SystemClock.uptimeMillis();
                boolean same = previous != null && previous.sameAs(current);
                if (!same) stableSince = now;
                if (previous != null) previous.recycle();
                previous = current;
                AtomicReference<Boolean> stillReady = new AtomicReference<>(false);
                page.onActivity(a -> {
                    View root = a.getWindow().getDecorView();
                    View snackbar = root.findViewById(com.google.android.material.R.id.snackbar_text);
                    View navigation = root.findViewById(R.id.bottom_navigation);
                    stillReady.set(ready(a) && a.hasWindowFocus() && root.isLaidOut()
                            && !root.isLayoutRequested() && (snackbar == null || !snackbar.isShown())
                            && navigation.getWidth() > 0 && navigation.getHeight() > 0);
                });
                if (!stillReady.get()) stableSince = now;
                if (same && stillReady.get() && now - stableSince >= 200) {
                    try (FileOutputStream stream = new FileOutputStream(output)) {
                        assertTrue(previous.compress(Bitmap.CompressFormat.PNG, 100, stream));
                    }
                    return;
                }
            } while (SystemClock.uptimeMillis() < deadline);
            throw new AssertionError("Native Activity compositor did not settle");
        } catch (Exception | AssertionError failure) {
            try {
                if (previous == null) previous = InstrumentationRegistry.getInstrumentation()
                        .getUiAutomation(android.app.UiAutomation.FLAG_DONT_USE_ACCESSIBILITY).takeScreenshot();
                if (previous != null) try (FileOutputStream stream = new FileOutputStream(new File(
                        context.getFilesDir(), "activity-" + name + "-font-" + scale
                        + "-compositor-failure-" + SystemClock.uptimeMillis() + ".png"))) {
                    assertTrue(previous.compress(Bitmap.CompressFormat.PNG, 100, stream));
                }
            } catch (Exception | AssertionError exportFailure) { failure.addSuppressed(exportFailure); }
            throw failure;
        } finally { if (previous != null) previous.recycle(); }
    }
    private static void assertTextFits(View view) {
        if (!view.isShown()) return;
        if (view instanceof TextView) {
            TextView text = (TextView) view;
            assertNotNull("Native paragraph layout: " + text.getText(), text.getLayout());
            int width = text.getWidth() - text.getCompoundPaddingLeft() - text.getCompoundPaddingRight();
            for (int line = 0; line < text.getLayout().getLineCount(); line++) {
                assertEquals("Text is not ellipsized", 0, text.getLayout().getEllipsisCount(line));
                assertTrue("Text width: " + text.getText(), text.getLayout().getLineMax(line) <= width + 1);
            }
            assertTrue("Text height: " + text.getText(), text.getLayout().getHeight()
                    <= text.getHeight() - text.getCompoundPaddingTop() - text.getCompoundPaddingBottom());
        }
        if (view instanceof ViewGroup) for (int index = 0; index < ((ViewGroup) view).getChildCount(); index++)
            assertTextFits(((ViewGroup) view).getChildAt(index));
    }
    private static boolean hasInput(View view) {
        if (view instanceof android.widget.EditText || view instanceof android.widget.Spinner || view instanceof android.widget.CompoundButton) return true;
        if (view instanceof ViewGroup) for (int index = 0; index < ((ViewGroup) view).getChildCount(); index++)
            if (hasInput(((ViewGroup) view).getChildAt(index))) return true;
        return false;
    }
    static String text(View view) {
        if (view == null || !view.isShown()) return "";
        StringBuilder value = new StringBuilder();
        if (view instanceof TextView) value.append(((TextView) view).getText());
        if (view instanceof ViewGroup) for (int index = 0; index < ((ViewGroup) view).getChildCount(); index++)
            value.append('\n').append(text(((ViewGroup) view).getChildAt(index)));
        return value.toString();
    }

    static final class FeedRepository implements ActivityRepository {
        volatile List<ActivityEntry> latest = List.of(), history = List.of();
        volatile List<ActivityEntry> localLatest = List.of(), localHistory = List.of();
        volatile int unreadQueries;
        final List<String> loads = Collections.synchronizedList(new ArrayList<>());
        final List<String> locations = Collections.synchronizedList(new ArrayList<>());
        final Set<String> read = Collections.synchronizedSet(new LinkedHashSet<>());
        volatile List<String> submitted = List.of();
        volatile boolean blockRead;
        final CountDownLatch readStarted = new CountDownLatch(1), releaseRead = new CountDownLatch(1);
        public Page load(String cursor, int limit) {
            loads.add(cursor);
            if ("local:MAX".equals(cursor)) return new Page(localLatest, "local:42", cursor);
            if ("local:42".equals(cursor)) return new Page(localHistory, null, cursor);
            return page("history-page".equals(cursor) ? history : latest, cursor);
        }
        public Page locate(String key, int limit) {
            locations.add(key);
            for (ActivityEntry entry : latest) if (entry.key.equals(key)) return page(latest, null);
            return page(history, "history-page");
        }
        private Page page(List<ActivityEntry> entries, String cursor) {
            List<ActivityEntry> snapshot = new ArrayList<>();
            for (ActivityEntry entry : entries) snapshot.add(read.contains(entry.key) ? entry.read() : entry);
            return new Page(snapshot, cursor == null && !history.isEmpty() ? "history-page" : null, cursor);
        }
        public java.util.List<ActivityEntry> recent(com.subhub.app.people.PersonId person, int limit) {
            java.util.List<ActivityEntry> entries = new java.util.ArrayList<>();
            for (ActivityEntry entry : latest) if (person == null || person.equals(entry.personId)) {
                if (entries.size() >= limit) break; entries.add(entry);
            }
            return entries;
        }
        public int unreadCount() {
            unreadQueries++;
            int count = 0;
            for (ActivityEntry entry : latest) if (entry.unread && !read.contains(entry.key)) count++;
            for (ActivityEntry entry : history) if (entry.unread && !read.contains(entry.key)) count++;
            return count;
        }
        public void markRead(List<String> keys) throws Exception {
            submitted = Collections.unmodifiableList(new ArrayList<>(keys));
            readStarted.countDown();
            if (blockRead && !releaseRead.await(8, TimeUnit.SECONDS)) throw new AssertionError("Read witness was not released");
            read.addAll(keys);
        }
    }
}
