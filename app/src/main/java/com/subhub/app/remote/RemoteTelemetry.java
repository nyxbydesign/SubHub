package com.subhub.app.remote;

import android.content.*;
import android.os.Build;
import android.provider.Settings;
import com.subhub.app.appmode.*;
import com.subhub.app.penance.*;
import com.subhub.app.service.*;
import com.subhub.app.settings.*;
import com.subhub.app.stats.*;
import org.json.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Explicit numeric projections never serialize private preference stores or session history. */
public final class RemoteTelemetry {
    private final Context context;private final RemoteStore store;
    public RemoteTelemetry(Context context,RemoteStore store){this.context=context.getApplicationContext();this.store=store;}
    public void prepare(RemoteStore.Peer peer) throws Exception {
        if(peer.localRole!=RemoteProtocol.Role.SUB)return;
        StatsRepository repository=new StatsRepository(context);StatsSnapshot stats=repository.load();PenanceManager wallet=new PenanceManager(context);
        JSONObject totals=new JSONObject().put("schema",1).put("currency",wallet.getCurrency())
                .put("protectedSeconds",stats.getTotalProtectedSeconds()).put("serviceSeconds",stats.getTotalSessionSeconds())
                .put("sessions",stats.getSessions()).put("activityEvents",stats.getActivityEvents()).put("censors",stats.getTotalBlocks())
                .put("limitMillis",stats.getLimitedAppMillis()).put("limitStops",stats.getLimitInterventions())
                .put("whispers",stats.getSubliminalImpressions()).put("popups",stats.getPopupImpressions()).put("tamperEvents",stats.getTamperEvents())
                .put("tributes",stats.getTributeEvents()).put("assessedEUR",repository.tributeCents("EUR",false)).put("assessedUSD",repository.tributeCents("USD",false))
                .put("paidEUR",wallet.getTotalPaidCents("EUR")).put("paidUSD",wallet.getTotalPaidCents("USD"))
                .put("currentSeconds",stats.getCurrentSessionSeconds()).put("currentEvents",stats.getCurrentSessionActivityEvents())
                .put("currentCensors",stats.getCurrentSessionBlocks()).put("currentLimitMillis",stats.getCurrentSessionLimitedAppMillis())
                .put("currentLimitStops",stats.getCurrentSessionLimitInterventions()).put("currentWhispers",stats.getCurrentSessionSubliminals())
                .put("currentPopups",stats.getCurrentSessionPopupImpressions()).put("currentTributes",stats.getCurrentSessionTributeEvents())
                .put("currentAssessedEUR",repository.tributeCents("EUR",true)).put("currentAssessedUSD",repository.tributeCents("USD",true));
        publish(peer,RemoteProtocol.Stream.STATS,"summary",totals);
        DailyStatsStore daily=DailyStatsStore.get(context);daily.syncStats(context.getSharedPreferences(StatsRepository.PREFS_NAME,0));daily.syncWallet();
        for(Map.Entry<String,Map<String,Long>> row:daily.days("0000-01-01","9999-12-31").entrySet()) {
            JSONObject metrics=new JSONObject();for(Map.Entry<String,Long> value:row.getValue().entrySet())metrics.put(value.getKey(),value.getValue());
            publish(peer,RemoteProtocol.Stream.DAILY,row.getKey(),new JSONObject().put("schema",1).put("day",row.getKey()).put("zone",JSONObject.NULL).put("metrics",metrics));
        }
        AppModeManager apps=new AppModeManager(context);AppTimerManager timers=new AppTimerManager(context);timers.checkpointHistory(System.currentTimeMillis());
        for(DailyAppUsageStore.Entry entry:DailyAppUsageStore.get(context).entries()) {
            String key=RemoteProtocol.sha256((entry.epoch+":"+entry.packageName).getBytes(StandardCharsets.UTF_8));
            publish(peer,RemoteProtocol.Stream.APP_USAGE,key,new JSONObject().put("schema",1).put("epoch",entry.epoch).put("day",entry.day)
                    .put("zone",entry.zone.isEmpty()?JSONObject.NULL:entry.zone).put("package",entry.packageName).put("millis",entry.millis));
        }
        JSONObject now=new JSONObject();Map<String,AppTimerManager.UsageSnapshot> usage=timers.snapshots(apps.getIncludedPackages(),System.currentTimeMillis());
        for(Map.Entry<String,AppTimerManager.UsageSnapshot> entry:usage.entrySet())now.put(entry.getKey(),entry.getKey().isEmpty()?entry.getValue().totalUsedMillis:entry.getValue().appUsedMillis);
        publish(peer,RemoteProtocol.Stream.APP_USAGE,"today",new JSONObject().put("schema",1).put("day",java.time.LocalDate.now().toString()).put("millis",now));
        Set<String> catalog=new HashSet<>();
        for(InstalledAppCatalog.Entry entry:InstalledAppCatalog.load(context)) {
            if(catalog.size()>=512)break;catalog.add(entry.packageName);
            String label=entry.label.length()>160?entry.label.substring(0,160):entry.label;
            publish(peer,RemoteProtocol.Stream.APP_CATALOG,entry.packageName,new JSONObject().put("schema",1).put("package",entry.packageName).put("label",label));
        }
        for(RemoteProtocol.Change old:store.published(peer.identity,peer.generation,RemoteProtocol.Stream.APP_CATALOG))
            if(!old.deleted && !catalog.contains(old.key))store.publish(peer.identity,peer.generation,RemoteProtocol.Stream.APP_CATALOG,old.key,new JSONObject(),true,System.currentTimeMillis());
        SharedPreferences main=context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME,0),session=context.getSharedPreferences(StatsRepository.PREFS_NAME,0);
        boolean screen=ScreenCaptureService.isRunning(),recognition=ScreenshotAccessibilityService.isRecognitionActive();
        android.view.accessibility.AccessibilityManager accessibility=(android.view.accessibility.AccessibilityManager)context.getSystemService(Context.ACCESSIBILITY_SERVICE);
        boolean enabled=false;
        if(accessibility!=null)for(android.accessibilityservice.AccessibilityServiceInfo info:accessibility.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK))
            if(info.getResolveInfo()!=null && info.getResolveInfo().serviceInfo!=null && context.getPackageName().equals(info.getResolveInfo().serviceInfo.packageName))enabled=true;
        JSONObject runtime=new JSONObject().put("schema",1).put("observedAt",System.currentTimeMillis()).put("device",Build.MODEL)
                .put("serviceActive",screen||recognition).put("screenCapture",screen).put("recognitionActive",recognition)
                .put("accessibilityBound",ScreenshotAccessibilityService.isRunning()).put("accessibilityEnabled",enabled).put("overlayPermission",Settings.canDrawOverlays(context))
                .put("notifications",Build.VERSION.SDK_INT<33 || context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)==android.content.pm.PackageManager.PERMISSION_GRANTED)
                .put("armed",apps.isArmed()).put("paused",new PaidPauseManager(context).isActive()).put("sessionStart",session.getLong("active_session_start_ms",0))
                .put("sessionEnd",main.getLong("commitment_ends_at",0)).put("appliedRevision",Long.parseLong(store.metadata(peer.identity,peer.generation,"applied_revision","0")));
        publish(peer,RemoteProtocol.Stream.RUNTIME,"current",runtime);
    }
    private void publish(RemoteStore.Peer peer,RemoteProtocol.Stream stream,String key,JSONObject body) {
        store.publish(peer.identity,peer.generation,stream,key,body,false,System.currentTimeMillis());
    }
}
