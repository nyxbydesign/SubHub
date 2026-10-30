package com.subhub.app.remote;

import static org.junit.Assert.*;
import com.subhub.app.people.ProfileData;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;

public class RemoteProfilesTest {
    @Test public void profileIsBoundToTheRelationshipRoleAndCarriesOnlyPublicFields() throws Exception {
        ProfileData source = new ProfileData("Fixture Sub", "First line\nSecond line", "");
        JSONObject encoded = RemoteProfiles.encode(source, RemoteProtocol.Role.SUB);
        assertEquals(4, encoded.length());
        assertEquals(source, RemoteProfiles.parse(encoded, RemoteProtocol.Role.SUB));
        assertThrows(JSONException.class, () -> RemoteProfiles.parse(encoded, RemoteProtocol.Role.DOM));
        assertThrows(JSONException.class, () -> RemoteProfiles.parse(
                new JSONObject(encoded.toString()).put("featureConsent", new JSONObject()), RemoteProtocol.Role.SUB));
    }

    @Test public void wireTextRefusesWrongTypesOverlengthAndUnnormalizedInput() throws Exception {
        JSONObject encoded = RemoteProfiles.encode(ProfileData.empty("Sub"), RemoteProtocol.Role.SUB);
        assertThrows(JSONException.class, () -> RemoteProfiles.parse(
                new JSONObject(encoded.toString()).put("about", true), RemoteProtocol.Role.SUB));
        assertThrows(JSONException.class, () -> RemoteProfiles.parse(
                new JSONObject(encoded.toString()).put("about", "a".repeat(601)), RemoteProtocol.Role.SUB));
        assertThrows(JSONException.class, () -> RemoteProfiles.parse(
                new JSONObject(encoded.toString()).put("name", " Sub "), RemoteProtocol.Role.SUB));
        assertThrows(JSONException.class, () -> RemoteProfiles.parse(
                new JSONObject(encoded.toString()).put("avatar", "bad"), RemoteProtocol.Role.SUB));
    }
}
