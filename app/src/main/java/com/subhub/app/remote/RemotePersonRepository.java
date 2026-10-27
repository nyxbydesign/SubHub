package com.subhub.app.remote;

import android.content.Context;
import com.subhub.app.R;
import com.subhub.app.configuration.ConfigurationSettingCatalog;
import com.subhub.app.people.*;
import org.json.*;
import java.util.*;

/** Every mirror read is pinned to a cryptographic identity and its relationship generation. */
public final class RemotePersonRepository implements PersonRepository {
    private final Context context;private final RemoteFeature feature;private final RemoteStore store;
    public RemotePersonRepository(Context context,RemoteFeature feature){this.context=context.getApplicationContext();this.feature=feature;store=feature.store;}
    public static PersonId id(RemoteStore.Peer peer){return new PersonId("remote_"+peer.identity+"_"+peer.generation);}
    public RemoteStore.Peer peer(PersonId id) {
        String raw=id.value();if(!raw.startsWith("remote_") || raw.length()<73 || raw.charAt(71)!='_')throw new IllegalArgumentException("Invalid peer selection");
        RemoteStore.Peer peer=store.peer(raw.substring(7,71),raw.substring(72));if(peer==null || !id(peer).equals(id))throw new IllegalArgumentException("Peer unavailable");return peer;
    }
    @Override public List<PersonId> people() {
        List<PersonId> result=new ArrayList<>();for(RemoteStore.Peer peer:store.peers(true))if(peer.localRole==RemoteProtocol.Role.DOM)result.add(id(peer));return result;
    }
    @Override public PersonSnapshot load(PersonId id) throws Exception {
        RemoteStore.Peer peer=peer(id);if(peer.localRole!=RemoteProtocol.Role.DOM)throw new IllegalArgumentException("Not a paired Sub");
        JSONObject configuration=feature.contracts.current(peer),stats=null,runtime=null;
        PersonSnapshot.Freshness contract=freshness(peer,RemoteProtocol.Stream.BASELINE,configuration!=null,30*60_000),statistics=freshness(peer,RemoteProtocol.Stream.STATS,stats!=null,30*60_000),live=freshness(peer,RemoteProtocol.Stream.RUNTIME,runtime!=null,90_000);
        PersonSnapshot.Connection connection=peer.state==RemoteStore.State.REVOKED?PersonSnapshot.Connection.REVOKED:peer.state==RemoteStore.State.PENDING?PersonSnapshot.Connection.PENDING:
                feature.isConnected(peer)?PersonSnapshot.Connection.CONNECTED:store.metadata(peer.identity,peer.generation,"connection_error","").isEmpty()?PersonSnapshot.Connection.PAIRED:PersonSnapshot.Connection.OFFLINE;
        EnumSet<PersonSnapshot.Capability> capabilities=EnumSet.of(PersonSnapshot.Capability.MANAGE_CONNECTION);
        if(configuration!=null)capabilities.add(PersonSnapshot.Capability.VIEW_CONTRACT);
        if(configuration!=null && peer.state==RemoteStore.State.ACTIVE)capabilities.add(PersonSnapshot.Capability.EDIT_CONTRACT);
        String sessionIdentity=runtime==null?"":runtime.optString("sessionIdentity","");
        if(peer.state==RemoteStore.State.ACTIVE && live==PersonSnapshot.Freshness.CURRENT && runtime!=null && runtime.optBoolean("endSessionSupported") && runtime.optBoolean("serviceActive") && sessionIdentity.matches("[0-9a-f]{64}"))capabilities.add(PersonSnapshot.Capability.END_SESSION);
        String applied=store.metadata(peer.identity,peer.generation,"applied_revision","0"),desired=store.metadata(peer.identity,peer.generation,"desired_revision","0");
        RemoteStore.Command latest=store.latestCommand(peer.identity,peer.generation);if(latest==null || !latest.pending())desired=applied;
        String service=runtime==null?context.getString(R.string.remote_unknown):context.getString(runtime.optBoolean("paused")?R.string.remote_paused:runtime.optBoolean("serviceActive")?R.string.remote_active:R.string.remote_inactive);
        if(runtime!=null && live!=PersonSnapshot.Freshness.CURRENT)service=context.getString(R.string.remote_last_observed,service);
        String currency=stats==null?"EUR":stats.optString("currency","EUR");
        long tribute=stats==null?0:stats.optLong("USD".equals(currency)?"assessedUSD":"assessedEUR");
        return new PersonSnapshot(id,peer.name,runtime==null?"":runtime.optString("device",""),configuration==null?context.getString(R.string.remote_unknown):configuration.getJSONObject("contract").getString("name"),
                service,connection,statistics,peer.lastSyncedAt,capabilities,configuration==null?Collections.emptyList():sections(peer,configuration),allowances(peer,configuration),currency,
                stats==null?0:Math.max(0,stats.optLong("protectedSeconds")),stats==null?0:Math.max(0,stats.optLong("activityEvents")),Math.max(0,tribute),stats==null?0:Math.max(0,stats.optInt("sessions")),
                live==PersonSnapshot.Freshness.CURRENT && runtime!=null && runtime.optBoolean("serviceActive"),new PersonSnapshot.SyncState(contract,statistics,live,desired,applied),sessionIdentity,store.additionOrder(peer.identity,peer.generation));
    }
    public PersonSnapshot localProjection(PersonSnapshot local) {
        if(!local.id.isLocal())throw new IllegalArgumentException("Not this device");
        for(RemoteStore.Peer peer:store.peers(false))if(peer.localRole==RemoteProtocol.Role.SUB && peer.state==RemoteStore.State.ACTIVE) {
            String name=context.getString(R.string.remote_managed_contract);
            try{JSONObject doc=feature.contracts.current(peer);if(doc!=null)name=doc.getJSONObject("contract").getString("name");}catch(Exception unavailable){/* Keep local runtime and authority when the contract mirror is unavailable. */}
            EnumSet<PersonSnapshot.Capability> caps=local.capabilities.isEmpty()?EnumSet.noneOf(PersonSnapshot.Capability.class):EnumSet.copyOf(local.capabilities);caps.remove(PersonSnapshot.Capability.EDIT_CONTRACT);
            return new PersonSnapshot(local.id,local.name,local.device,name,local.serviceLabel,local.connection,local.freshness,local.lastSyncedAt,caps,local.sections,local.allowances,local.currency,
                    local.protectedSeconds,local.activityEvents,local.tributeCents,local.sessions,local.serviceActive,local.sync);
        }
        try {
            JSONObject applied=feature.contracts.localApplied();
            String name=applied==null?context.getString(R.string.role_local_configuration):applied.getJSONObject("contract").getString("name");
            if(context.getSharedPreferences(com.subhub.app.settings.SettingsRepository.PREFERENCES_NAME,0).contains("remote_active_configuration"))
                return new PersonSnapshot(local.id,local.name,local.device,name,local.serviceLabel,local.connection,local.freshness,local.lastSyncedAt,local.capabilities,local.sections,local.allowances,local.currency,local.protectedSeconds,local.activityEvents,local.tributeCents,local.sessions,local.serviceActive,local.sync);
        }catch(Exception invalid){/* Unavailable metadata never changes local authority. */}
        return local;
    }
    private PersonSnapshot.Freshness freshness(RemoteStore.Peer peer,RemoteProtocol.Stream stream,boolean known,long lifetime) {
        if(!known)return PersonSnapshot.Freshness.UNKNOWN;
        long confirmed=Long.parseLong(store.metadata(peer.identity,peer.generation,"confirmed_"+stream.name(),"0")),age=System.currentTimeMillis()-confirmed;
        return peer.state==RemoteStore.State.ACTIVE && confirmed>0 && age>=0 && age<=lifetime?PersonSnapshot.Freshness.CURRENT:PersonSnapshot.Freshness.STALE;
    }
    private List<PersonSnapshot.Section> sections(RemoteStore.Peer peer,JSONObject configuration) throws Exception {
        JSONObject sections=configuration.getJSONObject("contract").getJSONObject("sections");List<PersonSnapshot.Section> result=new ArrayList<>();
        for(String group:new String[]{"censor","limits","wallet","atmosphere"}) {
            List<String> parts=new ArrayList<>();String[] names="atmosphere".equals(group)?new String[]{"subliminal","popup"}:new String[]{group};
            for(String section:names)for(ConfigurationSettingCatalog.Field field:ConfigurationSettingCatalog.fields(section)) {
                Object value=sections.getJSONObject(section).get(field.key);parts.add(context.getString(field.label)+": "+display(field,value));
            }
            int title="censor".equals(group)?R.string.sub_censor_title:"limits".equals(group)?R.string.sub_limits_title:"wallet".equals(group)?R.string.sub_wallet_title:R.string.atmosphere_title;
            result.add(new PersonSnapshot.Section(group,context.getString(title),"",String.join("\n",parts)));
        }
        JSONObject appDoc=configuration.getJSONObject("apps"),service=configuration.getJSONObject("service");
        List<String> names=new ArrayList<>();for(String id:RemoteConfiguration.selected(configuration)){
            JSONObject app=store.mirrored(peer.identity,peer.generation,RemoteProtocol.Stream.APP_CATALOG,id);names.add(app==null?id:app.optString("label",id));
        }
        String appSummary=appDoc.getBoolean("all")?context.getString(R.string.remote_all_apps):context.getString(R.string.remote_app_count,names.size());
        result.add(new PersonSnapshot.Section("apps",context.getString(R.string.remote_apps),appSummary,String.join("\n",names)));
        long duration=service.getLong("duration");String rendered;
        if(duration==-1)rendered=context.getResources().getStringArray(R.array.remote_duration_choices)[0];
        else if(duration==-2)rendered=(service.getLong("minimum")/60_000)+"–"+(service.getLong("maximum")/60_000)+" "+context.getString(R.string.remote_minutes);
        else rendered=(duration/60_000)+" "+context.getString(R.string.remote_minutes);
        result.add(new PersonSnapshot.Section("duration",context.getString(R.string.remote_duration),rendered,""));
        return result;
    }
    private List<PersonSnapshot.Allowance> allowances(RemoteStore.Peer peer,JSONObject configuration) throws Exception {
        if(configuration==null)return Collections.emptyList();JSONObject limits=configuration.getJSONObject("contract").getJSONObject("sections").getJSONObject("limits");
        if(!limits.optBoolean("app_timer_per_app_enabled") && !limits.optBoolean("app_timer_total_enabled"))return Collections.emptyList();
        JSONObject usage=new JSONObject(),apps=configuration.getJSONObject("apps"),budgets=apps.getJSONObject("allowances");
        List<PersonSnapshot.Allowance> result=new ArrayList<>();Set<String> packages=RemoteConfiguration.selected(configuration);
        if(apps.getBoolean("all"))for(RemoteProtocol.Change row:store.mirror(peer.identity,peer.generation,RemoteProtocol.Stream.APP_CATALOG))if(!row.deleted)packages.add(row.key);
        if(limits.optBoolean("app_timer_per_app_enabled"))for(String name:packages) {
            JSONObject entry=store.mirrored(peer.identity,peer.generation,RemoteProtocol.Stream.APP_CATALOG,name);
            result.add(new PersonSnapshot.Allowance(name,entry==null?name:entry.optString("label",name),usage.has(name)?knownUsage(usage,name):-1,budgets.optInt(name,limits.optInt("app_timer_per_app_minutes",30))));
        }
        if(limits.optBoolean("app_timer_total_enabled"))result.add(new PersonSnapshot.Allowance("",context.getString(R.string.role_shared_allowance),knownUsage(usage,""),limits.optInt("app_timer_total_minutes",120)));
        java.text.Collator order=java.text.Collator.getInstance(context.getResources().getConfiguration().getLocales().get(0));
        result.sort((a,b)->{int compared=order.compare(a.label,b.label);return compared!=0?compared:a.packageName.compareTo(b.packageName);});
        return result;
    }
    private static long knownUsage(JSONObject values,String name){try{long value=RemoteProtocol.integer(values,name);return value<0?-1:value;}catch(Exception invalid){return -1;}}
    private String display(ConfigurationSettingCatalog.Field field,Object value) {
        if(value instanceof Boolean)return context.getString((Boolean)value?R.string.role_enabled:R.string.role_disabled);
        if(value instanceof JSONArray){JSONArray rows=(JSONArray)value;List<String> items=new ArrayList<>();for(int i=0;i<rows.length();i++){String item=rows.optString(i);items.add(field.choices.isEmpty()?item:context.getString(ConfigurationSettingCatalog.choiceLabel(field,item)));}return String.join(", ",items);}
        if(field.kind==ConfigurationSettingCatalog.Kind.CHOICE)return context.getString(ConfigurationSettingCatalog.choiceLabel(field,String.valueOf(value)));
        return field.displayText(value);
    }
}
