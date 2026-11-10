package com.subhub.app.remote;

import com.subhub.app.configuration.ConfigurationSchema;
import com.subhub.app.configuration.ConfigurationSettingCatalog;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class RemoteAppliedNotificationsTest {
    @Test public void timestampsAndIdenticalSettingsAreNotContractChanges() throws Exception {
        JSONObject before = configuration(), after = new JSONObject(before.toString());
        after.getJSONObject("contract").put("updatedAt", 100);
        assertFalse(RemoteAppliedNotifications.changed(before, after));
    }
    @Test public void serviceAndContractIdentityChangesAreVisible() throws Exception {
        JSONObject before = configuration(), after = new JSONObject(before.toString());
        after.getJSONObject("service").put("hidden", true);
        assertTrue(RemoteAppliedNotifications.changed(before, after));
        after = new JSONObject(before.toString());
        after.getJSONObject("contract").put("name", "Different contract");
        assertTrue(RemoteAppliedNotifications.changed(before, after));
    }
    @Test public void changingOnlySelectedAppOrderIsANoop() throws Exception {
        JSONObject before = configuration(), after = new JSONObject(before.toString());
        before.getJSONObject("apps").put("packages", new JSONArray().put("com.example.alpha").put("com.example.beta"));
        after.getJSONObject("apps").put("packages", new JSONArray().put("com.example.beta").put("com.example.alpha"));
        assertFalse(RemoteAppliedNotifications.changed(before, after));
    }
    private static JSONObject configuration() throws Exception {
        JSONObject sections = new JSONObject();
        for (String section : ConfigurationSchema.SECTIONS) sections.put(section, ConfigurationSettingCatalog.defaults(section));
        return RemoteConfiguration.fromSections(RemoteConfiguration.currentMetadata(
                "00000000-0000-4000-8000-000000000001", "00000000-0000-4000-8000-000000000002", "Contract", 1, 1),
                sections, new JSONArray(), new JSONObject().put("all", false).put("packages", new JSONArray()).put("allowances", new JSONObject()),
                new JSONObject().put("duration", -1).put("minimum", 3600000).put("maximum", 86400000).put("hidden", false));
    }
}
