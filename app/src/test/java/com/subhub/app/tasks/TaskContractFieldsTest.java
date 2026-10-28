package com.subhub.app.tasks;

import com.subhub.app.configuration.ConfigurationSchema;
import com.subhub.app.configuration.ConfigurationSettingCatalog;
import com.subhub.app.remote.RemoteConfiguration;
import com.subhub.app.remote.RemoteProtocol;
import org.json.*;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class TaskContractFieldsTest {
    private JSONObject current() throws Exception {
        JSONObject sections = new JSONObject();
        for (String section : ConfigurationSchema.SECTIONS) sections.put(section, ConfigurationSettingCatalog.defaults(section));
        return RemoteConfiguration.fromSections(RemoteConfiguration.currentMetadata(UUID.randomUUID().toString(),
                UUID.randomUUID().toString(), "Synthetic profile", 1000, 1000), sections, new JSONArray(),
                new JSONObject().put("all", false).put("packages", new JSONArray()).put("allowances", new JSONObject()),
                new JSONObject().put("duration", -1).put("minimum", 3_600_000).put("maximum", 86_400_000).put("hidden", false));
    }
    private JSONObject wallet(JSONObject doc) throws Exception { return doc.getJSONObject("contract").getJSONObject("sections").getJSONObject("wallet"); }
    @Test public void legacyWireOmitsOnlyTasksExtensionAndNewReaderRestoresSafeDefaults() throws Exception {
        JSONObject current = current(); String before = RemoteProtocol.canonical(current);
        JSONObject legacy = TaskContractFields.forPeer(current, false, true);
        assertFalse(wallet(legacy).has(TaskContractFields.ENABLED)); assertFalse(wallet(legacy).has(TaskContractFields.CENTS));
        Set<String> legacyKeys = new HashSet<>(ConfigurationSettingCatalog.keys("wallet"));
        legacyKeys.remove(TaskContractFields.ENABLED); legacyKeys.remove(TaskContractFields.CENTS);
        Set<String> actual = new HashSet<>(); wallet(legacy).keys().forEachRemaining(actual::add);
        assertEquals(legacyKeys, actual);
        JSONObject restored = RemoteConfiguration.active(legacy);
        assertFalse(wallet(restored).getBoolean(TaskContractFields.ENABLED)); assertEquals(100, wallet(restored).getInt(TaskContractFields.CENTS));
        assertEquals(before, RemoteProtocol.canonical(current));
        assertEquals(before, RemoteProtocol.canonical(restored));
    }
    @Test public void incompleteExtensionAndUnrelatedMissingSettingsRemainRejected() throws Exception {
        JSONObject onlyOneMissing = current(); wallet(onlyOneMissing).remove(TaskContractFields.CENTS);
        assertThrows(IllegalArgumentException.class, () -> RemoteConfiguration.active(onlyOneMissing));
        JSONObject otherMissing = TaskContractFields.forPeer(current(), false, false); wallet(otherMissing).remove("daily_cap_cents");
        assertThrows(IllegalArgumentException.class, () -> RemoteConfiguration.active(otherMissing));
        JSONObject unknown = current(); wallet(unknown).put("rule_secret_new_feature", true);
        assertThrows(IllegalArgumentException.class, () -> RemoteConfiguration.active(unknown));
    }
    @Test public void negotiatedPeersKeepRuleAndOlderPeersCannotSilentlyEnableIt() throws Exception {
        JSONObject enabled = current(); wallet(enabled).put(TaskContractFields.ENABLED, true).put(TaskContractFields.CENTS, 125);
        assertTrue(wallet(TaskContractFields.forPeer(enabled, true, true)).getBoolean(TaskContractFields.ENABLED));
        assertEquals(125, wallet(TaskContractFields.forPeer(enabled, true, true)).getInt(TaskContractFields.CENTS));
        assertThrows(SecurityException.class, () -> TaskContractFields.forPeer(enabled, false, true));
        assertFalse(wallet(TaskContractFields.forPeer(enabled, false, false)).has(TaskContractFields.ENABLED));
    }
}
