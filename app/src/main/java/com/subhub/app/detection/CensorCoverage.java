package com.subhub.app.detection;

/** Visual coverage only; text and infraction selection are unaffected. */
public enum CensorCoverage {
    DETECTED_AREAS("detected_areas"), WHOLE_PERSON("whole_person");

    private final String value;
    CensorCoverage(String value) { this.value = value; }
    public String preferenceValue() { return value; }
    public static CensorCoverage fromPreference(String value) {
        return WHOLE_PERSON.value.equals(value) ? WHOLE_PERSON : DETECTED_AREAS;
    }
}
