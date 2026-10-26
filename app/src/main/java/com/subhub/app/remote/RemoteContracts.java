package com.subhub.app.remote;

import android.content.Context;
import android.content.SharedPreferences;
import com.subhub.app.appmode.AppModeManager;
import com.subhub.app.appmode.AppTimerManager;
import com.subhub.app.capture.CustomImageManager;
import com.subhub.app.pack.*;
import com.subhub.app.popup.PopupStormSettings;
import com.subhub.app.settings.SettingsRepository;
import org.json.*;
import java.io.*;
import java.util.*;

/** Portable contract metadata and content-addressed artwork are scoped to one relationship. */
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
        if(!"true".equals(store.metadata(peer.identity,peer.generation,"library_imported",""))) {
            SubHubPackManager manager=new SubHubPackManager(context);
            for(SubHubPackManager.Record record:manager.listLibrary()) {
                try(SubHubPackArchive.Verified archive=SubHubPackArchive.openVerified(manager.libraryArchive(record.pack.getId()))) {
                    JSONArray refs=new JSONArray();RemoteAssets blobs=assets(peer);
                    for(Map.Entry<String,SubHubPackArchive.Asset> entry:archive.assets.entrySet()) {
                        SubHubPackArchive.Asset value=entry.getValue();try(InputStream input=archive.openAsset(entry.getKey())){ingest(blobs,value.hash,value.length,input);}
                        refs.put(reference(entry.getKey(),value.hash,value.length));
                    }
                    JSONObject doc=RemoteConfiguration.fromPortable(archive.pack,refs,current.getJSONObject("apps"),current.getJSONObject("service"));
                    store.publish(peer.identity,peer.generation,RemoteProtocol.Stream.BASELINE,"saved:"+archive.pack.getId(),doc,false,System.currentTimeMillis());
                } catch(Exception unavailable) {
                    store.putMetadata(peer.identity,peer.generation,"contract_error","A saved contract could not be shared");
                }
            }
            store.putMetadata(peer.identity,peer.generation,"library_imported","true");
        }
        process(peer);writer.prunePrepared();
    }

    public JSONObject capture(RemoteStore.Peer peer) throws Exception {
        SubHubPackManager manager=new SubHubPackManager(context);SubHubPack pack=manager.createBlank();
        for(String section:SubHubPackSchema.SECTIONS)pack.setSection(section,manager.captureSection(section));
        String active=manager.activePackId();
        if(active!=null)for(SubHubPackManager.Record record:manager.listLibrary())if(active.equals(record.pack.getId())) {
            pack.setMetadata(record.pack.getName(),record.pack.getAuthor(),record.pack.getDescription(),record.pack.getPackVersion());break;
        }
        SharedPreferences main=context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME,0);
        JSONArray refs=new JSONArray();RemoteAssets blobs=assets(peer);int index=0;
        for(File image:new CustomImageManager(context).enabledFilesForPackExport()) {
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
            JSONObject metadata=remembered.getJSONObject("contract");pack.setMetadata(metadata.getString("name"),metadata.getString("author"),metadata.getString("description"),metadata.getString("version"));
        }
        return RemoteConfiguration.active(RemoteConfiguration.fromPortable(pack,refs,appDoc,service));
    }

    /** Retained settings remain the active contract after unpairing until a local edit replaces them. */
    public JSONObject localApplied() throws Exception {
        SharedPreferences main=context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME,0);
        String encoded=main.getString("remote_active_configuration","");if(encoded.isEmpty())return null;
        JSONObject doc=RemoteConfiguration.active(new JSONObject(encoded));SubHubPackManager manager=new SubHubPackManager(context);
        JSONObject sections=doc.getJSONObject("contract").getJSONObject("sections");
        for(String section:SubHubPackSchema.SECTIONS)if(!sameSection(section,manager.captureSection(section),sections.getJSONObject(section)))return null;
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
        for(PackSettingCatalog.Field field:PackSettingCatalog.fields(section)) {
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
            for(RemoteProtocol.Change row:store.mirror(peer.identity,peer.generation,RemoteProtocol.Stream.BASELINE))if(!row.deleted)documents.add(new JSONObject(row.body));
        } else {
            for(RemoteProtocol.Change row:store.mirror(peer.identity,peer.generation,RemoteProtocol.Stream.CONTRACTS))if(!row.deleted)documents.add(new JSONObject(row.body));
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

    public Map<String,JSONObject> library(RemoteStore.Peer peer) throws Exception {
        Map<String,JSONObject> result=new LinkedHashMap<>();
        List<RemoteProtocol.Change> baseline=peer.localRole==RemoteProtocol.Role.DOM?store.mirror(peer.identity,peer.generation,RemoteProtocol.Stream.BASELINE):store.published(peer.identity,peer.generation,RemoteProtocol.Stream.BASELINE);
        for(RemoteProtocol.Change row:baseline)if(row.key.startsWith("saved:") && !row.deleted)result.put(row.key.substring(6),RemoteConfiguration.validate(new JSONObject(row.body)));
        List<RemoteProtocol.Change> changes=peer.localRole==RemoteProtocol.Role.DOM?store.published(peer.identity,peer.generation,RemoteProtocol.Stream.CONTRACTS):store.mirror(peer.identity,peer.generation,RemoteProtocol.Stream.CONTRACTS);
        for(RemoteProtocol.Change row:changes) {
            if(row.deleted)result.remove(row.key);else {JSONObject doc=RemoteConfiguration.validate(new JSONObject(row.body));if(!row.key.equals(doc.getJSONObject("contract").getString("id")))throw new SecurityException("Contract identity mismatch");result.put(row.key,doc);}
        }
        return result;
    }
    public void save(RemoteStore.Peer peer,JSONObject input) throws Exception {
        JSONObject doc=RemoteConfiguration.validate(input);String id=doc.getJSONObject("contract").getString("id");
        store.publish(peer.identity,peer.generation,RemoteProtocol.Stream.CONTRACTS,id,doc,false,System.currentTimeMillis());
    }
    public void delete(RemoteStore.Peer peer,String id) throws Exception {
        UUID.fromString(id);store.publish(peer.identity,peer.generation,RemoteProtocol.Stream.CONTRACTS,id,new JSONObject(),true,System.currentTimeMillis());
    }
    public JSONObject merge(JSONObject current,JSONObject saved) throws Exception {
        JSONObject result=new JSONObject(RemoteProtocol.canonical(RemoteConfiguration.active(current))),choice=RemoteConfiguration.validate(saved).getJSONObject("contract");
        JSONObject sections=result.getJSONObject("contract").getJSONObject("sections"),incoming=choice.getJSONObject("sections");
        Iterator<String> names=incoming.keys();while(names.hasNext()){String section=names.next();JSONObject values=sections.getJSONObject(section),replacements=incoming.getJSONObject(section);Iterator<String> keys=replacements.keys();while(keys.hasNext()){String key=keys.next();values.put(key,replacements.get(key));}}
        JSONArray assets=new JSONArray(),old=result.getJSONObject("contract").getJSONArray("assets"),added=choice.getJSONArray("assets");
        for(int i=0;i<old.length();i++){String path=old.getJSONObject(i).getString("path");if(!path.startsWith("assets/cover/") && !(incoming.has("censor") && path.startsWith("assets/censor/")) && !(incoming.has("popup") && path.startsWith("assets/popup/")))assets.put(old.getJSONObject(i));}
        for(int i=0;i<added.length();i++)assets.put(added.getJSONObject(i));
        JSONObject metadata=new JSONObject(choice.toString()).put("sections",sections).put("assets",assets);result.put("contract",metadata);
        return RemoteConfiguration.active(result);
    }
    public void importArchive(RemoteStore.Peer peer,File file,JSONObject template) throws Exception {
        try(SubHubPackArchive.Verified archive=SubHubPackArchive.openVerified(file)) {
            RemoteAssets blobs=assets(peer);JSONArray refs=new JSONArray();
            for(Map.Entry<String,SubHubPackArchive.Asset> entry:archive.assets.entrySet()) {
                try(InputStream input=archive.openAsset(entry.getKey())){ingest(blobs,entry.getValue().hash,entry.getValue().length,input);}
                refs.put(reference(entry.getKey(),entry.getValue().hash,entry.getValue().length));
            }
            save(peer,RemoteConfiguration.fromPortable(archive.pack,refs,template.getJSONObject("apps"),template.getJSONObject("service")));
        }
    }
    public void export(RemoteStore.Peer peer,JSONObject input,OutputStream output) throws Exception {
        JSONObject doc=RemoteConfiguration.validate(input);Map<String,File> files=new LinkedHashMap<>();RemoteAssets blobs=assets(peer);JSONArray refs=doc.getJSONObject("contract").getJSONArray("assets");
        for(int i=0;i<refs.length();i++){JSONObject ref=refs.getJSONObject(i);files.put(ref.getString("path"),blobs.finish(ref.getString("hash")));}
        SubHubPackArchive.writeStreaming(RemoteConfiguration.portable(doc),files,output);
    }
    @FunctionalInterface public interface ExportDestination { OutputStream open() throws IOException; }
    public void exportAuthorized(RemoteStore.Peer peer,JSONObject input,ExportDestination destination)throws Exception {
        synchronized(store){requireExportAuthority(peer);}
        File prepared=File.createTempFile("peer-export-",".sub",context.getCacheDir());
        try {
            try(OutputStream output=new FileOutputStream(prepared)){export(peer,input,output);}
            OutputStream selected;
            // Destination opening is the admission point. Preparation and copying never hold this monitor.
            synchronized(store){requireExportAuthority(peer);selected=destination.open();}
            if(selected==null)throw new IOException("Export unavailable");
            try(OutputStream output=selected;InputStream source=new FileInputStream(prepared)) {
                byte[] buffer=new byte[8192];int count;while((count=source.read(buffer))!=-1)output.write(buffer,0,count);
            }
        } finally{prepared.delete();}
    }
    private void requireExportAuthority(RemoteStore.Peer selected) {
        RemoteStore.Peer peer=store.peer(selected.identity,selected.generation);
        if(peer==null||peer.localRole!=RemoteProtocol.Role.DOM||peer.state!=RemoteStore.State.ACTIVE
                ||!com.subhub.app.security.ControllerPinManager.isDomModeActive()
                ||!com.subhub.app.security.ControllerPinManager.hasCredentials(context))throw new SecurityException("Pairing is not authorized");
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
