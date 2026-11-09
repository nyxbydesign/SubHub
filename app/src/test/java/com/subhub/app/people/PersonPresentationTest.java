package com.subhub.app.people;

import static org.junit.Assert.*;
import com.subhub.app.util.RemoteAppIcon;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

public final class PersonPresentationTest {
    @Test public void transportTimestampsAndUsageCountersDoNotInvalidateAContract() {
        assertEquals(PersonPresentation.contract(person(1, "One app", null)),
                PersonPresentation.contract(person(999, "One app", null).withLastOnlineAt(9999)));
    }
    @Test public void visibleDetailsAndChangedIconIdentityDoInvalidateAContract() {
        PersonSnapshot original = person(1, "One app", null);
        assertNotEquals(PersonPresentation.contract(original), PersonPresentation.contract(person(1, "Two apps", null)));
        RemoteAppIcon icon = new RemoteAppIcon("peer", "generation", "org.example.app", null);
        assertNotEquals(PersonPresentation.contract(original), PersonPresentation.contract(person(1, "One app", icon)));
        assertNotEquals(PersonPresentation.contract(original), PersonPresentation.contract(
                original.withProfile(original.profile, Collections.singletonMap("censor", false))));
    }
    private static PersonSnapshot person(long telemetry, String summary, RemoteAppIcon icon) {
        return new PersonSnapshot(new PersonId("fixture"), "Sub", "Device", "Contract", "Session",
                PersonSnapshot.Connection.CONNECTED, PersonSnapshot.Freshness.CURRENT, telemetry,
                Collections.emptySet(), List.of(new PersonSnapshot.Section("apps", "Apps", summary, "Details",
                        List.of(new PersonSnapshot.App("org.example.app", "App", icon)))), Collections.emptyList(),
                "EUR", telemetry, telemetry, telemetry, (int) telemetry, true);
    }
}
