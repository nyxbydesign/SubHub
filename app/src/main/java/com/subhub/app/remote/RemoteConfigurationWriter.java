package com.subhub.app.remote;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.AtomicFile;
import com.subhub.app.appmode.AppModeManager;
import com.subhub.app.capture.CustomImageManager;
import com.subhub.app.configuration.ConfigurationSettingCatalog;
import com.subhub.app.configuration.ConfigurationSchema;
import com.subhub.app.popup.PopupStormSettings;
import com.subhub.app.settings.SettingsRepository;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** A private write-ahead journal protects cross-store configuration commits and asset pointers. */
public final class RemoteConfigurationWriter {
    interface Committer { boolean commit(String store, SharedPreferences.Editor editor); }
    private final Context context;
    private final RemoteStore store;
    private final AtomicFile journal;
    private final File root;
    private final Committer committer;

    public RemoteConfigurationWriter(Context context,RemoteStore store) {
        this(context,store,(name,editor)->editor.commit(),new File(context.getNoBackupFilesDir(),"remote-configuration"));
    }
    RemoteConfigurationWriter(Context context,RemoteStore store,Committer committer,File root) {
        this.context=context.getApplicationContext();this.store=store;this.committer=committer;this.root=root;
        journal=new AtomicFile(new File(root,"journal.json"));
    }

    /** Must run before exposing local edits or starting another peer application. */
    public synchronized void recover() throws Exception {
        synchronized(store) {
            if(!journal.getBaseFile().exists() && !new File(journal.getBaseFile()+".bak").exists())return;
            JSONObject saved;
            try(InputStream input=journal.openRead()) {
                ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int count;
                while((count=input.read(buffer))!=-1) {
                    if(bytes.size()+count>2*1024*1024)throw new IOException("Configuration journal is too large");
                    bytes.write(buffer,0,count);
                }
                saved=new JSONObject(bytes.toString(StandardCharsets.UTF_8.name()));
            }
            RemoteStore.Command command=store.command(saved.getString("peer"),saved.getString("generation"),saved.getString("command"));
            if(command==null || command.state!=RemoteStore.CommandState.APPLIED)restore(saved.getJSONObject("before"));
            journal.delete();
        }
    }

    /** Returns false only while required content is still arriving. */
    public synchronized boolean apply(RemoteStore.Peer peer,RemoteStore.Command command,RemoteAssets assets,Set<String> selectable) throws Exception {
        if(!com.subhub.app.configuration.LegacyPackMigration.isReady(context))throw new IOException("Configuration recovery is pending");
        if(!command.pending())return true;
        JSONObject body=new JSONObject(command.body),doc=RemoteConfiguration.active(body.getJSONObject("configuration"));
        JSONArray refs=doc.getJSONObject("contract").getJSONArray("assets");
        for(int i=0;i<refs.length();i++) {
            JSONObject ref=refs.getJSONObject(i);assets.reserve(ref.getString("hash"),ref.getLong("length"));
            if(!assets.isComplete(ref.getString("hash")))return false;
        }
        // Asset I/O happens outside the authority lock, keeping local recovery controls responsive.
        File prepared=prepare(command.id,refs,assets);
        synchronized(store) {
            if(!com.subhub.app.configuration.LegacyPackMigration.isReady(context))throw new IOException("Configuration recovery is pending");
            recover();
            RemoteStore.Peer current=store.peer(peer.identity,peer.generation);
            if(current==null || current.state!=RemoteStore.State.ACTIVE || current.localRole!=RemoteProtocol.Role.SUB)
                throw new SecurityException("Pairing is no longer authorized");
            RemoteStore.Command latest=store.command(peer.identity,peer.generation,command.id);
            if(latest==null || !latest.pending())return true;
            long applied=Long.parseLong(store.metadata(peer.identity,peer.generation,"applied_revision","0"));
            if(body.getLong("base")!=applied || command.revision<=applied) {
                store.commandResult(peer.identity,peer.generation,command,RemoteStore.CommandState.CONFLICT,null,"Configuration changed; reload before saving");return true;
            }
            Set<String> previous=new LinkedHashSet<>(preferences(SettingsRepository.PREFERENCES_NAME)
                    .getStringSet(AppModeManager.KEY_INCLUDED_PACKAGES,Collections.emptySet()));
            for(String name:RemoteConfiguration.selected(doc))if(!previous.contains(name) && !selectable.contains(name)) {
                store.commandResult(peer.identity,peer.generation,command,RemoteStore.CommandState.REJECTED,null,"An app is unavailable; reload the app list");return true;
            }
            synchronized (com.subhub.app.security.SubFeaturePolicy.LOCK) {
            String prior = preferences(SettingsRepository.PREFERENCES_NAME).getString("remote_active_configuration",
                    store.metadata(peer.identity, peer.generation, "initial_configuration", ""));
            try {
                RemoteFeaturePolicy.requireAllowed(context, doc, prior.isEmpty() ? null : new JSONObject(prior));
            } catch (SecurityException denied) {
                store.commandResult(peer.identity,peer.generation,command,RemoteStore.CommandState.REJECTED,null,"Feature disabled by Sub");
                return true;
            }
            Map<String,Map<String,Object>> writes=writes(doc,prepared,selectable);
            JSONObject saved=new JSONObject().put("peer",peer.identity).put("generation",peer.generation).put("command",command.id).put("before",snapshot(writes));
            writeJournal(saved);
            try {
                store.markApplying(peer.identity,peer.generation,command.id);
                for(Map.Entry<String,Map<String,Object>> entry:writes.entrySet()) {
                    SharedPreferences.Editor editor=preferences(entry.getKey()).edit();
                    for(Map.Entry<String,Object> value:entry.getValue().entrySet())put(editor,value.getKey(),value.getValue());
                    if(!committer.commit(entry.getKey(),editor))throw new IOException("Could not save configuration");
                }
                store.commandResult(peer.identity,peer.generation,command,RemoteStore.CommandState.APPLIED,doc,"");
                journal.delete();return true;
            } catch(Exception failure) {
                // A database commit can succeed before a later observer fails. Recovery checks its receipt.
                recover();
                RemoteStore.Command result=store.command(peer.identity,peer.generation,command.id);
                if(result==null || result.state!=RemoteStore.CommandState.APPLIED)
                    store.commandResult(peer.identity,peer.generation,command,RemoteStore.CommandState.REJECTED,null,"Configuration could not be saved");
                return true;
            }
            }
        }
    }

    /** Committed live pointers retain their directories; abandoned prepared copies are disposable. */
    public synchronized void prunePrepared() throws IOException {
        Set<String> live=new HashSet<>();File parent=new File(root,"assets");
        synchronized(store) {
            if(journal.getBaseFile().exists() || new File(journal.getBaseFile()+".bak").exists())return;
            SharedPreferences main=preferences(SettingsRepository.PREFERENCES_NAME);
            for(String key:new String[]{CustomImageManager.PACK_DIR_KEY,PopupStormSettings.K_PACK_DIR}) {
                String path=main.getString(key,"");if(!path.isEmpty())live.add(new File(path).getParentFile().getCanonicalPath());
            }
        }
        File[] candidates=parent.listFiles();if(candidates==null)return;
        String boundary=parent.getCanonicalPath()+File.separator;
        for(File directory:candidates) {
            if(!directory.getCanonicalPath().startsWith(boundary) || live.contains(directory.getCanonicalPath()))continue;
            try(java.util.stream.Stream<java.nio.file.Path> paths=java.nio.file.Files.walk(directory.toPath())) {
                for(java.nio.file.Path path:paths.sorted(Comparator.reverseOrder()).toArray(java.nio.file.Path[]::new))java.nio.file.Files.deleteIfExists(path);
            }
        }
    }

    private File prepare(String id,JSONArray refs,RemoteAssets assets) throws Exception {
        RemoteProtocol.identifier(id);File directory=new File(root,"assets/"+id);
        if(!directory.mkdirs() && !directory.isDirectory())throw new IOException("Asset directory unavailable");
        for(int i=0;i<refs.length();i++) {
            JSONObject ref=refs.getJSONObject(i);String path=ref.getString("path"),hash=ref.getString("hash");
            File target=new File(directory,path.substring("assets/".length()));
            if(!target.getCanonicalPath().startsWith(directory.getCanonicalPath()+File.separator))throw new IOException("Invalid asset path");
            assets.finish(hash);
            if(target.isFile() && target.length()==ref.getLong("length") && hash.equals(RemoteAssets.digest(target)))continue;
            if(!target.getParentFile().mkdirs() && !target.getParentFile().isDirectory())throw new IOException("Asset directory unavailable");
            try(InputStream input=assets.open(hash);FileOutputStream output=new FileOutputStream(target)) {
                byte[] buffer=new byte[8192];int count;long copied=0;
                while((count=input.read(buffer))!=-1) {
                    copied+=count;if(copied>ref.getLong("length"))throw new IOException("Asset changed");output.write(buffer,0,count);
                }
                if(copied!=ref.getLong("length"))throw new IOException("Incomplete asset");output.getFD().sync();
            }
            if(!hash.equals(RemoteAssets.digest(target)))throw new IOException("Asset changed");
        }
        return directory;
    }

    private Map<String,Map<String,Object>> writes(JSONObject doc,File prepared,Set<String> selectable) throws Exception {
        Map<String,Map<String,Object>> result=new LinkedHashMap<>();JSONObject sections=doc.getJSONObject("contract").getJSONObject("sections");
        for(String section:ConfigurationSchema.SECTIONS) {
            Map<String,Object> values=result.computeIfAbsent(ConfigurationSchema.preferenceStore(section),unused->new LinkedHashMap<>());
            JSONObject raw=sections.getJSONObject(section);ConfigurationSettingCatalog.validateRelationships(raw);
            for(String key:ConfigurationSettingCatalog.keys(section)) {
                ConfigurationSettingCatalog.Field field=ConfigurationSettingCatalog.field(section,key);Object value=field.normalize(raw.get(key));
                switch(field.kind) {
                    case SELECTION: Set<String> set=new LinkedHashSet<>();JSONArray array=(JSONArray)value;for(int i=0;i<array.length();i++)set.add(array.getString(i));value=set;break;
                    case LONG:value=((Number)value).longValue();break;
                    case DECIMAL:case RATIO:value=((Number)value).floatValue();break;
                    case INTEGER:case MONEY:value=((Number)value).intValue();break;
                    default:break;
                }
                values.put(key,value);
            }
        }
        Map<String,Object> main=result.get(SettingsRepository.PREFERENCES_NAME);JSONObject apps=doc.getJSONObject("apps");
        main.put(AppModeManager.KEY_INCLUDED_PACKAGES,RemoteConfiguration.selected(doc));main.put(AppModeManager.KEY_ALL_APPS,apps.getBoolean("all"));
        main.put("app_include_all_packages_v1",new LinkedHashSet<>(selectable));
        main.put("app_include_revision_v1",preferences(SettingsRepository.PREFERENCES_NAME).getLong("app_include_revision_v1",0)+1);
        JSONObject allowances=apps.getJSONObject("allowances");Iterator<String> packages=allowances.keys();
        while(packages.hasNext()){String name=packages.next();main.put("app_timer_allowance_minutes:"+name,allowances.getInt(name));}
        JSONArray refs=doc.getJSONObject("contract").getJSONArray("assets");boolean censor=false,popup=false;
        for(int i=0;i<refs.length();i++){String path=refs.getJSONObject(i).getString("path");censor|=path.startsWith("assets/censor/");popup|=path.startsWith("assets/popup/");}
        main.put(CustomImageManager.PACK_DIR_KEY,censor?new File(prepared,"censor").getAbsolutePath():null);
        main.put(PopupStormSettings.K_PACK_DIR,popup?new File(prepared,"popup").getAbsolutePath():null);
        // Old user files stay available, but an empty remote image set must not activate them.
        main.put(CustomImageManager.PREFS_KEY,"[]");main.put(PopupStormSettings.K_FOLDERS,Collections.emptySet());
        main.put("remote_active_configuration",RemoteProtocol.canonical(doc));main.put("remote_active_command",prepared.getName());
        main.put(CustomImageManager.REVISION_KEY,System.currentTimeMillis());
        Map<String,Object> duration=new LinkedHashMap<>();JSONObject service=doc.getJSONObject("service");
        for(String key:new String[]{"duration","minimum","maximum"})duration.put(key,service.getLong(key));duration.put("hidden",service.getBoolean("hidden"));
        result.put(RemoteConfiguration.DURATION_PREFS,duration);return result;
    }

    private JSONObject snapshot(Map<String,Map<String,Object>> writes) throws Exception {
        JSONObject result=new JSONObject();
        for(Map.Entry<String,Map<String,Object>> entry:writes.entrySet()) {
            JSONObject values=new JSONObject();Map<String,?> all=preferences(entry.getKey()).getAll();
            for(String key:entry.getValue().keySet()) {
                JSONObject value=new JSONObject().put("present",all.containsKey(key));Object old=all.get(key);
                if(old!=null) {
                    String type=old instanceof Boolean?"boolean":old instanceof Integer?"int":old instanceof Long?"long":old instanceof Float?"float":old instanceof Set?"set":"string";
                    value.put("type",type).put("value",old instanceof Set?new JSONArray((Set<?>)old):old);
                }
                values.put(key,value);
            }
            result.put(entry.getKey(),values);
        }
        return result;
    }
    private void restore(JSONObject saved) throws Exception {
        Iterator<String> stores=saved.keys();
        while(stores.hasNext()) {
            String name=stores.next();SharedPreferences.Editor editor=preferences(name).edit();JSONObject values=saved.getJSONObject(name);Iterator<String> keys=values.keys();
            while(keys.hasNext()) {
                String key=keys.next();JSONObject value=values.getJSONObject(key);Object old=null;
                if(value.getBoolean("present"))switch(value.getString("type")) {
                    case "int":old=value.getInt("value");break;case "long":old=value.getLong("value");break;
                    case "float":old=(float)value.getDouble("value");break;case "boolean":old=value.getBoolean("value");break;
                    case "string":old=value.getString("value");break;
                    case "set":Set<String> set=new LinkedHashSet<>();JSONArray array=value.getJSONArray("value");for(int i=0;i<array.length();i++)set.add(array.getString(i));old=set;break;
                    default:throw new IOException("Invalid recovery value");
                }
                put(editor,key,old);
            }
            if(!editor.commit())throw new IOException("Configuration recovery is pending");
        }
    }
    private void writeJournal(JSONObject value) throws IOException {
        if(!root.mkdirs() && !root.isDirectory())throw new IOException("Configuration storage unavailable");
        FileOutputStream output=null;
        try {output=journal.startWrite();output.write(value.toString().getBytes(StandardCharsets.UTF_8));journal.finishWrite(output);}
        catch(IOException failure){if(output!=null)journal.failWrite(output);throw failure;}
    }
    @SuppressWarnings("unchecked") private static void put(SharedPreferences.Editor editor,String key,Object value) {
        if(value==null)editor.remove(key);else if(value instanceof Boolean)editor.putBoolean(key,(Boolean)value);
        else if(value instanceof Integer)editor.putInt(key,(Integer)value);else if(value instanceof Long)editor.putLong(key,(Long)value);
        else if(value instanceof Float)editor.putFloat(key,(Float)value);else if(value instanceof Set)editor.putStringSet(key,(Set<String>)value);
        else if(value instanceof String)editor.putString(key,(String)value);else throw new IllegalArgumentException("Unsupported preference value");
    }
    private SharedPreferences preferences(String name){return context.getSharedPreferences(name,Context.MODE_PRIVATE);}
}
