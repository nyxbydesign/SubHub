package com.subhub.app.runtime;

import android.content.Intent;
import com.subhub.app.settings.CaptureMethod;
import java.util.concurrent.CompletableFuture;

/** Real prompt/coordinator ownership with a controllable application-level DEVICE completion. */
public final class DeviceStartEntryFixture implements AppRuntimeGateway.DeviceStart {
    private final RuntimeStartGatewayTest.Fixture core = new RuntimeStartGatewayTest.Fixture();
    private final RuntimeStartAttempt attempt = core.reserve(RuntimeTarget.DEVICE).attempt;
    private final AppRuntimeGateway.DeviceStartSpec specification;
    private final AppRuntimeGateway.DevicePlan plan;
    public final CompletableFuture<AppRuntimeGateway.StartResult> started = new CompletableFuture<>();
    public CompletableFuture<RuntimeStartAttempt.Prompt> issued;
    public boolean delayPermission, failPermission, failResult;
    public int starts, results, cancellations;
    public boolean actualGrant;
    public RuntimeStartAttempt.Prompt lastPrompt;

    public DeviceStartEntryFixture(CaptureMethod capture, boolean accessibility, boolean overlay, boolean notifications) {
        specification = new AppRuntimeGateway.DeviceStartSpec(capture, 0, 0, false);
        plan = new AppRuntimeGateway.DevicePlan(capture, accessibility, overlay, notifications);
    }
    @Override public RuntimeLease.Identity identity() { return attempt.identity(); }
    @Override public AppRuntimeGateway.DeviceStartSpec specification() { return specification; }
    @Override public AppRuntimeGateway.DevicePlan plan() { return plan; }
    @Override public CompletableFuture<RuntimeStartAttempt.Prompt> permission(RuntimeStartAttempt.Permission kind) {
        if (failPermission) return CompletableFuture.failedFuture(new IllegalStateException("Permission unavailable"));
        lastPrompt = core.await(core.gateway.permission(attempt, kind));
        issued = delayPermission ? new CompletableFuture<>() : CompletableFuture.completedFuture(lastPrompt);
        return issued;
    }
    @Override public CompletableFuture<Boolean> permissionResult(RuntimeStartAttempt.Prompt prompt, boolean granted,
            int resultCode, Intent data) {
        results++; actualGrant = granted;
        if (failResult) return CompletableFuture.failedFuture(new IllegalStateException("Result dispatch uncertain"));
        // The adapter passes the actual grant. Only the application factory owns optional policy.
        boolean accepted = core.await(core.gateway.permissionResult(attempt, prompt,
                granted || prompt.permission == RuntimeStartAttempt.Permission.NOTIFICATIONS));
        return CompletableFuture.completedFuture(accepted);
    }
    @Override public CompletableFuture<AppRuntimeGateway.StartResult> start() { starts++; return started; }
    @Override public boolean cancel() {
        cancellations++;
        boolean result = core.gateway.cancel(attempt); core.worker.drain(); return result;
    }
    public void completeActive() { started.complete(new AppRuntimeGateway.StartResult(AppRuntimeGateway.StartState.ACTIVE, identity())); }
    public void complete(AppRuntimeGateway.StartState state) { started.complete(new AppRuntimeGateway.StartResult(state, null)); }
    public RuntimeOwnerStore.Phase phase() { core.worker.drain(); return core.backend.record.phase; }
    public boolean occupied() { return core.coordinator.anyOccupied(); }
}
