package com.subhub.app.runtime;

import com.subhub.app.settings.CaptureMethod;
import java.util.Objects;

/**
 * Immutable DEVICE start-plan observation, not a whole-settings snapshot or durable config ACK.
 * Its revision is a factory process-local observation sequence. Ordinary DEVICE modules continue
 * to observe their existing live physical settings under exact runtime effect admission.
 * No Context, preferences, credentials, remote principal or financial authority is carried here.
 */
final class DeviceConfiguration {
    final long observationRevision;
    final AppRuntimeGateway.DeviceStartSpec specification;
    final AppRuntimeGateway.DevicePlan plan;
    final boolean censorEnabled, limitsEnabled, whispersEnabled, popupsEnabled, walletEnabled;
    final int sdkInt;

    DeviceConfiguration(long observationRevision, AppRuntimeGateway.DeviceStartSpec specification,
            int sdkInt, boolean censorEnabled, boolean limitsEnabled, boolean whispersEnabled,
            boolean popupsEnabled, boolean walletEnabled) {
        if (observationRevision <= 0 || sdkInt < 26)
            throw new IllegalArgumentException("Invalid DEVICE plan observation");
        this.specification = Objects.requireNonNull(specification);
        if (!censorEnabled && specification.capture != CaptureMethod.APP_MODE)
            throw new IllegalArgumentException("Capture selection changed before DEVICE admission");
        if (!censorEnabled && !limitsEnabled && !whispersEnabled && !popupsEnabled && !walletEnabled)
            throw new IllegalArgumentException("No enabled DEVICE runtime feature");
        this.observationRevision = observationRevision; this.sdkInt = sdkInt;
        this.censorEnabled = censorEnabled; this.limitsEnabled = limitsEnabled;
        this.whispersEnabled = whispersEnabled; this.popupsEnabled = popupsEnabled;
        this.walletEnabled = walletEnabled;
        boolean projection = specification.capture == CaptureMethod.SCREEN_RECORDING;
        plan = new AppRuntimeGateway.DevicePlan(specification.capture,
                !projection || limitsEnabled || whispersEnabled || popupsEnabled,
                projection, projection && sdkInt >= 33);
    }

    /** New observation epochs compare values, never confer freshness/authorization by themselves. */
    boolean sameStartPlan(DeviceConfiguration newer) {
        return newer != null && sdkInt == newer.sdkInt
                && specification.capture == newer.specification.capture
                && specification.minimum == newer.specification.minimum
                && specification.maximum == newer.specification.maximum
                && specification.hidden == newer.specification.hidden
                && censorEnabled == newer.censorEnabled && limitsEnabled == newer.limitsEnabled
                && whispersEnabled == newer.whispersEnabled && popupsEnabled == newer.popupsEnabled
                && walletEnabled == newer.walletEnabled;
    }
}
