package com.subhub.app.remote;

import android.content.Context;
import com.subhub.app.settings.InstalledAppCatalog;
import org.json.*;
import java.util.*;

/** App choices support paired configuration; no activity, usage, statistics or live state is collected. */
public final class RemoteTelemetry {
    private final Context context;private final RemoteStore store;
    public RemoteTelemetry(Context context,RemoteStore store){this.context=context.getApplicationContext();this.store=store;}
    public void prepare(RemoteStore.Peer peer) throws Exception {
        if(peer.localRole!=RemoteProtocol.Role.SUB)return;
        Set<String> catalog=new HashSet<>();
        for(InstalledAppCatalog.Entry entry:InstalledAppCatalog.load(context)) {
            catalog.add(entry.packageName);
            String label=entry.label.length()>160?entry.label.substring(0,160):entry.label;
            publish(peer,RemoteProtocol.Stream.APP_CATALOG,entry.packageName,new JSONObject().put("schema",1).put("package",entry.packageName).put("label",label));
        }
        for(RemoteProtocol.Change old:store.published(peer.identity,peer.generation,RemoteProtocol.Stream.APP_CATALOG))
            if(!old.deleted && !catalog.contains(old.key))store.publish(peer.identity,peer.generation,RemoteProtocol.Stream.APP_CATALOG,old.key,new JSONObject(),true,System.currentTimeMillis());
    }
    public static boolean validSummary(JSONObject value) {
        String[] numeric={"protectedSeconds","serviceSeconds","sessions","activityEvents","censors","limitMillis","limitStops","whispers","popups","tamperEvents","tributes","assessedEUR","assessedUSD","paidEUR","paidUSD","currentSeconds","currentEvents","currentCensors","currentLimitMillis","currentLimitStops","currentWhispers","currentPopups","currentTributes","currentAssessedEUR","currentAssessedUSD"};
        try{if(value==null || RemoteProtocol.integer(value,"schema")!=1 || !(value.get("currency") instanceof String))return false;
            for(String key:numeric)if(RemoteProtocol.integer(value,key)<0)return false;return true;
        }catch(JSONException|IllegalArgumentException invalid){return false;}
    }
    public static boolean validRuntime(JSONObject value) {
        try{if(value==null || RemoteProtocol.integer(value,"schema")!=1 || !(value.get("device") instanceof String))return false;
            for(String key:new String[]{"observedAt","sessionStart","sessionEnd","appliedRevision"})if(RemoteProtocol.integer(value,key)<0)return false;
            for(String key:new String[]{"serviceActive","screenCapture","recognitionActive","accessibilityBound","accessibilityEnabled","overlayPermission","notifications","armed","paused"})if(!(value.get(key) instanceof Boolean))return false;
            if (value.has("featureConsent")) {
                JSONObject consent = value.getJSONObject("featureConsent");
                if (consent.length() != com.subhub.app.security.SubFeaturePolicy.FEATURES.size()) return false;
                for (String key : com.subhub.app.security.SubFeaturePolicy.FEATURES)
                    if (!(consent.get(key) instanceof Boolean)) return false;
            }
            if(value.has("endSessionSupported") && !(value.get("endSessionSupported") instanceof Boolean))return false;
            if(value.has("sessionIdentity") && (!(value.get("sessionIdentity") instanceof String) || !value.getString("sessionIdentity").matches("([0-9a-f]{64})?")))return false;
            return true;
        }catch(JSONException|IllegalArgumentException invalid){return false;}
    }
    private void publish(RemoteStore.Peer peer,RemoteProtocol.Stream stream,String key,JSONObject body) {
        store.publish(peer.identity,peer.generation,stream,key,body,false,System.currentTimeMillis());
    }
}
