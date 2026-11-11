package com.subhub.app.journal;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.*;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static androidx.test.espresso.intent.Intents.intending;
import static androidx.test.espresso.intent.matcher.IntentMatchers.hasAction;
import static org.junit.Assert.*;

import android.Manifest;
import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.net.Uri;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import androidx.lifecycle.ViewModelProvider;
import androidx.test.core.app.ActivityScenario;
import androidx.test.espresso.intent.Intents;
import androidx.test.filters.SdkSuppress;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.R;
import com.subhub.app.UiAutomationStateRule;
import com.subhub.app.onboarding.OnboardingState;
import com.subhub.app.people.PersonId;
import com.subhub.app.people.PersonRoutes;
import com.subhub.app.people.ProfileActivity;
import com.subhub.app.people.ProfileStore;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.security.DeviceRolePreferences;
import com.subhub.app.security.KeyholderPresentationAndroidTest;
import com.subhub.app.settings.SettingsRepository;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public final class JournalUiAndroidTest {
    @Rule public final KeyholderPresentationAndroidTest.IntegrationFixture integration =
            new KeyholderPresentationAndroidTest.IntegrationFixture();
    @Rule public final UiAutomationStateRule automation = new UiAutomationStateRule();
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final java.util.Map<String, Boolean> created = new java.util.LinkedHashMap<>();
    private JournalRuntime runtime;
    private java.util.Map<String, ?> profiles;

    @Before public void prepare() throws Exception {
        profiles = new java.util.LinkedHashMap<>(context.getSharedPreferences("subhub_profile_names", 0).getAll());
        new SettingsRepository(context).preferences().edit().putString(DeviceRolePreferences.KEY_MODE,
                DeviceRolePreferences.Mode.BOTH.name()).commit();
        assertTrue(ControllerPinManager.setPin(context, "2468"));
        ControllerPinManager.enterSubMode(); OnboardingState.complete(context);
        runtime = JournalRuntime.get(context);
        if (fixtureLedger().isFile()) {
            org.json.JSONObject previous = new org.json.JSONObject(new String(java.nio.file.Files.readAllBytes(fixtureLedger().toPath()), java.nio.charset.StandardCharsets.UTF_8));
            java.util.Iterator<String> ids = previous.keys();
            while (ids.hasNext()) { String id = ids.next(); assertTrue(id.matches("[0-9a-f]{32}")); created.put(id, previous.getBoolean(id)); }
            deleteCreated();
        }
    }
    @After public void cleanup() throws Exception {
        deleteCreated();
        for (String name : new String[]{"small1", "small2", "small3", "small4", "small5", "small6", "photo", "pending", "oversize", "hiddenoversize"})
            context.getContentResolver().delete(source(name), null, null);
        com.subhub.app.settings.SharedPreferenceTestRestore.restore(context.getSharedPreferences("subhub_profile_names", 0), profiles);
    }

    @Test public void textDraftIsBoundedPrivateSavedAndRecreatedWithoutOpeningKeyboard() throws Exception {
        try (ActivityScenario<JournalActivity> scenario = launch(false, PersonId.LOCAL)) {
            await(scenario, a -> tagged(a, "journal_new") != null);
            tap("journal_new");
            scenario.onActivity(a -> {
                assertFalse(a.getCurrentFocus() instanceof EditText);
                assertEquals(JournalEntry.defaultTitle(model(a).created), ((EditText) tagged(a, "journal_title_input")).getText().toString());
                EditText input = (EditText) tagged(a, "journal_text"); input.setText("x".repeat(4001));
                assertEquals(4000, input.length());
                EditText title = (EditText) tagged(a, "journal_title_input"); title.setText("t".repeat(121));
                assertEquals(120, title.length());
                assertTrue(title.getTop() < input.getTop());
            });
            scenario.recreate();
            scenario.onActivity(a -> { assertFalse(a.getCurrentFocus() instanceof EditText);
                assertEquals(4000, ((EditText) tagged(a, "journal_text")).length());
                assertEquals(120, ((EditText) tagged(a, "journal_title_input")).length()); });
            onView(tag("journal_title_input")).perform(revealAboveNavigation(), click(), replaceText("My private reflection"), closeSoftKeyboard());
            onView(tag("journal_text")).perform(revealAboveNavigation(), click(), replaceText("Private journal fixture"), closeSoftKeyboard());
            String id = modelId(scenario); remember(false, id); tap("journal_save");
            await(scenario, a -> tagged(a, "journal_detail") != null);
            assertEquals("Private journal fixture", runtime.entry(false, PersonId.LOCAL, id).text);
            assertEquals("My private reflection", runtime.entry(false, PersonId.LOCAL, id).title);
            assertTrue(runtime.entries.audience(false, id).isEmpty());
            tap("journal_edit");
            onView(tag("journal_title_input")).perform(revealAboveNavigation(), replaceText("Revised reflection"), closeSoftKeyboard());
            scenario.recreate();
            scenario.onActivity(a -> assertEquals("Revised reflection", ((EditText) tagged(a, "journal_title_input")).getText().toString()));
            tap("journal_save"); await(scenario, a -> tagged(a, "journal_detail") != null);
            assertEquals("Revised reflection", runtime.entry(false, PersonId.LOCAL, id).title);
            assertEquals("Private journal fixture", runtime.entry(false, PersonId.LOCAL, id).text);
            tap("journal_edit");
            onView(tag("journal_title_input")).perform(revealAboveNavigation(), replaceText(""), closeSoftKeyboard());
            tap("journal_save"); await(scenario, a -> tagged(a, "journal_detail") != null);
            JournalEntry cleared = runtime.entry(false, PersonId.LOCAL, id);
            assertEquals("", cleared.title);
            scenario.onActivity(a -> assertEquals(JournalEntry.defaultTitle(cleared.created), ((TextView) tagged(a, "journal_entry_title")).getText().toString()));
            tap("journal_edit");
            scenario.onActivity(a -> assertEquals(JournalEntry.defaultTitle(cleared.created), ((EditText) tagged(a, "journal_title_input")).getText().toString()));
            onView(tag("journal_text")).perform(revealAboveNavigation(), replaceText("Cancelled edit"), closeSoftKeyboard());
            tap("journal_cancel"); await(scenario, a -> tagged(a, "journal_detail") != null);
            assertEquals("Private journal fixture", runtime.entry(false, PersonId.LOCAL, id).text);
        }
    }

    @Test public void pickerCopiesFiveDocumentsAndRejectsDeclaredAndActualOversize() throws Exception {
        try (ActivityScenario<JournalActivity> scenario = launch(false, PersonId.LOCAL)) {
            await(scenario, a -> tagged(a, "journal_new") != null); tap("journal_new");
            for (String source : new String[]{"oversize", "hiddenoversize"}) {
                pick(scenario, source);
                scenario.onActivity(a -> assertTrue(model(a).files().isEmpty()));
            }
            for (int i = 1; i <= 5; i++) { pick(scenario, "small" + i); final int count = i;
                scenario.onActivity(a -> assertEquals(count, model(a).files().size())); }
            scenario.onActivity(a -> assertFalse(tagged(a, "journal_add_attachment").isEnabled()));
            scenario.recreate();
            scenario.onActivity(a -> assertEquals(5, model(a).files().size()));
            final JournalAttachment[] first = new JournalAttachment[1];
            scenario.onActivity(a -> first[0] = model(a).files().get(0));
            tap("journal_remove:" + first[0].hash);
            scenario.onActivity(a -> { assertEquals(4, model(a).files().size()); assertTrue(tagged(a, "journal_add_attachment").isEnabled()); });
            pick(scenario, "small6"); String id = modelId(scenario); remember(false, id);
            tap("journal_save"); await(scenario, a -> tagged(a, "journal_detail") != null);
            JournalEntry entry = runtime.entry(false, PersonId.LOCAL, id); assertEquals(5, entry.attachments.size());
            for (JournalAttachment file : entry.attachments) assertEquals(64, runtime.mediaFile(false, PersonId.LOCAL, id, file.hash).length());
        }
    }

    @Test public void profileAndOverviewPreviewsShowOnlyTitleAndUseCreationTimeFallback() throws Exception {
        String id = seed(false, "Body must never become a preview", null, List.of(), false);
        JournalEntry original = runtime.entry(false, PersonId.LOCAL, id);
        runtime.save(false, new JournalEntry(id, original.created, original.updated,
                "A chosen title", original.text, null, List.of()), false, false);
        try (ActivityScenario<ProfileActivity> scenario = ActivityScenario.launch(ProfileActivity.class)) {
            await(scenario, a -> tagged(a, "profile_journal_preview") != null
                    && ((TextView) tagged(a, "profile_journal_preview")).getText().toString().equals("A chosen title"));
            scenario.onActivity(a -> JournalPreview.addOverview(
                    (android.widget.LinearLayout) tagged(a, "profile_journal_preview").getParent(), false, () -> { }));
            await(scenario, a -> tagged(a, "overview_journal_preview") != null
                    && ((TextView) tagged(a, "overview_journal_preview")).getText().toString().equals("A chosen title"));
            runtime.save(false, original, false, false);
            await(scenario, a -> ((TextView) tagged(a, "profile_journal_preview")).getText().toString().equals(original.displayTitle())
                    && ((TextView) tagged(a, "overview_journal_preview")).getText().toString().equals(original.displayTitle()));
        }
    }

    @Test @SdkSuppress(minSdkVersion = 29)
    public void previewStopsPollingWhileHiddenAndRefreshesOnReturnAndReattachment() throws Exception {
        String id = seed(false, "Private lifecycle fixture", null, List.of(), false);
        JournalEntry original = runtime.entry(false, PersonId.LOCAL, id);
        JournalPreview[] retained = new JournalPreview[1];
        android.os.Handler[] handler = new android.os.Handler[1];
        Runnable[] poll = new Runnable[1];
        try (ActivityScenario<ProfileActivity> scenario = ActivityScenario.launch(ProfileActivity.class)) {
            // Profile rebuilds its own pane on resume. Retain this additional preview outside
            // that pane so the fixture exercises the same View across a full stop/start.
            scenario.onActivity(a -> {
                android.widget.LinearLayout host = new android.widget.LinearLayout(a);
                ((ViewGroup) a.findViewById(android.R.id.content)).addView(host);
                JournalPreview.addOverview(host, false, () -> { });
                retained[0] = (JournalPreview) host.getChildAt(0);
                try {
                    java.lang.reflect.Field handlerField = JournalPreview.class.getDeclaredField("handler");
                    java.lang.reflect.Field pollField = JournalPreview.class.getDeclaredField("poll");
                    handlerField.setAccessible(true); pollField.setAccessible(true);
                    handler[0] = (android.os.Handler) handlerField.get(retained[0]);
                    poll[0] = (Runnable) pollField.get(retained[0]);
                } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
                if (android.os.Build.VERSION.SDK_INT >= 29) assertTrue(handler[0].hasCallbacks(poll[0]));
            });
            await(scenario, a -> ((TextView) retained[0].findViewWithTag("overview_journal_preview"))
                    .getText().toString().equals(original.displayTitle()));
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED);
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                assertTrue("Exercise a retained stopped view, not only detachment", retained[0].isAttachedToWindow());
                if (android.os.Build.VERSION.SDK_INT >= 29) assertFalse(handler[0].hasCallbacks(poll[0]));
                poll[0].run(); // A delivered stale callback must not restart its loop.
                if (android.os.Build.VERSION.SDK_INT >= 29) assertFalse(handler[0].hasCallbacks(poll[0]));
            });
            runtime.save(false, new JournalEntry(id, original.created, original.updated,
                    "Changed while hidden", original.text, null, List.of()), false, false);
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED);
            await(scenario, a -> ((TextView) retained[0].findViewWithTag("overview_journal_preview"))
                    .getText().toString().equals("Changed while hidden"));
            scenario.onActivity(a -> {
                if (android.os.Build.VERSION.SDK_INT >= 29) assertTrue(handler[0].hasCallbacks(poll[0]));
                ViewGroup parent = (ViewGroup) retained[0].getParent();
                int index = parent.indexOfChild(retained[0]);
                parent.removeView(retained[0]);
                if (android.os.Build.VERSION.SDK_INT >= 29) assertFalse(handler[0].hasCallbacks(poll[0]));
                poll[0].run();
                if (android.os.Build.VERSION.SDK_INT >= 29) assertFalse(handler[0].hasCallbacks(poll[0]));
                parent.addView(retained[0], index);
                if (android.os.Build.VERSION.SDK_INT >= 29) assertTrue(handler[0].hasCallbacks(poll[0]));
            });
        }
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            if (android.os.Build.VERSION.SDK_INT >= 29) assertFalse(handler[0].hasCallbacks(poll[0]));
        });
    }

    @Test public void sharedViewAndProviderRejectWithdrawnEntriesAndWrongRole() throws Exception {
        String draft = UUID.randomUUID().toString(); runtime.retainDraft(false, draft, Collections.emptyList());
        JournalAttachment file = runtime.importFile(false, draft, source("small1"));
        String id = UUID.randomUUID().toString().replace("-", ""); remember(false, id);
        long now = System.currentTimeMillis();
        JournalEntry entry = new JournalEntry(id, now, now, "Explicitly shared fixture", null, List.of(file));
        runtime.save(false, entry, false, false); runtime.releaseDraft(false, draft);
        ControllerPinManager.enterDomMode();
        PersonId target = new PersonId("journal_local_sub"); assertNull(runtime.entry(true, target, id));
        ControllerPinManager.enterSubMode(); runtime.save(false, entry, true, true); ControllerPinManager.enterDomMode();
        Uri uri = JournalProvider.uri(context, true, target, id, file);
        try (ActivityScenario<JournalActivity> scenario = launch(true, target)) {
            await(scenario, a -> tagged(a, "journal_entry:" + id) != null); tap("journal_entry:" + id);
            await(scenario, a -> tagged(a, "journal_detail") != null);
            scenario.onActivity(a -> { assertNull(tagged(a, "journal_edit")); assertNull(tagged(a, "journal_new")); assertNull(tagged(a, "journal_share")); });
            try (java.io.InputStream stream = context.getContentResolver().openInputStream(uri)) { assertNotNull(stream); assertTrue(stream.read() >= 0); }
            ControllerPinManager.enterSubMode();
            try { context.getContentResolver().openInputStream(uri); fail("Role change must deny an existing URI"); }
            catch (java.io.FileNotFoundException expected) { }
            runtime.save(false, entry, false, false); ControllerPinManager.enterDomMode();
            assertNull(runtime.entry(true, target, id));
            try { context.getContentResolver().openInputStream(uri); fail("Withdrawal must deny an existing URI"); }
            catch (java.io.FileNotFoundException expected) { }
        }
    }

    @Test @SdkSuppress(minSdkVersion = 29)
    public void deniedMicrophoneLeavesTextEditingAndSavingAvailable() throws Exception {
        assertEquals(PackageManager.PERMISSION_DENIED, context.checkSelfPermission(Manifest.permission.RECORD_AUDIO));
        try (ActivityScenario<JournalActivity> scenario = launch(false, PersonId.LOCAL)) {
            await(scenario, a -> tagged(a, "journal_new") != null); tap("journal_new");
            assertEquals(PackageManager.PERMISSION_DENIED, context.checkSelfPermission(Manifest.permission.RECORD_AUDIO));
            tap("journal_record");
            androidx.test.uiautomator.UiDevice device = androidx.test.uiautomator.UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
            androidx.test.uiautomator.UiObject2 deny = device.wait(androidx.test.uiautomator.Until.findObject(
                    androidx.test.uiautomator.By.res("com.android.permissioncontroller", "permission_deny_button")), 5000);
            assertNotNull("Record must request permission only after the tap", deny); deny.click();
            await(scenario, a -> tagged(a, "journal_save").isEnabled());
            assertEquals(PackageManager.PERMISSION_DENIED, context.checkSelfPermission(Manifest.permission.RECORD_AUDIO));
            if (android.os.Build.VERSION.SDK_INT >= 29) capture(scenario, "journal-microphone-denied");
            onView(tag("journal_text")).perform(revealAboveNavigation(), click(), replaceText("Text works without microphone access"), closeSoftKeyboard());
            String id = modelId(scenario); remember(false, id); tap("journal_save");
            await(scenario, a -> tagged(a, "journal_detail") != null);
            assertNull(runtime.entry(false, PersonId.LOCAL, id).audio);
        }
    }

    @Test @SdkSuppress(minSdkVersion = 29)
    public void nativeRecordingStartsOnlyOnTapStopsOnRecreationAndPlays() throws Exception {
        InstrumentationRegistry.getInstrumentation().getUiAutomation(android.app.UiAutomation.FLAG_DONT_USE_ACCESSIBILITY)
                .grantRuntimePermission(context.getPackageName(), Manifest.permission.RECORD_AUDIO);
        try (ActivityScenario<JournalActivity> scenario = launch(false, PersonId.LOCAL)) {
            await(scenario, a -> tagged(a, "journal_new") != null); tap("journal_new");
            scenario.onActivity(a -> { assertEquals(PackageManager.PERMISSION_GRANTED, a.checkSelfPermission(Manifest.permission.RECORD_AUDIO));
                assertEquals(a.getString(R.string.journal_record), ((TextView) tagged(a, "journal_record")).getText()); });
            tap("journal_record");
            await(scenario, a -> ((TextView) tagged(a, "journal_record")).getText().toString().equals(a.getString(R.string.journal_stop)));
            SystemClock.sleep(1600); capture(scenario, "journal-recording"); scenario.recreate();
            await(scenario, a -> tagged(a, "journal_play") != null && !model(a).busy);
            scenario.onActivity(a -> { assertTrue(model(a).audio.length > 0); assertTrue(tagged(a, "journal_save").isEnabled()); });
            capture(scenario, "journal-audio-editor"); tap("journal_play");
            await(scenario, a -> ((TextView) tagged(a, "journal_play")).getText().toString().equals(a.getString(R.string.journal_pause)));
            tap("journal_play");
            String id = modelId(scenario); remember(false, id); tap("journal_save");
            await(scenario, a -> tagged(a, "journal_detail") != null && tagged(a, "journal_play") != null);
            JournalEntry entry = runtime.entry(false, PersonId.LOCAL, id); assertNotNull(entry.audio); assertTrue(entry.text.isEmpty());
            capture(scenario, "journal-audio-detail");
            File fixture = voiceFixture();
            if (fixture.exists()) assertEquals(com.subhub.app.remote.RemoteAssets.digest(fixture), entry.audio.hash);
            else java.nio.file.Files.copy(runtime.mediaFile(false, PersonId.LOCAL, id, entry.audio.hash).toPath(), fixture.toPath());
        }
    }

    @Test @SdkSuppress(minSdkVersion = 29)
    public void nativeRecordingLimitStopsAtFiveMinutesAndKeepsPlayableBoundedAudio() throws Exception {
        InstrumentationRegistry.getInstrumentation().getUiAutomation(android.app.UiAutomation.FLAG_DONT_USE_ACCESSIBILITY)
                .grantRuntimePermission(context.getPackageName(), Manifest.permission.RECORD_AUDIO);
        try (ActivityScenario<JournalActivity> scenario = launch(false, PersonId.LOCAL)) {
            await(scenario, a -> tagged(a, "journal_new") != null); tap("journal_new"); tap("journal_record");
            await(scenario, a -> ((TextView) tagged(a, "journal_record")).getText().toString().equals(a.getString(R.string.journal_stop)));
            long start = SystemClock.elapsedRealtime(), deadline = start + 325_000;
            AtomicBoolean complete = new AtomicBoolean();
            while (!complete.get() && SystemClock.elapsedRealtime() < deadline) {
                scenario.onActivity(a -> complete.set(model(a).audio != null && !model(a).busy)); SystemClock.sleep(200);
            }
            assertTrue("Native duration limit must retain its recording", complete.get());
            assertTrue("No early automatic stop", SystemClock.elapsedRealtime() - start >= 295_000);
            final JournalAttachment[] audio = new JournalAttachment[1];
            scenario.onActivity(a -> audio[0] = model(a).audio);
            assertTrue(audio[0].length <= JournalEntry.MAX_RECORDING_BYTES);
            tap("journal_play"); await(scenario, a -> ((TextView) tagged(a, "journal_play")).getText().toString().equals(a.getString(R.string.journal_pause)));
            tap("journal_play"); String id = modelId(scenario); remember(false, id); tap("journal_save");
            await(scenario, a -> tagged(a, "journal_detail") != null);
            android.media.MediaMetadataRetriever metadata = new android.media.MediaMetadataRetriever();
            try {
                metadata.setDataSource(runtime.mediaFile(false, PersonId.LOCAL, id, audio[0].hash).getAbsolutePath());
                long duration = Long.parseLong(metadata.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION));
                assertTrue("Recorded duration " + duration, duration >= 299_000 && duration <= 301_000);
            } finally { metadata.release(); }
        }
    }

    @Test @SdkSuppress(minSdkVersion = 29)
    public void boundDomPreviewJournalOpensSharedReadOnlyTargetAndEmptyBioLeavesNoGap() throws Exception {
        com.subhub.app.people.ProfileData owner = new com.subhub.app.people.ProfileData("Alexandra Morgan", "A steady routine.", "");
        com.subhub.app.people.People.installLocalProjection(person -> person.withContractDom(owner, new PersonId("journal_local_dom")));
        try (ActivityScenario<com.subhub.app.people.ContractActivity> scenario = ActivityScenario.launch(com.subhub.app.people.ContractActivity.class)) {
            await(scenario, a -> tagged(a, "profile_journal") != null);
            scenario.onActivity(a -> { assertTrue(rect(tagged(a, "profile_journal")).top >= rect(tagged(a, "contract_dom_about")).bottom);
                assertNull(tagged(a, "contract_name")); textFits(tagged(a, "person_contract_identity")); });
            capture(scenario, "bound-dom-journal");
            com.subhub.app.people.People.installLocalProjection(person -> person.withContractDom(
                    new com.subhub.app.people.ProfileData("Alexandra Morgan", "", ""), new PersonId("journal_local_dom")));
            scenario.recreate(); await(scenario, a -> tagged(a, "profile_journal") != null);
            scenario.onActivity(a -> { assertNull(tagged(a, "contract_dom_about")); assertNull(tagged(a, "contract_dom_about_expand")); });
            capture(scenario, "bound-dom-empty-bio-journal");
            Instrumentation.ActivityMonitor monitor = InstrumentationRegistry.getInstrumentation().addMonitor(JournalActivity.class.getName(), null, false);
            try {
                tap("profile_journal"); Activity opened = InstrumentationRegistry.getInstrumentation().waitForMonitorWithTimeout(monitor, 5000);
                assertNotNull(opened); assertEquals("journal_local_dom", opened.getIntent().getStringExtra(PersonRoutes.EXTRA_PERSON_ID));
                assertFalse(opened.getIntent().getBooleanExtra(JournalRoutes.EXTRA_DOM, true));
                InstrumentationRegistry.getInstrumentation().runOnMainSync(opened::finish);
            } finally { InstrumentationRegistry.getInstrumentation().removeMonitor(monitor); }
        }
    }

    @Test @SdkSuppress(minSdkVersion = 29)
    public void bothOwnedProfilesPlaceJournalBelowAboutAndCapturePopulatedEditor() throws Exception {
        assertTrue(ProfileStore.save(context, false, "Journal Sub", "An About me paragraph.", ""));
        ControllerPinManager.enterDomMode(); assertTrue(ProfileStore.save(context, true, "Journal Dom", "Dom About me paragraph.", ""));
        for (boolean dom : new boolean[]{false, true}) {
            if (dom) ControllerPinManager.enterDomMode(); else ControllerPinManager.enterSubMode();
            try (ActivityScenario<ProfileActivity> scenario = ActivityScenario.launch(ProfileActivity.class)) {
                await(scenario, a -> tagged(a, "profile_journal") != null);
                scenario.onActivity(a -> { Rect about = rect(tagged(a, "self_profile_about")), journal = rect(tagged(a, "profile_journal"));
                    assertTrue(journal.top >= about.bottom); assertTrue(journal.height() >= Math.round(48 * a.getResources().getDisplayMetrics().density)); });
                capture(scenario, dom ? "profile-dom-journal" : "profile-sub-journal");
                Instrumentation.ActivityMonitor monitor = InstrumentationRegistry.getInstrumentation().addMonitor(JournalActivity.class.getName(), null, false);
                try {
                    tap("profile_journal"); Activity opened = InstrumentationRegistry.getInstrumentation().waitForMonitorWithTimeout(monitor, 5000);
                    assertNotNull(opened); assertEquals("local", opened.getIntent().getStringExtra(PersonRoutes.EXTRA_PERSON_ID));
                    assertEquals(dom, opened.getIntent().getBooleanExtra(JournalRoutes.EXTRA_DOM, !dom));
                    InstrumentationRegistry.getInstrumentation().runOnMainSync(opened::finish);
                } finally { InstrumentationRegistry.getInstrumentation().removeMonitor(monitor); }
            }
        }
        ControllerPinManager.enterSubMode();
        try (ActivityScenario<JournalActivity> scenario = launch(false, PersonId.LOCAL)) {
            await(scenario, a -> tagged(a, "journal_new") != null); capture(scenario, "journal-list"); tap("journal_new");
            onView(tag("journal_text")).perform(revealAboveNavigation(), replaceText("Today I made time to reflect.\nA second paragraph keeps the entry readable."), closeSoftKeyboard());
            capture(scenario, "journal-editor-text");
            pick(scenario, "photo");
            await(scenario, a -> {
                JournalAttachment photo = model(a).files().stream().filter(f -> f.mime.equals("image/png")).findFirst().orElse(null);
                if (photo == null) return false;
                android.widget.ImageView icon = (android.widget.ImageView) ((ViewGroup) tagged(a, "journal_attachment:" + photo.hash)).getChildAt(0);
                return icon.getImageTintList() == null && icon.getDrawable() instanceof android.graphics.drawable.BitmapDrawable;
            });
            for (int i = 2; i <= 5; i++) pick(scenario, "small" + i);
            onView(withContentDescription("Remove photo.png")).perform(revealAboveNavigation());
            capture(scenario, "journal-editor-files-top");
            onView(tag("journal_cancel")).perform(revealAboveNavigation());
            scenario.onActivity(a -> textFits(a.getWindow().getDecorView())); capture(scenario, "journal-editor-five-files");
            String id = modelId(scenario); remember(false, id); tap("journal_save");
            await(scenario, a -> tagged(a, "journal_detail") != null);
            capture(scenario, "journal-detail-files");
            androidx.test.espresso.Espresso.pressBack(); await(scenario, a -> tagged(a, "journal_entry:" + id) != null);
            capture(scenario, "journal-list-populated");
        }
    }

    @Test @SdkSuppress(minSdkVersion = 29)
    public void unifiedPreviewsAndOptionalEditorRenderWithoutLeakingPrivateJournalEntries() throws Exception {
        String about = "I value clear communication and a steady routine. I keep time for reflection and shared plans. ".repeat(3).trim();
        assertTrue(ProfileStore.save(context, false, "Alexandra Morgan", about, "", "Non-binary", 32));
        String privateId = seed(false, "Private preview for its own Sub workspace", null, List.of(), false);
        String subDefaultTitle = runtime.entry(false, PersonId.LOCAL, privateId).displayTitle();
        ControllerPinManager.enterDomMode();
        assertTrue(ProfileStore.save(context, true, "Rowan Avery", "Clear plans, patient support, and honest reflection.", "", "Woman", 34));
        String domPrivateId = seed(true, "Private preview for its own Dom workspace", null, List.of(), false);
        String domDefaultTitle = runtime.entry(true, PersonId.LOCAL, domPrivateId).displayTitle();
        ControllerPinManager.enterSubMode();
        try (ActivityScenario<ProfileActivity> scenario = ActivityScenario.launch(ProfileActivity.class)) {
            await(scenario, a -> tagged(a, "profile_journal_preview") != null && ((TextView) tagged(a, "profile_journal_preview")).getText().toString().equals(subDefaultTitle));
            scenario.onActivity(a -> {
                assertTrue(tagged(a, "profile_preview:sub") instanceof com.subhub.app.people.ProfilePreview);
                assertTrue(((TextView) tagged(a, "self_profile_details")).getText().toString().contains("Non-binary"));
                assertEquals(2, ((TextView) tagged(a, "self_profile_about")).getMaxLines());
            });
            await(scenario, a -> tagged(a, "self_profile_about_expand").isShown());
            capture(scenario, "polished-profile-sub"); tap("self_profile_about_expand");
            capture(scenario, "polished-profile-sub-expanded"); scenario.recreate();
            await(scenario, a -> tagged(a, "self_profile_about_expand") != null
                    && ((TextView) tagged(a, "self_profile_about_expand")).getText().toString().equals(a.getString(R.string.profile_show_less)));
        }
        try (ActivityScenario<com.subhub.app.people.ProfileEditorActivity> editor = ActivityScenario.launch(
                new Intent(context, com.subhub.app.people.ProfileEditorActivity.class).putExtra(com.subhub.app.people.ProfileEditorActivity.EXTRA_DOM, false))) {
            editor.onActivity(a -> { assertFalse(a.getCurrentFocus() instanceof EditText);
                assertEquals("32", ((EditText) tagged(a, "profile_age")).getText().toString()); });
            onView(tag("profile_gender")).perform(revealAboveNavigation()); capture(editor, "polished-profile-editor-details");
            onView(tag("profile_cancel")).perform(revealAboveNavigation()); capture(editor, "polished-profile-editor-bottom");
            onView(tag("profile_age")).perform(revealAboveNavigation(), replaceText("0"), closeSoftKeyboard()); tap("profile_save");
            editor.onActivity(a -> assertNotNull(((EditText) tagged(a, "profile_age")).getError()));
            onView(tag("profile_age")).perform(revealAboveNavigation(), click(), closeSoftKeyboard());
            capture(editor, "polished-profile-editor-invalid-age"); assertEquals(32, ProfileStore.load(context, false).age);
        }
        ControllerPinManager.enterDomMode();
        try (ActivityScenario<ProfileActivity> scenario = ActivityScenario.launch(ProfileActivity.class)) {
            await(scenario, a -> tagged(a, "profile_journal_preview") != null && ((TextView) tagged(a, "profile_journal_preview")).getText().toString().equals(domDefaultTitle));
            scenario.onActivity(a -> assertTrue(tagged(a, "profile_preview:dom") instanceof com.subhub.app.people.ProfilePreview));
            capture(scenario, "polished-profile-dom");
        }
        try (ActivityScenario<com.subhub.app.people.PersonActivity> scenario = ActivityScenario.launch(
                new Intent(context, com.subhub.app.people.PersonActivity.class).putExtra(PersonRoutes.EXTRA_PERSON_ID, PersonId.LOCAL.value()))) {
            await(scenario, a -> tagged(a, "profile_journal_preview") != null
                    && !((TextView) tagged(a, "profile_journal_preview")).getText().toString().equals(a.getString(R.string.profile_journal_loading)));
            scenario.onActivity(a -> {
                assertTrue(tagged(a, "profile_preview:sub") instanceof com.subhub.app.people.ProfilePreview);
                assertFalse(((TextView) tagged(a, "profile_journal_preview")).getText().toString().contains("Private preview"));
                assertNull(tagged(a, "profile_edit"));
            });
            capture(scenario, "polished-paired-sub-preview");
        }
        ControllerPinManager.enterSubMode();
        com.subhub.app.people.ProfileData owner = ProfileStore.load(context, true);
        com.subhub.app.people.People.installLocalProjection(person -> person.withContractDom(owner, new PersonId("journal_local_dom")));
        try (ActivityScenario<com.subhub.app.people.ContractActivity> scenario = ActivityScenario.launch(com.subhub.app.people.ContractActivity.class)) {
            await(scenario, a -> tagged(a, "profile_preview:dom") != null);
            scenario.onActivity(a -> assertTrue(((TextView) tagged(a, "contract_dom_details")).getText().toString().contains("34")));
            capture(scenario, "polished-contract-dom-preview");
        }
        assertNotNull(runtime.entry(false, PersonId.LOCAL, privateId));
        assertTrue(ProfileStore.save(context, false, "Avery", "", "", "", 0));
        try (ActivityScenario<ProfileActivity> scenario = ActivityScenario.launch(ProfileActivity.class)) {
            await(scenario, a -> tagged(a, "profile_preview:sub") != null);
            scenario.onActivity(a -> { assertNull(tagged(a, "self_profile_about"));
                assertEquals(a.getString(R.string.role_sub), ((TextView) tagged(a, "self_profile_details")).getText().toString()); });
            capture(scenario, "polished-profile-empty-details");
        }
    }

    @Test @SdkSuppress(minSdkVersion = 29)
    public void sharedPendingWithdrawalLoadErrorAndBusyStatesKeepTheirNativeHierarchy() throws Exception {
        String draft = UUID.randomUUID().toString(); runtime.retainDraft(false, draft, List.of());
        java.util.Set<String> previous = runtime.entries.media(false, null);
        JournalAttachment photo = runtime.importFile(false, draft, source("photo"));
        JournalAttachment pending = runtime.importFile(false, draft, source("pending")); assertFalse(previous.contains(pending.hash));
        String id = seed(false, "A shared reflection, with a photo ready to open and a file still waiting to sync.", null, List.of(photo, pending), true);
        runtime.releaseDraft(false, draft);
        File missing = runtime.mediaFile(false, PersonId.LOCAL, id, pending.hash);
        assertTrue(missing.getCanonicalFile().toPath().startsWith(new File(context.getFilesDir(), "profile-journal").getCanonicalFile().toPath()));
        java.nio.file.Files.copy(missing.toPath(), new File(context.getCacheDir(), "journal-polish-pending-" + id).toPath());
        assertTrue(missing.delete());
        ControllerPinManager.enterDomMode(); PersonId target = new PersonId("journal_local_sub");
        try (ActivityScenario<JournalActivity> scenario = launch(true, target)) {
            await(scenario, a -> tagged(a, "journal_entry:" + id) != null); capture(scenario, "journal-shared-list"); tap("journal_entry:" + id);
            await(scenario, a -> tagged(a, "journal_detail") != null);
            scenario.onActivity(a -> {
                assertNull(tagged(a, "journal_edit")); assertNull(tagged(a, "journal_delete")); assertNull(tagged(a, "journal_share"));
                assertFalse(tagged(a, "journal_attachment:" + pending.hash).isEnabled());
                assertTrue(tagged(a, "journal_attachment:" + photo.hash).isEnabled());
            });
            capture(scenario, "journal-shared-pending"); onView(tag("journal_read_only")).perform(revealAboveNavigation());
            capture(scenario, "journal-shared-pending-bottom");
            ControllerPinManager.enterSubMode(); JournalEntry entry = runtime.entry(false, PersonId.LOCAL, id);
            // Restore this fixture's own missing bytes before its private save validates media.
            java.nio.file.Files.copy(new File(context.getCacheDir(), "journal-polish-pending-" + id).toPath(), missing.toPath());
            assertEquals(pending.hash, com.subhub.app.remote.RemoteAssets.digest(missing));
            runtime.save(false, entry, false, false); ControllerPinManager.enterDomMode();
            await(scenario, a -> tagged(a, "journal_unavailable") != null); capture(scenario, "journal-withdrawn");
            scenario.onActivity(a -> assertNull(tagged(a, "journal_text_body")));
        }
        ControllerPinManager.enterSubMode();
        try (ActivityScenario<JournalActivity> scenario = launch(false, PersonId.LOCAL)) {
            await(scenario, a -> tagged(a, "journal_entry:" + id) != null); tap("journal_entry:" + id);
            await(scenario, a -> tagged(a, "journal_detail") != null);
            scenario.onActivity(a -> {
                model(a).busy = true;
                try { java.lang.reflect.Method failure = JournalActivity.class.getDeclaredMethod("failedRefresh"); failure.setAccessible(true); failure.invoke(a); }
                catch (ReflectiveOperationException error) { throw new AssertionError(error); }
                assertNotNull(tagged(a, "journal_text_body"));
            });
            capture(scenario, "journal-load-error-retains-entry");
            scenario.onActivity(a -> model(a).busy = false); scenario.recreate();
            await(scenario, a -> tagged(a, "journal_edit") != null); tap("journal_edit");
            CountDownLatch release = new CountDownLatch(1);
            try {
                scenario.onActivity(a -> model(a).work(() -> { assertTrue(release.await(10, TimeUnit.SECONDS)); return true; }, ignored -> { }, R.string.journal_failed));
                scenario.onActivity(a -> { assertNotNull(tagged(a, "journal_busy")); assertFalse(tagged(a, "journal_save").isEnabled()); });
                capture(scenario, "journal-composer-working");
            } finally { release.countDown(); }
            await(scenario, a -> !model(a).busy && tagged(a, "journal_save").isEnabled());
        }
        try (ActivityScenario<JournalActivity> scenario = launch(false, PersonId.LOCAL)) {
            await(scenario, a -> tagged(a, "journal_new") != null); tap("journal_new"); tap("journal_save");
            scenario.onActivity(a -> assertNotNull(tagged(a, "journal_message")));
            onView(tag("journal_message")).perform(revealAboveNavigation()); capture(scenario, "journal-empty-entry-error");
        }
    }

    @Test @SdkSuppress(minSdkVersion = 29)
    public void retainedVoiceFixtureRendersTheSameNativePlayerAndRemovalControls() throws Exception {
        File fixture = voiceFixture(); assertTrue("Run the bounded recording producer for this media seed first", fixture.isFile());
        String draft = UUID.randomUUID().toString(); runtime.retainDraft(false, draft, List.of());
        JournalAttachment sound = runtime.importRecording(false, draft, fixture);
        String id = seed(false, "A voice note with room for a short written reflection.", sound, List.of(), false);
        runtime.releaseDraft(false, draft);
        try (ActivityScenario<JournalActivity> scenario = launch(false, PersonId.LOCAL)) {
            await(scenario, a -> tagged(a, "journal_entry:" + id) != null); tap("journal_entry:" + id);
            await(scenario, a -> tagged(a, "journal_play") != null); capture(scenario, "polished-voice-detail");
            tap("journal_play"); await(scenario, a -> ((TextView) tagged(a, "journal_play")).getText().toString().equals(a.getString(R.string.journal_pause)));
            capture(scenario, "polished-voice-playing"); tap("journal_play"); tap("journal_edit");
            await(scenario, a -> tagged(a, "journal_remove_audio") != null);
            onView(tag("journal_record")).perform(revealAboveNavigation()); capture(scenario, "polished-voice-editor");
            tap("journal_remove_audio"); scenario.onActivity(a -> assertNull(model(a).audio));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void queuedRefreshCannotKeepPrivateRowsOrPlaybackAfterControllerLock() throws Exception {
        ControllerPinManager.enterDomMode();
        String id = seed(true, "Private queued-read fixture", null, List.of(), false);
        File sound = new File(context.getCacheDir(), "journal-held-read-" + UUID.randomUUID() + ".wav");
        // Actual silent PCM playback verifies decoder teardown without microphone permission.
        int bytes = 8_000 * 2 * 30;
        java.nio.ByteBuffer wave = java.nio.ByteBuffer.allocate(44 + bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        wave.put("RIFF".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(36 + bytes)
                .put("WAVEfmt ".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(16)
                .putShort((short) 1).putShort((short) 1).putInt(8_000).putInt(16_000)
                .putShort((short) 2).putShort((short) 16)
                .put("data".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(bytes);
        java.nio.file.Files.write(sound.toPath(), wave.array());
        CountDownLatch started = new CountDownLatch(2), release = new CountDownLatch(1);
        JournalAudioPlayer[] playback = new JournalAudioPlayer[1];
        try (ActivityScenario<JournalActivity> scenario = launch(true, PersonId.LOCAL)) {
            try {
                await(scenario, a -> tagged(a, "journal_entry:" + id) != null); tap("journal_entry:" + id);
                await(scenario, a -> tagged(a, "journal_text_body") != null);
                scenario.onActivity(a -> {
                    // Register the real native player with this page's normal teardown owner.
                    playback[0] = new JournalAudioPlayer(a, Uri.fromFile(sound), () -> fail("Fixture audio unavailable"));
                    ((android.widget.LinearLayout) privateField(a, "content")).addView(playback[0]);
                    ((List<JournalAudioPlayer>) privateField(a, "players")).add(playback[0]);
                    playback[0].findViewWithTag("journal_play").performClick();
                });
                await(scenario, a -> privateField(playback[0], "player") != null
                        && ((android.media.MediaPlayer) privateField(playback[0], "player")).isPlaying());
                scenario.onActivity(a -> {
                    com.subhub.app.util.AsyncUiScope work = com.subhub.app.util.AsyncUiScope.forPage(a);
                    work.cancel("journal-screen");
                    for (int index = 0; index < 2; index++) work.load("journal-gate:" + index, () -> {
                        started.countDown();
                        if (!release.await(30, TimeUnit.SECONDS)) throw new IllegalStateException("Fixture gate timed out");
                        return true;
                    }, ignored -> { }, failure -> { });
                });
                assertTrue("Both page workers are held", started.await(5, TimeUnit.SECONDS));
                scenario.onActivity(a -> {
                    try {
                        java.lang.reflect.Method refresh = JournalActivity.class.getDeclaredMethod("refresh");
                        refresh.setAccessible(true); refresh.invoke(a);
                    } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
                    assertTrue(com.subhub.app.util.AsyncUiScope.forPage(a).isPending("journal-screen"));
                    assertNotNull(tagged(a, "journal_text_body"));
                    ControllerPinManager.enterSubMode();
                });
                // Exercise the recurring poll, not a completed read: private content must disappear
                // while the queue is still held, including the actively playing native decoder.
                await(scenario, a -> tagged(a, "journal_unavailable") != null);
                assertEquals(1L, release.getCount());
                scenario.onActivity(a -> {
                    assertNull(tagged(a, "journal_text_body"));
                    assertNull(privateField(playback[0], "player"));
                    assertTrue(((List<?>) privateField(a, "players")).isEmpty());
                    assertFalse(com.subhub.app.util.AsyncUiScope.forPage(a).isPending("journal-screen"));
                });
                release.countDown();
                InstrumentationRegistry.getInstrumentation().waitForIdleSync();
                scenario.onActivity(a -> assertNull(tagged(a, "journal_text_body")));
            } finally { release.countDown(); }
        } finally { java.nio.file.Files.deleteIfExists(sound.toPath()); }
    }

    private static Object privateField(Object owner, String name) {
        try {
            java.lang.reflect.Field field = owner.getClass().getDeclaredField(name);
            field.setAccessible(true); return field.get(owner);
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }

    private String seed(boolean dom, String text, JournalAttachment audio, List<JournalAttachment> files, boolean shared) throws Exception {
        String id = UUID.randomUUID().toString().replace("-", ""); long now = System.currentTimeMillis();
        runtime.save(dom, new JournalEntry(id, now, now, text, audio, files), shared, shared); remember(dom, id);
        return id;
    }
    private File fixtureLedger() {
        String seed = InstrumentationRegistry.getArguments().getString("media_seed", "profile-journal-polish");
        assertTrue(seed.matches("[a-zA-Z0-9-]{1,80}"));
        return new File(context.getCacheDir(), "journal-ui-fixtures-" + seed + ".json");
    }
    private void remember(boolean dom, String id) throws Exception {
        created.put(id, dom); writeLedger();
    }
    private void writeLedger() throws Exception {
        java.nio.file.Files.write(fixtureLedger().toPath(), new org.json.JSONObject(created).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    private void deleteCreated() throws Exception {
        for (String id : new ArrayList<>(created.keySet())) {
            boolean dom = created.get(id);
            if (dom) ControllerPinManager.enterDomMode(); else ControllerPinManager.enterSubMode();
            runtime.delete(dom, id);
            java.nio.file.Files.deleteIfExists(new File(context.getCacheDir(), "journal-polish-pending-" + id).toPath());
            created.remove(id); writeLedger();
        }
        java.nio.file.Files.deleteIfExists(fixtureLedger().toPath());
        ControllerPinManager.enterSubMode();
    }
    private File voiceFixture() {
        String seed = InstrumentationRegistry.getArguments().getString("media_seed", "profile-journal-polish");
        assertTrue(seed.matches("[a-zA-Z0-9-]{1,80}"));
        return new File(context.getCacheDir(), "journal-audio-polish-" + seed + ".m4a");
    }

    private ActivityScenario<JournalActivity> launch(boolean dom, PersonId target) {
        return ActivityScenario.launch(new Intent(context, JournalActivity.class)
                .putExtra(JournalRoutes.EXTRA_DOM, dom).putExtra(PersonRoutes.EXTRA_PERSON_ID, target.value()));
    }
    private void pick(ActivityScenario<JournalActivity> scenario, String name) {
        Intents.init();
        try {
            intending(hasAction(Intent.ACTION_OPEN_DOCUMENT)).respondWith(new Instrumentation.ActivityResult(Activity.RESULT_OK, new Intent().setData(source(name))));
            tap("journal_add_attachment"); await(scenario, a -> !model(a).busy);
        } finally { Intents.release(); }
    }
    private static Uri source(String name) { return Uri.parse("content://com.subhub.app.test.journal-picker/" + name); }
    private static JournalModel model(JournalActivity activity) { return new ViewModelProvider(activity).get(JournalModel.class); }
    private static String modelId(ActivityScenario<JournalActivity> scenario) {
        String[] id = new String[1]; scenario.onActivity(a -> id[0] = model(a).id); return id[0];
    }
    private static View tagged(Activity a, String tag) { return a.findViewById(android.R.id.content).findViewWithTag(tag); }
    private static org.hamcrest.Matcher<View> tag(String value) { return withTagValue(org.hamcrest.Matchers.is((Object) value)); }
    private static void tap(String value) { onView(tag(value)).perform(revealAboveNavigation(), click()); }
    private static androidx.test.espresso.ViewAction revealAboveNavigation() {
        return new androidx.test.espresso.ViewAction() {
            public org.hamcrest.Matcher<View> getConstraints() { return isDescendantOfA(org.hamcrest.Matchers.instanceOf(android.widget.ScrollView.class)); }
            public String getDescription() { return "reveal complete Journal target inside the native viewport"; }
            public void perform(androidx.test.espresso.UiController ui, View view) {
                view.requestRectangleOnScreen(new Rect(0, 0, view.getWidth(), view.getHeight()), true);
                ui.loopMainThreadUntilIdle(); Rect visible = rect(view);
                assertEquals("Complete target width", view.getWidth(), visible.width());
                assertEquals("Complete target height", view.getHeight(), visible.height());
                View nav = view.getRootView().findViewById(R.id.bottom_navigation);
                if (nav != null && nav.isShown()) {
                    if (visible.bottom > rect(nav).top) {
                        android.view.ViewParent parent = view.getParent();
                        while (!(parent instanceof android.widget.ScrollView)) parent = parent.getParent();
                        android.widget.ScrollView scroll = (android.widget.ScrollView) parent;
                        scroll.scrollBy(0, visible.bottom - rect(nav).top + 8); ui.loopMainThreadUntilIdle(); visible = rect(view);
                    }
                    assertTrue(visible.bottom <= rect(nav).top);
                    assertEquals(view.getHeight(), visible.height());
                }
                android.view.WindowInsets insets = view.getRootView().getRootWindowInsets();
                assertTrue(visible.top >= insets.getSystemWindowInsetTop());
                assertTrue(visible.bottom <= view.getRootView().getHeight() - insets.getSystemWindowInsetBottom());
            }
        };
    }
    private static Rect rect(View view) { Rect result = new Rect(); view.getGlobalVisibleRect(result); return result; }
    private static <A extends Activity> void await(ActivityScenario<A> scenario, Predicate<A> condition) {
        long deadline = SystemClock.uptimeMillis() + 10_000;
        while (SystemClock.uptimeMillis() < deadline) { AtomicBoolean ready = new AtomicBoolean();
            scenario.onActivity(a -> ready.set(condition.test(a))); if (ready.get()) return; SystemClock.sleep(40); }
        fail("Journal UI did not settle");
    }
    @androidx.annotation.RequiresApi(29)
    private <A extends Activity> void capture(ActivityScenario<A> scenario, String name) throws Exception {
        for (int i = 0; i < 3; i++) {
            CountDownLatch frame = new CountDownLatch(1);
            scenario.onActivity(a -> { View root = a.getWindow().getDecorView(); assertTrue(root.isHardwareAccelerated());
                root.getViewTreeObserver().registerFrameCommitCallback(frame::countDown); root.invalidate(); });
            assertTrue(frame.await(5, TimeUnit.SECONDS));
        }
        Bitmap image = InstrumentationRegistry.getInstrumentation().getUiAutomation(android.app.UiAutomation.FLAG_DONT_USE_ACCESSIBILITY).takeScreenshot();
        assertNotNull(image); String run = InstrumentationRegistry.getArguments().getString("evidence_run", "journal-native");
        assertTrue(run.matches("[a-zA-Z0-9-]+")); File dir = new File(context.getFilesDir(), "ui-v3/" + run);
        assertTrue(dir.isDirectory() || dir.mkdirs());
        try (FileOutputStream stream = new FileOutputStream(new File(dir, name + ".png"))) { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, stream)); }
        finally { image.recycle(); }
    }
    private static void textFits(View view) {
        if (view.getVisibility() != View.VISIBLE) return;
        if (view instanceof TextView && !(view instanceof EditText)) { TextView text = (TextView) view;
            if (text.getLayout() != null && text.getWidth() > 0) for (int i = 0; i < text.getLineCount(); i++) {
                assertEquals(text.getText().toString(), 0, text.getLayout().getEllipsisCount(i));
                assertTrue(text.getText().toString(), text.getLayout().getLineMax(i) <= text.getWidth() - text.getCompoundPaddingLeft() - text.getCompoundPaddingRight() + 1);
            }
        }
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) textFits(((ViewGroup) view).getChildAt(i));
    }
}
