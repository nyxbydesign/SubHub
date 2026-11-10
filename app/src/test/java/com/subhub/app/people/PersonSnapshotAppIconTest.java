package com.subhub.app.people;

import static org.junit.Assert.*;
import com.subhub.app.util.RemoteAppIcon;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

public final class PersonSnapshotAppIconTest {
    private static final String PACKAGE = "com.example.remote";

    @Test public void appendedAppAndAllowanceOverloadsRetainLegacyLocalAndUnknownUsage() {
        assertNull(new PersonSnapshot.App(PACKAGE, "App").appIcon);
        assertNull(new PersonSnapshot.App("", "Legacy label").appIcon);
        PersonSnapshot.Allowance old = new PersonSnapshot.Allowance(PACKAGE, "App", -1, 37);
        assertNull(old.appIcon); assertEquals(-1, old.usedMillis); assertEquals(37, old.dailyMinutes);
        PersonSnapshot.Allowance shared = new PersonSnapshot.Allowance("", "Shared", 120000, 60);
        assertEquals("", shared.packageName); assertNull(shared.appIcon);
    }

    @Test public void allowanceOrderingUsesAppliedBudgetWithoutMutatingSourceOrder() {
        PersonSnapshot.Allowance low = new PersonSnapshot.Allowance("low", "Zulu", 58*60_000L, 60);
        PersonSnapshot.Allowance higher = new PersonSnapshot.Allowance("higher", "Alpha", 9*60_000L, 10);
        PersonSnapshot.Allowance bonus = new PersonSnapshot.Allowance("bonus", "Bonus", 5*60_000L, 5, null, 20*60_000L);
        PersonSnapshot.Allowance unknown = new PersonSnapshot.Allowance("unknown", "Unknown", -1, 30);
        java.util.List<PersonSnapshot.Allowance> original = Arrays.asList(unknown, bonus, higher, low);
        assertEquals(Arrays.asList(low, higher, bonus, unknown), PersonSnapshot.Allowance.ordered(original));
        assertSame(unknown, original.get(0)); assertEquals(5, bonus.dailyMinutes); assertEquals(20L, bonus.displayMinutes());
    }

    @Test public void exactRemoteIdentityIsRetainedAcrossSectionsProfilesAndContractCopies() {
        RemoteAppIcon icon = new RemoteAppIcon("peer", "generation", PACKAGE, null);
        PersonSnapshot.App app = new PersonSnapshot.App(PACKAGE, "App", icon);
        PersonSnapshot.Allowance allowance = new PersonSnapshot.Allowance(PACKAGE, "App", -1, 37, icon);
        PersonSnapshot.Section section = new PersonSnapshot.Section("apps", "Apps", "", "", Collections.singletonList(app));
        PersonSnapshot original = new PersonSnapshot(new PersonId("opaque-selection"), "Synthetic", "Device", "Contract", "Offline",
                PersonSnapshot.Connection.OFFLINE, PersonSnapshot.Freshness.STALE, 1, Collections.emptySet(),
                Collections.singletonList(section), Collections.singletonList(allowance), "EUR", 0, 0, 0, 0, false);
        PersonSnapshot copy = original.withProfile(ProfileData.empty("Synthetic"), Collections.emptyMap())
                .withContractDom(ProfileData.empty("Synthetic Dom"), new PersonId("opaque-dom"));
        assertSame(icon, copy.sections.get(0).apps.get(0).appIcon); assertSame(icon, copy.allowances.get(0).appIcon);
        assertEquals(PACKAGE, copy.allowances.get(0).packageName); assertEquals(-1, copy.allowances.get(0).usedMillis);
        assertEquals(37, copy.allowances.get(0).dailyMinutes); assertEquals(original.id, copy.id);
    }

    @Test public void mismatchNullAndSharedPackageCannotAcceptNonNullRemoteIcon() {
        RemoteAppIcon icon = new RemoteAppIcon("peer", "generation", PACKAGE, null);
        for (String name : Arrays.asList("", PACKAGE + ".other", "COM.EXAMPLE.REMOTE")) {
            assertThrows(IllegalArgumentException.class, () -> new PersonSnapshot.App(name, "App", icon));
            assertThrows(IllegalArgumentException.class, () -> new PersonSnapshot.Allowance(name, "App", 0, 30, icon));
        }
        assertThrows(NullPointerException.class, () -> new PersonSnapshot.App(null, "App", icon));
        assertThrows(IllegalArgumentException.class, () -> new PersonSnapshot.Allowance(null, "App", 0, 30, icon));
        assertNull(new PersonSnapshot.Allowance(null, null, -1, 30).appIcon); // Existing constructor compatibility.
    }

    @Test public void iconFieldsAreFinalAndBareToDescribedIdentityIsAnObservableChange() throws Exception {
        for (Class<?> type : Arrays.asList(PersonSnapshot.App.class, PersonSnapshot.Allowance.class)) {
            assertTrue(Modifier.isFinal(type.getField("appIcon").getModifiers()));
            assertEquals(RemoteAppIcon.class, type.getField("appIcon").getType());
        }
        RemoteAppIcon bare = new RemoteAppIcon("peer", "generation", PACKAGE, null);
        RemoteAppIcon described = new RemoteAppIcon("peer", "generation", PACKAGE,
                new RemoteAppIcon.Descriptor("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef", 100, 8, 8));
        assertNotEquals(bare, described); assertEquals(bare.packageName, described.packageName);
        assertSame(described, new PersonSnapshot.App(PACKAGE, "App", described).appIcon);
        assertSame(described, new PersonSnapshot.Allowance(PACKAGE, "App", 0, 30, described).appIcon);
    }
}
