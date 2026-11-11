package com.subhub.app.runtime;

import android.app.Application;
import com.subhub.app.internal.InternalSubRecord;
import com.subhub.app.people.InternalSubData;
import com.subhub.app.security.ControllerPinManager;
import java.util.Objects;

/** Real controller/data observation used by the common factory's INTERNAL reservation path. */
final class InternalRuntimeAuthorization {
    private final Application physical;
    private final RuntimeAdmissionAuthority authority;
    private final Runnable requireOwnerWorker;
    InternalRuntimeAuthorization(Application physical, RuntimeAdmissionAuthority authority, Runnable requireOwnerWorker) {
        this.physical = Objects.requireNonNull(physical); this.authority = Objects.requireNonNull(authority);
        this.requireOwnerWorker = Objects.requireNonNull(requireOwnerWorker);
    }
    /**
     * Call on the factory's owner worker after all prior editor transactions ended, immediately
     * before its normal reservation path. Actual visibility/configuration mutation routes must
     * revoke availability before admission; this observation is not a replacement for that route.
     */
    RuntimeAdmissionAuthority.Witness observe(InternalSubData data, InternalSubRecord expected,
            long expectedControllerRevision) {
        requireOwnerWorker.run(); Objects.requireNonNull(data); Objects.requireNonNull(expected);
        ControllerPinManager.InternalStartWitness controller = ControllerPinManager.captureInternalStartWitness(physical);
        if (controller.revision != expectedControllerRevision)
            throw new SecurityException("Demo Start belongs to an earlier controller workspace");
        RuntimeTarget target = RuntimeTarget.internal(expected.targetId);
        RuntimeAdmissionAuthority.AvailabilityPublication availability = authority.beginAvailability(target);
        if (!com.subhub.app.configuration.LegacyPackMigration.isReady(physical)
                || !data.ownerDataReady() || !expected.visible || !expected.equals(data.acknowledged())
                || !expected.equals(data.configuration.read()))
            throw new SecurityException("Internal profile/configuration changed before start");
        if (!authority.completeAvailability(availability, expected.generation, expected.configurationRevision, true))
            throw new SecurityException("Internal availability observation was superseded");
        RuntimeAdmissionAuthority.AuthorizationPublication publication = authority.beginAuthorization(target, RuntimeOrigin.LOCAL_UI);
        if (!authority.completeAuthorization(publication, controller.revision, true, controller.revocation))
            throw new SecurityException("Controller authority changed before start");
        return authority.capture(target, RuntimeOrigin.LOCAL_UI);
    }
}
