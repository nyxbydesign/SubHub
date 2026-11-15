package com.subhub.app.remote;

import com.subhub.app.configuration.ConfigurationSchema;
import com.subhub.app.configuration.ConfigurationSettingCatalog;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Fresh synthetic presentation data. No Context, repositories, assets or device enumeration. */
public final class DummyPreviewConfiguration {
    private DummyPreviewConfiguration() { }

    public static JSONObject create() {
        try {
            JSONObject sections=new JSONObject();
            for(String section:ConfigurationSchema.SECTIONS)
                sections.put(section,ConfigurationSettingCatalog.complete(section,new JSONObject()));
            sections.getJSONObject("modules").put("module_subliminal_enabled",true);
            sections.getJSONObject("limits").put("app_timer_per_app_enabled",true).put("app_timer_total_enabled",true);
            sections.getJSONObject("subliminal").put("subliminal_advanced",true);
            sections.getJSONObject("popup").put("popup_storm_enabled",true);
            JSONObject contract=RemoteConfiguration.currentMetadata(
                    "00000000-0000-4000-8000-000000000001",
                    "00000000-0000-4000-8000-000000000002","Dummy Sub",1L,1L)
                    .put("sections",sections).put("assets",new JSONArray());
            JSONObject apps=new JSONObject().put("all",false)
                    .put("packages",new JSONArray().put("preview.reader").put("preview.music"))
                    .put("allowances",new JSONObject().put("preview.reader",45).put("preview.music",60));
            JSONObject service=new JSONObject().put("duration",-2L).put("minimum",3600000L)
                    .put("maximum",86400000L).put("hidden",false);
            return new JSONObject().put("schema",RemoteConfiguration.SCHEMA).put("contract",contract)
                    .put("apps",apps).put("service",service);
        }catch(JSONException invalid){throw new IllegalStateException("Invalid synthetic preview",invalid);}
    }

}
