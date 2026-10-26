package com.subhub.app.people;

import org.junit.Test;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import static org.junit.Assert.*;

public final class PersonDirectoryTest {
    @Test public void localAndRemoteReadsStayBoundToTheirIdentities() throws Exception {
        PersonId peer = new PersonId("peer:alex");
        PersonDirectory directory = new PersonDirectory(repository(PersonId.LOCAL), repository(peer));
        assertEquals(List.of(PersonId.LOCAL, peer), directory.people());
        assertEquals(peer, directory.load(peer).id);
        assertEquals(PersonId.LOCAL, directory.load(PersonId.LOCAL).id);
    }

    @Test public void missingRemoteNeverFallsBackToLocalData() {
        PersonDirectory directory = new PersonDirectory(repository(PersonId.LOCAL), null);
        assertThrows(IllegalArgumentException.class, () -> directory.load(new PersonId("peer:missing")));
        assertThrows(IllegalArgumentException.class, () -> directory.load(null));
    }

    @Test public void remoteCannotImpersonateThisDevice() {
        PersonDirectory directory = new PersonDirectory(repository(PersonId.LOCAL), repository(PersonId.LOCAL));
        assertThrows(IllegalStateException.class, directory::people);
    }

    @Test public void mismatchedResponseIsRejectedInsteadOfDisplayingAnotherPerson() {
        PersonId requested = new PersonId("peer:alex");
        PersonRepository remote = new PersonRepository() {
            public List<PersonId> people() { return List.of(requested); }
            public PersonSnapshot load(PersonId id) { return snapshot(PersonId.LOCAL); }
        };
        PersonDirectory directory = new PersonDirectory(repository(PersonId.LOCAL), remote);
        assertThrows(IllegalStateException.class, () -> directory.load(requested));
    }

    @Test public void transportConnectionDoesNotMakeRuntimeOrConfigurationCurrent() {
        PersonSnapshot.SyncState state = new PersonSnapshot.SyncState(PersonSnapshot.Freshness.CURRENT,
                PersonSnapshot.Freshness.CURRENT, PersonSnapshot.Freshness.STALE, "desired-2", "applied-1");
        assertEquals(PersonSnapshot.Freshness.STALE, state.runtime);
        assertTrue(state.hasPendingConfiguration());
        assertFalse(new PersonSnapshot.SyncState(PersonSnapshot.Freshness.CURRENT,
                PersonSnapshot.Freshness.CURRENT, PersonSnapshot.Freshness.CURRENT, "2", "2")
                .hasPendingConfiguration());
    }

    private static PersonRepository repository(PersonId id) {
        return new PersonRepository() {
            public List<PersonId> people() { return List.of(id); }
            public PersonSnapshot load(PersonId requested) {
                if (!id.equals(requested)) throw new IllegalArgumentException("Unknown person");
                return snapshot(id);
            }
        };
    }

    private static PersonSnapshot snapshot(PersonId id) {
        return new PersonSnapshot(id, "Alex", "Pixel", "Contract", "Inactive",
                PersonSnapshot.Connection.OFFLINE, PersonSnapshot.Freshness.STALE, 123,
                EnumSet.of(PersonSnapshot.Capability.VIEW_CONTRACT), Collections.emptyList(),
                Collections.emptyList(), "EUR", 10, 2, 0, 1, false);
    }
}
