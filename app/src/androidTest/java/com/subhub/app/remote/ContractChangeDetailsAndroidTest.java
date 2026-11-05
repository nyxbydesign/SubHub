package com.subhub.app.remote;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.configuration.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class ContractChangeDetailsAndroidTest {
    @Test public void appliedDiffUsesLabelsUnitsAndActualValuesWithoutBookkeeping() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        JSONObject before = configuration(), after = new JSONObject(before.toString());
        after.getJSONObject("contract").put("updatedAt", 500);
        assertTrue(ContractChangeDetails.lines(context, before, after).isEmpty());
        after.getJSONObject("contract").getJSONObject("sections").getJSONObject("censor").put("detection_confidence_percent", 40);
        after.getJSONObject("apps").getJSONObject("allowances").put("com.example.synthetic", 25);
        after.getJSONObject("service").put("hidden", true);
        after.getJSONObject("contract").getJSONObject("sections").getJSONObject("censor").put("border_color", "#FFFFFF");
        after.getJSONObject("contract").getJSONObject("sections").getJSONObject("wallet").put("rule_new_detection_cents", 250);
        List<String> lines = ContractChangeDetails.lines(context, before, after);
        assertEquals(5, lines.size());
        String detail = String.join("\n", lines);
        assertTrue(detail.contains("25% → 40%"));
        assertTrue(detail.contains("25 min"));
        assertTrue(detail.contains("#9860BE → #FFFFFF"));
        assertTrue(detail.contains("100 cents → 250 cents"));
        assertTrue(detail.contains("Off → On"));
        assertFalse(detail.contains("detection_confidence_percent"));
        assertFalse(detail.contains("updatedAt"));
        assertFalse(detail.contains("{\""));
    }
    private JSONObject configuration() throws Exception {
        JSONObject sections = new JSONObject();
        for (String section : ConfigurationSchema.SECTIONS) sections.put(section, ConfigurationSettingCatalog.defaults(section));
        return RemoteConfiguration.fromSections(RemoteConfiguration.currentMetadata(
                "00000000-0000-4000-8000-000000000001", "00000000-0000-4000-8000-000000000002", "Synthetic contract", 1, 1),
                sections, new JSONArray(), new JSONObject().put("all", false).put("packages", new JSONArray()).put("allowances", new JSONObject()),
                new JSONObject().put("duration", -1).put("minimum", 3600000).put("maximum", 86400000).put("hidden", false));
    }
}
