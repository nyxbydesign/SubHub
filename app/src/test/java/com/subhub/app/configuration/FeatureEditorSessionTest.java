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
}
