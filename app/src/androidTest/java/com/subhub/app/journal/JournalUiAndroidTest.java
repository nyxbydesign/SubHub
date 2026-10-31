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
    private final List<String> created = new ArrayList<>();
    private JournalRuntime runtime;
    private java.util.Map<String, ?> profiles;

    @Before public void prepare() {
        profiles = new java.util.LinkedHashMap<>(context.getSharedPreferences("subhub_profile_names", 0).getAll());
        new SettingsRepository(context).preferences().edit().putString(DeviceRolePreferences.KEY_MODE,
                DeviceRolePreferences.Mode.BOTH.name()).commit();
        assertTrue(ControllerPinManager.setPin(context, "2468"));
        ControllerPinManager.enterSubMode(); OnboardingState.complete(context);
        runtime = JournalRuntime.get(context);
    }
    @After public void cleanup() throws Exception {
        ControllerPinManager.enterSubMode(); for (String id : created) runtime.delete(false, id);
        ControllerPinManager.enterDomMode(); for (String id : created) runtime.delete(true, id);
        for (String name : new String[]{"small1", "small2", "small3", "small4", "small5", "small6", "photo", "oversize", "hiddenoversize"})
            context.getContentResolver().delete(source(name), null, null);
        com.subhub.app.settings.SharedPreferenceTestRestore.restore(context.getSharedPreferences("subhub_profile_names", 0), profiles);
    }

    @Test public void textDraftIsBoundedPrivateSavedAndRecreatedWithoutOpeningKeyboard() throws Exception {
        try (ActivityScenario<JournalActivity> scenario = launch(false, PersonId.LOCAL)) {
            await(scenario, a -> tagged(a, "journal_new") != null);
            tap("journal_new");
            scenario.onActivity(a -> {
                assertFalse(a.getCurrentFocus() instanceof EditText);
                EditText input = (EditText) tagged(a, "journal_text"); input.setText("x".repeat(4001));
                assertEquals(4000, input.length());
            });
            scenario.recreate();
            scenario.onActivity(a -> { assertFalse(a.getCurrentFocus() instanceof EditText);
                assertEquals(4000, ((EditText) tagged(a, "journal_text")).length()); });
            onView(tag("journal_text")).perform(revealAboveNavigation(), click(), replaceText("Private journal fixture"), closeSoftKeyboard());
            String id = modelId(scenario); created.add(id); tap("journal_save");
            await(scenario, a -> tagged(a, "journal_detail") != null);
            assertEquals("Private journal fixture", runtime.entry(false, PersonId.LOCAL, id).text);
            assertTrue(runtime.entries.audience(false, id).isEmpty());
            tap("journal_edit");
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
            pick(scenario, "small6"); String id = modelId(scenario); created.add(id);
            tap("journal_save"); await(scenario, a -> tagged(a, "journal_detail") != null);
            JournalEntry entry = runtime.entry(false, PersonId.LOCAL, id); assertEquals(5, entry.attachments.size());
            for (JournalAttachment file : entry.attachments) assertEquals(64, runtime.mediaFile(false, PersonId.LOCAL, id, file.hash).length());
        }
    }

    @Test public void sharedViewAndProviderRejectWithdrawnEntriesAndWrongRole() throws Exception {
        String draft = UUID.randomUUID().toString(); runtime.retainDraft(false, draft, Collections.emptyList());
        JournalAttachment file = runtime.importFile(false, draft, source("small1"));
        String id = UUID.randomUUID().toString().replace("-", ""); created.add(id);
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
            onView(tag("journal_text")).perform(revealAboveNavigation(), click(), replaceText("Text works without microphone access"), closeSoftKeyboard());
            String id = modelId(scenario); created.add(id); tap("journal_save");
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
            SystemClock.sleep(1600); scenario.recreate();
            await(scenario, a -> tagged(a, "journal_play") != null && !model(a).busy);
            scenario.onActivity(a -> { assertTrue(model(a).audio.length > 0); assertTrue(tagged(a, "journal_save").isEnabled()); });
            capture(scenario, "journal-audio-editor"); tap("journal_play");
            await(scenario, a -> ((TextView) tagged(a, "journal_play")).getText().toString().equals(a.getString(R.string.journal_pause)));
            tap("journal_play");
            String id = modelId(scenario); created.add(id); tap("journal_save");
            await(scenario, a -> tagged(a, "journal_detail") != null && tagged(a, "journal_play") != null);
            JournalEntry entry = runtime.entry(false, PersonId.LOCAL, id); assertNotNull(entry.audio); assertTrue(entry.text.isEmpty());
            capture(scenario, "journal-audio-detail");
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
            tap("journal_play"); String id = modelId(scenario); created.add(id); tap("journal_save");
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
            String id = modelId(scenario); created.add(id); tap("journal_save");
            await(scenario, a -> tagged(a, "journal_detail") != null);
            capture(scenario, "journal-detail-files");
            androidx.test.espresso.Espresso.pressBack(); await(scenario, a -> tagged(a, "journal_entry:" + id) != null);
            capture(scenario, "journal-list-populated");
        }
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
        CountDownLatch frame = new CountDownLatch(1);
        scenario.onActivity(a -> { View root = a.getWindow().getDecorView(); assertTrue(root.isHardwareAccelerated());
            root.getViewTreeObserver().registerFrameCommitCallback(frame::countDown); root.invalidate(); });
        assertTrue(frame.await(5, TimeUnit.SECONDS));
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
