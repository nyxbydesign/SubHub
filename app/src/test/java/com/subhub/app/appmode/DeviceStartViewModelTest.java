package com.subhub.app.appmode;

import static org.junit.Assert.*;
import com.subhub.app.runtime.AppRuntimeGateway;
import com.subhub.app.runtime.DeviceStartEntryFixture;
import com.subhub.app.runtime.RuntimeLease;
import com.subhub.app.runtime.RuntimeOrigin;
import com.subhub.app.runtime.RuntimeOwnerStore;
import com.subhub.app.runtime.RuntimeStartAttempt;
import com.subhub.app.runtime.RuntimeStartAttempt.Permission;
import com.subhub.app.settings.CaptureMethod;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.Test;

public final class DeviceStartViewModelTest {
    @Test public void noPromptBeforeReservationAndDoubleTapCannotReplaceAttempt() {
        Fixture f = new Fixture(); CompletableFuture<AppRuntimeGateway.DeviceStart> admission = new CompletableFuture<>();
        assertTrue(f.model.begin(() -> admission));
        assertFalse(f.model.begin(() -> { fail("Duplicate admission"); return null; }));
        f.queue.drain(); assertTrue(f.ui.launched.isEmpty());
        admission.complete(f.owner); assertTrue(f.ui.launched.isEmpty());
        f.queue.drain(); assertEquals(List.of(Permission.PROJECTION), f.ui.launched);
        assertEquals(RuntimeOwnerStore.Phase.RESERVED, f.owner.phase());
    }
    @Test public void recreatedHostLaunchesOnlyItsRetainedQueuedPrompt() {
        Fixture f = new Fixture(); f.owner.delayPermission = true;
        f.begin(); f.model.detach(f.ui);
        f.owner.issued.complete(f.owner.lastPrompt); f.queue.drain(); assertTrue(f.ui.launched.isEmpty());
        Ui replacement = new Ui(); f.model.attach(replacement);
        assertEquals(List.of(Permission.PROJECTION), replacement.launched);
        f.model.detach(f.ui); // Old Activity teardown cannot detach the replacement.
        f.returned(Permission.PROJECTION, () -> true, -1, null);
        assertEquals(0, f.owner.results); f.queue.drain(); assertEquals(1, f.owner.starts);
        assertTrue(f.model.busy()); assertTrue(replacement.completed.isEmpty());
        f.owner.completeActive(); f.queue.drain();
        assertEquals(List.of(AppRuntimeGateway.StartState.ACTIVE), replacement.completed);
        assertFalse(f.model.busy());
    }
    @Test public void callbackDoesNotReleasePromptUntilItsRealReturn() {
        Fixture f = new Fixture(); f.ui.duringLaunch = () -> {
            f.returned(Permission.PROJECTION, () -> true, -1, null);
            assertEquals(0, f.owner.results);
            assertEquals(RuntimeOwnerStore.Phase.RESERVED, f.owner.phase());
        };
        f.begin(); assertEquals(1, f.owner.results); assertEquals(1, f.owner.starts);
    }
    @Test public void optionalNotificationDenialPassesActualFalseAndContinues() {
        Fixture f = new Fixture(new DeviceStartEntryFixture(CaptureMethod.APP_MODE, false, false, true));
        f.begin(); assertEquals(List.of(Permission.NOTIFICATIONS), f.ui.launched);
        f.returned(Permission.NOTIFICATIONS, () -> false, 0, null); f.queue.drain();
        assertFalse(f.owner.actualGrant); assertEquals(1, f.owner.starts);
        assertEquals(List.of(Permission.NOTIFICATIONS), f.ui.launched);
    }
    @Test public void requiredDenialCannotStartOrRepeatPrompt() {
        Fixture f = new Fixture(new DeviceStartEntryFixture(CaptureMethod.APP_MODE, true, false, false));
        f.begin(); f.returned(Permission.ACCESSIBILITY, () -> false, 0, null); f.queue.drain();
        assertEquals(0, f.owner.starts); assertFalse(f.model.busy());
        assertEquals(RuntimeOwnerStore.Phase.IDLE, f.owner.phase());
        assertEquals(List.of(Permission.ACCESSIBILITY), f.ui.launched);
    }
    @Test public void launchExceptionKeepsOccupiedUntilExactLateResult() {
        Fixture f = new Fixture(); f.ui.duringLaunch = () -> { throw new IllegalStateException("Accepted then threw"); };
        f.begin(); assertTrue(f.model.busy()); assertTrue(f.owner.occupied());
        assertEquals(List.of(AppRuntimeGateway.StartState.RECOVERY_REQUIRED), f.ui.completed);
        assertEquals(RuntimeOwnerStore.Phase.STOPPING, f.owner.phase()); assertEquals(0, f.owner.results);
        f.returned(Permission.PROJECTION, () -> true, -1, null); f.queue.drain();
        assertEquals(1, f.owner.results); assertEquals(0, f.owner.starts);
        assertEquals(RuntimeOwnerStore.Phase.IDLE, f.owner.phase()); assertFalse(f.model.busy());
    }
    @Test public void clearedViewModelStillDrainsLaunchedCallbackWithoutStarting() {
        Fixture f = new Fixture(); f.begin(); f.model.onCleared();
        assertEquals(RuntimeOwnerStore.Phase.STOPPING, f.owner.phase());
        f.returned(Permission.PROJECTION, () -> true, -1, null); f.queue.drain();
        assertEquals(1, f.owner.results); assertEquals(0, f.owner.starts);
        assertEquals(RuntimeOwnerStore.Phase.IDLE, f.owner.phase());
    }
    @Test public void admittedAfterClearIsCancelledWithoutLostOwnerOrPrompt() {
        Fixture f = new Fixture(); CompletableFuture<AppRuntimeGateway.DeviceStart> admission = new CompletableFuture<>();
        f.model.begin(() -> admission); f.model.onCleared(); admission.complete(f.owner); f.queue.drain();
        assertTrue(f.owner.cancellations > 0); assertEquals(RuntimeOwnerStore.Phase.IDLE, f.owner.phase());
        assertTrue(f.ui.launched.isEmpty());
    }
    @Test public void revokedHostCallbackDrainsWithoutReadingCurrentUiAuthority() {
        Fixture f = new Fixture(); f.begin(); f.ui.available = false; f.model.cancel();
        f.returned(Permission.PROJECTION, () -> true, -1, null); f.queue.drain();
        assertEquals(1, f.owner.results); assertEquals(0, f.owner.starts);
        assertEquals(RuntimeOwnerStore.Phase.IDLE, f.owner.phase());
    }
    @Test public void unrelatedAndDuplicateRoutesCannotConsumeAnotherPrompt() {
        Fixture f = new Fixture(); f.begin();
        Fixture unrelated = new Fixture(new DeviceStartEntryFixture(CaptureMethod.APP_MODE, true, false, false));
        unrelated.begin(); unrelated.returned(Permission.ACCESSIBILITY, () -> false, 0, null); unrelated.queue.drain();
        assertEquals(0, f.owner.results); assertTrue(f.model.busy());
        DeviceStartViewModel.PromptRoute exact = f.model.pendingRoute();
        exact.returned(() -> true, -1, null);
        exact.returned(() -> { fail("Duplicate callback read"); return false; }, -1, null);
        f.queue.drain(); assertEquals(1, f.owner.results); assertEquals(1, f.owner.starts);
    }
    @Test public void oldSettledCallbackCannotConsumeNewSameKindPrompt() throws Exception {
        Fixture f = new Fixture(); f.begin();
        DeviceStartViewModel.PromptRoute first = f.model.pendingRoute();
        first.returned(() -> false, 0, null); f.queue.drain(); assertFalse(f.model.busy());
        java.lang.reflect.Field delivery = DeviceStartViewModel.PromptRoute.class.getDeclaredField("delivery");
        delivery.setAccessible(true); assertNull("Retired route cannot retain owner/model data", delivery.get(first));
        DeviceStartEntryFixture second = new DeviceStartEntryFixture(CaptureMethod.SCREEN_RECORDING, false, false, false);
        assertTrue(f.model.begin(() -> CompletableFuture.completedFuture(second))); f.queue.drain();
        DeviceStartViewModel.PromptRoute next = f.model.pendingRoute();
        assertNotEquals(first.registrationKey, next.registrationKey);
        first.returned(() -> { fail("Old route must not observe another grant"); return false; }, 0, null);
        f.queue.drain(); assertEquals(0, second.results); assertTrue(f.model.busy());
        next.returned(() -> false, 0, null); f.queue.drain();
        assertEquals(1, second.results); assertFalse(f.model.busy());
    }
    @Test public void preparationFailureCancelsKnownNeverLaunchedPrompt() {
        Fixture f = new Fixture(); f.ui.duringPreparation = () -> { throw new IllegalStateException("Registry full"); };
        f.begin(); assertTrue(f.ui.launched.isEmpty()); assertEquals(0, f.owner.results);
        assertEquals(RuntimeOwnerStore.Phase.IDLE, f.owner.phase()); assertFalse(f.model.busy());
    }
    @Test public void cancelledPendingStartDoesNotAnnounceCancellationThenActive() {
        Fixture f = new Fixture(new DeviceStartEntryFixture(CaptureMethod.APP_MODE, false, false, false));
        f.begin(); f.model.cancel(); assertTrue(f.model.busy()); assertTrue(f.ui.completed.isEmpty());
        f.owner.completeActive(); f.queue.drain();
        assertTrue(f.ui.completed.isEmpty()); assertFalse(f.model.busy());
    }
    @Test public void clearedPendingStartCannotPublishLateActiveToReplacementUi() {
        Fixture f = new Fixture(new DeviceStartEntryFixture(CaptureMethod.APP_MODE, false, false, false));
        f.begin(); f.model.onCleared(); f.owner.completeActive(); f.queue.drain();
        Ui later = new Ui(); f.model.attach(later); assertTrue(later.completed.isEmpty());
    }
    @Test public void uncertainActivePublicationRequiresRecoveryInsteadOfSuccess() {
        Fixture f = new Fixture(new DeviceStartEntryFixture(CaptureMethod.APP_MODE, false, false, false));
        f.begin(); f.queue.throwAfterQueue = true; f.owner.completeActive();
        f.queue.throwAfterQueue = false; f.queue.drain();
        assertEquals(List.of(AppRuntimeGateway.StartState.RECOVERY_REQUIRED), f.ui.completed);
        assertTrue(f.model.busy());
    }
    @Test public void failedPermissionFutureCancelsWithoutInventingResult() {
        Fixture f = new Fixture(); f.owner.failPermission = true; f.begin();
        assertTrue(f.ui.launched.isEmpty()); assertEquals(0, f.owner.results); assertEquals(0, f.owner.starts);
        assertEquals(RuntimeOwnerStore.Phase.IDLE, f.owner.phase()); assertFalse(f.model.busy());
    }
    @Test public void cancelPendingPermissionNeverLaunchesWhenIssuedLater() {
        Fixture f = new Fixture(); f.owner.delayPermission = true; f.begin();
        f.model.cancel(); f.owner.issued.complete(f.owner.lastPrompt); f.queue.drain();
        assertTrue(f.ui.launched.isEmpty()); assertEquals(0, f.owner.results);
        assertEquals(RuntimeOwnerStore.Phase.IDLE, f.owner.phase()); assertFalse(f.model.busy());
    }
    @Test public void resultDispatchFailureCancelsButCannotManufactureDrainProof() {
        Fixture f = new Fixture(); f.owner.failResult = true; f.begin();
        f.returned(Permission.PROJECTION, () -> true, -1, null); f.queue.drain();
        assertEquals(0, f.owner.starts); assertTrue(f.owner.occupied());
        assertEquals(RuntimeOwnerStore.Phase.STOPPING, f.owner.phase());
        assertTrue(f.model.busy());
        assertEquals(List.of(AppRuntimeGateway.StartState.RECOVERY_REQUIRED), f.ui.completed);
    }
    @Test public void callbackObservationFailureStillReturnsExactDeniedResult() {
        Fixture f = new Fixture(); f.begin();
        f.returned(Permission.PROJECTION, () -> { throw new SecurityException("Revoked"); }, -1, null);
        f.queue.drain(); assertEquals(1, f.owner.results); assertFalse(f.owner.actualGrant);
        assertEquals(RuntimeOwnerStore.Phase.IDLE, f.owner.phase()); assertEquals(0, f.owner.starts);
    }
    @Test public void failedMainDispatchFencesAttemptWithoutLaunchingOrRetrying() {
        Fixture f = new Fixture(); f.queue.reject = true;
        f.model.begin(() -> CompletableFuture.completedFuture(f.owner));
        assertTrue(f.model.busy()); assertTrue(f.ui.launched.isEmpty()); assertTrue(f.owner.cancellations > 0);
        assertFalse(f.model.begin(() -> { fail("Retry after uncertain dispatch"); return null; }));
    }
    @Test public void enqueueThenThrowDoesNotLaunchLatePrompt() {
        Fixture f = new Fixture(); f.owner.delayPermission = true; f.begin();
        f.queue.throwAfterQueue = true; f.owner.issued.complete(f.owner.lastPrompt);
        f.queue.throwAfterQueue = false; f.queue.drain();
        assertTrue(f.ui.launched.isEmpty()); assertTrue(f.model.busy());
        assertEquals(RuntimeOwnerStore.Phase.IDLE, f.owner.phase());
    }
    @Test public void failedCallbackDispatchDoesNotReleaseItsAdmittedPrompt() {
        Fixture f = new Fixture(); f.begin(); f.queue.reject = true;
        f.returned(Permission.PROJECTION, () -> true, -1, null);
        assertEquals(0, f.owner.results); assertTrue(f.model.busy());
        assertEquals(RuntimeOwnerStore.Phase.STOPPING, f.owner.phase());
        assertTrue(f.owner.occupied());
    }
    @Test public void activePresentationWaitsForApplicationAcknowledgment() {
        Fixture f = new Fixture(new DeviceStartEntryFixture(CaptureMethod.APP_MODE, false, false, false));
        f.begin(); assertEquals(1, f.owner.starts); assertTrue(f.model.busy()); assertTrue(f.ui.completed.isEmpty());
        f.owner.complete(AppRuntimeGateway.StartState.OCCUPIED); f.queue.drain();
        assertEquals(List.of(AppRuntimeGateway.StartState.OCCUPIED), f.ui.completed); assertFalse(f.model.busy());
    }
    @Test public void idleHostCancellationDoesNotInventAStartOutcome() {
        Fixture f = new Fixture(); f.model.cancel(); assertTrue(f.ui.completed.isEmpty());
        assertFalse(f.model.busy());
    }
    @Test public void requiredPermissionsStayInSelectedPlanOrder() {
        Fixture f = new Fixture(new DeviceStartEntryFixture(CaptureMethod.SCREEN_RECORDING, true, true, true));
        f.begin();
        for (Permission kind : List.of(Permission.NOTIFICATIONS, Permission.ACCESSIBILITY, Permission.OVERLAY, Permission.PROJECTION)) {
            assertEquals(kind, f.ui.launched.get(f.ui.launched.size() - 1));
            f.ui.grants.add(kind); f.returned(kind, () -> true, -1, null); f.queue.drain();
        }
        assertEquals(1, f.owner.starts); assertEquals(4, f.owner.results);
    }

    @Test public void demoWaitsForDurationAndDurableStartAcrossHostReplacement() {
        Fixture f = new Fixture();
        f.ui.grants.addAll(List.of(Permission.NOTIFICATIONS, Permission.ACCESSIBILITY));
        AppRuntimeGateway.InternalStartSpec spec = demoSpec();
        InternalOwner owner = new InternalOwner(spec);
        CompletableFuture<AppRuntimeGateway.InternalStartSpec> duration = new CompletableFuture<>();
        assertTrue(f.model.beginInternal(ack -> {
            assertSame(spec, ack);
            return CompletableFuture.completedFuture(owner);
        }, () -> duration));
        f.queue.drain(); assertEquals(0, owner.starts);
        f.model.detach(f.ui);
        duration.complete(spec); f.queue.drain(); assertEquals(0, owner.starts);
        Ui replacement = new Ui(); replacement.grants.addAll(f.ui.grants);
        f.model.attach(replacement); assertEquals(1, owner.starts);
        assertTrue(replacement.completed.isEmpty());
        owner.active.complete(owner.identity); f.queue.drain();
        assertEquals(List.of(AppRuntimeGateway.StartState.ACTIVE), replacement.completed);
    }

    @Test public void cancelledDemoDoesNotReserveWhenDurationAcknowledgesLater() {
        Fixture f = new Fixture();
        CompletableFuture<AppRuntimeGateway.InternalStartSpec> duration = new CompletableFuture<>();
        assertTrue(f.model.beginInternal(ack -> { fail("Cancelled Demo reserved"); return null; }, () -> duration));
        f.model.cancel();
        duration.complete(demoSpec()); f.queue.drain();
        assertTrue(f.ui.launched.isEmpty());
        assertFalse(f.model.busy());
    }

    private static AppRuntimeGateway.InternalStartSpec demoSpec() {
        return new AppRuntimeGateway.InternalStartSpec("dummy_sub_preview", UUID.randomUUID().toString(), 4, 2);
    }

    private static final class InternalOwner implements AppRuntimeGateway.InternalStart {
        final RuntimeLease.Identity identity;
        final CompletableFuture<RuntimeLease.Identity> active = new CompletableFuture<>();
        int starts;
        InternalOwner(AppRuntimeGateway.InternalStartSpec spec) {
            identity = new RuntimeLease.Identity(spec.target, RuntimeOrigin.LOCAL_UI, spec.generation,
                    UUID.randomUUID().toString(), UUID.randomUUID().toString(), UUID.randomUUID().toString(), spec.configurationRevision);
        }
        @Override public RuntimeLease.Identity identity() { return identity; }
        @Override public boolean requiresProjection() { return false; }
        @Override public CompletableFuture<RuntimeStartAttempt.Prompt> permission(Permission kind) {
            throw new AssertionError("Unexpected permission: " + kind);
        }
        @Override public CompletableFuture<Boolean> permissionResult(RuntimeStartAttempt.Prompt prompt, boolean granted,
                int code, android.content.Intent data) { throw new AssertionError("Unexpected permission result"); }
        @Override public CompletableFuture<RuntimeLease.Identity> start() { starts++; return active; }
        @Override public boolean cancel() { return true; }
    }

    private static final class Queue implements DeviceStartViewModel.MainQueue {
        final ArrayDeque<Runnable> pending = new ArrayDeque<>(); boolean reject, throwAfterQueue;
        @Override public boolean post(Runnable action) {
            if (reject) return false;
            pending.add(action);
            if (throwAfterQueue) throw new IllegalStateException("Queued then failed");
            return true;
        }
        void drain() { while (!pending.isEmpty()) pending.remove().run(); }
    }
    private static final class Ui implements DeviceStartViewModel.Ui {
        boolean available = true;
        final EnumSet<Permission> grants = EnumSet.noneOf(Permission.class);
        final List<Permission> launched = new ArrayList<>();
        final List<AppRuntimeGateway.StartState> completed = new ArrayList<>();
        Runnable duringLaunch = () -> {}, duringPreparation = () -> {};
        @Override public boolean available() { return available; }
        @Override public boolean granted(Permission kind) { return grants.contains(kind); }
        @Override public Runnable prepare(DeviceStartViewModel.PromptRoute route) {
            duringPreparation.run();
            return () -> { launched.add(route.permission); duringLaunch.run(); };
        }
        @Override public void changed() { }
        @Override public void completed(AppRuntimeGateway.StartState state) { completed.add(state); }
    }
    private static final class Fixture {
        final Queue queue = new Queue(); final DeviceStartViewModel model = new DeviceStartViewModel(queue);
        final Ui ui = new Ui(); final DeviceStartEntryFixture owner;
        Fixture() { this(new DeviceStartEntryFixture(CaptureMethod.SCREEN_RECORDING, false, false, false)); }
        Fixture(DeviceStartEntryFixture owner) { this.owner = owner; model.attach(ui); }
        void begin() { assertTrue(model.begin(() -> CompletableFuture.completedFuture(owner))); queue.drain(); }
        void returned(Permission kind, java.util.function.BooleanSupplier granted, int code, android.content.Intent data) {
            DeviceStartViewModel.PromptRoute route = model.pendingRoute();
            assertNotNull(route); assertEquals(kind, route.permission); route.returned(granted, code, data);
        }
    }
}
