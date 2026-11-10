package com.subhub.app.detection;

import static org.junit.Assert.*;
import org.junit.Test;

public final class CensorCoverageTest {
    @Test public void defaultsAndUnknownPreferencesPreserveDetectedAreas() {
        assertEquals(CensorCoverage.DETECTED_AREAS, DetectorConfig.builder().build().getCensorCoverage());
        assertEquals(CensorCoverage.DETECTED_AREAS, CensorCoverage.fromPreference(null));
        assertEquals(CensorCoverage.DETECTED_AREAS, CensorCoverage.fromPreference("future-mode"));
    }
    @Test public void copyRetainsExplicitWholePersonChoice() {
        assertEquals(CensorCoverage.WHOLE_PERSON, DetectorConfig.builder()
                .censorCoverage(CensorCoverage.WHOLE_PERSON).build().toBuilder().build().getCensorCoverage());
        assertEquals(CensorCoverage.WHOLE_PERSON,
                CensorCoverage.fromPreference(CensorCoverage.WHOLE_PERSON.preferenceValue()));
    }
}
