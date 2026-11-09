package com.subhub.app.configuration;

import android.content.*;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleEventObserver;
import com.subhub.app.R;
import com.subhub.app.appmode.AppModeManager;
import com.subhub.app.people.*;
import com.subhub.app.remote.*;
import com.subhub.app.security.*;
import com.subhub.app.settings.SettingsRepository;
import com.subhub.app.util.PrimaryHeader;
import org.json.*;
import java.io.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** One native feature editor, backed by live local settings or an identity-bound remote draft. */
public final class FeatureEditorSession {
    private static final String TOKEN="feature_editor_token", DOCUMENT="feature_editor_document", BASE="feature_editor_base";
    private static final Map<AppCompatActivity,FeatureEditorSession> PAGES=Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<String,FeatureEditorSession> DRAFTS=new HashMap<>();
    private final RemoteFeature feature;
    public final RemoteStore.Peer peer;
    private final PersonId person;
    private final String token;
    private final DraftContext context;
    private final Map<String,DraftPreferences> stores=new LinkedHashMap<>();
    private final Set<String> disabledArtwork=new HashSet<>();
    private JSONObject document,editBase;
    private final Map<AppCompatActivity,EditorBinding> bindings=new IdentityHashMap<>();
    private final android.os.Handler main=new android.os.Handler(android.os.Looper.getMainLooper());
    private boolean flushing,invalidated,restorePending,userDirty;
    private long desired,applied;
    private int pages;


    private FeatureEditorSession(AppCompatActivity activity,Bundle saved) throws Exception {
        Context app=activity.getApplicationContext();
        feature=RemoteFeature.get(app);
        person=PersonRoutes.requireId(activity.getIntent());
        peer=new RemotePersonRepository(app,feature).peer(person);
        token=saved!=null?saved.getString(TOKEN,UUID.randomUUID().toString()):activity.getIntent().getStringExtra(TOKEN);
        String id=token==null?UUID.randomUUID().toString():token;
        context=new DraftContext(app,this,id);
        requireAuthority();
        String raw=saved==null?activity.getIntent().getStringExtra(DOCUMENT):saved.getString(DOCUMENT);
        document=raw==null?feature.contracts.editing(peer):RemoteConfiguration.active(new JSONObject(raw));
        if(document==null)throw new IOException("Configuration is unavailable");
        String base=saved==null?activity.getIntent().getStringExtra(BASE):saved.getString(BASE);
        editBase=base==null?new JSONObject(document.toString()):RemoteConfiguration.active(new JSONObject(base));
        restorePending=!RemoteProtocol.canonical(document).equals(RemoteProtocol.canonical(editBase));
        if(saved!=null&&saved.containsKey("feature_artwork_all")) {
            document.getJSONObject("contract").put("assets",new JSONArray(saved.getString("feature_artwork_all")));
            ArrayList<String> disabled=saved.getStringArrayList("feature_artwork_disabled");
            if(disabled!=null)disabledArtwork.addAll(disabled);
        }
        desired=saved==null?activity.getIntent().getLongExtra("feature_desired",Long.parseLong(feature.store.metadata(peer.identity,peer.generation,"desired_revision","0"))):saved.getLong("feature_desired");
        applied=saved==null?activity.getIntent().getLongExtra("feature_applied",Long.parseLong(feature.store.metadata(peer.identity,peer.generation,"applied_revision","0"))):saved.getLong("feature_applied");
        JSONObject sections=document.getJSONObject("contract").getJSONObject("sections");
        for(String section:ConfigurationSchema.SECTIONS){
            SharedPreferences.Editor edit=context.getSharedPreferences(ConfigurationSchema.preferenceStore(section),0).edit();
            JSONObject values=sections.getJSONObject(section);
            for(ConfigurationSettingCatalog.Field field:ConfigurationSettingCatalog.fields(section))put(edit,field.key,field.normalize(values.get(field.key)),field.kind);
            edit.commit();
        }
        SharedPreferences.Editor main=context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME,0).edit();
        JSONObject apps=document.getJSONObject("apps");
        main.putStringSet(AppModeManager.KEY_INCLUDED_PACKAGES,RemoteConfiguration.selected(document));
        main.putBoolean(AppModeManager.KEY_ALL_APPS,apps.getBoolean("all"));
        main.putStringSet("app_include_all_packages_v1",catalog().keySet());
        JSONObject allowances=apps.getJSONObject("allowances");
        Iterator<String> keys=allowances.keys();
        while(keys.hasNext()){String key=keys.next();main.putInt("app_timer_allowance_minutes:"+key,allowances.getInt(key));}
        main.commit();
        context.getSharedPreferences(com.subhub.app.penance.PenanceManager.PREFS_NAME,0).edit()
                .putBoolean(com.subhub.app.penance.PenanceManager.KEY_RULE_PARTICIPATION_MIGRATED,true).commit();
        JSONObject service=document.getJSONObject("service");
        SharedPreferences.Editor duration=context.getSharedPreferences(RemoteConfiguration.DURATION_PREFS,0).edit();
        for(String key:new String[]{"duration","minimum","maximum"})duration.putLong(key,service.getLong(key));
        duration.putBoolean("hidden",service.getBoolean("hidden")).commit();
    }
    public static boolean attach(AppCompatActivity activity,String section,Bundle saved) {
        if(PersonRoutes.isLocalTarget(activity.getIntent()))return true;
        try{
            String token=saved==null?activity.getIntent().getStringExtra(TOKEN):saved.getString(TOKEN);
            FeatureEditorSession session=token==null?null:DRAFTS.get(token);
            if(session!=null&&session.invalidated)session=null;
            if(session==null)session=new FeatureEditorSession(activity,saved);
            if(!session.person.equals(PersonRoutes.requireId(activity.getIntent())))throw new SecurityException("Editor target changed");
            session.requireAuthority();session.pages++;PAGES.put(activity,session);DRAFTS.put(session.context.token,session);
            FeatureEditorSession attached=session;
            activity.getLifecycle().addObserver((LifecycleEventObserver)(owner,event)->{
                if(event==Lifecycle.Event.ON_RESUME){
                    if(attached.invalidated){restart(activity);return;}
                    EditorBinding binding=attached.bindings.get(activity);
                    if(binding!=null){
                        binding.active=true;
                        if(attached.restorePending||attached.userDirty){
                            try{if(!RemoteProtocol.canonical(attached.snapshot()).equals(RemoteProtocol.canonical(binding.autosave.base())))binding.autosave.changed();else attached.userDirty=false;}
                            catch(Exception incomplete){binding.autosave.changed();}
                            attached.restorePending=false;
                        }
                    }
                    try{attached.requireAuthority();}catch(RuntimeException denied){activity.finish();return;}
                    activity.getWindow().getDecorView().post(()->decorate(activity));
                } else if(event==Lifecycle.Event.ON_PAUSE){
                    EditorBinding binding=attached.bindings.get(activity);
                    if(binding!=null){binding.autosave.flush();binding.active=false;attached.remember(binding);}
                } else if(event==Lifecycle.Event.ON_DESTROY){
                    EditorBinding binding=attached.bindings.remove(activity);
                    if(binding!=null){binding.autosave.close();attached.remember(binding);}
                    PAGES.remove(activity);attached.pages--;
                    if(attached.pages==0&&(attached.invalidated||!activity.isChangingConfigurations())) {
                        DRAFTS.remove(attached.context.token);attached.context.closeCache();
                    }
                }
            });
            return true;
        }catch(Exception unavailable){Toast.makeText(activity,R.string.remote_reload,Toast.LENGTH_LONG).show();activity.finish();return false;}
    }
    public static FeatureEditorSession scope(Context context) {
        if(context instanceof DraftContext)return ((DraftContext)context).owner;
        return context instanceof AppCompatActivity?PAGES.get(context):null;
    }
    public static String targetName(Context context) {
        FeatureEditorSession scope=scope(context);
        if(scope==null)return ProfileNames.get(context,false);
        RemoteStore.Peer current=scope.feature.store.peer(scope.peer.identity,scope.peer.generation);
        return current==null?scope.peer.name:current.name;
    }
    public static boolean remote(Context context){return scope(context)!=null;}
    public static void leaveEditor(AppCompatActivity activity) {
        if (remote(activity)) activity.finish(); else ControllerEditMode.enterSubMode(activity);
    }
    public static Context context(Context source){FeatureEditorSession scope=scope(source);return scope==null?source:scope.context;}
    public static boolean canEdit(AppCompatActivity activity,String section){
        FeatureEditorSession scope=scope(activity);
        if(scope!=null){
            try{scope.requireAuthority();return !scope.invalidated&&scope.person.equals(PersonRoutes.requireId(activity.getIntent()))
                    &&RemoteFeaturePolicy.remoteAllowed(scope.feature.store,scope.peer,section);}catch(RuntimeException denied){return false;}
        }
        return PersonRoutes.isLocalTarget(activity.getIntent())&&ControllerPinManager.canEditConfiguration()
                &&(!SubFeaturePolicy.FEATURES.contains(section)||SubFeaturePolicy.allowed(activity,section));
    }
    private void requireAuthority(){
        if(invalidated)throw new SecurityException("Editor was reloaded");
        RemoteStore.Peer current=feature.store.peer(peer.identity,peer.generation);
        if(!feature.recoveryReady()||!DeviceRolePreferences.supportsDom(context.getBaseContext())
                ||!ControllerPinManager.isDomModeActive()||!ControllerPinManager.hasCredentials(context.getBaseContext())
                ||current==null||current.localRole!=RemoteProtocol.Role.DOM||current.state!=RemoteStore.State.ACTIVE)
            throw new SecurityException("Remote editor is unavailable");
    }
    public static void saveState(AppCompatActivity activity,Bundle state){
        FeatureEditorSession scope=scope(activity);if(scope==null||scope.invalidated)return;
        EditorBinding binding=scope.bindings.get(activity);if(binding!=null){binding.autosave.flush();scope.remember(binding);}
        try{state.putString(BASE,scope.editBase.toString());state.putString(TOKEN,scope.context.token);state.putString(DOCUMENT,scope.snapshot().toString());
            state.putLong("feature_desired",scope.desired);state.putLong("feature_applied",scope.applied);
            state.putString("feature_artwork_all",scope.document.getJSONObject("contract").getJSONArray("assets").toString());
            state.putStringArrayList("feature_artwork_disabled",new ArrayList<>(scope.disabledArtwork));
        }catch(Exception invalid){throw new IllegalStateException("Could not retain remote draft",invalid);}
    }
    public static Intent child(AppCompatActivity activity,Class<?> destination){
        Intent intent=new Intent(activity,destination);FeatureEditorSession scope=scope(activity);
        intent.putExtra(PersonRoutes.EXTRA_PERSON_ID,scope==null?PersonId.LOCAL.value():scope.person.value());
        if(scope!=null)try{EditorBinding binding=scope.bindings.get(activity);if(binding!=null){binding.autosave.flush();scope.remember(binding);}intent.putExtra(TOKEN,scope.context.token).putExtra(BASE,scope.editBase.toString()).putExtra(DOCUMENT,scope.snapshot().toString())
                .putExtra("feature_desired",scope.desired).putExtra("feature_applied",scope.applied);}catch(Exception invalid){throw new IllegalStateException(invalid);}
        return intent;
    }
    public static void bind(AppCompatActivity activity,View root,String section,BooleanSupplier flush){
        FeatureEditorSession scope=scope(activity);if(scope==null){
            TextView label=PrimaryHeader.subtitle(root);if(label!=null&&label.getVisibility()==View.VISIBLE)label.setText(ProfileNames.get(activity,false));return;
        }
        decorate(activity);
        LinearLayout body=body(root);
        if(body==null)throw new IllegalStateException("Feature editor has no content");
        try {
            EditorBinding old=scope.bindings.remove(activity);if(old!=null){old.autosave.close();scope.remember(old);}
            EditorBinding binding=new EditorBinding();scope.bindings.put(activity,binding);
            binding.autosave=new RemoteConfigurationAutosave(activity,body,scope.feature,scope.peer,scope.editBase,()->{
                if(!canEdit(activity,section))throw new SecurityException("Editor unavailable");
                scope.flushing=true;
                try{if(!flush.getAsBoolean())throw new IllegalArgumentException("Check the edited fields");return scope.snapshot();}
                finally{scope.flushing=false;}
            },()->{
                scope.invalidated=true;DRAFTS.remove(scope.context.token);
                restart(activity);
            });
            scope.watchText(root,binding);
            binding.active=activity.getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.RESUMED);
        }catch(Exception invalid){throw new IllegalStateException("Could not start automatic editing",invalid);}
    }
    private static final class EditorBinding {
        RemoteConfigurationAutosave autosave;
        boolean active;
    }
    private void remember(EditorBinding binding){
        try{editBase=binding.autosave.base();}catch(Exception ignored){/* Keep the prior acknowledged base. */}
    }
    private void draftChanged(String store,String key){
        if(!configurationKey(store,key))return;
        if(android.os.Looper.myLooper()!=android.os.Looper.getMainLooper()){main.post(()->draftChanged(store,key));return;}
        if(flushing||invalidated)return;
        for(EditorBinding binding:bindings.values())if(binding.active&&binding.autosave!=null){userDirty=true;binding.autosave.changed();}
    }
    static boolean configurationKey(String store,String key){
        if(RemoteConfiguration.DURATION_PREFS.equals(store))return Arrays.asList("duration","minimum","maximum","hidden").contains(key);
        if(SettingsRepository.PREFERENCES_NAME.equals(store)&&(AppModeManager.KEY_ALL_APPS.equals(key)
                ||AppModeManager.KEY_INCLUDED_PACKAGES.equals(key)||key.startsWith("app_timer_allowance_minutes:")
                ||com.subhub.app.capture.CustomImageManager.REVISION_KEY.equals(key)))return true;
        for(String section:ConfigurationSchema.SECTIONS)if(ConfigurationSchema.preferenceStore(section).equals(store))
            for(ConfigurationSettingCatalog.Field field:ConfigurationSettingCatalog.fields(section))if(field.key.equals(key))return true;
        return false;
    }
    private void watchText(View root,EditorBinding binding){
        if(root instanceof EditText)((EditText)root).addTextChangedListener(new android.text.TextWatcher(){
            public void beforeTextChanged(CharSequence value,int start,int count,int after){}
            public void onTextChanged(CharSequence value,int start,int before,int count){if(binding.active&&!flushing&&!invalidated){userDirty=true;binding.autosave.changed();}}
            public void afterTextChanged(android.text.Editable value){}
        });
        if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++)watchText(((ViewGroup)root).getChildAt(i),binding);
    }
    private static void restart(AppCompatActivity activity){
        activity.getIntent().removeExtra(TOKEN);activity.getIntent().removeExtra(DOCUMENT);activity.getIntent().removeExtra(BASE);
        activity.recreate();
    }
    public static void decorate(AppCompatActivity activity){
        FeatureEditorSession scope=scope(activity);if(scope==null)return;
        View root=activity.findViewById(android.R.id.content);
        TextView subtitle=PrimaryHeader.subtitle(root);
        RemoteStore.Peer current=scope.feature.store.peer(scope.peer.identity,scope.peer.generation);
        if(subtitle!=null){subtitle.setText((current==null?scope.peer.name:current.name)+" · "+ProfileViews.targetDiscriminator(activity,scope.person,""));subtitle.setVisibility(View.VISIBLE);}
        View nav=root.findViewById(R.id.bottom_navigation);if(nav!=null)nav.setVisibility(View.GONE);
        View edit=PrimaryHeader.editLockButton(root);if(edit!=null)edit.setVisibility(View.GONE);
        View back=PrimaryHeader.backButton(root);if(back!=null){back.setVisibility(View.VISIBLE);back.setOnClickListener(v->activity.finish());}
    }
    private static LinearLayout body(View root){
        if(root instanceof ScrollView){View child=((ScrollView)root).getChildAt(0);if(child instanceof LinearLayout)return (LinearLayout)child;}
        if(root instanceof ViewGroup)for(int index=0;index<((ViewGroup)root).getChildCount();index++){
            LinearLayout found=body(((ViewGroup)root).getChildAt(index));if(found!=null)return found;
        }
        return null;
    }
    public synchronized JSONObject snapshot() throws Exception {
        JSONObject result=new JSONObject(document.toString()),sections=result.getJSONObject("contract").getJSONObject("sections");
        for(String section:ConfigurationSchema.SECTIONS)sections.put(section,ConfigurationSchema.captureSection(context,section));
        SharedPreferences main=context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME,0);
        JSONObject apps=result.getJSONObject("apps"),allowances=new JSONObject();
        apps.put("all",main.getBoolean(AppModeManager.KEY_ALL_APPS,false));
        apps.put("packages",new JSONArray(new TreeSet<>(main.getStringSet(AppModeManager.KEY_INCLUDED_PACKAGES,Collections.emptySet()))));
        for(Map.Entry<String,?> entry:main.getAll().entrySet())if(entry.getKey().startsWith("app_timer_allowance_minutes:"))
            allowances.put(entry.getKey().substring("app_timer_allowance_minutes:".length()),entry.getValue());
        apps.put("allowances",allowances);
        SharedPreferences duration=context.getSharedPreferences(RemoteConfiguration.DURATION_PREFS,0);
        JSONObject service=result.getJSONObject("service");for(String key:new String[]{"duration","minimum","maximum"})service.put(key,duration.getLong(key,service.getLong(key)));
        service.put("hidden",duration.getBoolean("hidden",false));
        JSONArray selected=new JSONArray(),refs=document.getJSONObject("contract").getJSONArray("assets");
        for(int index=0;index<refs.length();index++){JSONObject ref=refs.getJSONObject(index);if(!disabledArtwork.contains(ref.getString("path")))selected.put(ref);}
        result.getJSONObject("contract").put("assets",selected);
        return RemoteConfiguration.active(result);
    }
    public Map<String,String> catalog(){
        Map<String,String> result=new TreeMap<>();
        for(RemoteProtocol.Change change:feature.store.mirror(peer.identity,peer.generation,RemoteProtocol.Stream.APP_CATALOG))if(!change.deleted)
            try{JSONObject app=new JSONObject(change.body);result.put(change.key,app.optString("label",change.key));}catch(JSONException malformed){throw new IllegalStateException("Invalid app catalog",malformed);}
        return result;
    }
    public String appLabel(String name){return catalog().getOrDefault(name,name);}
    public PersonSnapshot person(){try{return new RemotePersonRepository(context.getBaseContext(),feature).load(person);}catch(Exception unavailable){return null;}}
    public synchronized List<String> artwork(String group){
        List<String> result=new ArrayList<>();JSONArray refs=document.optJSONObject("contract").optJSONArray("assets");
        for(int index=0;index<refs.length();index++){String path=refs.optJSONObject(index).optString("path");if(path.startsWith("assets/"+group+"/"))result.add(path);}
        return result;
    }
    public synchronized boolean artworkEnabled(String path){return !disabledArtwork.contains(path);}
    public synchronized void enableArtwork(String path,boolean enabled){if(enabled)disabledArtwork.remove(path);else disabledArtwork.add(path);artworkChanged();}
    public synchronized void deleteArtwork(String path){JSONArray refs=document.optJSONObject("contract").optJSONArray("assets");for(int index=refs.length()-1;index>=0;index--)if(path.equals(refs.optJSONObject(index).optString("path")))refs.remove(index);disabledArtwork.remove(path);artworkChanged();}
    private void artworkChanged(){context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME,0).edit().putLong(com.subhub.app.capture.CustomImageManager.REVISION_KEY,System.nanoTime()).apply();}
    public Bitmap thumbnail(String path,int maximum){
        try{JSONObject ref=null; synchronized(this){for(Object item:artworkRefs()){JSONObject candidate=(JSONObject)item;if(path.equals(candidate.optString("path"))){ref=candidate;break;}}}
            if(ref==null)return null;RemoteAssets assets=feature.contracts.assets(peer);String hash=ref.getString("hash");if(!assets.isComplete(hash))return null;
            BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;try(InputStream input=assets.open(hash)){BitmapFactory.decodeStream(input,null,bounds);}
            BitmapFactory.Options sample=new BitmapFactory.Options();sample.inSampleSize=1;while(Math.max(bounds.outWidth,bounds.outHeight)/sample.inSampleSize>maximum&&sample.inSampleSize>0)sample.inSampleSize=sample.inSampleSize==0?2:sample.inSampleSize*2;
            if(sample.inSampleSize==0)sample.inSampleSize=1;try(InputStream input=assets.open(hash)){return BitmapFactory.decodeStream(input,null,sample);}
        }catch(Exception unavailable){return null;}
    }
    private synchronized List<Object> artworkRefs(){List<Object> refs=new ArrayList<>();JSONArray array=document.optJSONObject("contract").optJSONArray("assets");for(int index=0;index<array.length();index++)refs.add(array.optJSONObject(index));return refs;}
    public int addArtwork(String group,List<Uri> uris){
        int count=0;for(Uri uri:uris){File temporary=null;try{
            requireAuthority();if(!RemoteFeaturePolicy.remoteAllowed(feature.store,peer,group))break;
            temporary=com.subhub.app.storage.DisposableCache.temporary(context.getCacheDir(),"artwork-");
            try(InputStream input=context.getContentResolver().openInputStream(uri);OutputStream output=new FileOutputStream(temporary)){
                if(input==null)throw new IOException("Image unavailable");byte[] buffer=new byte[8192];long length=0;int read;
                while((read=input.read(buffer))!=-1){length+=read;if(length>RemoteAssets.MAX_ASSET_BYTES)throw new IOException("Image is too large");output.write(buffer,0,read);}
            }
            BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeFile(temporary.getPath(),bounds);if(bounds.outWidth<=0||bounds.outHeight<=0)throw new IOException("Image unavailable");
            String hash=RemoteAssets.digest(temporary);try(InputStream input=new FileInputStream(temporary)){RemoteContracts.ingest(feature.contracts.assets(peer),hash,temporary.length(),input);}
            synchronized(this){if(document.getJSONObject("contract").getJSONArray("assets").length()>=512)break;
                document.getJSONObject("contract").getJSONArray("assets").put(new JSONObject().put("path","assets/"+group+"/"+UUID.randomUUID()+".png").put("hash",hash).put("length",temporary.length()));artworkChanged();}
            count++;
        }catch(Exception unavailable){/* The caller displays the successfully imported count. */}finally{com.subhub.app.storage.DisposableCache.releaseTemporary(temporary);}}
        return count;
    }
    private static void put(SharedPreferences.Editor edit,String key,Object value,ConfigurationSettingCatalog.Kind kind)throws JSONException{
        switch(kind){case BOOLEAN:edit.putBoolean(key,(Boolean)value);break;case LONG:edit.putLong(key,((Number)value).longValue());break;
            case INTEGER:case MONEY:edit.putInt(key,((Number)value).intValue());break;case DECIMAL:case RATIO:edit.putFloat(key,((Number)value).floatValue());break;
            case SELECTION:Set<String> set=new LinkedHashSet<>();JSONArray array=(JSONArray)value;for(int index=0;index<array.length();index++)set.add(array.getString(index));edit.putStringSet(key,set);break;
            default:edit.putString(key,(String)value);break;}
    }
    private static final class DraftContext extends ContextWrapper {
        private final FeatureEditorSession owner;private final String token;
        private com.subhub.app.storage.DisposableCache.EditorLease cache;private boolean closed;
        DraftContext(Context source,FeatureEditorSession owner,String token){super(source);this.owner=owner;this.token=token;}
        @Override public Context getApplicationContext(){return this;}
        @Override public SharedPreferences getSharedPreferences(String name,int mode){synchronized(owner.stores){return owner.stores.computeIfAbsent(name,unused->{DraftPreferences prefs=new DraftPreferences();prefs.registerOnSharedPreferenceChangeListener((store,key)->owner.draftChanged(name,key));return prefs;});}}
        private synchronized File directory(String name) {
            if(closed)throw new IllegalStateException("Editor is closed");
            try {
                if(cache==null)cache=com.subhub.app.storage.DisposableCache.editor(super.getCacheDir(),token);
                File folder=new File(cache.directory,name);
                if(java.nio.file.Files.isSymbolicLink(folder.toPath()) || (!folder.isDirectory()&&!folder.mkdirs()))
                    throw new IOException("Editor storage unavailable");
                return folder;
            }catch(IOException unavailable){throw new IllegalStateException("Editor storage unavailable",unavailable);}
        }
        private synchronized void closeCache() {
            closed=true;
            if(cache!=null)try{cache.close();}catch(IOException deferred){android.util.Log.w("StorageCache","Editor cleanup deferred");}
        }
        @Override public File getFilesDir(){return directory("files");}
        @Override public File getNoBackupFilesDir(){return directory("private");}
        @Override public File getCacheDir(){return directory("cache");}
    }
}
