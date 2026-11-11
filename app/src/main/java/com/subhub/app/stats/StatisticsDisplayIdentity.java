package com.subhub.app.stats;

import android.content.Context;
import com.subhub.app.people.People;
import com.subhub.app.relationships.RelationshipUi;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.security.DeviceRolePreferences;
import com.subhub.app.security.KeyholderProvider;

/** Cheap presentation fence; retaining a Dom aggregate also requires membership revalidation. */
final class StatisticsDisplayIdentity {
    private final Context context;
    private final AchievementRole role;
    private final DeviceRolePreferences.Mode mode;
    private final KeyholderProvider authority;
    private final RelationshipUi.Provider provider;
    private final long workspaceRevision;

    StatisticsDisplayIdentity(Context context) {
        this.context = context.getApplicationContext();
        role = AchievementRole.current(context);
        mode = DeviceRolePreferences.get(context);
        authority = People.keyholder();
        provider = RelationshipUi.provider();
        workspaceRevision = ControllerPinManager.domWorkspaceRevision();
    }

    boolean current() {
        return role.accessible(context) && mode == DeviceRolePreferences.get(context)
                && authority == People.keyholder() && provider == RelationshipUi.provider()
                && workspaceRevision == ControllerPinManager.domWorkspaceRevision();
    }
}
