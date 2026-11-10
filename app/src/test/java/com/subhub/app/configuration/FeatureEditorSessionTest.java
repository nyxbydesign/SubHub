package com.subhub.app.configuration;

import com.subhub.app.appmode.AppModeManager;
import com.subhub.app.remote.RemoteConfiguration;
import com.subhub.app.settings.SettingsRepository;
import org.junit.Test;
import static org.junit.Assert.*;

public final class FeatureEditorSessionTest {
    @Test public void everyConfigurationFieldTriggersAutomaticEditingOnlyInItsStore(){
        for(String section:ConfigurationSchema.SECTIONS)
            for(ConfigurationSettingCatalog.Field field:ConfigurationSettingCatalog.fields(section)){
                assertTrue(field.key,FeatureEditorSession.configurationKey(ConfigurationSchema.preferenceStore(section),field.key));
                assertFalse(field.key,FeatureEditorSession.configurationKey("unrelated-store",field.key));
            }
    }
    @Test public void appDurationAndArtworkChangesAreIncludedButPrivateUiStateIsNot(){
        String main=SettingsRepository.PREFERENCES_NAME;
        assertTrue(FeatureEditorSession.configurationKey(main,AppModeManager.KEY_ALL_APPS));
        assertTrue(FeatureEditorSession.configurationKey(main,AppModeManager.KEY_INCLUDED_PACKAGES));
        assertTrue(FeatureEditorSession.configurationKey(main,"app_timer_allowance_minutes:com.example.app"));
        assertTrue(FeatureEditorSession.configurationKey(main,com.subhub.app.capture.CustomImageManager.REVISION_KEY));
        for(String key:new String[]{"duration","minimum","maximum","hidden"})
            assertTrue(FeatureEditorSession.configurationKey(RemoteConfiguration.DURATION_PREFS,key));
        assertFalse(FeatureEditorSession.configurationKey(main,"local_only_witness"));
        assertFalse(FeatureEditorSession.configurationKey(main,"app_include_all_packages_v1"));
        assertFalse(FeatureEditorSession.configurationKey("credentials","password"));
    }
    @Test public void previewDocumentsAreCompleteValidAndFresh() throws Exception {
        org.json.JSONObject first=com.subhub.app.remote.DummyPreviewConfiguration.create();
        org.json.JSONObject checked=RemoteConfiguration.active(first);
        org.json.JSONObject sections=checked.getJSONObject("contract").getJSONObject("sections");
        for(String section:ConfigurationSchema.SECTIONS){
            assertEquals(ConfigurationSettingCatalog.fields(section).size(),sections.getJSONObject(section).length());
            for(ConfigurationSettingCatalog.Field field:ConfigurationSettingCatalog.fields(section))
                assertTrue(field.key,sections.getJSONObject(section).has(field.key));
        }
        assertEquals(45,first.getJSONObject("apps").getJSONObject("allowances").getInt("preview.reader"));
        assertEquals(60,first.getJSONObject("apps").getJSONObject("allowances").getInt("preview.music"));
        assertEquals(-2L,first.getJSONObject("service").getLong("duration"));
        assertEquals(0,first.getJSONObject("contract").getJSONArray("assets").length());
        first.getJSONObject("contract").put("name","mutated");
        first.getJSONObject("contract").getJSONObject("sections").getJSONObject("limits").put("app_timer_per_app_enabled",false);
        first.getJSONObject("apps").getJSONObject("allowances").put("preview.reader",1);
        first.getJSONObject("service").put("duration",0);
        org.json.JSONObject second=RemoteConfiguration.active(com.subhub.app.remote.DummyPreviewConfiguration.create());
        assertEquals("Dummy Sub",second.getJSONObject("contract").getString("name"));
        assertTrue(second.getJSONObject("contract").getJSONObject("sections").getJSONObject("limits").getBoolean("app_timer_per_app_enabled"));
        assertEquals(45,second.getJSONObject("apps").getJSONObject("allowances").getInt("preview.reader"));
        assertEquals(-2L,second.getJSONObject("service").getLong("duration"));
    }
}
