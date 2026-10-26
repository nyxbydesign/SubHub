package com.subhub.app.remote;

import com.subhub.app.configuration.ConfigurationSchema;
import com.subhub.app.configuration.ConfigurationSettingCatalog;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class RemoteConfigurationCompatibilityTest {
    @Test public void currentCaptureMetadataWorksOnDevelopmentAndStableBuilds() throws Exception {
        JSONObject value=configuration();
        for(String version:new String[]{"0.6.5-0","0.6.5-dev.1","0.6.5-dev.178","0.6.5","0.6.6"}) {
            assertEquals(RemoteConfiguration.MINIMUM_APP_VERSION,
                    RemoteConfiguration.active(value,version).getJSONObject("contract").getString("minimumAppVersion"));
        }
    }

    @Test public void defaultValidatorUsesTheActualInstalledBuildIdentity() throws Exception {
        JSONObject value=configuration();
        assertEquals(RemoteConfiguration.MINIMUM_APP_VERSION,
                RemoteConfiguration.active(value).getJSONObject("contract").getString("minimumAppVersion"));
    }

    @Test public void olderLinesAndFutureContractRequirementsRemainRejected() throws Exception {
        JSONObject value=configuration();
        for(String version:new String[]{"0.6.4","0.6.4-dev.999"})
            assertThrows(IllegalArgumentException.class,()->RemoteConfiguration.active(value,version));
        value.getJSONObject("contract").put("minimumAppVersion","0.7.0");
        assertThrows(IllegalArgumentException.class,()->RemoteConfiguration.active(value,"0.6.5-dev.178"));
        assertThrows(IllegalArgumentException.class,()->RemoteConfiguration.active(value,"0.6.5"));
    }

    @Test public void aStableMinimumDoesNotAccidentallyAdmitPrereleaseBuilds() throws Exception {
        JSONObject value=configuration();value.getJSONObject("contract").put("minimumAppVersion","0.6.5");
        assertThrows(IllegalArgumentException.class,()->RemoteConfiguration.active(value,"0.6.5-dev.178"));
        assertNotNull(RemoteConfiguration.active(value,"0.6.5"));
    }

    private static JSONObject configuration() throws Exception {
        JSONObject sections=new JSONObject();
        for(String section:ConfigurationSchema.SECTIONS)sections.put(section,ConfigurationSettingCatalog.defaults(section));
        JSONObject metadata=RemoteConfiguration.currentMetadata("00000000-0000-4000-8000-000000000001",
                "00000000-0000-4000-8000-000000000002","Current configuration",1,1);
        return RemoteConfiguration.fromSections(metadata,sections,new JSONArray(),
                new JSONObject().put("all",false).put("packages",new JSONArray()).put("allowances",new JSONObject()),
                new JSONObject().put("duration",-1).put("minimum",3600000).put("maximum",86400000).put("hidden",false));
    }
}
