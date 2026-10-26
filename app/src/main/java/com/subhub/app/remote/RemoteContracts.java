package com.subhub.app.remote;

import android.content.Context;
import android.content.SharedPreferences;
import com.subhub.app.appmode.AppModeManager;
import com.subhub.app.appmode.AppTimerManager;
import com.subhub.app.capture.CustomImageManager;
import com.subhub.app.configuration.*;
import com.subhub.app.popup.PopupStormSettings;
import com.subhub.app.settings.SettingsRepository;
import org.json.*;
import java.io.*;
import java.util.*;

/** Profile configuration metadata and content-addressed artwork are scoped to one relationship. */
public final class RemoteContracts {
    private final Context context;
    private final RemoteStore store;
    private final File root;
    private final RemoteConfigurationWriter writer;
    public RemoteContracts(Context context,RemoteStore store,RemoteConfigurationWriter writer) {
        this.context=context.getApplicationContext();this.store=store;this.writer=writer;
        root=new File(context.getNoBackupFilesDir(),"remote-assets");
    }
    public RemoteAssets assets(RemoteStore.Peer peer) throws IOException {
        RemoteProtocol.peerId(peer.identity);RemoteProtocol.identifier(peer.generation);
        return new RemoteAssets(new File(root,peer.identity+"/"+peer.generation));
    }

    public void prepare(RemoteStore.Peer peer) throws Exception {
        if(peer.localRole==RemoteProtocol.Role.DOM)return;
        if(store.metadata(peer.identity,peer.generation,"initial_configuration","").isEmpty()) {
            JSONObject current=capture(peer);
            store.putMetadata(peer.identity,peer.generation,"initial_configuration",RemoteProtocol.canonical(current));
        }
        String applied=store.metadata(peer.identity,peer.generation,"applied_configuration","");
        JSONObject current=new JSONObject(applied.isEmpty()?store.metadata(peer.identity,peer.generation,"initial_configuration",""):applied);
        store.publish(peer.identity,peer.generation,RemoteProtocol.Stream.BASELINE,"active",current,false,System.currentTimeMillis());
        process(peer);writer.prunePrepared();
    }

    public synchronized JSONObject capture(RemoteStore.Peer peer) throws Exception {
        if(!LegacyPackMigration.isReady(context))throw new IOException("Configuration recovery is incomplete");
        JSONObject sections=new JSONObject();
        for(String section:ConfigurationSchema.SECTIONS)sections.put(section,ConfigurationSchema.captureSection(context,section));
        SharedPreferences identity=context.getSharedPreferences("profile_configuration_identity",0);
        String id=identity.getString("id","");String origin=identity.getString("origin","");
        if(id.isEmpty()||origin.isEmpty()) {
            id=UUID.randomUUID().toString();origin=UUID.randomUUID().toString();
            if(!identity.edit().putString("id",id).putString("origin",origin).commit())throw new IOException("Configuration identity could not be saved");
        }
        long now=System.currentTimeMillis();
        long created=identity.getLong("createdAt",0);
        if(created<=0){created=now;if(!identity.edit().putLong("createdAt",created).commit())throw new IOException("Configuration date could not be saved");}
        JSONObject metadata=RemoteConfiguration.currentMetadata(id,origin,context.getString(com.subhub.app.R.string.role_local_configuration),created,now);
        SharedPreferences main=context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME,0);
        JSONArray refs=new JSONArray();RemoteAssets blobs=assets(peer);int index=0;
        for(File image:new CustomImageManager(context).enabledConfigurationFiles()) {
            if(image.length()>RemoteAssets.MAX_ASSET_BYTES)throw new IOException("Artwork exceeds the transfer limit");
            String hash=RemoteAssets.digest(image);try(InputStream input=new FileInputStream(image)){ingest(blobs,hash,image.length(),input);}
            refs.put(reference("assets/censor/image-"+(index++)+".png",hash,image.length()));
        }
        index=0;
        for(String source:com.subhub.app.popup.PopupContractAssets.selected(context,512-refs.length())) {
            File temporary=File.createTempFile("peer-artwork-",".part",context.getCacheDir());
            try {
                try(InputStream input=com.subhub.app.popup.PopupContractAssets.open(context,source);OutputStream output=new FileOutputStream(temporary)) {
                    byte[] buffer=new byte[8192];long length=0;int count;while((count=input.read(buffer))!=-1){length+=count;if(length>RemoteAssets.MAX_ASSET_BYTES)throw new IOException("Artwork exceeds the transfer limit");output.write(buffer,0,count);}
                }
                String hash=RemoteAssets.digest(temporary);try(InputStream input=new FileInputStream(temporary)){ingest(blobs,hash,temporary.length(),input);}
                refs.put(reference("assets/popup/image-"+(index++)+".png",hash,temporary.length()));
            } finally{temporary.delete();}
        }
        AppModeManager apps=new AppModeManager(context);Set<String> selected=apps.getSelectedPackages();JSONObject allowances=new JSONObject();
        for(Map.Entry<String,Integer> allowance:new AppTimerManager(context).loadAllowances(selected).entrySet())allowances.put(allowance.getKey(),allowance.getValue());
        JSONObject appDoc=new JSONObject().put("all",apps.isAllApps()).put("packages",new JSONArray(new TreeSet<>(selected))).put("allowances",allowances);
        SharedPreferences duration=context.getSharedPreferences(RemoteConfiguration.DURATION_PREFS,0);
        JSONObject service=new JSONObject().put("duration",duration.getLong("duration",-1)).put("minimum",duration.getLong("minimum",3_600_000))
                .put("maximum",duration.getLong("maximum",86_400_000)).put("hidden",duration.getBoolean("hidden",false));
        JSONObject remembered=localApplied();
        if(remembered!=null) {
            JSONObject previous=remembered.getJSONObject("contract");
            for(String key:new String[]{"id","origin","name","author","description","version","minimumAppVersion","createdAt"})metadata.put(key,previous.get(key));
        }
        return RemoteConfiguration.active(RemoteConfiguration.fromSections(metadata,sections,refs,appDoc,service));
    }

    /** Retained settings remain the active contract after unpairing until a local edit replaces them. */
    public JSONObject localApplied() throws Exception {
        SharedPreferences main=context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME,0);
        String encoded=main.getString("remote_active_configuration","");if(encoded.isEmpty())return null;
        JSONObject doc=RemoteConfiguration.active(new JSONObject(encoded));
        JSONObject sections=doc.getJSONObject("contract").getJSONObject("sections");
        for(String section:ConfigurationSchema.SECTIONS)if(!sameSection(section,ConfigurationSchema.captureSection(context,section),sections.getJSONObject(section)))return null;
        JSONObject apps=doc.getJSONObject("apps");
        if(apps.getBoolean("all")!=main.getBoolean(AppModeManager.KEY_ALL_APPS,false) || !RemoteConfiguration.selected(doc).equals(main.getStringSet(AppModeManager.KEY_INCLUDED_PACKAGES,Collections.emptySet())))return null;
        JSONObject budgets=apps.getJSONObject("allowances");Iterator<String> names=budgets.keys();while(names.hasNext()){String name=names.next();if(budgets.getInt(name)!=main.getInt("app_timer_allowance_minutes:"+name,30))return null;}
        JSONObject duration=doc.getJSONObject("service");SharedPreferences saved=context.getSharedPreferences(RemoteConfiguration.DURATION_PREFS,0);
        for(String key:new String[]{"duration","minimum","maximum"})if(duration.getLong(key)!=saved.getLong(key,Long.MIN_VALUE))return null;
        if(duration.getBoolean("hidden")!=saved.getBoolean("hidden",false))return null;
        String command=RemoteProtocol.identifier(main.getString("remote_active_command",""));
        File directory=new File(context.getNoBackupFilesDir(),"remote-configuration/assets/"+command);
        JSONArray assets=doc.getJSONObject("contract").getJSONArray("assets");boolean censor=false,popup=false;
        for(int i=0;i<assets.length();i++){String path=assets.getJSONObject(i).getString("path");censor|=path.startsWith("assets/censor/");popup|=path.startsWith("assets/popup/");}
        if(!Objects.equals(main.getString(CustomImageManager.PACK_DIR_KEY,null),censor?new File(directory,"censor").getAbsolutePath():null)
            || !Objects.equals(main.getString(PopupStormSettings.K_PACK_DIR,null),popup?new File(directory,"popup").getAbsolutePath():null)
            || !"[]".equals(main.getString(CustomImageManager.PREFS_KEY,"[]")) || !main.getStringSet(PopupStormSettings.K_FOLDERS,Collections.emptySet()).isEmpty())return null;
        return doc;
    }

    private static boolean sameSection(String section,JSONObject left,JSONObject right)throws JSONException {
        for(ConfigurationSettingCatalog.Field field:ConfigurationSettingCatalog.fields(section)) {
            Object a=field.normalize(left.get(field.key)),b=field.normalize(right.get(field.key));
            if(a instanceof JSONArray && b instanceof JSONArray) {
                Set<String> x=new TreeSet<>(),y=new TreeSet<>();JSONArray aa=(JSONArray)a,bb=(JSONArray)b;
                for(int i=0;i<aa.length();i++)x.add(aa.getString(i));for(int i=0;i<bb.length();i++)y.add(bb.getString(i));if(!x.equals(y))return false;
            }else if(!a.equals(b))return false;
        }
        return true;
    }

    public void process(RemoteStore.Peer peer) throws Exception {
        if(peer.localRole==RemoteProtocol.Role.DOM) {
            for(RemoteProtocol.Change row:store.mirror(peer.identity,peer.generation,RemoteProtocol.Stream.COMMAND_RESULT))if(!row.deleted)
                store.acceptResult(peer.identity,peer.generation,new JSONObject(row.body));
            return;
        }
        JSONObject desired=store.mirrored(peer.identity,peer.generation,RemoteProtocol.Stream.CONFIGURATION,"desired");if(desired==null)return;
        RemoteStore.Command command=store.stageCommand(peer.identity,peer.generation,desired);
        if(!command.pending())return;
        try {writer.apply(peer,command,assets(peer),com.subhub.app.settings.InstalledAppCatalog.packageNames(context));}
        catch(IllegalArgumentException|JSONException invalid) {
            store.commandResult(peer.identity,peer.generation,command,RemoteStore.CommandState.REJECTED,null,"Invalid contract configuration");
        }
    }

    public List<RemoteSession.AssetNeed> missing(RemoteStore.Peer peer) throws Exception {
        List<JSONObject> documents=new ArrayList<>();
        if(peer.localRole==RemoteProtocol.Role.DOM) {
            JSONObject active=store.mirrored(peer.identity,peer.generation,RemoteProtocol.Stream.BASELINE,"active");if(active!=null)documents.add(active);
        } else {
            JSONObject desired=store.mirrored(peer.identity,peer.generation,RemoteProtocol.Stream.CONFIGURATION,"desired");
            if(desired!=null)documents.add(desired.getJSONObject("configuration"));
        }
        Map<String,Long> wanted=new LinkedHashMap<>();
        for(JSONObject raw:documents) {
            JSONObject doc;
            try{doc=RemoteConfiguration.validate(raw);}catch(IllegalArgumentException|JSONException invalid){continue;}
            JSONArray refs=doc.getJSONObject("contract").getJSONArray("assets");
            for(int i=0;i<refs.length();i++) {
                JSONObject ref=refs.getJSONObject(i);Long old=wanted.put(ref.getString("hash"),ref.getLong("length"));
                if(old!=null && old.longValue()!=ref.getLong("length"))throw new IOException("Conflicting contract assets");
            }
        }
        RemoteAssets blobs=assets(peer);List<RemoteSession.AssetNeed> result=new ArrayList<>();
        for(Map.Entry<String,Long> ref:wanted.entrySet()) {
            blobs.reserve(ref.getKey(),ref.getValue());
            if(blobs.offset(ref.getKey())==ref.getValue())blobs.finish(ref.getKey());
            if(!blobs.isComplete(ref.getKey()))result.add(new RemoteSession.AssetNeed(ref.getKey(),ref.getValue(),blobs.offset(ref.getKey())));
        }
        return result;
    }

    public JSONObject current(RemoteStore.Peer peer) throws Exception {
        if(peer.localRole==RemoteProtocol.Role.SUB) {
            String raw=store.metadata(peer.identity,peer.generation,"applied_configuration",store.metadata(peer.identity,peer.generation,"initial_configuration",""));
            return raw.isEmpty()?null:RemoteConfiguration.active(new JSONObject(raw));
        }
        JSONObject baseline=store.mirrored(peer.identity,peer.generation,RemoteProtocol.Stream.BASELINE,"active");
        return baseline==null?null:RemoteConfiguration.active(baseline);
    }

    public static void ingest(RemoteAssets blobs,String hash,long length,InputStream input) throws IOException {
        blobs.reserve(hash,length);if(blobs.isComplete(hash)){blobs.finish(hash);return;}
        long offset=blobs.offset(hash),skipped=0;byte[] buffer=new byte[RemoteAssets.CHUNK_BYTES];
        while(skipped<offset){int count=input.read(buffer,0,(int)Math.min(buffer.length,offset-skipped));if(count<0)throw new IOException("Asset changed");skipped+=count;}
        while(offset<length){int count=input.read(buffer,0,(int)Math.min(buffer.length,length-offset));if(count<0)throw new IOException("Incomplete asset");offset=blobs.append(hash,offset,count==buffer.length?buffer:Arrays.copyOf(buffer,count));}
        if(input.read()!=-1)throw new IOException("Asset changed");blobs.finish(hash);
    }
    private static JSONObject reference(String path,String hash,long length) throws JSONException {return new JSONObject().put("path",path).put("hash",hash).put("length",length);}
}
