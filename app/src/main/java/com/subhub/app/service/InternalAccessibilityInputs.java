package com.subhub.app.service;

import com.subhub.app.censor.android.OwnedLegacyCensorData;
import com.subhub.app.detection.DetectionEngine;
import com.subhub.app.detection.DetectorConfig;
import com.subhub.app.overlay.LegacyRenderDependencies;
import com.subhub.app.runtime.RuntimeConfiguration;
import com.subhub.app.settings.SettingsRepository;
import java.util.Objects;
import java.util.concurrent.ExecutorService;

/** Explicit inputs for the shared DISPLAY algorithm. Contains no physical data fallback. */
final class InternalAccessibilityInputs {
    final InternalRuntimeSession session;
    final RuntimeCaptureHost.InternalRequest request;
    final OwnedLegacyCensorData accounting;
    private RuntimeConfiguration rendering;

    InternalAccessibilityInputs(InternalRuntimeSession session, OwnedLegacyCensorData accounting) {
        this.session = Objects.requireNonNull(session);
        request = Objects.requireNonNull(session.request());
        this.accounting = Objects.requireNonNull(accounting);
        if (request.plan.mode != RuntimeCaptureHost.Mode.ACCESSIBILITY_DISPLAY || request.plan.sdkInt < 30)
            throw new IllegalArgumentException("Not an INTERNAL legacy DISPLAY source");
        accounting.requireBinding(request.runtime, request.data);
        rendering = request.initialConfiguration;
        if (rendering != request.runtime.configuration()
                || rendering.configRevision != request.runtime.appliedConfigurationRevision())
            throw new SecurityException("Unacknowledged DISPLAY inputs");
    }
    void requireHost(ScreenshotAccessibilityService host) {
        if (request.connection.accessibility().service() != host || !request.connection.isConnected())
            throw new SecurityException("Different or disconnected DISPLAY source");
    }
    RuntimeConfiguration configuration() { return rendering; }
    SettingsRepository settings() { return SettingsRepository.forPreferences(rendering.main); }
    /** Module receiver supplies the actual immutable candidate; owner ACK is separate. */
    void adopt(RuntimeConfiguration configuration) {
        if (configuration == null || !request.owner().equals(configuration.owner)
                || configuration.configRevision < rendering.configRevision)
            throw new SecurityException("Different or stale DISPLAY settings");
        accounting.adopt(configuration); rendering = configuration;
    }
    void acknowledged(RuntimeConfiguration configuration) {
        if (configuration != rendering || configuration != request.runtime.configuration()
                || configuration.configRevision != request.runtime.appliedConfigurationRevision())
            throw new SecurityException("DISPLAY revision did not acknowledge");
        accounting.acknowledged(configuration);
    }
    DetectionEngine detector(ScreenshotAccessibilityService host, DetectorConfig configuration,
            boolean latencyPriority) {
        requireHost(host);
        return new DetectionEngine(host, configuration, latencyPriority,
                request.resources.providers.forOwner(request.owner()));
    }
    LegacyRenderDependencies renderer(ExecutorService exclusiveArtworkWorker) {
        return LegacyRenderDependencies.owned(request, exclusiveArtworkWorker);
    }
}
