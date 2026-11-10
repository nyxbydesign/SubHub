package com.subhub.app.tasks;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.closeSoftKeyboard;
import static androidx.test.espresso.action.ViewActions.replaceText;
import static androidx.test.espresso.assertion.ViewAssertions.doesNotExist;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withTagValue;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.*;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.NotificationManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.SystemClock;
import android.service.notification.StatusBarNotification;
import android.util.Base64;
import android.view.View;
import android.view.ViewGroup;
import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.espresso.intent.Intents;
import androidx.test.espresso.intent.matcher.IntentMatchers;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.NativeUiActions;
import com.subhub.app.R;
import com.subhub.app.UiAutomationStateRule;
import com.subhub.app.people.PersonId;
import com.subhub.app.people.PersonRoutes;
import com.subhub.app.people.PersonUi;
import com.subhub.app.remote.RemoteAssets;
import com.subhub.app.remote.RemoteFeature;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.security.DeviceRolePreferences;
import com.subhub.app.security.SubFeaturePolicy;
import com.subhub.app.settings.SettingsRepository;
import com.subhub.app.settings.SharedPreferenceTestRestore;
import java.io.File;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Native controls against the real local ledger on an unpaired, empty Tasks fixture. */
@RunWith(AndroidJUnit4.class)
public final class TasksAndroidTest {
    @Rule public final com.subhub.app.OwnedUiIntegrationFixture integration =
            new com.subhub.app.OwnedUiIntegrationFixture();
    @Rule public final UiAutomationStateRule automation = new UiAutomationStateRule();
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final Context context = instrumentation.getTargetContext();
    private final String titlePrefix = "Tasks UI " + UUID.randomUUID() + " ";
    private final Map<String, List<ContentValues>> originalRows = new LinkedHashMap<>();
    private final List<File> createdMediaFiles = new ArrayList<>();
    private Map<String, ?> originalConsent;
    private final Map<String, Map<String, ?>> presentationPreferences = new LinkedHashMap<>();
    private TaskRepository repository;
    private TaskStore store;
    private String scope;
    private boolean ownsFixture;

    @Before public void prepare() throws Exception {
        assertTrue("Use an unpaired Tasks UI guest", RemoteFeature.get(context).store.peers(false).isEmpty());
        repository = new TaskRepository(context, PersonId.LOCAL);
        store = TaskRuntime.get(context).store;
        scope = repository.target().scope;
        assertEquals("local", scope);
        synchronized (SubFeaturePolicy.LOCK) {
            assertTrue("Use an empty Tasks fixture; existing tasks are never removed", store.load(scope).definitions().isEmpty());
            SQLiteDatabase db = store.getReadableDatabase();
            for (String table : List.of("projections", "journal", "receipts", "cursors", "effects")) {
                ArrayList<ContentValues> rows = new ArrayList<>();
                try (Cursor cursor = db.query(table, null, "scope=?", new String[]{scope}, null, null, null)) {
                    while (cursor.moveToNext()) {
                        ContentValues row = new ContentValues();
                        for (int i = 0; i < cursor.getColumnCount(); i++) {
                            String key = cursor.getColumnName(i);
                            switch (cursor.getType(i)) {
                                case Cursor.FIELD_TYPE_NULL: row.putNull(key); break;
                                case Cursor.FIELD_TYPE_INTEGER: row.put(key, cursor.getLong(i)); break;
                                case Cursor.FIELD_TYPE_STRING: row.put(key, cursor.getString(i)); break;
                                default: throw new AssertionError("Unexpected task fixture column");
                            }
                        }
                        rows.add(row);
                    }
                }
                originalRows.put(table, rows);
            }
            ownsFixture = true;
        }
        SharedPreferences consent = context.getSharedPreferences(SubFeaturePolicy.STORE, 0);
        originalConsent = new LinkedHashMap<>(consent.getAll());
        assertTrue(consent.edit().putBoolean("tasks", true).putBoolean("hardcore_locally_consented", false).commit());
        assertTrue(context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME, 0).edit()
                .putString(DeviceRolePreferences.KEY_MODE, DeviceRolePreferences.Mode.BOTH.name()).commit());
        ControllerPinManager.initialize(context);
        assertTrue(ControllerPinManager.setPin(context, "2468"));
        ControllerPinManager.enterDomMode();
        assertTrue(PersonRoutes.canEditLocal(context, PersonId.LOCAL));
        instrumentation.waitForIdleSync();
    }

    @After public void restore() throws Exception {
        integration.drainReaders();
        for (Map.Entry<String, Map<String, ?>> saved : presentationPreferences.entrySet())
            SharedPreferenceTestRestore.restore(context.getSharedPreferences(saved.getKey(), 0), saved.getValue());
        if (ownsFixture) {
            synchronized (SubFeaturePolicy.LOCK) {
                TaskEngine engine = store.load(scope);
                for (TaskDefinition task : engine.definitions())
                    assertTrue("Do not delete another fixture's tasks", task.title.startsWith(titlePrefix));
                Set<String> ids = new java.util.HashSet<>();
                JSONArray actions = engine.json().getJSONArray("actions");
                for (int i = 0; i < actions.length(); i++) ids.add(actions.getJSONObject(i).getString("id"));
                NotificationManager notifications = context.getSystemService(NotificationManager.class);
                for (StatusBarNotification posted : notifications.getActiveNotifications()) {
                    for (String id : ids) if (("tasks:" + scope + ":" + id).equals(posted.getTag()))
                        notifications.cancel(posted.getTag(), posted.getId());
                }
                SQLiteDatabase db = store.getWritableDatabase();
                db.beginTransaction();
                try {
                    for (Map.Entry<String, List<ContentValues>> table : originalRows.entrySet()) {
                        db.delete(table.getKey(), "scope=?", new String[]{scope});
                        for (ContentValues row : table.getValue()) db.insertOrThrow(table.getKey(), null, row);
                    }
                    db.setTransactionSuccessful();
                } finally { db.endTransaction(); }
            }
        }
        if (originalConsent != null)
            SharedPreferenceTestRestore.restore(context.getSharedPreferences(SubFeaturePolicy.STORE, 0), originalConsent);
        for (File file : createdMediaFiles) {
            assertEquals(taskMediaRoot(), file.getParentFile());
            assertFalse(Files.isSymbolicLink(file.toPath()));
            assertTrue(!file.exists() || file.delete());
        }
    }

    @Test public void createWithNativeControlsAndRetainAuthorDraftAfterRecreation() throws Exception {
        try (ActivityScenario<TasksActivity> page = launch()) {
            awaitTag(page, "tasks_create");
            tap("tasks_create");
            edit("tasks_title", titlePrefix + "created");
            edit("tasks_text", "A synthetic task with clear instructions.");
            edit("tasks_duration", "45");
            tap("tasks_day_1"); tap("tasks_day_5"); tap("tasks_refusal");
            page.recreate();
            awaitTag(page, "tasks_save");
            onView(withTagValue(is("tasks_zone"))).check(doesNotExist());
            onView(withTagValue(is("tasks_timezone"))).check(matches(withText(
                    context.getString(R.string.tasks_timezone, ZoneId.systemDefault().getId()))));
            onView(withTagValue(is("tasks_title"))).check(matches(withText(titlePrefix + "created")));
            onView(withTagValue(is("tasks_duration"))).check(matches(withText("45")));
            onView(withTagValue(is("tasks_title"))).perform(NativeUiActions.revealAboveNavigation());
            capture("tasks-create");
            tap("tasks_save");
            await(() -> store.load(scope).definitions().size() == 1);
            TaskDefinition created = store.load(scope).definitions().get(0);
            assertEquals(titlePrefix + "created", created.title);
            assertEquals(45 * 60_000L, created.durationMillis);
            assertEquals(EnumSet.of(DayOfWeek.MONDAY, DayOfWeek.FRIDAY), created.weekdays);
            assertTrue(created.allowRefusal);
            assertFalse(created.failureTribute);
            assertEquals(ZoneId.systemDefault(), created.zone);
            awaitTag(page, "tasks_create");
            onView(withTagValue(is("tasks_allowed"))).check(doesNotExist());
            assertEquals(1, store.load(scope).definitions().size());
        }
    }

    @Test public void subOnlyHeaderSurvivesDetailBackAndRecreation() throws Exception {
        assertTaskHeaderNavigation(DeviceRolePreferences.Mode.SUB_ONLY, false);
    }

    @Test public void bothSubHeaderSurvivesDetailBackAndRecreation() throws Exception {
        assertTaskHeaderNavigation(DeviceRolePreferences.Mode.BOTH, false);
    }

    @Test public void bothDomHeaderSurvivesDetailBackAndRecreation() throws Exception {
        assertTaskHeaderNavigation(DeviceRolePreferences.Mode.BOTH, true);
    }

    private void assertTaskHeaderNavigation(DeviceRolePreferences.Mode mode, boolean dom) throws Exception {
        TaskDefinition task = seed("header navigation", false);
        assertTrue(context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME, 0).edit()
                .putString(DeviceRolePreferences.KEY_MODE, mode.name()).commit());
        if (dom) ControllerPinManager.enterDomMode(); else ControllerPinManager.enterSubMode();
        try (ActivityScenario<TasksActivity> page = launch()) {
            awaitTag(page, "task_open:" + occurrenceId(task));
            assertTaskHeader(page, mode, dom, false);
            TaskDefinition added = seed("arrives while list is open", false);
            awaitTag(page, "task_open:" + occurrenceId(added));
            for (int visit = 0; visit < 2; visit++) {
                openTask(page, task);
                assertTaskHeader(page, mode, dom, true);
                page.recreate();
                awaitTag(page, "task_detail:" + occurrenceId(task));
                assertTaskHeader(page, mode, dom, true);
                if (visit == 0) {
                    assertTrue(store.append(scope, new TaskAction(UUID.randomUUID().toString(), task.id,
                            TaskAction.Kind.SUBMIT, task.firstStart.toLocalDate(), System.currentTimeMillis(),
                            "Synthetic completion while detail is open", "", List.of(), null), false, false));
                    awaitTag(page, "task_submission:" + occurrenceId(task));
                    assertTaskHeader(page, mode, dom, true);
                }
                page.moveToState(Lifecycle.State.CREATED);
                page.moveToState(Lifecycle.State.RESUMED);
                awaitTag(page, "task_detail:" + occurrenceId(task));
                assertTaskHeader(page, mode, dom, true);
                awaitNativeTaskControl(page, R.id.button_back);
                AtomicReference<String> backTrace = new AtomicReference<>(visit == 0 ? "header Back" : "system Back");
                AtomicReference<TasksActivity> backHost = new AtomicReference<>();
                page.onActivity(activity -> {
                    backHost.set(activity);
                    backTrace.updateAndGet(previous -> previous + "; before=" + taskNavigationState(activity));
                });
                boolean nativeWitness = visit == 0 && Boolean.parseBoolean(InstrumentationRegistry.getArguments()
                        .getString("header_touch_witness", "false"));
                try (com.subhub.app.NativeWindowTouchWitness touch = nativeWitness
                        ? com.subhub.app.NativeWindowTouchWitness.begin(instrumentation, backHost.get(),
                                R.id.button_back, mode.name()) : null) {
                    if (visit == 0) tapTaskControl(page, R.id.button_back);
                    else androidx.test.espresso.Espresso.pressBack();
                    instrumentation.runOnMainSync(() -> backTrace.updateAndGet(previous -> previous
                            + "; immediatelyAfter=" + taskNavigationState(backHost.get())));
                    try { awaitTag(page, "task_open:" + occurrenceId(task)); }
                    catch (AssertionError | IllegalStateException failure) {
                        throw new AssertionError(taskNavigationDiagnostic(page, backHost.get(), backTrace.get())
                                + (touch == null ? "" : "; " + touch.describe()), failure);
                    }
                }
                assertTaskHeader(page, mode, dom, false);
                page.recreate();
                awaitTag(page, "task_open:" + occurrenceId(task));
                assertTaskHeader(page, mode, dom, false);
            }
            if (!dom) {
                TaskDefinition last = null;
                for (int index = 0; index < 6; index++) last = seed("scroll row " + index, false);
                awaitTag(page, "task_open:" + occurrenceId(last));
            }
            openTask(page, task);
            assertTaskHeader(page, mode, dom, true);
            awaitNativeTaskControl(page, R.id.nav_tasks);
            page.onActivity(activity -> assertTrue(activity.findViewById(R.id.nav_tasks).isSelected()));
            if (dom) {
                // Dom's selected section root is the overview, not this local Sub task list.
                Intents.init();
                try {
                    Intents.intending(IntentMatchers.hasComponent(TasksOverviewActivity.class.getName()))
                            .respondWith(new Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null));
                    tapTaskControl(page, R.id.nav_tasks);
                    Intents.intended(IntentMatchers.hasComponent(TasksOverviewActivity.class.getName()));
                    Intent route = Intents.getIntents().stream().filter(intent -> intent.getComponent() != null
                            && TasksOverviewActivity.class.getName().equals(intent.getComponent().getClassName()))
                            .findFirst().get();
                    assertTrue((route.getFlags() & Intent.FLAG_ACTIVITY_CLEAR_TOP) != 0);
                    assertTrue(route.getBooleanExtra(com.subhub.app.util.SubHubNavigation.EXTRA_SECTION_HOME, false));
                } finally { Intents.release(); }
            } else {
                tapTaskControl(page, R.id.nav_tasks);
                awaitTag(page, "task_open:" + occurrenceId(task));
                assertTaskHeader(page, mode, false, false);
                page.onActivity(activity -> assertTrue("Synthetic task list overflows the viewport",
                        ((android.widget.ScrollView) activity.findViewById(R.id.role_page_content).getParent())
                                .canScrollVertically(1)));
                awaitNativeTaskControl(page, R.id.nav_tasks);
                onView(androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom(android.widget.ScrollView.class))
                        .perform(swipeTaskListAboveNavigation());
                page.onActivity(activity -> assertTrue("Native swipe moves the task list",
                        ((android.widget.ScrollView) activity.findViewById(R.id.role_page_content).getParent())
                                .getScrollY() > 0));
                awaitNativeTaskControl(page, R.id.nav_tasks);
                tapTaskControl(page, R.id.nav_tasks);
                await(() -> {
                    AtomicBoolean top = new AtomicBoolean();
                    page.onActivity(activity -> top.set(((android.widget.ScrollView)
                            activity.findViewById(R.id.role_page_content).getParent()).getScrollY() == 0));
                    return top.get();
                });
                assertTaskHeader(page, mode, false, false);
            }
        }
    }

    private void assertTaskHeader(ActivityScenario<TasksActivity> page,
            DeviceRolePreferences.Mode mode, boolean dom, boolean detail) {
        page.onActivity(activity -> {
            View root = activity.findViewById(R.id.primary_header);
            View back = root.findViewById(R.id.button_back);
            View settings = root.findViewById(R.id.primary_header_settings);
            android.widget.TextView control = root.findViewById(R.id.button_edit_lock);
            android.widget.TextView badge = root.findViewById(R.id.header_role_badge);
            assertEquals(detail ? View.VISIBLE : View.GONE, back.getVisibility());
            assertEquals(detail ? View.GONE : View.VISIBLE,
                    root.findViewById(R.id.primary_header_icon).getVisibility());
            assertEquals(detail ? View.GONE : View.VISIBLE, settings.getVisibility());
            assertEquals(detail ? View.GONE : View.VISIBLE, control.getVisibility());
            assertEquals(detail ? View.VISIBLE : View.GONE, badge.getVisibility());
            assertEquals("Exactly one role indicator", 1,
                    (badge.isShown() ? 1 : 0) + (control.isShown() ? 1 : 0));
            assertFalse("No manual refresh control", containsText(
                    activity.findViewById(R.id.role_page_body), "Refresh"));
            assertEquals(activity.getString(dom ? R.string.role_dom : R.string.role_sub),
                    badge.getText().toString());
            assertFalse("Detail role badge is read-only", badge.isClickable());
            assertFalse("Detail role badge is not a focus target", badge.isFocusable());
            if (!detail) {
                assertTrue(control.isClickable());
                assertTrue(control.isFocusable());
                int label = mode == DeviceRolePreferences.Mode.BOTH
                        ? dom ? R.string.role_dom : R.string.role_sub
                        : dom ? R.string.device_role_lock : R.string.device_role_unlock;
                assertEquals(activity.getString(label), control.getText().toString());
            }
            assertEquals(dom, DeviceRolePreferences.isDomWorkspace(activity));
        });
    }

    private void awaitNativeTaskControl(ActivityScenario<TasksActivity> page, int controlId) throws Exception {
        // Lifecycle and UI idle do not prove that a resumed window's current layout has committed.
        await(() -> {
            AtomicBoolean ready = new AtomicBoolean();
            page.onActivity(activity -> {
                View back = activity.findViewById(controlId);
                android.graphics.Rect visible = new android.graphics.Rect();
                try {
                    java.lang.reflect.Field modelField = TasksActivity.class.getDeclaredField("model");
                    modelField.setAccessible(true); Object model = modelField.get(activity);
                    java.lang.reflect.Field loading = TasksActivity.UiState.class.getDeclaredField("loading");
                    java.lang.reflect.Field busy = TasksActivity.UiState.class.getDeclaredField("busy");
                    loading.setAccessible(true); busy.setAccessible(true);
                    ready.set(!loading.getBoolean(model) && !busy.getBoolean(model) && back.hasWindowFocus()
                            && back.isAttachedToWindow() && back.isLaidOut() && !back.isLayoutRequested()
                            && back.getWidth() > 0 && back.getHeight() > 0 && back.getGlobalVisibleRect(visible)
                            && visible.width() == back.getWidth() && visible.height() == back.getHeight());
                } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
            });
            return ready.get();
        });
        instrumentation.waitForIdleSync();
        java.util.concurrent.CountDownLatch frame = new java.util.concurrent.CountDownLatch(1);
        page.onActivity(activity -> {
            View decor = activity.getWindow().getDecorView();
            if (android.os.Build.VERSION.SDK_INT >= 29 && decor.isHardwareAccelerated())
                decor.getViewTreeObserver().registerFrameCommitCallback(frame::countDown);
            else decor.postOnAnimation(frame::countDown);
            decor.invalidate();
        });
        assertTrue("Current native window frame must complete before input",
                frame.await(5, java.util.concurrent.TimeUnit.SECONDS));
        instrumentation.waitForIdleSync();
    }

    private void tapTaskControl(ActivityScenario<TasksActivity> page, int controlId) {
        assertNotEquals(android.os.Looper.getMainLooper(), android.os.Looper.myLooper());
        AtomicReference<float[]> target = new AtomicReference<>();
        page.onActivity(activity -> {
            View control = activity.findViewById(controlId);
            android.graphics.Rect visible = new android.graphics.Rect();
            assertTrue(control.hasWindowFocus());
            assertTrue(control.isShown() && control.isEnabled() && control.isClickable());
            assertTrue(control.getGlobalVisibleRect(visible));
            assertEquals(control.getWidth(), visible.width());
            assertEquals(control.getHeight(), visible.height());
            int[] origin = new int[2]; control.getLocationOnScreen(origin);
            target.set(new float[]{origin[0] + control.getWidth() / 2f, origin[1] + control.getHeight() / 2f});
        });
        float[] point = target.get();
        long downTime = SystemClock.uptimeMillis();
        android.view.MotionEvent down = android.view.MotionEvent.obtain(downTime, downTime,
                android.view.MotionEvent.ACTION_DOWN, point[0], point[1], 0);
        down.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
        try {
            // Android's public app-scoped injector synchronizes compositor/input transactions
            // around this one DOWN/UP pair; a View frame alone can still be mid-window transition.
            instrumentation.sendPointerSync(down);
            android.view.MotionEvent up = android.view.MotionEvent.obtain(downTime, SystemClock.uptimeMillis(),
                    android.view.MotionEvent.ACTION_UP, point[0], point[1], 0);
            up.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
            try { instrumentation.sendPointerSync(up); }
            finally { up.recycle(); }
        } finally { down.recycle(); }
        instrumentation.waitForIdleSync();
    }

    private static androidx.test.espresso.ViewAction swipeTaskListAboveNavigation() {
        return new androidx.test.espresso.ViewAction() {
            @Override public org.hamcrest.Matcher<View> getConstraints() {
                return org.hamcrest.Matchers.allOf(
                        androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom(android.widget.ScrollView.class),
                        androidx.test.espresso.matcher.ViewMatchers.isDisplayed());
            }
            @Override public String getDescription() { return "one native swipe above the floating Tasks navigation"; }
            @Override public void perform(androidx.test.espresso.UiController ui, View view) {
                android.graphics.Rect viewport = new android.graphics.Rect();
                android.graphics.Rect navigation = new android.graphics.Rect();
                assertTrue(view.getGlobalVisibleRect(viewport));
                View nav = view.getRootView().findViewById(R.id.bottom_navigation);
                assertNotNull(nav);
                assertTrue(nav.getGlobalVisibleRect(navigation));
                int bottom = Math.min(viewport.bottom, navigation.top);
                float[] finger = androidx.test.espresso.action.Press.FINGER.describePrecision();
                float clearance = Math.max(PersonUi.dp(view.getContext(), 24), 2 * finger[1]);
                assertTrue("Unobscured viewport must fit one real swipe", bottom - viewport.top > 4 * clearance);
                float[] start = {viewport.exactCenterX(), bottom - clearance};
                float[] end = {viewport.exactCenterX(), viewport.top + clearance};
                assertTrue("Start clears the actual floating navbar", start[1] + finger[1] < navigation.top);
                assertTrue(viewport.contains(Math.round(start[0]), Math.round(start[1])));
                assertTrue(viewport.contains(Math.round(end[0]), Math.round(end[1])));
                System.out.println("Tasks native swipe start=" + start[0] + "," + start[1]
                        + " end=" + end[0] + "," + end[1] + " viewport=" + viewport.flattenToString()
                        + " nav=" + navigation.flattenToString());
                assertEquals("Exactly one native swipe must inject successfully",
                        androidx.test.espresso.action.Swiper.Status.SUCCESS,
                        androidx.test.espresso.action.Swipe.FAST.sendSwipe(ui, start, end, finger));
                ui.loopMainThreadUntilIdle();
            }
        };
    }

    /** Failure-only metadata; never include task text, names or content descriptions. */
    private String taskNavigationDiagnostic(ActivityScenario<TasksActivity> page, TasksActivity host, String gesture) {
        AtomicReference<String> result = new AtomicReference<>();
        String scenarioState = page.getState().toString();
        instrumentation.runOnMainSync(() -> {
            StringBuilder out = new StringBuilder(gesture).append("; scenarioState=").append(scenarioState)
                    .append("; failureState=").append(taskNavigationState(host));
            result.set(out.toString());
        });
        return result.get();
    }

    private static String taskNavigationState(TasksActivity activity) {
        StringBuilder out = new StringBuilder("hostLifecycle=").append(activity.getLifecycle().getCurrentState())
                .append(" finishing=").append(activity.isFinishing()).append(" destroyed=").append(activity.isDestroyed());
        try {
            java.lang.reflect.Field modelField = TasksActivity.class.getDeclaredField("model");
            modelField.setAccessible(true); Object model = modelField.get(activity);
            for (String name : List.of("selected", "form", "proofAttachments", "busy", "loading", "dom", "readSequence")) {
                java.lang.reflect.Field field = TasksActivity.UiState.class.getDeclaredField(name);
                field.setAccessible(true); out.append("; ").append(name).append('=').append(field.get(model));
            }
            for (String name : List.of("primary", "resumed", "taskDataDirty")) {
                java.lang.reflect.Field field = TasksActivity.class.getDeclaredField(name);
                field.setAccessible(true); out.append("; ").append(name).append('=').append(field.get(activity));
            }
        } catch (ReflectiveOperationException unavailable) { out.append("; model observer unavailable"); }
        View back = activity.findViewById(R.id.button_back);
        if (back != null) {
            int[] origin = new int[2]; back.getLocationOnScreen(origin);
            out.append("; backWindowFocus=").append(back.hasWindowFocus()).append(" backBounds=")
                    .append(origin[0]).append(',').append(origin[1]).append(',').append(back.getWidth()).append(',').append(back.getHeight());
            out.append(" backClickable=").append(back.isClickable()).append(" backListener=").append(back.hasOnClickListeners());
            if (back instanceof android.widget.TextView) {
                android.widget.TextView text = (android.widget.TextView) back;
                out.append(" backPressed=").append(text.isPressed()).append(" backFocused=").append(text.isFocused())
                        .append(" backFocusableInTouchMode=").append(text.isFocusableInTouchMode())
                        .append(" backTextSelectable=").append(text.isTextSelectable()).append(" backInputType=").append(text.getInputType())
                        .append(" backMovementMethod=").append(text.getMovementMethod() == null ? "none"
                                : text.getMovementMethod().getClass().getName());
            }
        }
        return out.toString();
    }

    @Test public void subRequestsCompletionWithNativeTextThenDomAcceptsTheExactSubmission() throws Exception {
        TaskDefinition task = seed("proof", false);
        ControllerPinManager.enterSubMode();
        try (ActivityScenario<TasksActivity> page = launch()) {
            String id = occurrenceId(task);
            openTask(page, task);
            awaitTag(page, "request:" + id); tap("request:" + id);
            edit("tasks_text", "Synthetic completion proof");
            page.recreate(); awaitTag(page, "tasks_send");
            onView(withTagValue(is("tasks_text"))).check(matches(withText("Synthetic completion proof")));
            tap("tasks_send");
            await(() -> row(task).status == TaskEngine.Status.PENDING);
            TaskAction submitted = row(task).submission;
            assertEquals("Synthetic completion proof", submitted.text);
            assertEquals(TaskAction.Kind.SUBMIT, submitted.kind);
            switchRole(page, true);
            openTask(page, task);
            awaitTag(page, "accept:" + id); tap("accept:" + id);
            await(() -> row(task).status == TaskEngine.Status.ACCEPTED);
            JSONArray actions = store.load(scope).json().getJSONArray("actions");
            TaskAction last = TaskAction.parse(actions.getJSONObject(actions.length() - 1));
            assertEquals(TaskAction.Kind.ACCEPT, last.kind);
            assertEquals(submitted.id, last.submissionId);
            assertSubmissionVerdict(page, id, submitted.id, R.string.tasks_accepted);
            page.recreate();
            assertSubmissionVerdict(page, id, submitted.id, R.string.tasks_accepted);
            capture("submission-approved");
        }
    }

    @Test public void outsideRequestCanBeDeniedWithNativeReviewControls() throws Exception {
        TaskDefinition task = seed("outside", false);
        ControllerPinManager.enterSubMode();
        try (ActivityScenario<TasksActivity> page = launch()) {
            String id = occurrenceId(task);
            openTask(page, task);
            awaitTag(page, "outside:" + id); tap("outside:" + id);
            await(() -> row(task).status == TaskEngine.Status.PENDING);
            String submission = row(task).submission.id;
            assertEquals(TaskAction.Kind.OUTSIDE, row(task).submission.kind);
            switchRole(page, true);
            openTask(page, task);
            awaitTag(page, "deny:" + id); tap("deny:" + id);
            await(() -> row(task).status == TaskEngine.Status.DENIED);
            JSONArray actions = store.load(scope).json().getJSONArray("actions");
            assertEquals(submission, TaskAction.parse(actions.getJSONObject(actions.length() - 1)).submissionId);
            assertSubmissionVerdict(page, id, submission, R.string.tasks_denied);
            page.recreate();
            assertSubmissionVerdict(page, id, submission, R.string.tasks_denied);
            capture("submission-denied");
        }
    }

    @Test public void refuseOneOccurrenceThenCancelARecurringSeriesWithNativeTaps() throws Exception {
        TaskDefinition single = seed("single refusal", false);
        TaskDefinition series = seed("series refusal", true);
        ControllerPinManager.enterSubMode();
        try (ActivityScenario<TasksActivity> page = launch()) {
            openTask(page, single);
            awaitTag(page, "refuse:" + occurrenceId(single)); tap("refuse:" + occurrenceId(single));
            await(() -> row(single).status == TaskEngine.Status.REFUSED);
            assertFalse(store.load(scope).seriesRefused(single.id));
            androidx.test.espresso.Espresso.pressBack();
            openTask(page, series);
            String tag = "refuse_series:" + occurrenceId(series);
            awaitTag(page, tag); tap(tag);
            await(() -> store.load(scope).seriesRefused(series.id));
            assertEquals(TaskEngine.Status.REFUSED, row(series).status);
            androidx.test.espresso.Espresso.pressBack();
            page.recreate(); awaitTag(page, "tasks_history");
            onView(withTagValue(is(tag))).check(doesNotExist());
            tap("tasks_history");
            awaitTag(page, "task:" + occurrenceId(series));
        }
    }

    @Test public void resumeRevalidatesRoleAndDropsAnAuthorDraft() throws Exception {
        try (ActivityScenario<TasksActivity> page = launch()) {
            awaitTag(page, "tasks_create"); tap("tasks_create");
            edit("tasks_title", titlePrefix + "must not be submitted");
            switchRole(page, false);
            awaitTag(page, "tasks_target");
            onView(withTagValue(is("tasks_allowed"))).check(doesNotExist());
            onView(withTagValue(is("tasks_save"))).check(doesNotExist());
            onView(withTagValue(is("tasks_create"))).check(doesNotExist());
            assertTrue(store.load(scope).definitions().isEmpty());
            switchRole(page, true);
            awaitTag(page, "tasks_create"); tap("tasks_create");
            onView(withTagValue(is("tasks_title"))).check(matches(withText("")));
        }
    }

    @Test public void localSubOwnsOptOutAcrossRecreationAndDomCannotEditIt() throws Exception {
        ControllerPinManager.enterSubMode();
        try (ActivityScenario<com.subhub.app.people.ProfileActivity> profile = ActivityScenario.launch(com.subhub.app.people.ProfileActivity.class)) {
            awaitTag(profile, "person_allowance:tasks"); tap("profile_edit");
            onView(withTagValue(is("sub_feature:tasks"))).perform(com.subhub.app.NativeUiActions.revealAboveNavigation(),click());
            assertTrue(repository.allowed()); tap("profile_save");
            await(() -> !repository.allowed());
            awaitTag(profile, "person_allowance:tasks"); profile.recreate();
            profile.onActivity(activity -> assertNull(activity.findViewById(android.R.id.content).findViewWithTag("sub_feature:tasks")));
        }
        try (ActivityScenario<TasksActivity> page = launch()) {
            awaitTag(page, "tasks_target");
            onView(withTagValue(is("tasks_allowed"))).check(doesNotExist());
            onView(withText(R.string.tasks_disabled)).check(matches(isDisplayed()));
            switchRole(page, true);
            awaitTag(page, "tasks_target");
            onView(withTagValue(is("tasks_allowed"))).check(doesNotExist());
            assertFalse(repository.allowed());
        }
        ControllerPinManager.enterDomMode();
        try (ActivityScenario<com.subhub.app.people.ProfileActivity> profile = ActivityScenario.launch(com.subhub.app.people.ProfileActivity.class)) {
            awaitTag(profile, "self_profile:dom");
            onView(withTagValue(is("sub_feature:tasks"))).check(doesNotExist());
            assertFalse(repository.allowed());
        }
    }

    @Test public void documentPickerUsesSupportedMimeTypesAndCancellationRetainsForm() throws Exception {
        Intents.init();
        try {
            Intents.intending(IntentMatchers.hasAction(Intent.ACTION_OPEN_DOCUMENT))
                    .respondWith(new Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null));
            try (ActivityScenario<TasksActivity> page = launch()) {
                awaitTag(page, "tasks_create"); tap("tasks_create");
                edit("tasks_title", titlePrefix + "picker draft");
                tap("tasks_add_media");
                Intents.intended(IntentMatchers.hasAction(Intent.ACTION_OPEN_DOCUMENT));
                Intent picker = Intents.getIntents().stream()
                        .filter(intent -> Intent.ACTION_OPEN_DOCUMENT.equals(intent.getAction())).findFirst().get();
                assertTrue(picker.hasCategory(Intent.CATEGORY_OPENABLE));
                assertEquals("*/*", picker.getType());
                String[] types = picker.getStringArrayExtra(Intent.EXTRA_MIME_TYPES);
                assertNotNull(types); assertEquals(10, types.length);
                assertTrue(List.of(types).containsAll(List.of("image/png", "audio/mpeg", "video/mp4")));
                assertEquals(0, picker.getFlags() & Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                awaitTag(page, "tasks_save");
                onView(withTagValue(is("tasks_title"))).check(matches(withText(titlePrefix + "picker draft")));
                assertTrue(store.load(scope).definitions().isEmpty());
            }
        } finally { Intents.release(); }
    }

    @Test public void mediaViewerRejectsAnAttachmentOutsideTheRelationshipProjection() throws Exception {
        TaskAttachment forged = new TaskAttachment("0000000000000000000000000000000000000000000000000000000000000000", "image/png", 128);
        Intent intent = new Intent(context, TaskMediaActivity.class)
                .putExtra(PersonRoutes.EXTRA_PERSON_ID, PersonId.LOCAL.value())
                .putExtra(TaskMediaActivity.EXTRA_SCOPE, scope)
                .putExtra(TaskMediaActivity.EXTRA_DOM, true)
                .putExtra(TaskMediaActivity.EXTRA_ATTACHMENT, forged.json().toString());
        try (ActivityScenario<TaskMediaActivity> page = ActivityScenario.launch(intent)) {
            await(() -> mediaRefused(page));
        }
    }

    @Test public void skippedOccurrencesAppearOnlyInHistoryWithoutUserActions() throws Exception {
        long now = System.currentTimeMillis();
        LocalDateTime start = LocalDateTime.now().minusHours(2).withSecond(0).withNano(0);
        TaskDefinition task = new TaskDefinition(UUID.randomUUID().toString(), titlePrefix + "skipped",
                "Synthetic task from before Tasks resumed", start, ZoneId.systemDefault(), 60_000L,
                Set.of(), false, true, null);
        synchronized (SubFeaturePolicy.LOCK) {
            store.append(scope, new TaskAction(UUID.randomUUID().toString(), task.id, TaskAction.Kind.CREATE,
                    null, task.start(start.toLocalDate()).toEpochMilli() - 60_000L,
                    "", "", List.of(), task), true, false);
            store.append(scope, new TaskAction(UUID.randomUUID().toString(), task.id, TaskAction.Kind.SKIP,
                    start.toLocalDate(), now, "", "", List.of(), null), false, false);
        }
        assertEquals(TaskEngine.Status.SKIPPED, row(task).status);
        assertFalse(row(task).failed());
        assertTrue(store.pending(scope).isEmpty());
        ControllerPinManager.enterSubMode();
        try (ActivityScenario<TasksActivity> page = launch()) {
            String id = occurrenceId(task);
            awaitTag(page, "tasks_history");
            onView(withTagValue(is("task:" + id))).check(doesNotExist());
            tap("tasks_history");
            awaitTag(page, "task:" + id);
            page.onActivity(activity -> assertTrue(containsText(
                    activity.findViewById(R.id.role_page_body).findViewWithTag("task:" + id),
                    context.getString(R.string.tasks_skipped))));
            onView(withTagValue(is("request:" + id))).check(doesNotExist());
            onView(withTagValue(is("refuse:" + id))).check(doesNotExist());
            onView(withTagValue(is("accept:" + id))).check(doesNotExist());
        }
    }

    @Test public void historyPreviewsKeepOutcomeOrderAndCompletionRequestTimesAcrossRefreshAndRecreation() throws Exception {
        long at = System.currentTimeMillis() - 600_000L;
        ZoneId zone = ZoneId.of("Asia/Kathmandu");
        TaskDefinition approved, declined, cancelled, refused, skipped;
        TaskAction approvedProof, declinedProof;
        synchronized (SubFeaturePolicy.LOCK) {
            approved = historyTask("old schedule approved", at - 8 * 3_600_000L, zone, 86_400_000L);
            declined = historyTask("new schedule declined", at - 3_600_000L, zone, 86_400_000L);
            cancelled = historyTask("cancelled pending request", at - 4 * 3_600_000L, zone, 86_400_000L);
            refused = historyTask("refused without completion", at - 2 * 3_600_000L, zone, 86_400_000L);
            skipped = historyTask("skipped without completion", at - 3 * 3_600_000L, zone, 60_000L);
            approvedProof = historyAction(approved, TaskAction.Kind.SUBMIT,
                    approved.start(approved.firstStart.toLocalDate()).toEpochMilli() + 60_000L, "");
            declinedProof = historyAction(declined, TaskAction.Kind.OUTSIDE,
                    declined.start(declined.firstStart.toLocalDate()).toEpochMilli() + 120_000L, "");
            store.append(scope, approvedProof, false, false);
            store.append(scope, declinedProof, false, false);
            store.append(scope, historyAction(declined, TaskAction.Kind.DENY, at + 10_000L, declinedProof.id), true, false);
            store.append(scope, historyAction(cancelled, TaskAction.Kind.OUTSIDE, at - 300_000L, ""), false, false);
            store.append(scope, historyAction(cancelled, TaskAction.Kind.CANCEL_ONCE, at, ""), true, false);
            store.append(scope, historyAction(refused, TaskAction.Kind.REFUSE_ONCE, at - 10_000L, ""), false, false);
            store.append(scope, historyAction(skipped, TaskAction.Kind.SKIP, at - 10_000L, ""), false, false);
        }
        String requested = completionRequestText(approved, approvedProof.at);
        List<String> expected = new ArrayList<>(List.of(occurrenceId(approved), occurrenceId(declined), occurrenceId(cancelled)));
        List<String> tied = new ArrayList<>(List.of(occurrenceId(refused), occurrenceId(skipped)));
        java.util.Collections.sort(tied); expected.addAll(tied);
        try (ActivityScenario<TasksActivity> page = launch()) {
            openTask(page, approved);
            assertCompletionText(page, approved, requested);
            androidx.test.espresso.Espresso.pressBack();
            awaitTag(page, "tasks_history"); tap("tasks_history");
            onView(withTagValue(is("task_completion_time:" + occurrenceId(approved)))).check(doesNotExist());
            // A live review moves a pending card into History; its earlier request time stays unchanged.
            store.append(scope, historyAction(approved, TaskAction.Kind.ACCEPT, at + 20_000L, approvedProof.id), true, false);
            assertCompletionText(page, approved, requested);
            assertHistoryPreviews(page, expected, approved, declined, cancelled, refused, skipped);
            captureHistoryView(page, "history-dom", null);
            page.recreate();
            assertCompletionText(page, approved, requested);
            assertHistoryPreviews(page, expected, approved, declined, cancelled, refused, skipped);
            try (TaskStore reopened = new TaskStore(context)) {
                TaskEngine.Occurrence saved = reopened.load(scope).occurrence(approved.id,
                        approved.firstStart.toLocalDate(), System.currentTimeMillis());
                assertEquals(approvedProof.at, saved.submission.at);
                assertEquals(at + 20_000L, saved.historyEnteredAt);
            }
            TaskDefinition unrelated = seed("unrelated refresh", false);
            awaitTag(page, "task:" + occurrenceId(unrelated));
            assertHistoryPreviews(page, expected, approved, declined, cancelled, refused, skipped);
            assertCompletionText(page, approved, requested);
            openTask(page, approved);
            assertCompletionText(page, approved, requested);
            assertSubmissionVerdict(page, occurrenceId(approved), approvedProof.id, R.string.tasks_accepted);
            page.recreate();
            assertCompletionText(page, approved, requested);
            captureHistoryView(page, "approved-detail", "task_detail:" + occurrenceId(approved));
            androidx.test.espresso.Espresso.pressBack();
            awaitTag(page, "tasks_history");
            openTask(page, declined);
            assertCompletionText(page, declined, completionRequestText(declined, declinedProof.at));
            captureHistoryView(page, "declined-detail", "task_detail:" + occurrenceId(declined));
            androidx.test.espresso.Espresso.pressBack();
            for (TaskDefinition noRequest : List.of(cancelled, refused, skipped)) {
                openTask(page, noRequest);
                onView(withTagValue(is("task_completion_time:" + occurrenceId(noRequest)))).check(doesNotExist());
                androidx.test.espresso.Espresso.pressBack();
            }
            switchRole(page, false);
            awaitTag(page, "tasks_history");
            assertHistoryPreviews(page, expected, approved, declined, cancelled, refused, skipped);
            assertCompletionText(page, approved, requested);
            captureHistoryView(page, "history-sub", null);
        }
    }

    private TaskDefinition historyTask(String suffix, long at, ZoneId zone, long duration) throws Exception {
        LocalDateTime start = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(at), zone).withSecond(0).withNano(0);
        TaskDefinition task = new TaskDefinition(UUID.randomUUID().toString(), titlePrefix + suffix,
                "Synthetic History instructions", start, zone, duration, Set.of(), true, false, null);
        store.append(scope, new TaskAction(UUID.randomUUID().toString(), task.id, TaskAction.Kind.CREATE, null,
                task.start(start.toLocalDate()).toEpochMilli() - 60_000L, "", "", List.of(), task), true, false);
        return task;
    }

    private TaskAction historyAction(TaskDefinition task, TaskAction.Kind kind, long at, String submission) {
        return new TaskAction(UUID.randomUUID().toString(), task.id, kind, task.firstStart.toLocalDate(),
                at, kind == TaskAction.Kind.SUBMIT ? "Synthetic completion proof" : "", submission, List.of(), null);
    }

    private String completionRequestText(TaskDefinition task, long at) {
        return context.getString(R.string.tasks_completion_requested_at,
                java.time.format.DateTimeFormatter.ofLocalizedDateTime(java.time.format.FormatStyle.SHORT)
                        .withZone(task.zone).format(java.time.Instant.ofEpochMilli(at)), task.zone.getId());
    }

    private void assertCompletionText(ActivityScenario<TasksActivity> page, TaskDefinition task, String expected) throws Exception {
        String tag = "task_completion_time:" + occurrenceId(task);
        awaitTag(page, tag);
        onView(withTagValue(is(tag))).perform(NativeUiActions.revealAboveNavigation()).check(matches(isDisplayed()));
        assertLayoutText(page, tag, expected);
    }

    private void assertHistoryPreviews(ActivityScenario<TasksActivity> page, List<String> expected,
            TaskDefinition approved, TaskDefinition declined, TaskDefinition cancelled,
            TaskDefinition refused, TaskDefinition skipped) throws Exception {
        for (TaskDefinition task : List.of(approved, declined, cancelled, refused, skipped))
            awaitTag(page, "task:" + occurrenceId(task));
        page.onActivity(activity -> {
            ViewGroup body = activity.findViewById(R.id.role_page_body);
            List<String> actual = new ArrayList<>();
            for (int i = 0; i < body.getChildCount(); i++) {
                Object tag = body.getChildAt(i).getTag();
                if (tag instanceof String && ((String) tag).startsWith("task:")) {
                    String id = ((String) tag).substring(5);
                    if (expected.contains(id)) actual.add(id);
                }
            }
            assertEquals("History must follow transition time, with stable identity ties", expected, actual);
            for (TaskDefinition task : List.of(cancelled, refused, skipped))
                assertNull(body.findViewWithTag("task_completion_time:" + occurrenceId(task)));
        });
        String approvedTag = "task_preview_status:" + occurrenceId(approved);
        String declinedTag = "task_preview_status:" + occurrenceId(declined);
        onView(withTagValue(is(approvedTag))).perform(NativeUiActions.revealAboveNavigation()).check(matches(isDisplayed()));
        assertLayoutText(page, approvedTag, "Approved");
        onView(withTagValue(is(declinedTag))).perform(NativeUiActions.revealAboveNavigation()).check(matches(isDisplayed()));
        assertLayoutText(page, declinedTag, "Declined");
        assertLayoutText(page, "task_preview_status:" + occurrenceId(cancelled), context.getString(R.string.tasks_cancelled));
        assertCompletionText(page, declined, completionRequestText(declined, row(declined).submission.at));
    }

    private void assertLayoutText(ActivityScenario<TasksActivity> page, String tag, String expected) throws Exception {
        await(() -> {
            AtomicBoolean laidOut = new AtomicBoolean();
            page.onActivity(activity -> {
                android.widget.TextView text = activity.findViewById(android.R.id.content).findViewWithTag(tag);
                laidOut.set(text != null && text.getLayout() != null && !text.isLayoutRequested()
                        && expected.contentEquals(text.getText()));
            });
            return laidOut.get();
        });
        page.onActivity(activity -> {
            android.widget.TextView text = activity.findViewById(android.R.id.content).findViewWithTag(tag);
            assertEquals(expected, text.getText().toString());
            android.text.Layout layout = text.getLayout();
            assertNotNull(layout);
            int width = text.getWidth() - text.getCompoundPaddingLeft() - text.getCompoundPaddingRight();
            for (int line = 0; line < layout.getLineCount(); line++) {
                assertEquals("No ellipsis in " + tag, 0, layout.getEllipsisCount(line));
                assertTrue("Text fits " + tag, layout.getLineMax(line) <= width + 1);
            }
            assertTrue("Full text height " + tag, layout.getHeight()
                    <= text.getHeight() - text.getCompoundPaddingTop() - text.getCompoundPaddingBottom());
        });
    }

    /** View.draw captures laid-out synthetic content, including History rows outside the viewport. */
    private void captureHistoryView(ActivityScenario<TasksActivity> page, String name, String tag) throws Exception {
        instrumentation.waitForIdleSync();
        AtomicReference<android.graphics.Bitmap> capture = new AtomicReference<>();
        page.onActivity(activity -> {
            View view = tag == null ? activity.findViewById(R.id.role_page_body)
                    : activity.findViewById(android.R.id.content).findViewWithTag(tag);
            assertNotNull(view); assertFalse(view.isLayoutRequested());
            assertTrue(view.getWidth() > 0 && view.getHeight() > 0);
            android.graphics.Bitmap pixels = android.graphics.Bitmap.createBitmap(view.getWidth(), view.getHeight(),
                    android.graphics.Bitmap.Config.ARGB_8888);
            view.draw(new android.graphics.Canvas(pixels)); capture.set(pixels);
        });
        android.graphics.Bitmap pixels = capture.get();
        File output = new File(context.getCacheDir(), "tasks-history-outcomes-17217f18-" + name + "-" + UUID.randomUUID() + ".png");
        try {
            assertTrue("Unique test-owned cache capture", output.createNewFile());
            try (java.io.FileOutputStream stream = new java.io.FileOutputStream(output)) {
                assertTrue(pixels.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream));
            }
            android.util.Log.i("TasksHistoryFixture", "View.draw capture: " + output.getAbsolutePath());
        } finally { pixels.recycle(); }
    }

    @Test public void visibleImageClosesOnRoleOrOptOutChangeWithoutPausing() throws Exception {
        byte[] bytes = Base64.decode("R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7", Base64.DEFAULT);
        StringBuilder hash = new StringBuilder();
        for (byte value : MessageDigest.getInstance("SHA-256").digest(bytes))
            hash.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        File root = taskMediaRoot();
        for (String suffix : List.of("blob", "part", "size")) {
            File file = new File(root, hash + "." + suffix);
            assertFalse(Files.isSymbolicLink(file.toPath()));
            if (!file.exists()) createdMediaFiles.add(file);
        }
        RemoteAssets assets = TaskRuntime.get(context).assets(scope);
        assets.reserve(hash.toString(), bytes.length);
        if (!assets.isComplete(hash.toString())) {
            assertEquals(0, assets.offset(hash.toString()));
            assets.append(hash.toString(), 0, bytes);
            assets.finish(hash.toString());
        }
        TaskAttachment image = new TaskAttachment(hash.toString(), "image/gif", bytes.length);
        seed("image authorization", false, image);
        for (boolean roleChange : new boolean[]{true, false}) {
            if (roleChange) ControllerPinManager.enterDomMode(); else ControllerPinManager.enterSubMode();
            Intent intent = new Intent(context, TaskMediaActivity.class)
                    .putExtra(PersonRoutes.EXTRA_PERSON_ID, PersonId.LOCAL.value())
                    .putExtra(TaskMediaActivity.EXTRA_SCOPE, scope)
                    .putExtra(TaskMediaActivity.EXTRA_DOM, roleChange)
                    .putExtra(TaskMediaActivity.EXTRA_ATTACHMENT, image.json().toString());
            try (ActivityScenario<TaskMediaActivity> page = ActivityScenario.launch(intent)) {
                await(() -> {
                    AtomicBoolean shown = new AtomicBoolean();
                    page.onActivity(activity -> {
                        View view = activity.findViewById(R.id.role_page_body).findViewWithTag("task_media_image");
                        shown.set(view instanceof android.widget.ImageView && view.isShown()
                                && ((android.widget.ImageView) view).getDrawable() != null);
                    });
                    return shown.get();
                });
                assertEquals(Lifecycle.State.RESUMED, page.getState());
                if (roleChange) ControllerPinManager.enterSubMode(); else repository.setAllowed(false);
                await(() -> page.getState() == Lifecycle.State.DESTROYED);
            }
        }
    }

    @Test @androidx.test.filters.SdkSuppress(minSdkVersion = 29)
    public void taskListRefreshRetainsUnchangedCardsAndPositionWhenAnotherTaskArrives() throws Exception {
        List<TaskDefinition> tasks = new ArrayList<>();
        for (int i = 0; i < 12; i++) tasks.add(seed("Scroll row " + i, false));
        ControllerPinManager.enterSubMode();
        try (ActivityScenario<TasksActivity> page = launch()) {
            String first = "task:" + occurrenceId(tasks.get(0));
            awaitTag(page, "task_open:" + occurrenceId(tasks.get(0)));
            AtomicReference<View> retained = new AtomicReference<>();
            java.util.concurrent.atomic.AtomicInteger position = new java.util.concurrent.atomic.AtomicInteger();
            page.onActivity(activity -> {
                retained.set(activity.findViewById(android.R.id.content).findViewWithTag(first));
                com.subhub.app.UiRefreshWitness.scroll(activity.findViewById(R.id.role_page_body)).scrollTo(0, 300);
            });
            AtomicReference<TasksActivity> current = new AtomicReference<>(); page.onActivity(current::set);
            com.subhub.app.UiRefreshWitness.frame(current.get());
            page.onActivity(activity -> position.set(com.subhub.app.UiRefreshWitness.scroll(activity.findViewById(R.id.role_page_body)).getScrollY()));
            assertTrue("Fixture must be scrolled", position.get() > 0);
            refreshTasks(page);
            com.subhub.app.UiRefreshWitness.capture(current.get(), "tasks-list-unchanged-refresh");
            page.onActivity(activity -> {
                assertSame(retained.get(), activity.findViewById(android.R.id.content).findViewWithTag(first));
                assertEquals(position.get(), com.subhub.app.UiRefreshWitness.scroll(activity.findViewById(R.id.role_page_body)).getScrollY());
            });
            TaskDefinition arriving = seed("New arrival", false);
            refreshTasks(page);
            awaitTag(page, "task_open:" + occurrenceId(arriving));
            com.subhub.app.UiRefreshWitness.capture(current.get(), "tasks-list-new-arrival");
            page.onActivity(activity -> {
                assertSame(retained.get(), activity.findViewById(android.R.id.content).findViewWithTag(first));
                assertEquals(position.get(), com.subhub.app.UiRefreshWitness.scroll(activity.findViewById(R.id.role_page_body)).getScrollY());
            });
            openTask(page, arriving);
            awaitTag(page, "task_detail:" + occurrenceId(arriving));
            androidx.test.espresso.Espresso.pressBack(); awaitTag(page, "task_open:" + occurrenceId(arriving));
        }
    }

    @Test @androidx.test.filters.SdkSuppress(minSdkVersion = 29)
    public void subHomeTaskCountUpdatesWithoutReplacingOtherControlsOrPosition() throws Exception {
        seed("Home count first", false);
        for (String name : List.of("betablocker_achievements", "betablocker_stats"))
            presentationPreferences.put(name, new LinkedHashMap<>(context.getSharedPreferences(name, 0).getAll()));
        com.subhub.app.onboarding.OnboardingState.complete(context);
        java.util.concurrent.atomic.AtomicLong usage = new java.util.concurrent.atomic.AtomicLong(60_000);
        com.subhub.app.people.People.installLocalProjection(local -> {
            List<com.subhub.app.people.PersonSnapshot.Allowance> allowances = new ArrayList<>();
            for (int i = 0; i < 8; i++) allowances.add(new com.subhub.app.people.PersonSnapshot.Allowance(
                    i == 0 ? "com.android.chrome" : "synthetic.app" + i, "Synthetic app " + (i + 1), usage.get(), 30 + i * 10));
            return new com.subhub.app.people.PersonSnapshot(local.id, local.name, local.device, local.contractName,
                    local.serviceLabel, local.connection, local.freshness, local.lastSyncedAt, local.capabilities,
                    local.sections, allowances, local.currency, local.protectedSeconds, local.activityEvents,
                    local.tributeCents, local.sessions, local.serviceActive, local.sync, local.sessionIdentity, local.additionOrder);
        });
        ControllerPinManager.enterSubMode();
        try (ActivityScenario<com.subhub.app.MainActivity> page = ActivityScenario.launch(new Intent(context, com.subhub.app.MainActivity.class)
                .setAction(Intent.ACTION_MAIN).putExtra(com.subhub.app.MainActivity.EXTRA_SUPPRESS_PERMISSION_READINESS, true))) {
            awaitTag(page, "home_tasks"); awaitTag(page, "home_app_time"); awaitTag(page, "achievement_preview");
            AtomicReference<com.subhub.app.MainActivity> current = new AtomicReference<>(); page.onActivity(current::set);
            AtomicReference<View> tasks = new AtomicReference<>(), allowances = new AtomicReference<>(), achievements = new AtomicReference<>();
            java.util.concurrent.atomic.AtomicInteger position = new java.util.concurrent.atomic.AtomicInteger();
            page.onActivity(activity -> {
                View root = activity.findViewById(R.id.home_sub_summary);
                tasks.set(root.findViewWithTag("home_tasks")); allowances.set(root.findViewWithTag("home_app_time"));
                achievements.set(root.findViewWithTag("achievement_preview"));
                com.subhub.app.UiRefreshWitness.scroll(root).scrollTo(0, com.subhub.app.people.PersonUi.dp(activity, 120));
            });
            com.subhub.app.UiRefreshWitness.frame(current.get());
            page.onActivity(activity -> position.set(com.subhub.app.UiRefreshWitness.scroll(activity.findViewById(R.id.home_sub_summary)).getScrollY()));
            assertTrue("Fixture must be scrolled", position.get() > 0);
            seed("Home count second", false);
            refreshHome(page);
            com.subhub.app.UiRefreshWitness.capture(current.get(), "home-sub-changed-task-count");
            page.onActivity(activity -> {
                View root = activity.findViewById(R.id.home_sub_summary);
                assertSame(tasks.get(), root.findViewWithTag("home_tasks"));
                assertTrue(containsText(tasks.get(), activity.getResources().getQuantityString(R.plurals.tasks_active_count, 2, 2)));
                assertSame(allowances.get(), root.findViewWithTag("home_app_time"));
                assertSame(achievements.get(), root.findViewWithTag("achievement_preview"));
                assertEquals(position.get(), com.subhub.app.UiRefreshWitness.scroll(root).getScrollY());
            });
            usage.set(120_000); refreshHome(page);
            com.subhub.app.UiRefreshWitness.capture(current.get(), "home-sub-changed-usage");
            page.onActivity(activity -> {
                View root = activity.findViewById(R.id.home_sub_summary);
                assertSame(tasks.get(), root.findViewWithTag("home_tasks"));
                assertNotSame(allowances.get(), root.findViewWithTag("home_app_time"));
                assertTrue(containsText(root.findViewWithTag("home_app_time"), activity.getString(R.string.role_allowance_usage, 2, 30)));
                assertSame(achievements.get(), root.findViewWithTag("achievement_preview"));
                assertEquals(position.get(), com.subhub.app.UiRefreshWitness.scroll(root).getScrollY());
            });
        }
    }

    private void refreshTasks(ActivityScenario<TasksActivity> page) throws Exception {
        page.onActivity(TasksActivity::refresh);
        await(() -> {
            AtomicBoolean done = new AtomicBoolean();
            page.onActivity(activity -> {
                try { java.lang.reflect.Field model = TasksActivity.class.getDeclaredField("model"); model.setAccessible(true);
                    Object state = model.get(activity); java.lang.reflect.Field loading = state.getClass().getDeclaredField("loading");
                    loading.setAccessible(true); done.set(!loading.getBoolean(state));
                } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
            });
            return done.get();
        });
    }

    private void refreshHome(ActivityScenario<com.subhub.app.MainActivity> page) throws Exception {
        page.onActivity(activity -> {
            try { java.lang.reflect.Field field = com.subhub.app.MainActivity.class.getDeclaredField("roleHome"); field.setAccessible(true);
                ((com.subhub.app.people.RoleHomePresenter) field.get(activity)).refresh(true);
            } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
        });
        await(() -> {
            AtomicBoolean done = new AtomicBoolean();
            page.onActivity(activity -> {
                try { java.lang.reflect.Field field = com.subhub.app.MainActivity.class.getDeclaredField("roleHome"); field.setAccessible(true);
                    java.lang.reflect.Field data = com.subhub.app.people.RoleHomePresenter.class.getDeclaredField("data"); data.setAccessible(true);
                    done.set(!((com.subhub.app.util.AsyncUiScope) data.get(field.get(activity))).isPending("role-home"));
                } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
            });
            return done.get();
        });
    }

    @Test public void tasksTabRetainsListDetailAndSubmissionNavigation() throws Exception {
        TaskDefinition task = seed("Read one chapter", false);
        ControllerPinManager.enterSubMode();
        try (ActivityScenario<TasksActivity> page = launch()) {
            awaitTag(page, "task_open:" + occurrenceId(task));
            onView(androidx.test.espresso.matcher.ViewMatchers.withId(R.id.nav_tasks))
                    .check(matches(androidx.test.espresso.matcher.ViewMatchers.isSelected()));
            capture("tasks-list");
            openTask(page, task);
            awaitTag(page, "task_detail:" + occurrenceId(task));
            capture("tasks-detail");
            androidx.test.espresso.Espresso.pressBack();
            awaitTag(page, "task_open:" + occurrenceId(task));
            onView(androidx.test.espresso.matcher.ViewMatchers.withId(R.id.primary_header_settings)).perform(click());
            onView(androidx.test.espresso.matcher.ViewMatchers.withId(R.id.nav_tasks)).perform(click());
            awaitTag(page, "task_open:" + occurrenceId(task));
            assertEquals(Lifecycle.State.RESUMED, page.getState());
            openTask(page, task);
            tap("request:" + occurrenceId(task));
            awaitTag(page, "tasks_send");
            edit("tasks_text", "Read the chapter and wrote a short summary.");
            onView(withTagValue(is("tasks_text"))).perform(NativeUiActions.revealAboveNavigation());
            capture("tasks-submission");
            androidx.test.espresso.Espresso.pressBack();
            awaitTag(page, "task_detail:" + occurrenceId(task));
            capture("tasks-before-tab-return");
            page.onActivity(activity -> android.util.Log.i("TasksNavigationWitness", "before tap selected="
                    + activity.findViewById(R.id.nav_tasks).isSelected() + " dom=" + ControllerPinManager.isDomModeActive()));
            onView(androidx.test.espresso.matcher.ViewMatchers.withId(R.id.nav_tasks)).perform(click());
            capture("tasks-after-tab-return");
            page.onActivity(activity -> android.util.Log.i("TasksNavigationWitness", "after tap list="
                    + (activity.findViewById(android.R.id.content).findViewWithTag("task_open:" + occurrenceId(task)) != null)
                    + " detail=" + (activity.findViewById(android.R.id.content).findViewWithTag("task_detail:" + occurrenceId(task)) != null)
                    + " dom=" + ControllerPinManager.isDomModeActive()));
            awaitTag(page, "task_open:" + occurrenceId(task));
            onView(withTagValue(is("tasks_send"))).check(doesNotExist());
        }
    }

    @Test public void domTasksOverviewOpensTheSelectedProfileAndReturnsFromItsList() throws Exception {
        seed("Prepare tomorrow", false);
        try (ActivityScenario<TasksOverviewActivity> page = ActivityScenario.launch(TasksOverviewActivity.class)) {
            awaitTag(page, "tasks_profile:" + PersonId.LOCAL.value());
            onView(androidx.test.espresso.matcher.ViewMatchers.withId(R.id.nav_tasks))
                    .check(matches(androidx.test.espresso.matcher.ViewMatchers.isSelected()));
            capture("tasks-overview");
            tap("tasks_profile:" + PersonId.LOCAL.value());
            await(() -> {
                AtomicBoolean found = new AtomicBoolean();
                instrumentation.runOnMainSync(() -> {
                    for (Activity activity : androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
                            .getInstance().getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED)) {
                        if (activity instanceof TasksActivity) found.set(PersonId.LOCAL.equals(
                                PersonRoutes.requireId(activity.getIntent()))
                                && activity.findViewById(android.R.id.content).findViewWithTag("tasks_create") != null);
                    }
                });
                return found.get();
            });
            androidx.test.espresso.Espresso.pressBack();
            awaitTag(page, "tasks_profile:" + PersonId.LOCAL.value());
        }
    }

    private void openTask(ActivityScenario<TasksActivity> page, TaskDefinition task) throws Exception {
        awaitTag(page, "task_open:" + occurrenceId(task));
        tap("task_open:" + occurrenceId(task));
        awaitTag(page, "task_detail:" + occurrenceId(task));
    }

    @Test public void profileGroupsTasksImmediatelyAfterContractAndKeepsItsNativeRoute() throws Exception {
        try (ActivityScenario<com.subhub.app.people.PersonActivity> page = ActivityScenario.launch(
                new Intent(context, com.subhub.app.people.PersonActivity.class)
                        .putExtra(PersonRoutes.EXTRA_PERSON_ID, "local"))) {
            awaitTag(page, "person_tab:tasks");
            page.onActivity(activity -> {
                ViewGroup group = activity.findViewById(android.R.id.content).findViewWithTag("person_destinations");
                View contract = group.findViewWithTag("person_tab:contracts");
                View tasks = group.findViewWithTag("person_tab:tasks");
                assertSame(group, tasks.getParent()); assertSame(group, contract.getParent());
                assertEquals(group.indexOfChild(contract) + 1, group.indexOfChild(tasks));
            });
            Intents.init();
            try {
                tap("person_tab:tasks");
                Intents.intended(IntentMatchers.hasComponent(TasksActivity.class.getName()));
            } finally { Intents.release(); }
        }
    }

    @Test public void homeActiveCountTracksZeroSingularPluralAndReplayedTerminalOutcomes() throws Exception {
        com.subhub.app.onboarding.OnboardingState.complete(context);
        ControllerPinManager.enterSubMode();
        try (ActivityScenario<com.subhub.app.MainActivity> page = ActivityScenario.launch(
                new Intent(context, com.subhub.app.MainActivity.class).setAction(Intent.ACTION_MAIN)
                        .putExtra(com.subhub.app.MainActivity.EXTRA_SUPPRESS_PERMISSION_READINESS, true))) {
            awaitHomeCount(page, 0);
            TaskDefinition first = seed("count first", false); awaitHomeCount(page, 1);
            TaskDefinition second = seed("count second", true); awaitHomeCount(page, 2);
            capture("home-task-count");
            TaskAction proof = new TaskAction(UUID.randomUUID().toString(), first.id, TaskAction.Kind.OUTSIDE,
                    first.firstStart.toLocalDate(), System.currentTimeMillis(), "", "", List.of(), null);
            store.append(scope, proof, false, false); store.append(scope, proof, false, false);
            awaitHomeCount(page, 1);
            TaskAction cancel = new TaskAction(UUID.randomUUID().toString(), second.id, TaskAction.Kind.CANCEL_SERIES,
                    second.firstStart.toLocalDate(), System.currentTimeMillis(), "", "", List.of(), null);
            store.append(scope, cancel, true, false); store.append(scope, cancel, true, false);
            awaitHomeCount(page, 0); page.recreate(); awaitHomeCount(page, 0);
        }
    }

    @Test public void completedCounterTracksAcceptedOnlyAcrossRolesAndRecreation() throws Exception {
        try (ActivityScenario<TasksActivity> page = launch()) {
            awaitCounterRow(page, 0, 0, 0, 0);
            TaskDefinition pending = seedCounterOutcomes();
            awaitCounterRow(page, 0, 1, 1, 1);
            TaskAction accepted = acceptCounterTask(pending);
            awaitCounterRow(page, 1, 1, 0, 1);
            assertFalse(store.append(scope, accepted, true, false));
            awaitCounterRow(page, 1, 1, 0, 1);
            page.recreate();
            awaitCounterRow(page, 1, 1, 0, 1);
            switchRole(page, false);
            awaitCounterRow(page, 1, 1, 0, 1);
            page.recreate();
            awaitCounterRow(page, 1, 1, 0, 1);
            tap("tasks_history");
            awaitCounterRow(page, 1, 1, 0, 1);
            openTask(page, pending);
            androidx.test.espresso.Espresso.pressBack();
            awaitCounterRow(page, 1, 1, 0, 1);
        }
    }

    @Test public void domOverviewCompletedCounterRefreshesForAcceptedReviews() throws Exception {
        TaskDefinition pending = seedCounterOutcomes();
        try (ActivityScenario<TasksOverviewActivity> page = ActivityScenario.launch(TasksOverviewActivity.class)) {
            awaitTag(page, "tasks_profile:" + PersonId.LOCAL.value());
            awaitCounterRow(page, 0, 1, 1, 1);
            acceptCounterTask(pending);
            awaitCounterRow(page, 1, 1, 0, 1);
            page.recreate();
            awaitCounterRow(page, 1, 1, 0, 1);
            TaskAction cancel = new TaskAction(UUID.randomUUID().toString(), pending.id,
                    TaskAction.Kind.CANCEL_ONCE, pending.firstStart.toLocalDate(),
                    System.currentTimeMillis(), "", "", List.of(), null);
            assertTrue(store.append(scope, cancel, true, false));
            awaitCounterRow(page, 0, 1, 0, 1);
        }
    }

    private TaskDefinition seedCounterOutcomes() throws Exception {
        seed("counter active and next scheduled", true);
        TaskDefinition pending = seed("counter pending", false);
        submitCounterTask(pending);
        TaskDefinition denied = seed("counter denied", false);
        TaskAction proof = submitCounterTask(denied);
        assertTrue(store.append(scope, new TaskAction(UUID.randomUUID().toString(), denied.id,
                TaskAction.Kind.DENY, denied.firstStart.toLocalDate(), System.currentTimeMillis(),
                "", proof.id, List.of(), null), true, false));
        TaskDefinition refused = seed("counter refused", false);
        assertTrue(store.append(scope, new TaskAction(UUID.randomUUID().toString(), refused.id,
                TaskAction.Kind.REFUSE_ONCE, refused.firstStart.toLocalDate(), System.currentTimeMillis(),
                "", "", List.of(), null), false, false));
        TaskDefinition cancelled = seed("counter cancelled", false);
        assertTrue(store.append(scope, new TaskAction(UUID.randomUUID().toString(), cancelled.id,
                TaskAction.Kind.CANCEL_ONCE, cancelled.firstStart.toLocalDate(), System.currentTimeMillis(),
                "", "", List.of(), null), true, false));
        return pending;
    }

    private TaskAction submitCounterTask(TaskDefinition task) throws Exception {
        TaskAction proof = new TaskAction(UUID.randomUUID().toString(), task.id, TaskAction.Kind.OUTSIDE,
                task.firstStart.toLocalDate(), System.currentTimeMillis(), "", "", List.of(), null);
        assertTrue(store.append(scope, proof, false, false));
        return proof;
    }

    private TaskAction acceptCounterTask(TaskDefinition task) throws Exception {
        TaskAction accepted = new TaskAction(UUID.randomUUID().toString(), task.id, TaskAction.Kind.ACCEPT,
                task.firstStart.toLocalDate(), System.currentTimeMillis(), "", row(task).submission.id, List.of(), null);
        assertTrue(store.append(scope, accepted, true, false));
        return accepted;
    }

    private void awaitCounterRow(ActivityScenario<? extends Activity> page,
            int completed, int active, int pending, int scheduled) throws Exception {
        int[] numbers = {completed, active, pending, scheduled};
        int[] captions = {R.string.tasks_completed, R.string.tasks_active,
                R.string.tasks_pending, R.string.tasks_scheduled};
        await(() -> {
            AtomicBoolean ready = new AtomicBoolean();
            page.onActivity(activity -> {
                ViewGroup row = activity.findViewById(android.R.id.content).findViewWithTag("tasks_counts");
                if (row == null || row.getWidth() == 0 || row.getChildCount() != 4) return;
                for (int column = 0; column < 4; column++) {
                    ViewGroup cell = (ViewGroup) row.getChildAt(column);
                    android.widget.TextView value = (android.widget.TextView) cell.getChildAt(0);
                    android.widget.TextView caption = (android.widget.TextView) cell.getChildAt(1);
                    if (!Integer.toString(numbers[column]).contentEquals(value.getText())
                            || !activity.getString(captions[column]).contentEquals(caption.getText())
                            || value.getLayout() == null || caption.getLayout() == null) return;
                }
                ready.set(true);
            });
            return ready.get();
        });
        page.onActivity(activity -> {
            android.widget.LinearLayout row = activity.findViewById(android.R.id.content).findViewWithTag("tasks_counts");
            assertEquals("All four counters stay in one row", android.widget.LinearLayout.HORIZONTAL, row.getOrientation());
            for (int column = 0; column < 4; column++) {
                ViewGroup cell = (ViewGroup) row.getChildAt(column);
                for (int child = 0; child < cell.getChildCount(); child++) {
                    android.widget.TextView text = (android.widget.TextView) cell.getChildAt(child);
                    android.text.Layout layout = text.getLayout();
                    assertNotNull(layout);
                    for (int line = 0; line < layout.getLineCount(); line++) {
                        assertEquals("Counter text is not truncated", 0, layout.getEllipsisCount(line));
                        assertTrue("Counter text fits its column", layout.getLineMax(line) <= layout.getWidth() + 1);
                    }
                }
            }
        });
    }

    private void awaitHomeCount(ActivityScenario<? extends Activity> page, int count) throws Exception {
        await(() -> {
            AtomicBoolean found = new AtomicBoolean();
            page.onActivity(activity -> found.set(containsText(
                    activity.findViewById(android.R.id.content).findViewWithTag("home_tasks"),
                    context.getResources().getQuantityString(R.plurals.tasks_active_count, count, count))));
            return found.get();
        });
    }

    @Test public void domCancellationRequiresConfirmationRetainsAuditAndStopsPendingEffects() throws Exception {
        TaskDefinition task = seed("cancel recurring", true);
        ControllerPinManager.enterSubMode();
        repository.act(task.id, task.firstStart.toLocalDate(), TaskAction.Kind.OUTSIDE, "", List.of(), "");
        ControllerPinManager.enterDomMode();
        try (ActivityScenario<TasksActivity> page = launch()) {
            openTask(page, task); String tag = "cancel_task:" + occurrenceId(task);
            awaitTag(page, tag); tap(tag);
            onView(withText(R.string.tasks_keep_task)).perform(click());
            assertEquals(TaskEngine.Status.PENDING, row(task).status);
            tap(tag); onView(withText(R.string.tasks_cancel_series)).perform(click());
            onView(androidx.test.espresso.matcher.ViewMatchers.withId(android.R.id.button1)).perform(click());
            await(() -> row(task).status == TaskEngine.Status.CANCELLED);
            assertTrue(store.load(scope).seriesCancelled(task.id));
            assertFalse(row(task).failed()); assertNull(row(task).submission);
            int before = store.load(scope).json().getJSONArray("actions").length();
            repository.cancel(task.id, task.firstStart.toLocalDate(), true);
            assertEquals(before, store.load(scope).json().getJSONArray("actions").length());
            try (TaskStore reopened = new TaskStore(context)) {
                assertTrue(reopened.load(scope).seriesCancelled(task.id));
                assertEquals(TaskEngine.Status.CANCELLED, reopened.load(scope)
                        .occurrence(task.id, task.firstStart.toLocalDate().plusDays(7), System.currentTimeMillis()).status);
            }
            try (Cursor pending = store.getReadableDatabase().rawQuery(
                    "SELECT COUNT(*) FROM effects WHERE scope=? AND done=0", new String[]{scope})) {
                assertTrue(pending.moveToFirst()); assertEquals(0, pending.getInt(0));
            }
            for (StatusBarNotification notification : context.getSystemService(NotificationManager.class).getActiveNotifications())
                assertFalse(notification.getTag() != null && notification.getTag().startsWith("tasks:" + scope + ":"));
            ControllerPinManager.enterSubMode();
            assertThrows(SecurityException.class, () -> repository.cancel(task.id, task.firstStart.toLocalDate(), true));
        }
    }

    @Test public void domCanArchiveOnlyOneOccurrenceAndArchiveWhileTasksAreDisabled() throws Exception {
        TaskDefinition task = seed("cancel once", true);
        try (ActivityScenario<TasksActivity> page = launch()) {
            openTask(page, task); String tag = "cancel_task:" + occurrenceId(task);
            awaitTag(page, tag); tap(tag);
            onView(androidx.test.espresso.matcher.ViewMatchers.withId(android.R.id.button1)).perform(click());
            await(() -> row(task).status == TaskEngine.Status.CANCELLED);
            assertFalse(store.load(scope).seriesCancelled(task.id));
            assertEquals(TaskEngine.Status.SCHEDULED, store.load(scope).occurrence(task.id,
                    task.firstStart.toLocalDate().plusDays(1), System.currentTimeMillis()).status);
            context.getSharedPreferences(SubFeaturePolicy.STORE, 0).edit().putBoolean("tasks", false).commit();
            repository.cancel(task.id, task.firstStart.toLocalDate().plusDays(1), false);
            assertEquals(TaskEngine.Status.CANCELLED, store.load(scope).occurrence(task.id,
                    task.firstStart.toLocalDate().plusDays(1), System.currentTimeMillis()).status);
        }
    }

    private void capture(String name) throws Exception {
        instrumentation.waitForIdleSync();
        java.util.concurrent.CountDownLatch frame = new java.util.concurrent.CountDownLatch(1);
        instrumentation.runOnMainSync(() -> {
            for (Activity activity : androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
                    .getInstance().getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED))
                activity.getWindow().getDecorView().postOnAnimation(() ->
                        activity.getWindow().getDecorView().postOnAnimation(frame::countDown));
        });
        assertTrue(frame.await(5, java.util.concurrent.TimeUnit.SECONDS));
        android.graphics.Bitmap pixels = instrumentation.getUiAutomation().takeScreenshot();
        assertNotNull(pixels);
        File folder = new File(context.getExternalFilesDir(null), "tasks-ui");
        assertTrue(folder.isDirectory() || folder.mkdirs());
        try (java.io.FileOutputStream stream = new java.io.FileOutputStream(new File(folder, name + ".png"))) {
            assertTrue(pixels.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream));
        } finally { pixels.recycle(); }
    }

    private ActivityScenario<TasksActivity> launch() {
        // No target extra exercises the incoming/local route's explicit LOCAL default.
        return ActivityScenario.launch(new Intent(context, TasksActivity.class));
    }

    private TaskDefinition seed(String suffix, boolean recurring) throws Exception {
        return seed(suffix, recurring, null);
    }

    private TaskDefinition seed(String suffix, boolean recurring, TaskAttachment attachment) throws Exception {
        LocalDateTime start = LocalDateTime.now().minusMinutes(2).withSecond(0).withNano(0);
        TaskDefinition task = new TaskDefinition(UUID.randomUUID().toString(), titlePrefix + suffix,
                "Synthetic task instructions", start, ZoneId.systemDefault(), 86_400_000L,
                recurring ? EnumSet.allOf(DayOfWeek.class) : Set.of(), true, false, attachment);
        store.append(scope, new TaskAction(UUID.randomUUID().toString(), task.id, TaskAction.Kind.CREATE,
                null, System.currentTimeMillis(), "", "", List.of(), task), true, false);
        return task;
    }

    private String occurrenceId(TaskDefinition task) { return task.occurrenceId(task.firstStart.toLocalDate()); }
    private TaskEngine.Occurrence row(TaskDefinition task) throws Exception {
        return store.load(scope).occurrence(task.id, task.firstStart.toLocalDate(), System.currentTimeMillis());
    }
    private void assertSubmissionVerdict(ActivityScenario<TasksActivity> page, String occurrence,
            String submission, int verdict) throws Exception {
        String tag = "task_submission_verdict:" + submission;
        awaitTag(page, tag);
        page.onActivity(activity -> {
            View root = activity.findViewById(android.R.id.content);
            View detail = root.findViewWithTag("task_detail:" + occurrence);
            View completion = root.findViewWithTag("task_submission:" + occurrence);
            View badge = root.findViewWithTag(tag);
            assertNotNull(detail); assertNotNull(completion); assertNotNull(badge);
            assertSame(completion, badge.getParent());
            assertEquals(activity.getString(verdict), ((android.widget.TextView) badge).getText().toString());
            assertFalse(containsText(detail, activity.getString(verdict)));
        });
    }

    private void switchRole(ActivityScenario<TasksActivity> page, boolean dom) {
        page.moveToState(Lifecycle.State.CREATED);
        if (dom) ControllerPinManager.enterDomMode(); else ControllerPinManager.enterSubMode();
        page.moveToState(Lifecycle.State.RESUMED);
    }
    private void tap(String tag) {
        onView(withTagValue(is(tag))).perform(NativeUiActions.revealAboveNavigation(), click());
        instrumentation.waitForIdleSync();
    }
    private void edit(String tag, String text) {
        onView(withTagValue(is(tag))).perform(NativeUiActions.revealAboveNavigation(), click(), replaceText(text), closeSoftKeyboard());
    }
    private void awaitTag(ActivityScenario<? extends Activity> page, String tag) throws Exception {
        await(() -> {
            AtomicBoolean present = new AtomicBoolean();
            page.onActivity(activity -> {
                View target = activity.findViewById(android.R.id.content).findViewWithTag(tag);
                present.set(target != null && target.isEnabled());
            });
            return present.get();
        });
        instrumentation.waitForIdleSync();
    }
    private File taskMediaRoot() throws Exception {
        // Android's /data/user/0 and /data/data aliases identify the same app-private directory.
        File root = new File(context.getFilesDir().getCanonicalFile(), "tasks-media/" + scope);
        assertFalse(Files.isSymbolicLink(root.toPath()));
        return root;
    }

    private boolean mediaRefused(ActivityScenario<TaskMediaActivity> page) {
        if (page.getState() == Lifecycle.State.DESTROYED) return true;
        AtomicBoolean result = new AtomicBoolean();
        try {
            page.onActivity(activity -> {
                View root = activity.findViewById(R.id.role_page_body);
                assertNull(root.findViewWithTag("task_media_image"));
                assertNull(root.findViewWithTag("task_media_player"));
                result.set(containsText(root, context.getString(R.string.tasks_media_unavailable)));
            });
        } catch (IllegalStateException race) {
            if (page.getState() == Lifecycle.State.DESTROYED) return true;
            throw race;
        }
        return result.get();
    }
    private static boolean containsText(View view, String text) {
        if (view == null) return false;
        if (view instanceof android.widget.TextView && text.contentEquals(((android.widget.TextView) view).getText())) return true;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) if (containsText(group.getChildAt(i), text)) return true;
        }
        return false;
    }
    private interface Condition { boolean ready() throws Exception; }
    private static void await(Condition condition) throws Exception {
        long deadline = SystemClock.uptimeMillis() + 10_000;
        while (!condition.ready()) {
            if (SystemClock.uptimeMillis() >= deadline) throw new AssertionError("Tasks UI did not reach the expected state");
            SystemClock.sleep(40);
        }
    }
}
