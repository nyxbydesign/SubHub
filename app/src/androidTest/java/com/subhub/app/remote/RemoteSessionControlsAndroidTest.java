package com.subhub.app.remote;

import static org.junit.Assert.*;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.matcher.ViewMatchers.withTagValue;
import static org.hamcrest.Matchers.is;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.filters.SdkSuppress;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.R;
import com.subhub.app.people.*;
import com.subhub.app.security.*;
import com.subhub.app.settings.SettingsRepository;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.*;
import org.junit.*;

public class RemoteSessionControlsAndroidTest {
    @Rule public final KeyholderPresentationAndroidTest.IntegrationFixture integration =
            new KeyholderPresentationAndroidTest.IntegrationFixture();
    @Rule public final com.subhub.app.UiAutomationStateRule automation = new com.subhub.app.UiAutomationStateRule();
    private static final String DOM = repeat('a'), SUB = repeat('b'), WITNESS = repeat('c'), IDLE = repeat('d');
    private Context context;
    private RemoteFeature feature, previous;
    private RemoteStore source;
    private RemoteStore.Peer peer;
    private String database, mode;
    private boolean hadMode;
    private java.lang.reflect.Field singleton;
    private final AtomicBoolean dirty = new AtomicBoolean();
    private final RemoteSessionCommands.Duration service = new RemoteSessionCommands.Duration(3_600_000, 1_800_000, 7_200_000, false);

    @Before public void setup() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences prefs = context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME, 0);
        hadMode = prefs.contains(DeviceRolePreferences.KEY_MODE); mode = prefs.getString(DeviceRolePreferences.KEY_MODE, "BOTH");
        prefs.edit().putString(DeviceRolePreferences.KEY_MODE, DeviceRolePreferences.Mode.BOTH.name()).commit();
        com.subhub.app.configuration.LegacyPackMigration.recoverAndMigrate(context);
        assertTrue(ControllerPinManager.setPin(context, "2468")); assertTrue(ControllerPinManager.verify(context, "2468"));
        ControllerPinManager.enterDomMode();
        previous = RemoteFeature.get(context);
        assertAccepting(previous, "Application endpoint before fixture");
        database = "session-ui-" + UUID.randomUUID();
        feature = new RemoteFeature(context, new RemoteStore(context, database + ".db"));
        source = new RemoteStore(context, database + "-source.db");
        PairingInvite invite = PairingInvite.create(DOM, "fixture", 1000);
        feature.store.saveInvite(invite); feature.store.bindInvite(invite.id, SUB, invite.tokenHash(), 1001);
        feature.store.approveInvite(invite.id, SUB, "Fixture Sub", 1001); feature.store.activate(SUB, invite.id);
        source.acceptInvite(invite, "Dom"); source.activate(DOM, invite.id); peer = feature.store.peer(SUB, invite.id);
        source.publish(DOM, invite.id, RemoteProtocol.Stream.BASELINE, "active", RemoteConfigurationAndroidTest.configuration(), false, 1);
        feature.store.receive(SUB, source.nextBatch(DOM, invite.id, RemoteProtocol.Stream.BASELINE));
        singleton = RemoteFeature.class.getDeclaredField("instance"); singleton.setAccessible(true);
        synchronized (RemoteFeature.class) {
            assertSame("Application endpoint changed during fixture setup", previous, singleton.get(null));
            assertNotSame(previous, feature); singleton.set(null, feature);
        }
        People.installRemoteRepository(new RemotePersonRepository(context, feature));
        People.installConnectionUi(new RemoteUi()); People.installRemoteAuthority(feature::controlsLocal);
    }

    @After public void cleanup() throws Exception {
        if (singleton != null) synchronized (RemoteFeature.class) {
            assertSame("Selected fixture endpoint changed before restoration", feature, singleton.get(null));
            singleton.set(null, previous);
        }
        if (feature != null) feature.shutdownForTest(); if (source != null) source.close();
        if (previous != null) {
            assertSame(previous, RemoteFeature.get(context)); assertAccepting(previous, "Restored application endpoint");
        }
        if (database != null) { context.deleteDatabase(database + ".db"); context.deleteDatabase(database + "-source.db"); }
        if (context != null) {
            SharedPreferences.Editor edit = context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME, 0).edit();
            if (hadMode) edit.putString(DeviceRolePreferences.KEY_MODE, mode); else edit.remove(DeviceRolePreferences.KEY_MODE);
            assertTrue(edit.commit());
        }
        ControllerPinManager.enterSubMode();
    }

    @Test public void unsupportedPeerHasOneDisabledStartAndRecreationQueuesNothing() throws Exception {
        try (ActivityScenario<PersonActivity> page = open()) {
            await(() -> hasText(page, "remote_session_reason", context.getString(R.string.remote_session_unsupported)));
            page.onActivity(a -> {
                assertEquals(1, count(a.findViewById(android.R.id.content), "remote_session_controls"));
                assertFalse(tag(a, "remote_session_start").isEnabled()); assertFalse(tag(a, "remote_session_stop").isEnabled());
                assertNull(a.findViewById(R.id.person_session_action));
            });
            page.recreate();
            await(() -> hasText(page, "remote_session_reason", context.getString(R.string.remote_session_unsupported)));
            assertNull(feature.store.latestCommand(SUB, peer.generation));
            assertTrue(feature.store.pendingSessionControls(SUB, peer.generation, RemoteSessionRuntime.Direction.OUTGOING, 10).isEmpty());
        }
    }

    @Test public void delayedReviewCannotConfirmAfterRecreationOrControllerLock() throws Exception {
        Gateway gateway = new Gateway(); gateway.reviewed = new CompletableFuture<>();
        try (ActivityScenario<PersonActivity> page = open()) {
            inject(page, gateway); tap("remote_session_start"); await(() -> gateway.reviews.get() == 1);
            page.recreate(); gateway.reviewed.complete(review(RemoteSessionCommands.Action.START, WITNESS));
            InstrumentationRegistry.getInstrumentation().waitForIdleSync(); assertEquals(0, gateway.confirms.get());
            gateway.reviewed = new CompletableFuture<>(); inject(page, gateway);
            tap("remote_session_start"); await(() -> gateway.reviews.get() == 2);
            ControllerPinManager.enterSubMode(); gateway.reviewed.complete(review(RemoteSessionCommands.Action.START, WITNESS));
            InstrumentationRegistry.getInstrumentation().waitForIdleSync(); assertEquals(0, gateway.confirms.get());
        }
    }

    @Test public void failedSnapshotInvalidatesAnOutstandingReviewBeforeItCanConfirm() throws Exception {
        Gateway gateway = new Gateway(); gateway.reviewed = new CompletableFuture<>();
        try (ActivityScenario<PersonActivity> page = open()) {
            inject(page, gateway); tap("remote_session_start"); await(() -> gateway.reviews.get() == 1);
            gateway.failSnapshot = true; tap("remote_session_refresh");
            await(() -> hasText(page, "remote_session_status", context.getString(R.string.remote_unknown)));
            gateway.reviewed.complete(review(RemoteSessionCommands.Action.START, WITNESS));
            InstrumentationRegistry.getInstrumentation().waitForIdleSync(); assertEquals(0, gateway.confirms.get());
        }
    }

    @Test public void dirtyDurationBlocksStartAndReceiptRemainsSeparateFromAppliedProof() throws Exception {
        Gateway gateway = new Gateway(); dirty.set(true);
        try (ActivityScenario<PersonActivity> page = open()) {
            inject(page, gateway);
            page.onActivity(a -> assertFalse(tag(a, "remote_session_start").isEnabled()));
            dirty.set(false); tap("remote_session_refresh"); await(() -> enabled(page, "remote_session_start"));
            tap("remote_session_start"); await(() -> gateway.confirms.get() == 1);
            RemoteSessionCommands.Request request = gateway.confirmed.request;
            gateway.receipt(request, RemoteSessionCommands.State.ACKNOWLEDGED, null);
            tap("remote_session_refresh");
            await(() -> hasText(page, "remote_session_status", context.getString(R.string.remote_session_received)));
            assertFalse(hasText(page, "remote_session_status", context.getString(R.string.remote_session_applied)));
            RemoteSessionCommands.Proof proof = new RemoteSessionCommands.Proof(true, repeat('e'), request.issuedAt,
                    request.issuedAt + 3_600_000, 3_600_000);
            gateway.receipt(request, RemoteSessionCommands.State.APPLIED, proof);
            tap("remote_session_refresh");
            await(() -> hasText(page, "remote_session_status", context.getString(R.string.remote_session_applied)));
            page.recreate(); InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            assertEquals(1, gateway.reviews.get()); assertEquals(1, gateway.confirms.get());
        }
    }

    @Test public void changedSourceReviewCannotQueueAndStopDoesNotDependOnDurationDraftOrHardcore() throws Exception {
        Gateway gateway = new Gateway(); gateway.reviewWitness = repeat('f');
        try (ActivityScenario<PersonActivity> page = open()) {
            inject(page, gateway); tap("remote_session_start"); await(() -> gateway.reviews.get() == 1);
            await(() -> hasText(page, "remote_session_status", context.getString(R.string.remote_unknown)));
            assertEquals(0, gateway.confirms.get());
            gateway.reviewWitness = WITNESS; gateway.stopAvailable = true; dirty.set(true);
            tap("remote_session_refresh"); await(() -> enabled(page, "remote_session_stop"));
            assertFalse(SubFeaturePolicy.hardcoreOverride(context));
            tap("remote_session_stop"); await(() -> gateway.confirms.get() == 1);
            assertEquals(RemoteSessionCommands.Action.STOP, gateway.confirmed.request.action);
            assertEquals(repeat('e'), gateway.confirmed.request.sessionIdentity);
            assertNull(gateway.confirmed.request.service);
        }
    }

    @Test public void actualDurationDraftAndPendingApplyFenceTheSharedStartControls() throws Exception {
        Gateway gateway = new Gateway();
        try (ActivityScenario<PersonActivity> page = open()) {
            bindSharedDuration(page, gateway);
            await(() -> enabled(page, "remote_session_start"));
            onView(withTagValue(is("remote_duration_choice"))).perform(com.subhub.app.NativeUiActions.revealAboveNavigation(), click());
            androidx.test.espresso.Espresso.onData(org.hamcrest.Matchers.anything()).atPosition(2).perform(click());
            tap("remote_session_refresh");
            await(() -> hasText(page, "remote_session_reason", context.getString(R.string.remote_session_apply_duration)));
            page.onActivity(a -> assertFalse(tag(a, "remote_session_start").isEnabled()));
            assertEquals(0, gateway.reviews.get()); assertEquals(0, gateway.confirms.get());
            assertNull(feature.store.latestCommand(peer.identity, peer.generation));
            page.recreate(); bindSharedDuration(page, gateway);
            await(() -> hasText(page, "remote_session_reason", context.getString(R.string.remote_session_apply_duration)));
            page.onActivity(a -> assertEquals(2, ((android.widget.Spinner) tag(a, "remote_duration_choice")).getSelectedItemPosition()));
            tap("remote_duration_apply");
            await(() -> feature.store.latestCommand(peer.identity, peer.generation) != null);
            tap("remote_session_refresh");
            page.onActivity(a -> assertFalse(tag(a, "remote_session_start").isEnabled()));
            assertEquals(RemoteStore.CommandState.QUEUED, feature.store.latestCommand(peer.identity, peer.generation).state);
            assertEquals(0, gateway.reviews.get()); assertEquals(0, gateway.confirms.get());
        }
    }

    private void bindSharedDuration(ActivityScenario<PersonActivity> page, Gateway gateway) throws Exception {
        await(() -> hasTag(page, "remote_duration_apply") && hasTag(page, "remote_session_controls"));
        PersonSnapshot person = new RemotePersonRepository(context, feature).load(RemotePersonRepository.id(peer));
        page.onActivity(a -> {
            View actions = tag(a, "remote_session_controls"), oldDuration = tag(a, "remote_session_duration");
            LinearLayout parent = (LinearLayout) actions.getParent();
            assertSame(parent, oldDuration.getParent()); parent.removeView(actions); parent.removeView(oldDuration);
            RemoteDurationControls duration = new RemoteDurationControls(a, parent, person); duration.bind();
            new RemoteSessionControls(a, parent, person, duration::hasUnappliedDraft, gateway).bind();
        });
        await(() -> hasTag(page, "remote_duration_apply"));
        tap("remote_session_refresh");
    }

    @Test @SdkSuppress(minSdkVersion = 29)
    public void nativeStartStopControlsFitAndKeepTheirAppliedDurationVisible() throws Exception {
        Gateway gateway = new Gateway();
        try (ActivityScenario<PersonActivity> page = open()) {
            inject(page, gateway); AtomicReference<PersonActivity> activity = new AtomicReference<>();
            page.onActivity(a -> {
                activity.set(a); android.os.Bundle args = InstrumentationRegistry.getArguments();
                String font = args.getString("shell_expected_font_scale"), width = args.getString("shell_expected_width_dp");
                if (font != null) assertEquals(Float.parseFloat(font), a.getResources().getConfiguration().fontScale, .01f);
                if (width != null) assertEquals(Integer.parseInt(width), a.getResources().getConfiguration().screenWidthDp);
                assertTrue(tag(a, "remote_session_applied_duration").isShown());
                View timer = tag(a, "remote_session_clock");
                assertTrue("Use the established session time widget", timer instanceof com.subhub.app.commitment.ServiceCountdownView);
                assertTrue(timer.isShown());
                assertEquals(tag(a, "remote_session_applied_duration") instanceof TextView
                        ? ((TextView) tag(a, "remote_session_applied_duration")).getText() : "", timer.getContentDescription());
                assertTrue(tag(a, "remote_session_start") instanceof androidx.appcompat.widget.AppCompatButton);
                assertTrue(tag(a, "remote_session_stop") instanceof androidx.appcompat.widget.AppCompatButton);
                assertEquals(1, count(a.findViewById(android.R.id.content), "remote_session_timer_actions"));
            });
            ProfileStatsActivityAndroidTest.captureNativeProfile(activity.get(), "remote-session-actions", "remote_session_timer_actions");
            AtomicReference<TextView> duration = new AtomicReference<>(); AtomicReference<View> scroll = new AtomicReference<>();
            page.onActivity(a -> {
                duration.set((TextView) tag(a, "remote_session_applied_duration"));
                scroll.set(com.subhub.app.UiRefreshWitness.scroll(duration.get()));
                assertTimerGlyphFits((com.subhub.app.commitment.ServiceCountdownView) tag(a, "remote_session_clock"));
                View controls = tag(a, "remote_session_timer_actions"); int[] origin = new int[2]; controls.getLocationOnScreen(origin);
                android.graphics.Rect target = new android.graphics.Rect(origin[0], origin[1], origin[0] + controls.getWidth(), origin[1] + controls.getHeight());
                assertTrue("Complete time and Start/Stop panel must clear its scroll viewport", com.subhub.app.TextFrameWitness.screenClip(scroll.get()).contains(target));
                View navigation = a.findViewById(R.id.bottom_navigation);
                if (navigation != null && navigation.isShown()) {
                    int[] nav = new int[2]; navigation.getLocationOnScreen(nav);
                    assertTrue("Session action row must clear floating navigation", target.bottom <= nav[1]);
                }
            });
            String run = InstrumentationRegistry.getArguments().getString("evidence_run", "refresh");
            String suffix = activity.get().getResources().getConfiguration().fontScale + "-"
                    + activity.get().getResources().getConfiguration().screenWidthDp;
            com.subhub.app.TextFrameWitness.capture(duration.get(), scroll.get(), run, "remote-session-actions-composed-" + suffix);
            java.nio.file.Files.deleteIfExists(new java.io.File(context.getFilesDir(),
                    "ui-v3/" + run + "/remote-session-actions-" + suffix + ".png").toPath());
        }
    }

    private static void assertTimerGlyphFits(com.subhub.app.commitment.ServiceCountdownView timer) {
        assertTrue(timer.getWidth() > 0 && timer.getHeight() > 0);
        android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(timer.getWidth(), timer.getHeight(), android.graphics.Bitmap.Config.ARGB_8888);
        try {
            timer.draw(new android.graphics.Canvas(bitmap));
            java.lang.reflect.Field paintField = timer.getClass().getDeclaredField("text");
            java.lang.reflect.Field areaField = timer.getClass().getDeclaredField("center");
            java.lang.reflect.Field labelField = timer.getClass().getDeclaredField("label");
            paintField.setAccessible(true); areaField.setAccessible(true); labelField.setAccessible(true);
            android.graphics.Paint paint = (android.graphics.Paint) paintField.get(timer);
            android.graphics.RectF area = (android.graphics.RectF) areaField.get(timer);
            assertTrue("Native Canvas timer text must fit its allotted region",
                    paint.measureText((String) labelField.get(timer)) <= area.width() + 1f);
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
        finally { bitmap.recycle(); }
    }

    private final class Gateway implements RemoteSessionControls.Gateway {
        final AtomicInteger reviews = new AtomicInteger(), confirms = new AtomicInteger();
        volatile boolean stopAvailable, failSnapshot;
        volatile String reviewWitness = WITNESS;
        volatile RemoteSessionRuntime.Record record;
        CompletableFuture<RemoteSessionRuntime.Review> reviewed;
        RemoteSessionRuntime.Review confirmed;
        public CompletableFuture<RemoteSessionRuntime.Snapshot> snapshot() {
            if (failSnapshot) {
                CompletableFuture<RemoteSessionRuntime.Snapshot> failed = new CompletableFuture<>();
                failed.completeExceptionally(new IllegalStateException("Fixture snapshot unavailable")); return failed;
            }
            return CompletableFuture.completedFuture(new RemoteSessionRuntime.Snapshot(SUB, peer.generation,
                    true, !stopAvailable, stopAvailable, 1, service, stopAvailable ? "" : IDLE,
                    stopAvailable ? repeat('e') : "", RemoteSessionRuntime.Readiness.APP_MODE_READY,
                    RemoteSessionCommands.Reason.NONE, WITNESS, record));
        }
        public CompletableFuture<RemoteSessionRuntime.Review> review(RemoteSessionCommands.Action action) {
            reviews.incrementAndGet();
            try { return reviewed != null ? reviewed : CompletableFuture.completedFuture(RemoteSessionControlsAndroidTest.this.review(action, reviewWitness)); }
            catch (Exception error) { throw new AssertionError(error); }
        }
        public CompletableFuture<String> confirm(RemoteSessionRuntime.Review value) {
            confirmed = value; confirms.incrementAndGet(); return CompletableFuture.completedFuture(value.request.id);
        }
        void receipt(RemoteSessionCommands.Request request, RemoteSessionCommands.State state, RemoteSessionCommands.Proof proof) throws Exception {
            record = new RemoteSessionRuntime.Record(RemoteSessionRuntime.Direction.OUTGOING, request,
                    new RemoteSessionCommands.Result(request, state, RemoteSessionCommands.Reason.NONE, System.currentTimeMillis(), proof),
                    new RemoteSessionClock.Bounds(request.issuedAt, android.os.SystemClock.elapsedRealtime(), 1,
                            android.os.SystemClock.elapsedRealtime() + 120_000));
        }
    }
    private RemoteSessionRuntime.Review review(RemoteSessionCommands.Action action, String witness) throws Exception {
        long now = System.currentTimeMillis(); String id = UUID.randomUUID().toString();
        RemoteSessionCommands.Request request = action == RemoteSessionCommands.Action.START
                ? RemoteSessionCommands.Request.start(id, DOM, SUB, peer.generation, 1, 1, now, now + 120_000, IDLE, service)
                : RemoteSessionCommands.Request.stop(id, DOM, SUB, peer.generation, 1, 1, now, now + 120_000, repeat('e'));
        return new RemoteSessionRuntime.Review(request, witness, now);
    }
    private ActivityScenario<PersonActivity> open() {
        return ActivityScenario.launch(new Intent(context, PersonActivity.class).putExtra(PersonRoutes.EXTRA_PERSON_ID,
                RemotePersonRepository.id(peer).value()));
    }
    private void inject(ActivityScenario<PersonActivity> page, Gateway gateway) throws Exception {
        await(() -> hasTag(page, "remote_session_controls"));
        PersonSnapshot person = new RemotePersonRepository(context, feature).load(RemotePersonRepository.id(peer));
        page.onActivity(a -> {
            View old = tag(a, "remote_session_controls"); LinearLayout parent = (LinearLayout) old.getParent();
            parent.removeView(old); new RemoteSessionControls(a, parent, person, dirty::get, gateway).bind();
        });
        await(() -> hasText(page, "remote_session_applied_duration", context.getString(R.string.remote_session_applied_duration,
                "60 " + context.getString(R.string.remote_minutes))));
    }
    private static View tag(PersonActivity a, String value) { return a.findViewById(android.R.id.content).findViewWithTag(value); }
    private static boolean hasTag(ActivityScenario<PersonActivity> page, String value) {
        AtomicBoolean found = new AtomicBoolean(); page.onActivity(a -> found.set(tag(a, value) != null)); return found.get();
    }
    private static boolean enabled(ActivityScenario<PersonActivity> page, String value) {
        AtomicBoolean found = new AtomicBoolean(); page.onActivity(a -> found.set(tag(a, value) != null && tag(a, value).isEnabled())); return found.get();
    }
    private static boolean hasText(ActivityScenario<PersonActivity> page, String value, String text) {
        AtomicBoolean found = new AtomicBoolean(); page.onActivity(a -> {
            View view = tag(a, value); found.set(view instanceof TextView && ((TextView) view).getText().toString().equals(text));
        }); return found.get();
    }
    private static void tap(String tag) { onView(withTagValue(is(tag))).perform(com.subhub.app.NativeUiActions.revealAboveNavigation(), click()); }
    private static int count(View view, String tag) {
        int value = tag.equals(view.getTag()) ? 1 : 0;
        if (view instanceof android.view.ViewGroup) for (int i = 0; i < ((android.view.ViewGroup) view).getChildCount(); i++)
            value += count(((android.view.ViewGroup) view).getChildAt(i), tag);
        return value;
    }
    private interface Check { boolean ready() throws Exception; }
    private static void await(Check check) throws Exception {
        long until = android.os.SystemClock.elapsedRealtime() + 15_000;
        while (android.os.SystemClock.elapsedRealtime() < until) { if (check.ready()) return; Thread.sleep(50); }
        assertTrue(check.ready());
    }
    private static void assertAccepting(RemoteFeature value, String label) throws Exception {
        java.lang.reflect.Field executor = RemoteFeature.class.getDeclaredField("sessions"); executor.setAccessible(true);
        java.util.concurrent.ThreadPoolExecutor pool = (java.util.concurrent.ThreadPoolExecutor) executor.get(value);
        assertFalse(label + " must retain its executor", pool.isShutdown());
    }
    private static String repeat(char character) { char[] data = new char[64]; Arrays.fill(data, character); return new String(data); }
}
