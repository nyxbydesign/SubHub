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
    private final Map<RemoteProtocol.Stream,android.widget.TextView> freshness = new EnumMap<>(RemoteProtocol.Stream.class);
    private final Runnable age=new Runnable(){public void run(){updateFreshness();clock.postDelayed(this,1000);}};
    private int dayOffset,usageOffset,scrollY;
    private static final Map<String,Integer> LABELS=new LinkedHashMap<>();
    static {
        LABELS.put("photos",R.string.daily_photos);LABELS.put("videos",R.string.daily_videos);
        LABELS.put("protectedSeconds",R.string.stats_time);LABELS.put("serviceSeconds",R.string.stats_service_time);
        LABELS.put("sessions",R.string.stats_sessions);LABELS.put("activityEvents",R.string.stats_activity);LABELS.put("censors",R.string.stats_censors);
        LABELS.put("limitMillis",R.string.stats_limited_app_time);LABELS.put("limitStops",R.string.stats_limit_stops);LABELS.put("whispers",R.string.stats_whispers);LABELS.put("popups",R.string.stats_popups);
        LABELS.put("tamperEvents",R.string.remote_tamper);LABELS.put("tributes",R.string.stats_tributes);LABELS.put("assessedEUR",R.string.stats_tribute_value);LABELS.put("assessedUSD",R.string.stats_tribute_value);LABELS.put("paidEUR",R.string.stats_paid);LABELS.put("paidUSD",R.string.stats_paid);
        LABELS.put("currentSeconds",R.string.stats_service_time);LABELS.put("currentEvents",R.string.stats_activity);LABELS.put("currentCensors",R.string.stats_censors);LABELS.put("currentLimitMillis",R.string.stats_limited_app_time);LABELS.put("currentLimitStops",R.string.stats_limit_stops);LABELS.put("currentWhispers",R.string.stats_whispers);LABELS.put("currentPopups",R.string.stats_popups);LABELS.put("currentTributes",R.string.stats_tributes);LABELS.put("currentAssessedEUR",R.string.stats_tribute_value);LABELS.put("currentAssessedUSD",R.string.stats_tribute_value);
    }
    @Override protected void onCreate(Bundle state){super.onCreate(state);page(R.string.role_stats);if(state!=null){dayOffset=Math.max(0,state.getInt("dayOffset"));usageOffset=Math.max(0,state.getInt("usageOffset"));scrollY=Math.max(0,state.getInt("scrollY"));}if(selectPeer())render();}
    @Override protected void onSaveInstanceState(Bundle state){state.putInt("dayOffset",dayOffset);state.putInt("usageOffset",usageOffset);android.widget.ScrollView scroll=scroll();state.putInt("scrollY",scroll==null?0:scroll.getScrollY());super.onSaveInstanceState(state);}
    @Override protected void onResume(){super.onResume();if(!isFinishing()&&peer!=null){render();clock.post(age);}}
    @Override protected void onPause(){saveScroll();clock.removeCallbacks(age);super.onPause();}
    private android.widget.ScrollView scroll(){android.view.View view=body;while(view.getParent() instanceof android.view.View){view=(android.view.View)view.getParent();if(view instanceof android.widget.ScrollView)return (android.widget.ScrollView)view;}return null;}
    private void updateFreshness(){if(peer==null)return;
        RemoteStore.Peer current=feature.store.peer(peer.identity,peer.generation);if(current==null){finish();return;}peer=current;
        for(Map.Entry<RemoteProtocol.Stream,android.widget.TextView> item:freshness.entrySet()){
            long confirmed=Long.parseLong(feature.store.metadata(peer.identity,peer.generation,"confirmed_"+item.getKey().name(),"0")),elapsed=System.currentTimeMillis()-confirmed;
            item.getValue().setVisibility(peer.state!=RemoteStore.State.ACTIVE||confirmed<=0||elapsed<0||elapsed>(item.getKey()==RemoteProtocol.Stream.RUNTIME?90_000:1_800_000)?android.view.View.VISIBLE:android.view.View.GONE);
        }
    }
    private LinearLayout section(int label,RemoteProtocol.Stream stream){
        LinearLayout card=PersonUi.card(body);card.setTag("remote_stats_"+stream.name());text(card,getString(label),true);
        text(card,getString(R.string.role_last_synced,observed(stream)),false);
        android.widget.TextView stale=text(card,getString(R.string.role_stale_data),false);stale.setTag("remote_freshness_"+stream.name());freshness.put(stream,stale);return card;
    }
    private void render(){
        if(!ControllerPinManager.isDomModeActive()||!com.subhub.app.security.DeviceRolePreferences.supportsDom(this)||peer.localRole!=RemoteProtocol.Role.DOM){finish();return;}
        body.removeAllViews();freshness.clear();target();
        LinearLayout summary=section(R.string.role_stats,RemoteProtocol.Stream.STATS);
        try {
            JSONObject stats=feature.store.mirrored(peer.identity,peer.generation,RemoteProtocol.Stream.STATS,"summary");
            if(!RemoteTelemetry.validSummary(stats))text(summary,getString(R.string.remote_unknown),false);
            else{boolean current=false;for(Map.Entry<String,Integer> item:LABELS.entrySet()){String key=item.getKey();if(key.startsWith("current")&&!current){current=true;text(summary,getString(R.string.remote_current_session),true);}if(stats.has(key))metric(summary,key,item.getValue(),RemoteProtocol.integer(stats,key));}}
        }catch(JSONException invalid){text(summary,getString(R.string.remote_incomplete),false);}
        history(RemoteProtocol.Stream.DAILY,R.string.remote_daily_history,dayOffset);
        history(RemoteProtocol.Stream.APP_USAGE,R.string.role_app_time,usageOffset);
        LinearLayout runtime=section(R.string.remote_runtime,RemoteProtocol.Stream.RUNTIME);
        try{runtime(runtime,feature.store.mirrored(peer.identity,peer.generation,RemoteProtocol.Stream.RUNTIME,"current"));}
        catch(JSONException invalid){text(runtime,getString(R.string.remote_incomplete),false);}
        updateFreshness();android.widget.ScrollView scroll=scroll();if(scroll!=null)scroll.post(()->scroll.scrollTo(0,scrollY));
    }
    private void history(RemoteProtocol.Stream stream,int label,int offset){
        LinearLayout group=section(label,stream);List<RemoteProtocol.Change> rows=feature.store.mirror(peer.identity,peer.generation,stream);rows.removeIf(row->row.deleted);
        List<JSONObject> parsed=new ArrayList<>();boolean incomplete=false;
        for(RemoteProtocol.Change row:rows)try{JSONObject value=new JSONObject(row.body);if(RemoteProtocol.integer(value,"schema")!=1)throw new JSONException("Unknown history schema");java.time.LocalDate.parse(value.getString("day"));value.put("_today","today".equals(row.key));parsed.add(value);}catch(JSONException|RuntimeException invalid){incomplete=true;}
        parsed.sort((a,b)->b.optString("day").compareTo(a.optString("day")));
        if(incomplete)text(group,getString(R.string.remote_incomplete),false);
        if(parsed.isEmpty())text(group,getString(R.string.remote_unknown),false);
        for(int i=offset;i<Math.min(parsed.size(),offset+30);i++)try{
            JSONObject row=parsed.get(i);LinearLayout card=PersonUi.column(this);group.addView(card);text(card,row.getString("day"),true);
            text(card,row.isNull("zone")?getString(R.string.remote_unknown_zone):row.getString("zone"),false);
            if(stream==RemoteProtocol.Stream.DAILY){JSONObject metrics=row.getJSONObject("metrics");for(String key:new TreeSet<>(keys(metrics)))dailyMetric(card,key,RemoteProtocol.integer(metrics,key));}
            else if(row.getBoolean("_today")){JSONObject values=row.getJSONObject("millis");for(String key:new TreeSet<>(keys(values)))usage(card,key,RemoteProtocol.integer(values,key));}
            else usage(card,row.getString("package"),RemoteProtocol.integer(row,"millis"));
        }catch(JSONException invalid){text(group,getString(R.string.remote_incomplete),false);}
        if(offset>0)button(group,R.string.remote_previous,()->{if(stream==RemoteProtocol.Stream.DAILY)dayOffset=Math.max(0,offset-30);else usageOffset=Math.max(0,offset-30);saveScroll();render();});
        if(offset+30<parsed.size())button(group,R.string.remote_next,()->{if(stream==RemoteProtocol.Stream.DAILY)dayOffset=offset+30;else usageOffset=offset+30;saveScroll();render();});
    }
    private void saveScroll(){android.widget.ScrollView scroll=scroll();scrollY=scroll==null?0:scroll.getScrollY();}
    private void runtime(LinearLayout target,JSONObject runtime)throws JSONException {
        if(!RemoteTelemetry.validRuntime(runtime)){text(target,getString(R.string.remote_unknown),false);return;}
        text(target,getString(R.string.remote_source_observed,java.text.DateFormat.getDateTimeInstance().format(new Date(runtime.getLong("observedAt")))),false);
        String[] keys={"serviceActive","screenCapture","recognitionActive","accessibilityBound","accessibilityEnabled","overlayPermission","notifications","armed","paused"};
        int[] labels={R.string.remote_service_active,R.string.remote_screen_capture,R.string.remote_recognition,R.string.remote_accessibility_bound,R.string.remote_accessibility_enabled,R.string.remote_overlay_permission,R.string.remote_notifications,R.string.remote_armed,R.string.remote_paused};
        for(int i=0;i<keys.length;i++)PersonUi.row(target,0,getString(labels[i]),getString(runtime.getBoolean(keys[i])?R.string.role_enabled:R.string.role_disabled),null);
        for(String key:new String[]{"sessionStart","sessionEnd"}){long value=runtime.getLong(key);if(value>0)PersonUi.row(target,0,getString("sessionStart".equals(key)?R.string.remote_session_start:R.string.remote_session_end),java.text.DateFormat.getDateTimeInstance().format(new Date(value)),null);}
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
