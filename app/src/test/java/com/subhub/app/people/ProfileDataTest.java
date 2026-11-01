package com.subhub.app.people;

import static org.junit.Assert.*;
import java.util.Base64;
import org.junit.Test;

public class ProfileDataTest {
    @Test public void optionalDetailsStayEmptyForLegacyProfilesAndAgeIsOnlySelfEntered() {
        ProfileData legacy = new ProfileData("Person", "About", "");
        assertEquals("", legacy.gender); assertEquals(0, legacy.age);
        assertEquals(0, ProfileData.parseAge("")); assertEquals(0, ProfileData.parseAge("  "));
        assertEquals(1, ProfileData.parseAge("1")); assertEquals(130, ProfileData.parseAge("130"));
        assertEquals(32, ProfileData.parseAge(" 32 "));
        for (String invalid : new String[]{"0", "131", "999", "-1", "+25", "25.5", "2000-01-01", "age 25"})
            assertThrows(invalid, IllegalArgumentException.class, () -> ProfileData.parseAge(invalid));
        assertThrows(IllegalArgumentException.class, () -> new ProfileData("A", "", "", "", -1));
        assertThrows(IllegalArgumentException.class, () -> new ProfileData("A", "", "", "", 131));
    }

    @Test public void genderIsOptionalBoundedAndDoesNotSplitUnicodeCharacters() {
        assertEquals("Non-binary", ProfileData.cleanGender("  Non-binary  "));
        assertEquals("Self entered", ProfileData.cleanGender("Self\nentered"));
        assertEquals("x".repeat(39), ProfileData.cleanGender("x".repeat(39) + "😀"));
        ProfileData details = new ProfileData("A", "", "", "Non-binary", 32);
        assertNotEquals(details, new ProfileData("A", "", "", "", 32));
        assertNotEquals(details, new ProfileData("A", "", "", "Non-binary", 0));
    }
    @Test public void textKeepsParagraphsAndWholeUnicodeCharactersWithinItsLimits() {
        ProfileData value = new ProfileData("  A\nName  ", "  First\r\nSecond\tline  ", "");
        assertEquals("A Name", value.name);
        assertEquals("First\nSecond line", value.about);
        assertEquals("a".repeat(599), ProfileData.cleanAbout("a".repeat(599) + "😀tail"));
        assertEquals(600, ProfileData.cleanAbout("a".repeat(600) + "more").length());
    }

    @Test public void avatarMetadataRejectsOversizedMalformedAndNonCanonicalBytes() {
        assertThrows(IllegalArgumentException.class, () -> new ProfileData("A", "", "not base64"));
        assertThrows(IllegalArgumentException.class, () -> new ProfileData("A", "",
                Base64.getEncoder().encodeToString(new byte[ProfileData.MAX_AVATAR_BYTES + 1])));
        assertThrows(IllegalArgumentException.class, () -> new ProfileData("A", "",
                Base64.getEncoder().encodeToString(new byte[]{1, 2, 3, 4})));
        assertEquals(ProfileData.empty("A"), new ProfileData("A", "", ""));
    }

    @Test public void contractDomProjectionSurvivesOwnProfileUpdatesWithoutReplacingSubIdentity() {
        PersonSnapshot original = new PersonSnapshot(PersonId.LOCAL, "Sub", "Device", "Contract", "Inactive",
                PersonSnapshot.Connection.LOCAL, PersonSnapshot.Freshness.CURRENT, 0,
                java.util.Collections.emptySet(), java.util.Collections.emptyList(), java.util.Collections.emptyList(),
                "EUR", 0, 0, 0, 0, false);
        ProfileData dom = new ProfileData("Owning Dom", "Owner bio", "");
        PersonId target = new PersonId("journal_local_dom");
        PersonSnapshot projected = original.withContractDom(dom, target).withProfile(
                new ProfileData("Own Sub", "Own bio", ""), java.util.Collections.emptyMap());
        assertEquals(dom, projected.contractDom); assertEquals("Own Sub", projected.name);
        assertEquals(target, projected.contractDomId); assertNull(original.contractDomId);
        assertEquals("Own bio", projected.profile.about); assertNull(original.contractDom);
        assertEquals(original.capabilities, projected.capabilities);
    }

    @Test public void completeProfileProjectionKeepsItsNameAndConsentSnapshotTogether() {
        PersonSnapshot original = new PersonSnapshot(PersonId.LOCAL, "Earlier name", "Device", "Contract", "Inactive",
                PersonSnapshot.Connection.LOCAL, PersonSnapshot.Freshness.CURRENT, 0,
                java.util.Collections.emptySet(), java.util.Collections.emptyList(), java.util.Collections.emptyList(),
                "EUR", 0, 0, 0, 0, false);
        java.util.Map<String, Boolean> consent = new java.util.LinkedHashMap<>(); consent.put("censor", false);
        PersonSnapshot projected = original.withProfile(new ProfileData("Current name", "Current bio", ""), consent, true);
        consent.put("censor", true);
        assertEquals("Current name", projected.name);
        assertEquals("Current name", projected.profile.name);
        assertEquals("Earlier name", original.name);
        assertFalse(projected.featureConsent.get("censor"));
        assertTrue(projected.consentHardcoreOverride);
        assertEquals(original.capabilities, projected.capabilities);
        assertThrows(UnsupportedOperationException.class, () -> projected.featureConsent.put("limits", true));
    }
}
