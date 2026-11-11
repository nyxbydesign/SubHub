package com.subhub.app.runtime;

import android.app.Application;
import android.content.SharedPreferences;
import com.subhub.app.appmode.AppModeManager;
import com.subhub.app.appmode.RuntimeSessionIdentity;
import com.subhub.app.appmode.SessionExpiryAlarms;
import com.subhub.app.settings.SettingsRepository;
import com.subhub.app.stats.StatsRepository;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Backend-captured DEVICE data source, not executable authority. This never lazily adopts a new
 * session stamp or a caller-supplied repository. Every use requires the matching component's fresh
 * NORMAL admission; queued work must retain this object rather than resolve a current owner.
 */
public final class AndroidDeviceStatisticsSource {
    private final Application physical;
    private final RuntimeLease.Identity owner;
    private final StatsRepository.SessionStamp stamp;
    private final String statisticsIdentity;
    private final StatsRepository repository;

    /** Factory/lifecycle only, after acknowledged physical startup and before service attachment. */
    AndroidDeviceStatisticsSource(DeviceRuntimeEffects effects, Application physical, RuntimeLease.Identity owner,
            StatsRepository.SessionStamp stamp, String statisticsIdentity, StatsRepository acknowledgedRepository) {
        this.physical = Objects.requireNonNull(physical);
        this.owner = Objects.requireNonNull(owner);
        this.stamp = Objects.requireNonNull(stamp);
        this.statisticsIdentity = Objects.requireNonNull(statisticsIdentity);
        if (physical.getApplicationContext() != physical || owner.target.kind != RuntimeTarget.Kind.DEVICE
                || !stamp.isDeviceSession() || !SessionExpiryAlarms.validIdentity(statisticsIdentity))
            throw new SecurityException("Invalid DEVICE statistics source");
        // Real StatsRepository construction queues a daily snapshot. Never reconstruct it here.
        // The explicit frame check also prevents independent out-of-admission source construction.
        try (DeviceContinuationScope.Borrow permit = Objects.requireNonNull(effects).borrowManagedMutation(
                owner, DeviceContinuationScope.Mutation.START_OR_REARM, true)) {
            Objects.requireNonNull(permit);
            repository = Objects.requireNonNull(acknowledgedRepository);
            if (repository.observedSessionStamp() != stamp || !stamp.isCurrent())
                throw new SecurityException("Statistics startup source changed before capture");
        }
    }

    RuntimeLease.Identity owner() { return owner; }
    /** Comparison only. Matching durable evidence never grants executable repository admission. */
    public void requireOwner(RuntimeLease.Identity expected) {
        if (!owner.equals(Objects.requireNonNull(expected)))
            throw new SecurityException("Different DEVICE statistics owner");
    }

    /** Immutable ABA evidence only, never permission to read or write the physical repository. */
    public StatsRepository.SessionStamp stamp() { return stamp; }

    /**
     * False means busy/revoked and the caller must retain required work. The callback is synchronous:
     * it must not retain the repository for a later callback or dispatch an unowned write.
     */
    public boolean use(DeviceRuntimeEffects.DeviceComponent component, Consumer<StatsRepository> action) {
        Objects.requireNonNull(action);
        if (component == null || !owner.equals(component.owner()))
            throw new SecurityException("Statistics source belongs to another DEVICE runtime");
        return component.normal(() -> useAdmitted(action));
    }

    /** A retired capture generation cannot borrow the still-ACTIVE parent's data authority. */
    public boolean use(DeviceCaptureSupervisor.Child child, Consumer<StatsRepository> action) {
        Objects.requireNonNull(action);
        if (child == null || !owner.equals(child.owner())) throw new SecurityException("Different capture runtime");
        return child.normal(() -> useAdmitted(action));
    }

    private void useAdmitted(Consumer<StatsRepository> action) {
            synchronized (RuntimeSessionIdentity.LOCK) {
                SharedPreferences main = physical.getSharedPreferences(SettingsRepository.PREFERENCES_NAME, 0);
                SharedPreferences statistics = physical.getSharedPreferences(StatsRepository.PREFS_NAME, 0);
                if (!stamp.isCurrent() || repository.observedSessionStamp() != stamp
                        || !owner.session.equals(main.getString(AppModeManager.KEY_MANAGED_DEVICE_SESSION, ""))
                        || !statisticsIdentity.equals(statistics.getString("active_session_identity", "")))
                    throw new SecurityException("DEVICE statistics source is no longer current");
                action.accept(repository);
            }
    }
}
