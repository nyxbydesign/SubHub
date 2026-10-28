package com.subhub.app.stats;

import android.content.Context;
import com.subhub.app.people.*;
import com.subhub.app.remote.*;
import com.subhub.app.security.*;
import org.json.*;
import java.io.IOException;
import java.time.LocalDate;
import java.util.*;

/** Read-only mirrored statistics for the selected relationship; no local statistics fallback. */
final class RemoteStatsSource implements DailyStatsPanel.Source {
    private final Context context;
    private final RemoteFeature feature;
    private final PersonId id;
    private final RemoteStore.Peer peer;
    RemoteStatsSource(Context context,PersonId id) {
        this.context=context.getApplicationContext();this.id=id;feature=RemoteFeature.get(this.context);
        peer=new RemotePersonRepository(this.context,feature).peer(id);requireAccess();
    }
    void requireAccess(){
        RemoteStore.Peer current=feature.store.peer(peer.identity,peer.generation);
        if(!ControllerPinManager.isDomModeActive()||!DeviceRolePreferences.supportsDom(context)
                ||current==null||current.localRole!=RemoteProtocol.Role.DOM)throw new SecurityException("Statistics unavailable");
    }
    PersonSnapshot person()throws Exception{requireAccess();return new RemotePersonRepository(context,feature).load(id);}
    JSONObject summary(){requireAccess();JSONObject summary=feature.store.mirrored(peer.identity,peer.generation,RemoteProtocol.Stream.STATS,"summary");return RemoteTelemetry.validSummary(summary)?summary:null;}
    @Override public DailyStatsPanel.DailyData load(String first,String last)throws Exception{
        requireAccess();Map<String,Map<String,Long>> days=new TreeMap<>();String since=null;
        for(RemoteProtocol.Change row:feature.store.mirror(peer.identity,peer.generation,RemoteProtocol.Stream.DAILY))if(!row.deleted){
            JSONObject entry=new JSONObject(row.body);if(RemoteProtocol.integer(entry,"schema")!=1)throw new IOException("Unsupported history");
            String day=LocalDate.parse(entry.getString("day")).toString();if(since==null||day.compareTo(since)<0)since=day;
            JSONObject raw=entry.getJSONObject("metrics");Map<String,Long> metrics=new LinkedHashMap<>();
            Iterator<String> keys=raw.keys();while(keys.hasNext()){String key=keys.next();long value=RemoteProtocol.integer(raw,key);if(value<0)throw new IOException("Invalid history");metrics.put(key,value);}
            if(day.compareTo(first)>=0&&day.compareTo(last)<=0)days.put(day,Collections.unmodifiableMap(metrics));
        }
        if(since==null)throw new IOException("History has not arrived");
        return new DailyStatsPanel.DailyData(since,Collections.unmodifiableMap(days));
    }
}
