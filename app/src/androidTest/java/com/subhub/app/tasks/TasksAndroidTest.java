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
import com.subhub.app.remote.RemoteAssets;
import com.subhub.app.remote.RemoteFeature;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.security.DeviceRolePreferences;
import com.subhub.app.security.KeyholderPresentationAndroidTest;
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
    @Rule public final KeyholderPresentationAndroidTest.IntegrationFixture integration =
            new KeyholderPresentationAndroidTest.IntegrationFixture();
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
            awaitTag(page, "tasks_allowed");
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
        try (ActivityScenario<TasksActivity> page = launch()) {
            awaitTag(page, "tasks_allowed"); tap("tasks_allowed");
            await(() -> !repository.allowed());
            page.recreate(); awaitTag(page, "tasks_allowed");
            onView(withText(R.string.tasks_disabled)).check(matches(isDisplayed()));
            switchRole(page, true);
            awaitTag(page, "tasks_target");
            onView(withTagValue(is("tasks_allowed"))).check(doesNotExist());
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
            onView(androidx.test.espresso.matcher.ViewMatchers.withId(R.id.nav_settings)).perform(click());
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
