package com.subhub.app.people;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public final class HomeOrderingTest {
    @Test public void recentAdditionsUseRecordedInsertionOrderRatherThanNameOrLastSync() {
        PersonSnapshot old = person('a', "A", 1, 999999, PersonSnapshot.Connection.CONNECTED);
        PersonSnapshot newest = person('b', "Z", 9, 1, PersonSnapshot.Connection.OFFLINE);
        PersonSnapshot middle = person('c', "M", 8, 2, PersonSnapshot.Connection.PAIRED);
        PersonSnapshot third = person('d', "B", 7, 3, PersonSnapshot.Connection.CONNECTED);
        PersonSnapshot unknown = person('e', "First alphabetically", 0, Long.MAX_VALUE, PersonSnapshot.Connection.CONNECTED);
        assertEquals(List.of(newest, middle, third), RoleHomePresenter.recentAdditions(List.of(old, third, unknown, middle, newest), null));
    }

    @Test public void favoriteRevokedPendingAndLocalRowsDoNotCrowdRecentAdditions() {
        PersonSnapshot favorite = person('a', "Favorite", 9, 0, PersonSnapshot.Connection.CONNECTED);
        PersonSnapshot revoked = person('b', "Revoked", 20, 0, PersonSnapshot.Connection.REVOKED);
        PersonSnapshot pending = person('c', "Pending", 19, 0, PersonSnapshot.Connection.PENDING);
        PersonSnapshot firstTie = person('d', "Later name", 5, 0, PersonSnapshot.Connection.OFFLINE);
        PersonSnapshot secondTie = person('e', "Earlier name", 5, 0, PersonSnapshot.Connection.CONNECTED);
        PersonSnapshot local = new PersonSnapshot(PersonId.LOCAL, "This device", "Device", "Contract", "Service",
                PersonSnapshot.Connection.LOCAL, PersonSnapshot.Freshness.CURRENT, 0,
                EnumSet.noneOf(PersonSnapshot.Capability.class), Collections.emptyList(), Collections.emptyList(),
                "EUR", 0, 0, 0, 0, false, firstTie.sync, "", 99);
        assertEquals(List.of(firstTie, secondTie), RoleHomePresenter.recentAdditions(
                List.of(secondTie, revoked, local, favorite, pending, firstTie), favorite));
    }

    @Test public void favoriteIdentityIgnoresRelationshipGenerationAndKeepsPeersDistinct() {
        PersonId original = id('a', "original"), next = id('a', "next"), other = id('b', "original");
        assertEquals(FavoriteSubStore.identity(original), FavoriteSubStore.identity(next));
        assertNotEquals(FavoriteSubStore.identity(original), FavoriteSubStore.identity(other));
        assertEquals("local", FavoriteSubStore.identity(PersonId.LOCAL));
    }

    private static PersonId id(char peer, String generation) {
        return new PersonId("remote_" + String.join("", Collections.nCopies(64, Character.toString(peer))) + "_" + generation);
    }

    private static PersonSnapshot person(char peer, String name, long order, long synced, PersonSnapshot.Connection connection) {
        return new PersonSnapshot(id(peer, "generation"), name, "Device", "Contract", "Service", connection,
                PersonSnapshot.Freshness.CURRENT, synced, EnumSet.noneOf(PersonSnapshot.Capability.class),
                Collections.emptyList(), Collections.emptyList(), "EUR", 0, 0, 0, 0, false,
                new PersonSnapshot.SyncState(PersonSnapshot.Freshness.CURRENT, PersonSnapshot.Freshness.CURRENT,
                        PersonSnapshot.Freshness.CURRENT, "", ""), "", order);
    }
}
