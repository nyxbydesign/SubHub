package com.subhub.app.people;

import android.content.Context;
import com.subhub.app.internal.InternalSubRecord;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.security.DeviceRolePreferences;

/** Admission ordering for data writes, never a session-start or effects capability. */
public final class InternalEditorAuthority {
    private InternalEditorAuthority() { }
    /**
     * Call on the serialized worker with an immutable write image. Admission is the ordering point:
     * an admitted write may finish after lock/revocation, while queued work must acquire a new permit.
     * No controller/runtime lock spans file IO. Full-record CAS separately fences hide/re-add changes.
     * A successful save never reconstructs controller authority or publishes runtime admission.
     */
    public static void admit(Context physical, InternalSubRecord base, String generation, long revision) {
        if (!DummySubPreview.ID.value().equals(base.targetId) || !base.visible || !base.generation.equals(generation))
            throw new SecurityException("Internal editor target changed");
        synchronized (com.subhub.app.appmode.RuntimeSessionIdentity.LOCK) {
            if (!DummySubPreview.current(physical, generation, revision)
                    || !ControllerPinManager.isDomWorkspaceUnlocked() || !DeviceRolePreferences.supportsDom(physical)
                    || revision != ControllerPinManager.domWorkspaceRevision())
                throw new SecurityException("Internal editor authority changed");
        }
    }
}
