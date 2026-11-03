package com.subhub.app.activity;

import static org.junit.Assert.*;
import com.subhub.app.people.PersonId;
import com.subhub.app.util.RemoteAppIcon;
import java.lang.reflect.Modifier;
import org.junit.Test;

public final class ActivityEntryAppIconTest {
    private static final String PACKAGE = "com.example.remote";
    private static final PersonId PERSON = new PersonId("opaque-person-selection");

    @Test public void appendedIconOverloadPreservesLegacyConstructionAndReadIdentity() throws Exception {
        ActivityEvent event = app(ActivityEvent.Kind.APP_OPENED);
        ActivityEntry legacy = new ActivityEntry("legacy", PERSON, "Synthetic", "Device", event, 2, true, "Remote app");
        assertNull(legacy.appIcon); assertNull(legacy.read().appIcon);
        for (RemoteAppIcon icon : new RemoteAppIcon[]{bare("peer-a", "generation-a"),
                bare("peer-b", "generation-a"), bare("peer-a", "generation-b"), described()}) {
            ActivityEntry original = new ActivityEntry("event", PERSON, "Synthetic", "Device", event, 2, true, "Remote app", icon);
            ActivityEntry read = original.read();
            assertSame(icon, original.appIcon); assertSame(icon, read.appIcon); assertSame(event, read.event);
            assertEquals(original.key, read.key); assertEquals(original.personId, read.personId);
            assertEquals(original.personName, read.personName); assertEquals(original.device, read.device);
            assertEquals(original.receivedAt, read.receivedAt); assertEquals(original.appLabel, read.appLabel);
            assertFalse(read.unread); assertTrue(original.unread); assertSame(read, read.read());
        }
        assertTrue(Modifier.isFinal(ActivityEntry.class.getField("appIcon").getModifiers()));
        assertEquals(RemoteAppIcon.class, ActivityEntry.class.getField("appIcon").getType());
    }

    @Test public void openedAndAppLimitAcceptOnlyTheirExactPackageWithoutParsingPersonId() {
        for (ActivityEvent.Kind kind : new ActivityEvent.Kind[]{ActivityEvent.Kind.APP_OPENED, ActivityEvent.Kind.APP_LIMIT_REACHED}) {
            ActivityEvent event = app(kind); RemoteAppIcon icon = bare("peer", "generation");
            ActivityEntry entry = new ActivityEntry("event", PERSON, "Synthetic", "", event, 2, true, "", icon);
            assertSame(icon, entry.appIcon); assertEquals(PERSON, entry.personId); assertEquals(PACKAGE, entry.appLabel);
            RemoteAppIcon wrong = new RemoteAppIcon("peer", "generation", PACKAGE + ".other", null);
            assertThrows(IllegalArgumentException.class,
                    () -> new ActivityEntry("event", PERSON, "Synthetic", "", event, 2, true, "", wrong));
        }
    }

    @Test public void nonApplicationFactsCannotAcquireRemoteIconAuthorityEvenWithMatchingPackage() {
        ActivityEvent shared = new ActivityEvent("shared", ActivityEvent.Kind.SHARED_LIMIT_REACHED, 1, PACKAGE, 0, "", "", 30);
        ActivityEvent tribute = new ActivityEvent("tribute", ActivityEvent.Kind.TRIBUTE_TRIGGERED, 1, PACKAGE, 100, "EUR", "watched_app_open", 0);
        for (ActivityEvent event : new ActivityEvent[]{shared, tribute}) {
            assertNull(new ActivityEntry("event", PERSON, "Synthetic", "", event, 2, true, "").appIcon);
            assertThrows(IllegalArgumentException.class,
                    () -> new ActivityEntry("event", PERSON, "Synthetic", "", event, 2, true, "", bare("peer", "generation")));
        }
    }

    private static ActivityEvent app(ActivityEvent.Kind kind) {
        return new ActivityEvent("event", kind, 1, PACKAGE, 0, "", "", kind == ActivityEvent.Kind.APP_LIMIT_REACHED ? 30 : 0);
    }
    private static RemoteAppIcon bare(String peer, String generation) { return new RemoteAppIcon(peer, generation, PACKAGE, null); }
    private static RemoteAppIcon described() {
        return new RemoteAppIcon("peer", "generation", PACKAGE,
                new RemoteAppIcon.Descriptor("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef", 100, 8, 8));
    }
}
