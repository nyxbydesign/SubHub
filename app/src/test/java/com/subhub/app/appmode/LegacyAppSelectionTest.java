package com.subhub.app.appmode;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.Set;

public class LegacyAppSelectionTest {
    @Test
    public void selectedAssignmentsMergeWithoutIncludingOtherInstalledApps() {
        assertEquals(
                Set.of("censor.app", "limits.app", "whispers.app"),
                LegacyAppSelection.merge(
                        false,
                        Set.of("censor.app"),
                        Set.of("limits.app"),
                        Set.of("whispers.app"),
                        Set.of("other.app")));
    }

    @Test
    public void explicitAllAppsKeepsInstalledAndPreviouslyChosenPackages() {
        assertTrue(LegacyAppSelection.wasAllApps("always", true, Set.of("censor.app"), true));
        assertEquals(
                Set.of("installed.app", "retained.app"),
                LegacyAppSelection.merge(
                        true, Set.of("retained.app"), Set.of(), Set.of(), Set.of("installed.app")));
    }

    @Test
    public void oldSpecificChoiceWinsOverUnmarkedAlwaysAndFreshInstallStartsEmpty() {
        assertFalse(LegacyAppSelection.wasAllApps("always", false, Set.of("censor.app"), true));
        assertFalse(LegacyAppSelection.wasAllApps("selected", true, Set.of(), true));
        assertFalse(LegacyAppSelection.wasAllApps("always", false, Set.of(), false));
        assertTrue(LegacyAppSelection.wasAllApps("always", false, Set.of(), true));
        assertTrue(
                LegacyAppSelection.merge(
                                false, Set.of(), Set.of(), Set.of(), Set.of("installed.app"))
                        .isEmpty());
    }

    @Test
    public void migrationSanitizesDuplicatesAndMalformedNames() {
        assertEquals(
                Set.of("chosen.app"),
                LegacyAppSelection.merge(
                        false, Set.of(" chosen.app ", "bad"), Set.of("chosen.app"), null, null));
    }
}
