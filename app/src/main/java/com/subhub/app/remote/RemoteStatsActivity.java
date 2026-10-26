package com.subhub.app.remote;

import android.os.Bundle;
import android.widget.LinearLayout;
import com.subhub.app.R;
import com.subhub.app.people.PersonUi;
import com.subhub.app.security.ControllerPinManager;
import org.json.*;
import java.util.*;

public final class RemoteStatsActivity extends RemotePage {
    private final android.os.Handler clock=new android.os.Handler(android.os.Looper.getMainLooper());
    private android.widget.TextView freshness;
    private final Runnable age=new Runnable(){public void run(){updateFreshness();clock.postDelayed(this,1000);}};
    private int offset;private RemoteProtocol.Stream selected=RemoteProtocol.Stream.STATS;
    private static final Map<String,Integer> LABELS=new LinkedHashMap<>();
    static {
        LABELS.put("photos",R.string.daily_photos);LABELS.put("videos",R.string.daily_videos);
        LABELS.put("protectedSeconds",R.string.stats_time);LABELS.put("serviceSeconds",R.string.stats_service_time);
        LABELS.put("sessions",R.string.stats_sessions);LABELS.put("activityEvents",R.string.stats_activity);LABELS.put("censors",R.string.stats_censors);
        LABELS.put("limitMillis",R.string.stats_limited_app_time);LABELS.put("limitStops",R.string.stats_limit_stops);LABELS.put("whispers",R.string.stats_whispers);LABELS.put("popups",R.string.stats_popups);
        LABELS.put("tamperEvents",R.string.remote_tamper);LABELS.put("tributes",R.string.stats_tributes);LABELS.put("assessedEUR",R.string.stats_tribute_value);LABELS.put("assessedUSD",R.string.stats_tribute_value);LABELS.put("paidEUR",R.string.stats_paid);LABELS.put("paidUSD",R.string.stats_paid);
        LABELS.put("currentSeconds",R.string.stats_service_time);LABELS.put("currentEvents",R.string.stats_activity);LABELS.put("currentCensors",R.string.stats_censors);LABELS.put("currentLimitMillis",R.string.stats_limited_app_time);LABELS.put("currentLimitStops",R.string.stats_limit_stops);LABELS.put("currentWhispers",R.string.stats_whispers);LABELS.put("currentPopups",R.string.stats_popups);LABELS.put("currentTributes",R.string.stats_tributes);LABELS.put("currentAssessedEUR",R.string.stats_tribute_value);LABELS.put("currentAssessedUSD",R.string.stats_tribute_value);
    }
    @Override protected void onCreate(Bundle state){super.onCreate(state);page(R.string.role_stats);if(state!=null){offset=Math.max(0,state.getInt("offset",0));try{selected=RemoteProtocol.Stream.valueOf(state.getString("stream",RemoteProtocol.Stream.STATS.name()));}catch(IllegalArgumentException invalid){selected=RemoteProtocol.Stream.STATS;}if(!Arrays.asList(RemoteProtocol.Stream.STATS,RemoteProtocol.Stream.DAILY,RemoteProtocol.Stream.APP_USAGE,RemoteProtocol.Stream.RUNTIME).contains(selected))selected=RemoteProtocol.Stream.STATS;}if(selectPeer())render();}
    @Override protected void onSaveInstanceState(Bundle state){state.putInt("offset",offset);state.putString("stream",selected.name());super.onSaveInstanceState(state);}
    @Override protected void onResume(){super.onResume();if(!isFinishing()&&peer!=null){render();clock.post(age);}}
    @Override protected void onPause(){clock.removeCallbacks(age);super.onPause();}
    private void updateFreshness(){if(freshness==null||peer==null)return;
        RemoteStore.Peer current=feature.store.peer(peer.identity,peer.generation);if(current==null){finish();return;}peer=current;
        long confirmed=Long.parseLong(feature.store.metadata(peer.identity,peer.generation,"confirmed_"+selected.name(),"0")),elapsed=System.currentTimeMillis()-confirmed;
        freshness.setVisibility(peer.state!=RemoteStore.State.ACTIVE||confirmed<=0||elapsed<0||elapsed>(selected==RemoteProtocol.Stream.RUNTIME?90_000:1_800_000)?android.view.View.VISIBLE:android.view.View.GONE);
    }
    private void render(){
        if(!ControllerPinManager.isDomModeActive()||peer.localRole!=RemoteProtocol.Role.DOM){finish();return;}
        body.removeAllViews();target();
        navigation(R.string.role_stats,RemoteProtocol.Stream.STATS);navigation(R.string.remote_daily_history,RemoteProtocol.Stream.DAILY);navigation(R.string.role_app_time,RemoteProtocol.Stream.APP_USAGE);navigation(R.string.remote_runtime,RemoteProtocol.Stream.RUNTIME);
        text(body,observed(selected),false);
        freshness=text(body,getString(R.string.role_stale_data),false);updateFreshness();
        try {
            if(selected==RemoteProtocol.Stream.STATS){JSONObject stats=feature.store.mirrored(peer.identity,peer.generation,selected,"summary");if(!RemoteTelemetry.validSummary(stats)){text(body,getString(R.string.remote_unknown),false);return;}
                boolean current=false;for(Map.Entry<String,Integer> item:LABELS.entrySet()){String key=item.getKey();if(key.startsWith("current")&&!current){current=true;text(body,getString(R.string.remote_current_session),true);}if(stats.has(key))metric(body,key,item.getValue(),stats.getLong(key));}return;}
            if(selected==RemoteProtocol.Stream.RUNTIME){runtime(feature.store.mirrored(peer.identity,peer.generation,selected,"current"));return;}
            List<RemoteProtocol.Change> rows=feature.store.mirror(peer.identity,peer.generation,selected);rows.removeIf(row->row.deleted);
            rows.sort((a,b)->{try{return new JSONObject(b.body).optString("day").compareTo(new JSONObject(a.body).optString("day"));}catch(JSONException invalid){return 0;}});
            if(rows.isEmpty())text(body,getString(R.string.remote_unknown),false);
            for(int i=offset;i<Math.min(rows.size(),offset+30);i++) {
                JSONObject row=new JSONObject(rows.get(i).body);LinearLayout card=PersonUi.card(body);text(card,row.getString("day"),true);
                text(card,row.isNull("zone")?getString(R.string.remote_unknown_zone):row.getString("zone"),false);
                if(selected==RemoteProtocol.Stream.DAILY){JSONObject metrics=row.getJSONObject("metrics");for(String key:new TreeSet<>(keys(metrics)))dailyMetric(card,key,metrics.getLong(key));}
                else if("today".equals(rows.get(i).key)){JSONObject values=row.getJSONObject("millis");for(String key:new TreeSet<>(keys(values)))usage(card,key,values.getLong(key));}
                else usage(card,row.getString("package"),row.getLong("millis"));
            }
            if(offset>0)button(body,R.string.remote_previous,()->{offset=Math.max(0,offset-30);render();});if(offset+30<rows.size())button(body,R.string.remote_next,()->{offset+=30;render();});
        }catch(JSONException invalid){text(body,getString(R.string.remote_incomplete),false);}
    }
    private void navigation(int label,RemoteProtocol.Stream stream){button(body,label,()->{selected=stream;offset=0;render();});}
    private void runtime(JSONObject runtime)throws JSONException {
        if(!RemoteTelemetry.validRuntime(runtime)){text(body,getString(R.string.remote_unknown),false);return;}
        text(body,getString(R.string.remote_source_observed,java.text.DateFormat.getDateTimeInstance().format(new Date(runtime.getLong("observedAt")))),false);
        String[] keys={"serviceActive","screenCapture","recognitionActive","accessibilityBound","accessibilityEnabled","overlayPermission","notifications","armed","paused"};
        int[] labels={R.string.remote_service_active,R.string.remote_screen_capture,R.string.remote_recognition,R.string.remote_accessibility_bound,R.string.remote_accessibility_enabled,R.string.remote_overlay_permission,R.string.remote_notifications,R.string.remote_armed,R.string.remote_paused};
        for(int i=0;i<keys.length;i++)PersonUi.row(body,0,getString(labels[i]),getString(runtime.getBoolean(keys[i])?R.string.role_enabled:R.string.role_disabled),null);
        for(String key:new String[]{"sessionStart","sessionEnd"}){long value=runtime.getLong(key);if(value>0)PersonUi.row(body,0,getString("sessionStart".equals(key)?R.string.remote_session_start:R.string.remote_session_end),java.text.DateFormat.getDateTimeInstance().format(new Date(value)),null);}
    }
    private void dailyMetric(LinearLayout target,String key,long value){
        if(value<0){text(target,getString(R.string.remote_incomplete),false);return;}
        if("service_ms".equals(key)){metric(target,"serviceSeconds",R.string.stats_service_time,value/1000);return;}
        String canonical=key.equals("limits_ms")?"limitMillis":key.equals("limit_stops")?"limitStops":key.replace("_","");
        if("service_seconds".equals(key))canonical="serviceSeconds";if("protected_seconds".equals(key))canonical="protectedSeconds";
        Integer label=LABELS.get(canonical);if(label==null){text(target,getString(R.string.remote_incomplete),false);return;}metric(target,canonical,label,value);
    }
    private void usage(LinearLayout target,String id,long millis)throws JSONException {
        if(millis<0)throw new JSONException("Invalid usage duration");
        JSONObject app=id.isEmpty()?null:feature.store.mirrored(peer.identity,peer.generation,RemoteProtocol.Stream.APP_CATALOG,id);
        String name=id.isEmpty()?getString(R.string.role_shared_allowance):app==null?id:app.getString("label");
        PersonUi.row(target,0,name,time(millis/1000),null);
    }
    private void metric(LinearLayout target,String key,int label,long value){
        if(value<0){text(target,getString(R.string.remote_incomplete),false);return;}
        String unit=key.endsWith("EUR")?"EUR":key.endsWith("USD")?"USD":"";
        String rendered=!unit.isEmpty()?java.math.BigDecimal.valueOf(value,2).toPlainString()+" "+unit:key.toLowerCase(java.util.Locale.ROOT).endsWith("millis")?time(value/1000):key.endsWith("Seconds")||"currentSeconds".equals(key)?time(value):Long.toString(value);
        PersonUi.row(target,0,getString(label),rendered,null);
    }
    private String time(long seconds){return getString(R.string.remote_time,seconds/3600,seconds/60%60,seconds%60);}
    private static Set<String> keys(JSONObject value){Set<String> keys=new HashSet<>();Iterator<String> iterator=value.keys();while(iterator.hasNext())keys.add(iterator.next());return keys;}
}
