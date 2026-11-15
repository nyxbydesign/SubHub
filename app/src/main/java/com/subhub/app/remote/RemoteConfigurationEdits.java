package com.subhub.app.remote;

import android.content.Context;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.security.SubFeaturePolicy;
import org.json.*;
import java.util.*;

/** Durable latest-value editor queue. Commands themselves remain immutable until acknowledged. */
public final class RemoteConfigurationEdits {
    private static final String DRAFT="configuration_edit";
    private final Context context;
    private final RemoteStore store;
    RemoteConfigurationEdits(Context context,RemoteStore store){this.context=context;this.store=store;}

    public JSONObject editing(RemoteStore.Peer peer)throws Exception {
        synchronized(store){
            String draft=store.metadata(peer.identity,peer.generation,DRAFT,"");
            if(!draft.isEmpty())return RemoteConfiguration.active(new JSONObject(draft));
            RemoteStore.Command command=store.latestCommand(peer.identity,peer.generation);
            if(command!=null && !reset(command,peer) && (command.pending()||command.state==RemoteStore.CommandState.APPLIED))
                return RemoteConfiguration.active(new JSONObject(command.body).getJSONObject("configuration"));
            JSONObject baseline=store.mirrored(peer.identity,peer.generation,RemoteProtocol.Stream.BASELINE,"active");
            if(baseline==null)throw new IllegalStateException("Source configuration unavailable");
            return RemoteConfiguration.active(baseline);
        }
    }

    public JSONObject save(RemoteStore.Peer peer,JSONObject base,JSONObject edited)throws Exception {
        return save(peer,base,edited,ControllerPinManager.domWorkspaceRevision());
    }

    public JSONObject save(RemoteStore.Peer peer,JSONObject base,JSONObject edited,long expectedWorkspaceRevision)throws Exception {
        return store.authorityAccess(()->{
            synchronized(SubFeaturePolicy.LOCK){synchronized(store){synchronized(com.subhub.app.appmode.RuntimeSessionIdentity.LOCK){
                if(expectedWorkspaceRevision!=ControllerPinManager.domWorkspaceRevision())throw new SecurityException("Dom workspace changed");
                store.requireConfigurationEditor(peer.identity,peer.generation);
                if(!ControllerPinManager.isDomWorkspaceUnlocked()
                        || !(ControllerPinManager.isLocalDomWorkspaceUnlocked()||ControllerPinManager.hasCredentials(context)))
                    throw new SecurityException("Dom workspace is locked");
                JSONObject current=editing(peer);
                if(current==null)throw new IllegalStateException("Source configuration unavailable");
                JSONObject merged=RemoteConfiguration.active(merge(RemoteConfiguration.active(base),RemoteConfiguration.active(edited),current));
                requireConsent(peer,current,merged);
                if(same(current,merged))return current;
                store.saveConfigurationEdit(peer.identity,peer.generation,RemoteProtocol.canonical(merged));
                // A persisted edit must survive even if publication cannot complete on this attempt.
                try{drain(peer);}catch(Exception unavailable){store.putMetadata(peer.identity,peer.generation,"configuration_edit_failure","Changes saved; automatic sync is blocked");}
                return merged;
            }}}
        });
    }

    public String failure(RemoteStore.Peer peer){
        synchronized(store){
            RemoteStore.Command command=store.latestCommand(peer.identity,peer.generation);
            if(command!=null && !command.pending() && command.state!=RemoteStore.CommandState.APPLIED && !reset(command,peer))
                return "Automatic sync stopped: "+command.state.name().toLowerCase(Locale.ROOT);
            return store.metadata(peer.identity,peer.generation,"configuration_edit_failure","");
        }
    }

    private boolean reset(RemoteStore.Command command,RemoteStore.Peer peer){
        return command!=null && !command.pending() && command.id.equals(store.metadata(peer.identity,peer.generation,"configuration_edit_reset",""));
    }

    JSONObject reload(RemoteStore.Peer peer)throws Exception {
        return reload(peer,ControllerPinManager.domWorkspaceRevision());
    }

    JSONObject reload(RemoteStore.Peer peer,long expectedWorkspaceRevision)throws Exception {
        return store.authorityAccess(()->{
            synchronized(SubFeaturePolicy.LOCK){synchronized(store){synchronized(com.subhub.app.appmode.RuntimeSessionIdentity.LOCK){
                if(expectedWorkspaceRevision!=ControllerPinManager.domWorkspaceRevision())throw new SecurityException("Dom workspace changed");
                store.requireConfigurationEditor(peer.identity,peer.generation);
                if(!ControllerPinManager.isDomWorkspaceUnlocked()
                        || !(ControllerPinManager.isLocalDomWorkspaceUnlocked()||ControllerPinManager.hasCredentials(context)))
                    throw new SecurityException("Dom workspace is locked");
                JSONObject baseline=store.mirrored(peer.identity,peer.generation,RemoteProtocol.Stream.BASELINE,"active");
                if(baseline==null)throw new IllegalStateException("Source configuration unavailable");
                baseline=RemoteConfiguration.active(baseline);
                store.resetConfigurationEdits(peer.identity,peer.generation);
                return baseline;
            }}}
        });
    }

    void drain(RemoteStore.Peer peer)throws Exception {
        synchronized(store){
            String raw=store.metadata(peer.identity,peer.generation,DRAFT,"");if(raw.isEmpty())return;
            RemoteStore.Command command=store.latestCommand(peer.identity,peer.generation);
            if(command!=null && command.state!=RemoteStore.CommandState.APPLIED && !reset(command,peer))return;
            JSONObject desired=RemoteConfiguration.active(new JSONObject(raw));
            JSONObject previous=command==null||reset(command,peer)?store.mirrored(peer.identity,peer.generation,RemoteProtocol.Stream.BASELINE,"active"):
                    new JSONObject(command.body).getJSONObject("configuration");
            if(previous==null)throw new IllegalStateException("Source configuration unavailable");
            previous=RemoteConfiguration.active(previous);
            requireConsent(peer,previous,desired);
            store.publishConfigurationEdit(peer.identity,peer.generation,desired,same(previous,desired));
        }
    }

    private void requireConsent(RemoteStore.Peer peer,JSONObject before,JSONObject after)throws Exception {
        JSONObject a=before.getJSONObject("contract"),b=after.getJSONObject("contract");
        if(!same(a.getJSONObject("sections").optJSONObject(GagContractFields.SECTION),
                b.getJSONObject("sections").optJSONObject(GagContractFields.SECTION))
                && !GagContractFields.supported(store,peer))
            throw new SecurityException("Update the paired app to configure Gag");
        for(String feature:SubFeaturePolicy.FEATURES){
            if(RemoteFeaturePolicy.remoteAllowed(store,peer,feature))continue;
            boolean changed=!same(a.getJSONObject("sections").getJSONObject(feature),b.getJSONObject("sections").getJSONObject(feature));
            if("censor".equals(feature)||"subliminal".equals(feature)){
                String key="module_"+feature+"_enabled";
                changed|=!same(a.getJSONObject("sections").getJSONObject("modules").get(key),b.getJSONObject("sections").getJSONObject("modules").get(key));
            }
            if("limits".equals(feature))changed|=!same(before.getJSONObject("apps").getJSONObject("allowances"),after.getJSONObject("apps").getJSONObject("allowances"));
            if("censor".equals(feature)||"popup".equals(feature))changed|=!assetRefs(a,feature).equals(assetRefs(b,feature));
            if(changed)throw new SecurityException("Feature disabled by Sub");
        }
    }
    private static Set<String> assetRefs(JSONObject contract,String feature)throws Exception {
        Set<String> refs=new TreeSet<>();JSONArray assets=contract.getJSONArray("assets");
        for(int i=0;i<assets.length();i++){JSONObject ref=assets.getJSONObject(i);if(ref.getString("path").startsWith("assets/"+feature+"/"))refs.add(RemoteProtocol.canonical(ref));}
        return refs;
    }

    /** Merge only changed leaves; arrays are atomic so selection/removal cannot silently lose edits. */
    static JSONObject merge(JSONObject base,JSONObject edited,JSONObject current)throws JSONException {
        JSONObject result=new JSONObject(current.toString());Set<String> keys=new TreeSet<>();
        base.keys().forEachRemaining(keys::add);edited.keys().forEachRemaining(keys::add);
        for(String key:keys){
            Object old=base.opt(key),next=edited.opt(key),now=current.opt(key);
            if(same(old,next))continue;
            if(old instanceof JSONObject && next instanceof JSONObject && now instanceof JSONObject)
                result.put(key,merge((JSONObject)old,(JSONObject)next,(JSONObject)now));
            else{
                if(!same(old,now)&&!same(next,now))throw new IllegalStateException("Configuration changed; reload before saving");
                if(next==null)result.remove(key);else result.put(key,next);
            }
        }
        return result;
    }
    static boolean same(Object a,Object b)throws JSONException {
        if(a==null||b==null)return a==b;
        if(a instanceof JSONObject && b instanceof JSONObject)return RemoteProtocol.canonical((JSONObject)a).equals(RemoteProtocol.canonical((JSONObject)b));
        if(a instanceof JSONArray && b instanceof JSONArray)return RemoteProtocol.canonical(new JSONObject().put("value",a)).equals(RemoteProtocol.canonical(new JSONObject().put("value",b)));
        if(a instanceof Number && b instanceof Number)return a.toString().equals(b.toString());
        return a.equals(b);
    }
}
