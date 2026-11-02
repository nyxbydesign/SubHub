package com.subhub.app.service;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.appmode.AppModeManager;
import com.subhub.app.appmode.RuntimeSessionIdentity;
import com.subhub.app.appmode.ForegroundAppState;
import com.subhub.app.capture.MediaProjectionLeaseRegistry;
import com.subhub.app.detection.BBox;
import com.subhub.app.diagnostics.DiagnosticsRepository;
import com.subhub.app.penance.CensorTapTracker;
import com.subhub.app.penance.PenanceManager;
import com.subhub.app.penance.PenanceInfraction;
import com.subhub.app.penance.PenanceChargeNotifier;
import com.subhub.app.popup.PopupStormManager;
import com.subhub.app.remote.RemoteSessionNative;
import com.subhub.app.security.SubFeaturePolicy;
import com.subhub.app.settings.SettingsRepository;
import com.subhub.app.settings.SharedPreferenceTestRestore;
import com.subhub.app.stats.RemoteSessionStatsFixture;
import com.subhub.app.stats.StatsRepository;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Android-managed service lifecycle with explicitly synthetic capture ownership, never a projection grant. */
public class RemoteSessionCaptureOwnershipAndroidTest {
    private Context context;
    private ScreenCaptureService service;
    private CaptureRunOwnership registry;
    private StatsRepository stats;
    private RemoteSessionNative fixture, previousScope;
    private ConcurrentHashMap<Context, RemoteSessionNative> scopes;
    private final Map<String, Map<String, ?>> original = new LinkedHashMap<>();
    private ExecutorService executor, inference;
    private PopupStormManager.ServiceParticipation popup;
    private String originalForeground;
    @Before public void setup() throws Exception {
        org.junit.Assume.assumeTrue(android.os.Build.MODEL.contains("sdk_gphone"));
        context = InstrumentationRegistry.getInstrumentation().getTargetContext().getApplicationContext();
        originalForeground = ForegroundAppState.snapshot().packageName;
        assertFalse("A pre-existing capture is not owned by this fixture", ScreenCaptureService.isRunning());
        assertNull(live());
        for (String name : new String[]{SettingsRepository.PREFERENCES_NAME, StatsRepository.PREFS_NAME, PenanceManager.PREFS_NAME, SubFeaturePolicy.STORE}) {
            SharedPreferences saved = context.getSharedPreferences(name, 0); original.put(name, saved.getAll());
            assertTrue(saved.edit().clear().commit());
        }
        Field scopesField = RemoteSessionNative.class.getDeclaredField("scopes"); scopesField.setAccessible(true);
        @SuppressWarnings("unchecked") ConcurrentHashMap<Context, RemoteSessionNative> values =
                (ConcurrentHashMap<Context, RemoteSessionNative>) scopesField.get(null);
        scopes = values;
        synchronized (RuntimeSessionIdentity.LOCK) {
            previousScope = scopes.remove(context); stats = RemoteSessionStatsFixture.create(context);
            Method install = RemoteSessionNative.class.getDeclaredMethod("installFixture", Context.class, Supplier.class); install.setAccessible(true);
            fixture = (RemoteSessionNative) install.invoke(null, context, (Supplier<StatsRepository>) () -> stats);
        }
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> context.startService(
                new Intent(context, ScreenCaptureService.class).setAction("com.subhub.app.action.OWNERSHIP_PROBE")));
        await(() -> liveUnchecked() != null);
        service = live(); registry = (CaptureRunOwnership) field(null, "ownership");
        executor = (ExecutorService) field(service, "executor"); inference = (ExecutorService) field(service, "inferenceExecutor");
        assertFalse(executor.isShutdown()); assertFalse(inference.isShutdown());
    }
    @After public void cleanup() throws Exception {
        if (service != null && live() == service) {
            context.stopService(new Intent(context, ScreenCaptureService.class));
        }
        if (service != null) awaitDestroyed();
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        if (registry != null && registry.current() != null) registry.release(registry.current());
        PopupStormManager.get().stopOwnedService(popup);
        CensorTapTracker.shared().clear(); DiagnosticsRepository.stop("MediaProjection");
        ForegroundAppState.update(originalForeground);
        if (fixture != null) fixture.close();
        if (scopes != null && previousScope != null) scopes.put(context, previousScope);
        for (Map.Entry<String, Map<String, ?>> entry : original.entrySet()) {
            SharedPreferences saved = context.getSharedPreferences(entry.getKey(), 0);
            SharedPreferenceTestRestore.restore(saved, entry.getValue()); assertEquals(entry.getValue(), saved.getAll());
        }
        if (stats != null) stats.sessionStamp();
        assertFalse(ScreenCaptureService.isRunning());
        assertNull(MediaProjectionLeaseRegistry.owner());
    }
    private static Object field(Object instance, String name) throws Exception {
        Field field = ScreenCaptureService.class.getDeclaredField(name); field.setAccessible(true); return field.get(instance);
    }
    private static void put(Object instance, String name, Object value) throws Exception {
        Field field = ScreenCaptureService.class.getDeclaredField(name); field.setAccessible(true); field.set(instance, value);
    }
    private static ScreenCaptureService live() throws Exception { return (ScreenCaptureService) field(null, "liveInstance"); }
    private static ScreenCaptureService liveUnchecked() {
        try { return live(); } catch (Exception error) { throw new AssertionError(error); }
    }
    private static void await(BooleanSupplier condition) {
        long deadline = SystemClock.uptimeMillis() + 6000;
        while (!condition.getAsBoolean() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(10);
        assertTrue("Owned service transition did not complete", condition.getAsBoolean());
    }
    private SharedPreferences main() { return context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME, 0); }
    private CaptureRunOwnership.Token admitSyntheticCapture() throws Exception {
        new AppModeManager(context).setArmed(true);
        CaptureRunOwnership.Token owner;
        synchronized (RuntimeSessionIdentity.LOCK) {
            owner = registry.activate((String) field(service, "instanceIdentity"), (Integer) field(service, "lastStartId"),
                    new CaptureRunOwnership.Components(stats.activeSessionIdentity(), main().getString("commitment_session_identity", ""), ""));
            put(service, "captureOwner", owner);
        }
        assertNotNull(ScreenCaptureService.observedStopTarget(context)); return owner;
    }
    private void seedReplacementWitness() {
        DiagnosticsRepository.begin("MediaProjection", 512); DiagnosticsRepository.ready("MediaProjection", "Replacement", "synthetic-model", 512);
        CensorTapTracker map = CensorTapTracker.shared(); long now = SystemClock.uptimeMillis();
        map.publishDisplayed(this, map.scope(), List.of(new BBox(10, 10, 40, 40)), new BBox(0, 0, 100, 100), now, false);
    }
    private void assertReplacementWitness() {
        assertTrue(DiagnosticsRepository.snapshot().isRunning()); assertEquals("Replacement", DiagnosticsRepository.snapshot().getProvider());
        assertTrue(CensorTapTracker.shared().matchesPoint(20, 20, 100, 100, SystemClock.uptimeMillis()));
    }
    private void assertOwnResourcesClosed() throws Exception {
        awaitDestroyed();
        assertTrue(executor.isShutdown()); assertTrue(inference.isShutdown()); assertNull(field(service, "loadGovernor"));
    }
    private void awaitDestroyed() {
        await(() -> { try { return (Boolean) field(service, "destructionComplete"); } catch (Exception error) { throw new AssertionError(error); } });
    }

    @Test public void unownedServiceDestructionPreservesALaterLocalSession() throws Exception {
        new AppModeManager(context).setArmed(true); String later = stats.activeSessionIdentity(); seedReplacementWitness();
        context.stopService(new Intent(context, ScreenCaptureService.class)); await(() -> liveUnchecked() == null);
        assertTrue(new AppModeManager(context).isArmed()); assertEquals(later, stats.activeSessionIdentity());
        assertReplacementWitness(); assertOwnResourcesClosed();
    }
    @Test public void queuedOldStopAndLateDestroyPreserveTheReplacementSession() throws Exception {
        admitSyntheticCapture(); ScreenCaptureService.StopTarget old = ScreenCaptureService.observedStopTarget(context);
        new AppModeManager(context).setArmed(false); new AppModeManager(context).setArmed(true);
        String later = stats.activeSessionIdentity(); seedReplacementWitness();
        assertTrue(ScreenCaptureService.requestStop(old)); awaitDestroyed();
        assertNull(live()); assertTrue(old.isClosed());
        assertEquals(later, stats.activeSessionIdentity()); assertTrue(new AppModeManager(context).isArmed());
        assertReplacementWitness(); assertOwnResourcesClosed();
    }
    @Test public void newPactWithUnchangedStatisticsAllowsOnlyOldResourceRetirement() throws Exception {
        main().edit().putString("commitment_session_identity", UUID.randomUUID().toString()).commit();
        admitSyntheticCapture(); ScreenCaptureService.StopTarget old = ScreenCaptureService.observedStopTarget(context);
        String statistics = stats.activeSessionIdentity(), laterPact = UUID.randomUUID().toString();
        synchronized (RuntimeSessionIdentity.LOCK) { main().edit().putString("commitment_session_identity", laterPact).commit(); RuntimeSessionIdentity.changed(); }
        seedReplacementWitness();
        context.startService(ScreenCaptureService.targetedStopIntent(context, old));
        awaitDestroyed(); assertNull(live()); assertTrue(old.isClosed());
        assertEquals(laterPact, main().getString("commitment_session_identity", "")); assertEquals(statistics, stats.activeSessionIdentity());
        assertTrue(new AppModeManager(context).isArmed()); assertReplacementWitness(); assertOwnResourcesClosed();
    }
    @Test public void matchingQueuedStopClosesOnlyItsSessionAndReleasesItsSyntheticLease() throws Exception {
        admitSyntheticCapture(); ScreenCaptureService.StopTarget target = ScreenCaptureService.observedStopTarget(context);
        assertTrue(MediaProjectionLeaseRegistry.acquire(MediaProjectionLeaseRegistry.Owner.PROTECTION));
        ((AtomicBoolean) field(service, "projectionLeaseHeld")).set(true);
        assertTrue(ScreenCaptureService.requestStop(target)); await(() -> liveUnchecked() == null);
        assertFalse(ScreenCaptureService.isRunning()); assertFalse(new AppModeManager(context).isArmed());
        assertEquals("", stats.activeSessionIdentity()); assertEquals(1, stats.getSessionHistory().size());
        assertFalse(ScreenCaptureService.requestStop(target)); assertNull(live()); assertOwnResourcesClosed();
        assertTrue(target.isClosed());
        assertNull(MediaProjectionLeaseRegistry.owner());
    }
    @Test public void duplicateAndroidStartIdDoesNotInvalidateTheSameActivationStop() throws Exception {
        CaptureRunOwnership.Token owner = admitSyntheticCapture(); ScreenCaptureService.StopTarget target = ScreenCaptureService.observedStopTarget(context);
        int oldStart = (Integer) field(service, "lastStartId");
        context.startService(ScreenCaptureService.startIntent(context, 0, new Intent()));
        await(() -> { try { return (Integer) field(service, "lastStartId") > oldStart; } catch (Exception error) { throw new AssertionError(error); } });
        assertSame(owner, registry.current()); assertEquals(target.identity(), ScreenCaptureService.observedStopTarget(context).identity());
        context.startService(ScreenCaptureService.targetedStopIntent(context, target)); await(() -> liveUnchecked() == null);
        assertFalse(new AppModeManager(context).isArmed()); assertEquals("", stats.activeSessionIdentity());
        assertEquals(1, stats.getSessionHistory().size()); assertOwnResourcesClosed();
    }
    @Test public void teardownAfterItsNativeStopCannotClearNewWitnessesOrPopupOwnership() throws Exception {
        admitSyntheticCapture(); popup = PopupStormManager.get().startOwned(context);
        ScreenCaptureService.StopTarget old = ScreenCaptureService.observedStopTarget(context);
        Object resources = field(service, "resources"); String later;
        synchronized (resources) {
            context.stopService(new Intent(context, ScreenCaptureService.class));
            await(() -> { try { return (Boolean) field(service, "destroyed") && !ScreenCaptureService.isRunning(); } catch (Exception error) { throw new AssertionError(error); } });
            new AppModeManager(context).setArmed(true); later = stats.activeSessionIdentity(); seedReplacementWitness();
            popup = PopupStormManager.get().startOwned(context);
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        assertNull(live()); assertTrue(new AppModeManager(context).isArmed()); assertEquals(later, stats.activeSessionIdentity());
        assertReplacementWitness(); assertOwnResourcesClosed();
        assertTrue(old.isClosed()); assertTrue(new AppModeManager(context).isArmed());
        Field token = popup.getClass().getDeclaredField("token"); token.setAccessible(true);
        Field owner = PopupStormManager.class.getDeclaredField("ownership"); owner.setAccessible(true);
        Object popupOwner = owner.get(PopupStormManager.get());
        Method isService = popupOwner.getClass().getDeclaredMethod("isService", long.class); isService.setAccessible(true);
        assertEquals(Boolean.TRUE, isService.invoke(popupOwner, token.getLong(popup)));
    }
    @Test public void dispatchIsRefusedWhileAuthorityLocksAreHeld() throws Exception {
        admitSyntheticCapture(); ScreenCaptureService.StopTarget target = ScreenCaptureService.observedStopTarget(context);
        synchronized (RuntimeSessionIdentity.LOCK) { assertThrows(IllegalStateException.class, () -> ScreenCaptureService.requestStop(target)); }
        synchronized (SubFeaturePolicy.LOCK) { assertThrows(IllegalStateException.class, () -> ScreenCaptureService.requestStop(target)); }
        assertSame(service, live()); assertTrue(new AppModeManager(context).isArmed());
    }

    private ScreenCaptureService.FrameScope frameScope() {
        synchronized (RuntimeSessionIdentity.LOCK) {
            ScreenCaptureService.FrameScope scope = service.observeFrameScope(); assertNotNull(scope); assertTrue(scope.isCurrent()); return scope;
        }
    }
    private void changePact() {
        synchronized (RuntimeSessionIdentity.LOCK) {
            assertTrue(main().edit().putString("commitment_session_identity", UUID.randomUUID().toString()).commit());
            RuntimeSessionIdentity.changed();
        }
    }
    private Runnable holdMain(CountDownLatch entered, CountDownLatch release) {
        return () -> {
            entered.countDown();
            try { assertTrue("Main fixture release timed out", release.await(8, TimeUnit.SECONDS)); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
        };
    }
    @Test public void queuedFrameRejectsAChangedPactWithoutRenderingOrClearingTheNewTapMap() throws Exception {
        admitSyntheticCapture(); ScreenCaptureService.FrameScope scope = frameScope();
        AtomicInteger renders = new AtomicInteger(), releases = new AtomicInteger();
        service.postFramePublication(scope, renders::incrementAndGet, releases::incrementAndGet);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync(); assertEquals(1, renders.get()); assertEquals(0, releases.get());
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        new android.os.Handler(android.os.Looper.getMainLooper()).post(holdMain(entered, release));
        assertTrue(entered.await(3, TimeUnit.SECONDS));
        try {
            service.postFramePublication(scope, renders::incrementAndGet, releases::incrementAndGet);
            String sameStatistics = stats.activeSessionIdentity(); changePact(); assertEquals(sameStatistics, stats.activeSessionIdentity());
            seedReplacementWitness();
        } finally { release.countDown(); }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        assertEquals(1, renders.get()); assertEquals(1, releases.get()); assertFalse(scope.isCurrent()); assertReplacementWitness();
    }
    private PenanceManager configureLedger() {
        assertFalse("Automatic payments must not be configured in this emulator fixture",
                new com.subhub.app.penance.HardcoreAutoPayManager(context).isConfigured());
        PenanceManager ledger = new PenanceManager(context);
        ledger.configure(true, 100, 5000, 10000, 0);
        return ledger;
    }
    private void assertLedgerWaitRejects(boolean replaceStatistics) throws Exception {
        admitSyntheticCapture(); ScreenCaptureService.FrameScope scope = frameScope(); PenanceManager ledger = configureLedger();
        Field field = PenanceManager.class.getDeclaredField("LOCK"); field.setAccessible(true); Object lock = field.get(null);
        AtomicReference<PenanceManager.SourceDetectionResult> result = new AtomicReference<>(); AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread waiting = new Thread(() -> {
            try { result.set(ledger.recordSourceDetectionResult(1, System.currentTimeMillis(), scope.statistics, scope::isCurrent)); }
            catch (Throwable error) { failure.set(error); }
        });
        try {
            synchronized (lock) {
                waiting.start(); await(() -> waiting.getState() == Thread.State.BLOCKED);
                if (replaceStatistics) {
                    new AppModeManager(context).setArmed(false); new AppModeManager(context).setArmed(true);
                } else {
                    assertTrue(scope.statistics.isCurrent()); changePact(); assertTrue(scope.statistics.isCurrent());
                }
                assertFalse(scope.isCurrent());
            }
        } finally { waiting.join(6000); }
        assertFalse(waiting.isAlive()); assertNull(failure.get()); assertNotNull(result.get());
        assertEquals(PenanceManager.SourceDetectionStatus.REJECTED, result.get().status);
        assertEquals(0, result.get().chargedCents); assertEquals(0, ledger.getDetectionRemainder());
        assertTrue(ledger.snapshot(System.currentTimeMillis()).getEvents().isEmpty());
    }
    @Test public void walletWaitingForItsLockRejectsReplacementStatistics() throws Exception { assertLedgerWaitRejects(true); }
    @Test public void walletWaitingForItsLockRejectsANewPactWithTheSameStatistics() throws Exception { assertLedgerWaitRejects(false); }
    @Test public void eligibleSourceLedgerKeepsDetectionBatchAndDwellCounts() throws Exception {
        admitSyntheticCapture(); ScreenCaptureService.FrameScope scope = frameScope(); PenanceManager ledger = configureLedger();
        context.getSharedPreferences(PenanceManager.PREFS_NAME, 0).edit()
                .putInt("detection_batch", 3).putBoolean("rule_censored_dwell_enabled", true)
                .putInt("rule_censored_dwell_cents", 150).commit();
        PenanceManager.SourceDetectionResult first = ledger.recordSourceDetectionResult(2, System.currentTimeMillis(), scope.statistics, scope::isCurrent);
        assertEquals(PenanceManager.SourceDetectionStatus.ACKNOWLEDGED, first.status); assertEquals(0, first.chargedCents);
        assertEquals(2, ledger.getDetectionRemainder());
        PenanceManager.SourceDetectionResult second = ledger.recordSourceDetectionResult(1, System.currentTimeMillis(), scope.statistics, scope::isCurrent);
        assertEquals(100, second.chargedCents); assertEquals(0, ledger.getDetectionRemainder());
        PenanceManager.SourceDetectionResult dwell = ledger.recordSourceDwellResult(3, System.currentTimeMillis(), scope.statistics, scope::isCurrent);
        assertEquals(PenanceManager.SourceDetectionStatus.ACKNOWLEDGED, dwell.status); assertEquals(450, dwell.chargedCents);
        java.util.List<com.subhub.app.penance.PenanceEvent> events = ledger.snapshot(System.currentTimeMillis()).getEvents();
        assertEquals(2, events.size());
        assertTrue(events.stream().anyMatch(event -> event.getInfraction() == PenanceInfraction.CENSORED_DWELL && event.getStrikeCount() == 3));
    }
    @Test public void delayedChargeNoticeDoesNotEscapeIntoAReplacementPact() throws Exception {
        admitSyntheticCapture(); ScreenCaptureService.FrameScope scope = frameScope(); PenanceManager ledger = configureLedger();
        int charged = ledger.recordSourceDetectionResult(1, System.currentTimeMillis(), scope.statistics, scope::isCurrent).chargedCents;
        assertTrue(charged > 0);
        Field active = PenanceChargeNotifier.class.getDeclaredField("activeToast"); active.setAccessible(true); Object previous = active.get(null);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        new android.os.Handler(android.os.Looper.getMainLooper()).post(holdMain(entered, release)); assertTrue(entered.await(3, TimeUnit.SECONDS));
        AtomicInteger rechecks = new AtomicInteger();
        try {
            PenanceChargeNotifier.show(context, ledger, PenanceInfraction.NEW_DETECTION, charged, System.currentTimeMillis(),
                    () -> { rechecks.incrementAndGet(); return scope.isCurrent(); });
            changePact();
        } finally { release.countDown(); }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync(); assertEquals(1, rechecks.get()); assertSame(previous, active.get(null));
    }
    @Test public void queuedFrameRejectsChangedForegroundWithUnchangedRuntimeComponents() throws Exception {
        admitSyntheticCapture(); ForegroundAppState.update("synthetic.foreground.one"); ScreenCaptureService.FrameScope scope = frameScope();
        RuntimeSessionIdentity.ComponentObservation sameComponents = RuntimeSessionIdentity.componentObservation(context);
        AtomicInteger renders = new AtomicInteger(), releases = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        new android.os.Handler(android.os.Looper.getMainLooper()).post(holdMain(entered, release)); assertTrue(entered.await(3, TimeUnit.SECONDS));
        try {
            service.postFramePublication(scope, renders::incrementAndGet, releases::incrementAndGet);
            ForegroundAppState.update("synthetic.foreground.two");
            assertTrue(sameComponents.isCurrent()); assertTrue(scope.statistics.isCurrent());
        } finally { release.countDown(); }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        assertFalse(scope.isCurrent()); assertEquals(0, renders.get()); assertEquals(1, releases.get());
    }
    @Test public void corruptCurrentComponentAllowsOnlyOldResourceRetirement() throws Exception {
        admitSyntheticCapture(); ScreenCaptureService.StopTarget old = ScreenCaptureService.observedStopTarget(context);
        String statistics = stats.activeSessionIdentity();
        synchronized (RuntimeSessionIdentity.LOCK) { assertTrue(main().edit().putInt("commitment_session_identity", 7).commit()); RuntimeSessionIdentity.changed(); }
        assertNull(ScreenCaptureService.observedStopTarget(context)); seedReplacementWitness();
        assertTrue(ScreenCaptureService.requestStop(old)); awaitDestroyed();
        assertEquals(statistics, stats.activeSessionIdentity()); assertTrue(new AppModeManager(context).isArmed());
        assertReplacementWitness(); assertOwnResourcesClosed(); assertTrue(old.isClosed());
    }
    @Test public void unexpectedCaptureLossRearmsOnlyTheOriginalStillActivePact() throws Exception {
        long now = System.currentTimeMillis(), elapsed = SystemClock.elapsedRealtime(), duration = 3600000;
        int boot = android.provider.Settings.Global.getInt(context.getContentResolver(), android.provider.Settings.Global.BOOT_COUNT, -1);
        assertTrue(main().edit().putString("commitment_session_identity", UUID.randomUUID().toString())
                .putLong("commitment_started_at", now).putLong("commitment_ends_at", now + duration)
                .putLong("commitment_duration", duration).putLong("commitment_ends_elapsed", elapsed + duration).putInt("commitment_boot", boot).commit());
        CaptureRunOwnership.Token original = admitSyntheticCapture(); ScreenCaptureService.StopTarget old = ScreenCaptureService.observedStopTarget(context);
        context.stopService(new Intent(context, ScreenCaptureService.class)); awaitDestroyed();
        assertFalse(ScreenCaptureService.isRunning()); assertTrue(new AppModeManager(context).isArmed());
        assertNotEquals(original.components.statistics, stats.activeSessionIdentity());
        assertEquals(1, stats.getSessionHistory().size()); assertTrue(old.isClosed()); assertOwnResourcesClosed();
    }
    @Test public void sourceEligibilityIsRecheckedAtZeroCentAndEventCommitBoundaries() throws Exception {
        admitSyntheticCapture(); ScreenCaptureService.FrameScope scope = frameScope(); PenanceManager ledger = configureLedger();
        SharedPreferences saved = context.getSharedPreferences(PenanceManager.PREFS_NAME, 0);
        assertTrue(saved.edit().putInt("detection_batch", 3).commit());
        AtomicInteger checks = new AtomicInteger();
        PenanceManager.SourceDetectionResult zero = ledger.recordSourceDetectionResult(1, System.currentTimeMillis(), scope.statistics,
                () -> scope.isCurrent() && checks.incrementAndGet() < 3);
        assertEquals(PenanceManager.SourceDetectionStatus.REJECTED, zero.status); assertEquals(3, checks.get());
        assertEquals(0, ledger.getDetectionRemainder()); assertTrue(ledger.snapshot(System.currentTimeMillis()).getEvents().isEmpty());
        assertTrue(saved.edit().putInt("detection_batch", 1).commit()); checks.set(0);
        PenanceManager.SourceDetectionResult event = ledger.recordSourceDetectionResult(1, System.currentTimeMillis(), scope.statistics,
                () -> scope.isCurrent() && checks.incrementAndGet() < 3);
        assertEquals(PenanceManager.SourceDetectionStatus.REJECTED, event.status); assertEquals(3, checks.get());
        assertEquals(0, ledger.getDetectionRemainder()); assertTrue(ledger.snapshot(System.currentTimeMillis()).getEvents().isEmpty());
    }
    @Test public void localSessionAndPactTransitionsInvalidateRetainedTapProvenance() throws Exception {
        admitSyntheticCapture(); CensorTapTracker map = CensorTapTracker.shared();
        seedReplacementWitness(); long first = map.scope(); String originalStatistics = stats.activeSessionIdentity();
        new AppModeManager(context).setArmed(false); assertTrue(map.scope() > first);
        assertFalse(map.matchesPoint(20, 20, 100, 100, SystemClock.uptimeMillis()));
        new AppModeManager(context).setArmed(true); assertNotEquals(originalStatistics, stats.activeSessionIdentity());
        seedReplacementWitness(); long beforePact = map.scope(); String sameStatistics = stats.activeSessionIdentity();
        try {
            assertTrue(com.subhub.app.commitment.CommitmentManager.start(context, 3600000));
            assertEquals(sameStatistics, stats.activeSessionIdentity()); assertTrue(map.scope() > beforePact);
            map.publishDisplayed(this, beforePact, List.of(new BBox(10, 10, 40, 40)), new BBox(0, 0, 100, 100), SystemClock.uptimeMillis(), false);
            assertFalse(map.matchesPoint(20, 20, 100, 100, SystemClock.uptimeMillis()));
            seedReplacementWitness(); assertReplacementWitness();
        } finally { com.subhub.app.commitment.CommitmentManager.emergencyRelease(context); }
    }
    @Test public void pendingRetirementDrainsARegisteredButNotYetDeliveredAndroidStart() throws Exception {
        CaptureRunOwnership.Token owner = admitSyntheticCapture(); ScreenCaptureService.StopTarget target = ScreenCaptureService.observedStopTarget(context);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync(); int priorStart = (Integer) field(service, "lastStartId");
        AtomicReference<Throwable> failure = new AtomicReference<>(); AtomicBoolean posted = new AtomicBoolean(), registered = new AtomicBoolean();
        Thread stop = new Thread(() -> {
            try { posted.set(ScreenCaptureService.requestStop(target)); } catch (Throwable error) { failure.set(error); }
        });
        Thread start = new Thread(() -> {
            try {
                Intent incoming = "start".equals(InstrumentationRegistry.getArguments().getString("drain_start"))
                        ? ScreenCaptureService.startIntent(context, 0, new Intent())
                        : new Intent(context, ScreenCaptureService.class).setAction("com.subhub.app.action.OWNERSHIP_PROBE");
                registered.set(context.startService(incoming) != null);
            }
            catch (Throwable error) { failure.set(error); }
        });
        synchronized (RuntimeSessionIdentity.LOCK) {
            stop.start(); stop.join(3000); assertFalse(stop.isAlive()); assertTrue(posted.get());
            Thread main = android.os.Looper.getMainLooper().getThread();
            await(() -> main.getState() == Thread.State.BLOCKED && java.util.Arrays.stream(main.getStackTrace())
                    .anyMatch(frame -> frame.getClassName().equals(ScreenCaptureService.class.getName()) && frame.getMethodName().equals("stopOwned")));
            start.start(); start.join(3000); assertFalse(start.isAlive()); assertTrue(registered.get()); assertNull(failure.get());
            assertEquals(priorStart, (int) (Integer) field(service, "lastStartId"));
        }
        boolean expectDrained = !"false".equals(InstrumentationRegistry.getArguments().getString("expect_drained"));
        if (expectDrained) awaitDestroyed();
        else {
            await(() -> { try { return (Integer) field(service, "lastStartId") > priorStart; } catch (Exception error) { throw new AssertionError(error); } });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            assertSame(service, live()); assertTrue(registry.isStopping(owner)); assertFalse(target.isClosed());
        }
        android.os.Bundle observed = new android.os.Bundle();
        observed.putInt("capture_prior_start", priorStart); observed.putInt("capture_delivered_start", (Integer) field(service, "lastStartId"));
        observed.putBoolean("capture_closed", target.isClosed()); observed.putBoolean("capture_still_live", live() == service);
        observed.putBoolean("capture_expected_drained", expectDrained);
        InstrumentationRegistry.getInstrumentation().sendStatus(42, observed);
        assertFalse(new AppModeManager(context).isArmed()); assertEquals("", stats.activeSessionIdentity());
        if (expectDrained) { assertTrue(target.isClosed()); assertNull(field(service, "projection")); assertOwnResourcesClosed(); }
    }
}
