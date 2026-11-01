package com.subhub.app.remote;

import static org.junit.Assert.*;
import com.subhub.app.people.ProfileData;
import org.json.JSONObject;
import org.junit.Test;

public final class RemoteProfileDetailsTest {
    @Test public void legacyWireOmitsOptionalDetailsAndExtendedWireRoundTripsExplicitValues() throws Exception {
        ProfileData entered = new ProfileData("Alex", "About", "", "Non-binary", 32);
        JSONObject legacy = RemoteProfiles.encode(entered, RemoteProtocol.Role.DOM);
        assertEquals(4, legacy.length()); assertFalse(legacy.has("age")); assertFalse(legacy.has("gender"));
        ProfileData parsed = RemoteProfiles.parse(legacy, RemoteProtocol.Role.DOM);
        assertEquals(0, parsed.age); assertEquals("", parsed.gender); assertEquals(entered.about, parsed.about);
        JSONObject details = RemoteProfiles.encode(entered, RemoteProtocol.Role.DOM, true);
        assertEquals(6, details.length()); assertEquals(entered, RemoteProfiles.parse(details, RemoteProtocol.Role.DOM));
        ProfileData cleared = RemoteProfiles.parse(RemoteProfiles.encode(ProfileData.empty("Alex"), RemoteProtocol.Role.DOM, true), RemoteProtocol.Role.DOM);
        assertEquals(0, cleared.age); assertEquals("", cleared.gender);
    }

    @Test public void wireRejectsInferredFractionalOutOfRangeOrUnexpectedFields() throws Exception {
        JSONObject valid = RemoteProfiles.encode(new ProfileData("Alex", "", "", "Woman", 32), RemoteProtocol.Role.SUB, true);
        for (Object invalid : new Object[]{-1, 131, 32.5, "32", true, JSONObject.NULL}) {
            JSONObject candidate = new JSONObject(valid.toString()).put("age", invalid);
            assertThrows(org.json.JSONException.class, () -> RemoteProfiles.parse(candidate, RemoteProtocol.Role.SUB));
        }
        assertThrows(org.json.JSONException.class, () -> RemoteProfiles.parse(new JSONObject(valid.toString()).put("gender", "Woman\n"), RemoteProtocol.Role.SUB));
        assertThrows(org.json.JSONException.class, () -> RemoteProfiles.parse(new JSONObject(valid.toString()).put("birthday", "2000-01-01"), RemoteProtocol.Role.SUB));
        assertThrows(org.json.JSONException.class, () -> RemoteProfiles.parse(new JSONObject(valid.toString()).put("role", "DOM"), RemoteProtocol.Role.SUB));
        JSONObject missingGender = new JSONObject(valid.toString()); missingGender.remove("gender");
        assertThrows(org.json.JSONException.class, () -> RemoteProfiles.parse(missingGender, RemoteProtocol.Role.SUB));
    }
}
