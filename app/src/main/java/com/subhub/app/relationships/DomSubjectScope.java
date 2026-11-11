package com.subhub.app.relationships;

import android.content.Context;
import com.subhub.app.people.People;
import com.subhub.app.people.PersonId;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.security.DeviceRolePreferences;
import com.subhub.app.security.KeyholderProvider;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Read-only outgoing Dom membership, sampled before entering a caller's store transaction. */
public final class DomSubjectScope {
    private final Context context;
    private final RelationshipPresentation.Access access;
    private final KeyholderProvider authority;
    private final DeviceRolePreferences.Mode mode;
    private final long workspaceRevision;
    private final Map<PersonId, RelationshipUi.Edge> edges;

    private DomSubjectScope(Context context, RelationshipPresentation.Access access) {
        this.context = context.getApplicationContext();
        this.access = access;
        authority = People.keyholder();
        mode = DeviceRolePreferences.get(context);
        workspaceRevision = ControllerPinManager.domWorkspaceRevision();
        Map<PersonId, RelationshipUi.Edge> values = new LinkedHashMap<>();
        if (!access.legacy) for (RelationshipUi.Edge edge : access.snapshot.eligibleSubs) {
            if (PersonId.INTERNAL.equals(edge.personId) || edge.personId.isLocal() != (edge.delivery == RelationshipUi.Delivery.LOCAL)
                    || values.put(edge.personId, edge) != null)
                throw new IllegalStateException("Ambiguous Dom subject");
        }
        edges = Collections.unmodifiableMap(values);
        requireCurrent();
    }

    /** Call on a worker, without holding any store, SQL transaction, or authority lock. */
    public static DomSubjectScope read(Context context) throws Exception {
        outsideAuthorityLocks();
        if (!DeviceRolePreferences.supportsDom(context) || !ControllerPinManager.isDomWorkspaceUnlocked())
            throw new SecurityException("Dom access required");
        DeviceRolePreferences.Mode mode = DeviceRolePreferences.get(context);
        long revision = ControllerPinManager.domWorkspaceRevision();
        KeyholderProvider authority = People.keyholder();
        DomSubjectScope result = from(context, RelationshipPanel.readAccess(context));
        if (mode != result.mode || revision != result.workspaceRevision || authority != result.authority)
            throw new IllegalStateException("Dom subject context changed during observation");
        return result;
    }

    public static DomSubjectScope from(Context context, RelationshipPresentation.Access access) {
        return new DomSubjectScope(context, access);
    }

    /** Compatibility retains remote identities, but local existence never proves a local link. */
    public boolean includes(PersonId person) {
        return !PersonId.INTERNAL.equals(person)
                && (access.legacy ? !person.isLocal() : edges.containsKey(person));
    }

    public RelationshipUi.Edge localEdge() { return edges.get(PersonId.LOCAL); }
    public boolean legacy() { return access.legacy; }
    public java.util.Set<PersonId> personIds() { return edges.keySet(); }

    /** Cheap context fence; this never calls the coordinator or waits on a future. */
    public void requireCurrent() {
        if (!access.current(RelationshipUi::provider) || authority != People.keyholder()
                || mode != DeviceRolePreferences.get(context)
                || workspaceRevision != ControllerPinManager.domWorkspaceRevision()
                || !DeviceRolePreferences.supportsDom(context)
                || !ControllerPinManager.isDomWorkspaceUnlocked())
            throw new IllegalStateException("Dom subject context changed");
    }

    /** Recheck membership after leaving all caller locks, including same-provider revocation/relink. */
    public void revalidate() throws Exception {
        outsideAuthorityLocks();
        requireCurrent();
        DomSubjectScope current = read(context);
        requireCurrent();
        if (access.legacy != current.access.legacy || !edges.keySet().equals(current.edges.keySet()))
            throw new IllegalStateException("Dom subjects changed");
        for (Map.Entry<PersonId, RelationshipUi.Edge> value : edges.entrySet()) {
            RelationshipUi.Edge before = value.getValue(), after = current.edges.get(value.getKey());
            if (!before.relationshipId.equals(after.relationshipId) || !before.generation.equals(after.generation)
                    || !before.domParticipantRef.equals(after.domParticipantRef)
                    || !before.subParticipantRef.equals(after.subParticipantRef))
                throw new IllegalStateException("Dom subject generation changed");
        }
    }

    private static void outsideAuthorityLocks() {
        if (Thread.holdsLock(com.subhub.app.security.SubFeaturePolicy.LOCK)
                || Thread.holdsLock(com.subhub.app.appmode.RuntimeSessionIdentity.LOCK))
            throw new IllegalStateException("Dom subjects require no outer authority lock");
    }
}
