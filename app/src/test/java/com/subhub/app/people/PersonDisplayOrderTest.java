package com.subhub.app.people;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import org.junit.Test;
import static org.junit.Assert.*;

public final class PersonDisplayOrderTest {
    @Test public void localStaysFirstAndNamesIgnoreWhitespaceAndCase() {
        PersonSnapshot sam = person("peer:sam", "Sam", false);
        PersonSnapshot alex = person("peer:alex", " alex ", true);
        PersonSnapshot local = person("local", "This device", false);
        List<PersonSnapshot> input = List.of(sam, alex, local);
        assertEquals(List.of(local, alex, sam), PersonDisplayOrder.sorted(input, Locale.ENGLISH));
        assertEquals(List.of(sam, alex, local), input);
        assertEquals(List.of(local, alex, sam), PersonDisplayOrder.sorted(List.of(alex, local, sam), Locale.ENGLISH));
    }

    @Test public void equalNamesUseIdentityAndIgnoreChangingSessionState() {
        PersonSnapshot second = person("peer:b", "Alex", false);
        PersonSnapshot first = person("peer:a", "alex", true);
        assertEquals(List.of(first, second), PersonDisplayOrder.sorted(List.of(second, first), Locale.ENGLISH));
        PersonSnapshot firstIdle = person("peer:a", "alex", false);
        PersonSnapshot secondActive = person("peer:b", "Alex", true);
        assertEquals(List.of(firstIdle, secondActive), PersonDisplayOrder.sorted(List.of(secondActive, firstIdle), Locale.ENGLISH));
    }

    @Test public void namesFollowTheSelectedLocale() {
        PersonSnapshot accented = person("peer:a", "Åsa", false);
        PersonSnapshot zoe = person("peer:z", "Zoe", false);
        assertEquals(List.of(accented, zoe), PersonDisplayOrder.sorted(List.of(zoe, accented), Locale.ENGLISH));
        assertEquals(List.of(zoe, accented), PersonDisplayOrder.sorted(List.of(accented, zoe), Locale.forLanguageTag("sv")));
    }

    private static PersonSnapshot person(String id, String name, boolean active) {
        return new PersonSnapshot(new PersonId(id), name, "Device", "Contract", "Service",
                PersonSnapshot.Connection.OFFLINE, PersonSnapshot.Freshness.STALE, 0,
                EnumSet.noneOf(PersonSnapshot.Capability.class), Collections.emptyList(),
                Collections.emptyList(), "EUR", 0, 0, 0, 0, active);
    }
}
