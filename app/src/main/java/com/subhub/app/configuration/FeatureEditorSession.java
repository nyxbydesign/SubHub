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
import com.subhub.app.internal.*;
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

/** Native feature editors use physical settings, peer drafts, or an explicit independent internal backend. */
public final class FeatureEditorSession {
    private static final String TOKEN="feature_editor_token", DOCUMENT="feature_editor_document", BASE="feature_editor_base";
    private static final Map<AppCompatActivity,FeatureEditorSession> PAGES=Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<String,FeatureEditorSession> DRAFTS=new HashMap<>();
    private final RemoteFeature feature;
    public enum Backend { REMOTE, INTERNAL }
    public final Backend backend;
    private final boolean internal;
    private final Context physical;
    private InternalSubData internalData;
    private InternalConfigurationStore.EditorDraft internalDraft;
    private InternalEditorArtwork internalArtwork;
    private InternalEditorSaveDrain internalSaves;
    private String internalGeneration;
    private long controllerRevision;
    private final java.util.concurrent.atomic.AtomicLong internalEditEpoch = new java.util.concurrent.atomic.AtomicLong();
    private static final String INTERNAL_EDIT_EPOCH = "internal_editor_edit_epoch";
    private static final Map<Context,FeatureEditorSession> CONTEXTS=Collections.synchronizedMap(new WeakHashMap<>());

    public final RemoteStore.Peer peer;
    private final PersonId person;
    private final String token;
    private final ContextWrapper context;
    private final Map<String,DraftPreferences> stores=new LinkedHashMap<>();
    private final Set<String> disabledArtwork=new HashSet<>();
    private JSONObject document,editBase;
    private final Map<AppCompatActivity,EditorBinding> bindings=new IdentityHashMap<>();
    private final android.os.Handler main=new android.os.Handler(android.os.Looper.getMainLooper());
    private boolean flushing,invalidated,restorePending,userDirty;
    private long desired,applied;
    private int pages;


    private FeatureEditorSession(AppCompatActivity activity,Bundle saved) throws Exception {
        Context app=activity.getApplicationContext(); physical=app;
        internal=DummySubPreview.isRequested(activity.getIntent());
        backend=internal?Backend.INTERNAL:Backend.REMOTE;
        if(internal){
            if(!DummySubPreview.isAllowed(activity))throw new SecurityException("Internal Sub unavailable");
            feature=null;peer=null;person=DummySubPreview.ID;
            token=UUID.randomUUID().toString();
            internalData=InternalSubData.get(app);
            InternalSubRecord record=internalData.acknowledged();
            internalGeneration=record.generation;controllerRevision=ControllerPinManager.domWorkspaceRevision();
            internalDraft=internalData.configuration.edit(record);
            internalSaves=InternalEditorAutosave.createDrain(app,internalData,internalDraft,internalGeneration,controllerRevision,main::post);
            context=new InternalSubContext(app,internalData.storage,internalDraft);
            CONTEXTS.put(context,this);
            internalArtwork=new InternalEditorArtwork(app,internalData.storage,internalDraft,this::requireAuthority);
            for(String name:List.of(SettingsRepository.PREFERENCES_NAME,
                    ConfigurationSchema.preferenceStore(ConfigurationSchema.WALLET),
                    InternalConfigurationStore.DURATION_PREFERENCES,InternalConfigurationStore.PRESET_PREFERENCES))
                context.getSharedPreferences(name,0).registerOnSharedPreferenceChangeListener((store,key)->draftChanged(name,key));
            return;
        }else{
        feature=RemoteFeature.get(app);
        controllerRevision=ControllerPinManager.domWorkspaceRevision();
        person=PersonRoutes.requireId(activity.getIntent());
        peer=new RemotePersonRepository(app,feature).peer(person);
        String requestedToken=saved!=null?saved.getString(TOKEN):activity.getIntent().getStringExtra(TOKEN);
        token=requestedToken==null?UUID.randomUUID().toString():requestedToken;
        context=new DraftContext(app,this,token);
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
        }
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
        if(!DummySubPreview.isRequested(activity.getIntent())&&PersonRoutes.isLocalTarget(activity.getIntent()))return true;
        try{
            String token=saved==null?activity.getIntent().getStringExtra(TOKEN):saved.getString(TOKEN);
            FeatureEditorSession session=token==null?null:DRAFTS.get(token);
            if(DummySubPreview.isRequested(activity.getIntent())&&saved!=null&&token!=null&&session==null)
                throw new SecurityException("An unacknowledged internal draft cannot be restored after process loss");
            if(session!=null&&session.invalidated)session=null;
            if(session==null)session=new FeatureEditorSession(activity,saved);
            if(!session.person.equals(PersonRoutes.requireId(activity.getIntent())))throw new SecurityException("Editor target changed");
            session.requireAuthority();session.pages++;PAGES.put(activity,session);
            if(session.internal){DRAFTS.put(session.token,session);session.attachInternalLifecycle(activity);return true;}
            DRAFTS.put(session.token,session);
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
                        DRAFTS.remove(attached.token);attached.closeCache();
                    }
                }
            });
            return true;
        }catch(Exception unavailable){Toast.makeText(activity,R.string.remote_reload,Toast.LENGTH_LONG).show();activity.finish();return false;}
    }
    private void attachInternalLifecycle(AppCompatActivity activity){
        Runnable check=new Runnable(){public void run(){
            if(!internalAllowed(activity)){
                activity.findViewById(android.R.id.content).setVisibility(View.INVISIBLE);activity.finish();return;
            }
            if(activity.getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.RESUMED))main.postDelayed(this,250);
        }};
        activity.getLifecycle().addObserver((LifecycleEventObserver)(owner,event)->{
            if(event==Lifecycle.Event.ON_RESUME){
                if(!internalAllowed(activity)){activity.finish();return;}
                if(!internalDraft.hasUnflushedChanges() && internalDraft.acknowledgedBase().revision != internalData.acknowledged().revision){
                    invalidated=true;DRAFTS.remove(token);restart(activity);return;
                }
                EditorBinding binding=bindings.get(activity);if(binding!=null)binding.active=true;
                View root=activity.findViewById(android.R.id.content);root.setVisibility(View.VISIBLE);
                root.post(()->{if(internalAllowed(activity))decorate(activity);});main.post(check);
            }else if(event==Lifecycle.Event.ON_PAUSE){
                main.removeCallbacks(check);EditorBinding binding=bindings.get(activity);
                if(binding!=null){binding.flush();binding.active=false;}
                activity.findViewById(android.R.id.content).setVisibility(View.INVISIBLE);
            }else if(event==Lifecycle.Event.ON_DESTROY){
                main.removeCallbacks(check);EditorBinding binding=bindings.remove(activity);if(binding!=null)binding.close();
                PAGES.remove(activity);pages--;
                if(pages==0&&!activity.isChangingConfigurations()){DRAFTS.remove(token);CONTEXTS.remove(context);}
            }
        });
    }
    private boolean internalAllowed(AppCompatActivity activity){
        return !invalidated&&DummySubPreview.isAllowed(activity)
                &&DummySubPreview.current(physical,internalGeneration,controllerRevision);
    }
    private void closeCache(){if(context instanceof DraftContext)((DraftContext)context).closeCache();}
    public static FeatureEditorSession scope(Context context) {
        if(context instanceof DraftContext)return ((DraftContext)context).owner;
        FeatureEditorSession scoped=context instanceof AppCompatActivity?PAGES.get(context):CONTEXTS.get(context);
        if(scoped!=null)return scoped;
        Context app=context.getApplicationContext();
        return app instanceof DraftContext?((DraftContext)app).owner:CONTEXTS.get(app);
    }
    public static String targetName(Context context) {
        FeatureEditorSession scope=scope(context);
        if(scope==null)return ProfileNames.get(context,false);
        if(scope.internal)return scope.internalData.acknowledged().profile.name;
        RemoteStore.Peer current=scope.feature.store.peer(scope.peer.identity,scope.peer.generation);
        return current==null?scope.peer.name:current.name;
    }
    /** Legacy read-only preview is replaced by the explicit INTERNAL editor backend. */
    public static boolean preview(Context context){return false;}
    public static boolean internal(Context context){FeatureEditorSession scope=scope(context);return scope!=null&&scope.internal;}
    public static boolean canView(AppCompatActivity activity,String section){
        return canEdit(activity,section);
    }
    public static boolean remote(Context context){return scope(context)!=null;}
    public static void leaveEditor(AppCompatActivity activity) {
        if (remote(activity)||DummySubPreview.isRequested(activity.getIntent())) activity.finish(); else ControllerEditMode.enterSubMode(activity);
    }
    public static Context context(Context source){FeatureEditorSession scope=scope(source);return scope==null?source:scope.context;}
    public static boolean canEdit(AppCompatActivity activity,String section){
        FeatureEditorSession scope=scope(activity);
        if(DummySubPreview.isRequested(activity.getIntent())||scope!=null&&scope.internal)
            return scope!=null&&scope.internal&&scope.internalData.ownerDataReady()&&scope.internalAllowed(activity);
        if(scope!=null){
            try{scope.requireAuthority();return !scope.invalidated&&scope.person.equals(PersonRoutes.requireId(activity.getIntent()))
                    &&RemoteFeaturePolicy.remoteAllowed(scope.feature.store,scope.peer,section);}catch(RuntimeException denied){return false;}
        }
        return PersonRoutes.isLocalTarget(activity.getIntent())&&ControllerPinManager.canEditConfiguration()
                &&(!SubFeaturePolicy.FEATURES.contains(section)||SubFeaturePolicy.allowed(activity,section));
    }

    public static boolean canEditGag(AppCompatActivity activity) {
        FeatureEditorSession scope=scope(activity);
        return scope!=null&&scope.controllerRevision==ControllerPinManager.domWorkspaceRevision()
                &&canEdit(activity,GagContractFields.SECTION);
    }

    public static JSONObject gagRules(AppCompatActivity activity) throws Exception {
        if(!canEditGag(activity))throw new SecurityException("Gag editor unavailable");
        FeatureEditorSession scope = scope(activity);
        if (scope.internal) return scope.internalDraft.gagRules();
        JSONObject rules=GagContractFields.section(scope.document);
        return rules==null?GagContractFields.defaults():new JSONObject(rules.toString());
    }

    public static void editGagRules(AppCompatActivity activity, JSONObject rules) throws JSONException {
        if(!canEditGag(activity))throw new SecurityException("Gag editor unavailable");
        FeatureEditorSession scope = scope(activity);
        if (scope.internal) {
            scope.internalDraft.editGagRules(rules);
            return;
        }
        scope.document.getJSONObject("contract").getJSONObject("sections")
                .put(GagContractFields.SECTION,GagContractFields.validate(rules));
    }

    public static void gagRulesChanged(AppCompatActivity activity) {
        if (canEditGag(activity)) {
            FeatureEditorSession scope = scope(activity);
            if (scope.internal) scope.internalEditEpoch.incrementAndGet();
            scope.markDraftChanged();
        }
    }
    private void requireAuthority(){
        if(internal){if(invalidated||!DummySubPreview.current(physical,internalGeneration,controllerRevision))throw new SecurityException("Internal Sub unavailable");return;}
        if(invalidated)throw new SecurityException("Editor was reloaded");
        RemoteStore.Peer current=feature.store.peer(peer.identity,peer.generation);
        if(!feature.recoveryReady()||!DeviceRolePreferences.supportsDom(context.getBaseContext())
                ||!ControllerPinManager.isDomModeActive()||!ControllerPinManager.hasCredentials(context.getBaseContext())
                ||current==null||current.localRole!=RemoteProtocol.Role.DOM||current.state!=RemoteStore.State.ACTIVE)
            throw new SecurityException("Remote editor is unavailable");
    }
    public static void saveState(AppCompatActivity activity,Bundle state){
        FeatureEditorSession scope=scope(activity);if(scope==null||scope.invalidated)return;
        if(scope.internal){
            EditorBinding binding=scope.bindings.get(activity);if(binding!=null)binding.flush();
            state.putString(TOKEN,scope.token);state.putLong(INTERNAL_EDIT_EPOCH,scope.internalEditEpoch.get());return;
        }
        EditorBinding binding=scope.bindings.get(activity);if(binding!=null){binding.autosave.flush();scope.remember(binding);}
        try{state.putString(BASE,scope.editBase.toString());state.putString(TOKEN,scope.token);state.putString(DOCUMENT,scope.snapshot().toString());
            state.putLong("feature_desired",scope.desired);state.putLong("feature_applied",scope.applied);
            state.putString("feature_artwork_all",scope.document.getJSONObject("contract").getJSONArray("assets").toString());
            state.putStringArrayList("feature_artwork_disabled",new ArrayList<>(scope.disabledArtwork));
        }catch(Exception invalid){throw new IllegalStateException("Could not retain remote draft",invalid);}
    }
    /** Raw fields may survive rotation only while the same retained draft and exact authority remain current. */
    public static boolean restoreInternalWidgets(AppCompatActivity activity,Bundle saved){
        FeatureEditorSession scope=scope(activity);
        return scope!=null&&scope.internal&&saved!=null&&scope.internalAllowed(activity)
                &&scope.token.equals(saved.getString(TOKEN))
                &&saved.getLong(INTERNAL_EDIT_EPOCH,-1)==scope.internalEditEpoch.get()
                &&scope.internalDraft.acknowledgedBase().revision==scope.internalData.acknowledged().revision;
    }
    public static Intent child(AppCompatActivity activity,Class<?> destination){
        FeatureEditorSession scope=scope(activity);
        if(scope!=null&&scope.internal){
            EditorBinding binding=scope.bindings.get(activity);if(binding!=null)binding.flush();
            return DummySubPreview.childIntent(activity,destination).putExtra(TOKEN,scope.token);
        }
        Intent intent=new Intent(activity,destination);
        intent.putExtra(PersonRoutes.EXTRA_PERSON_ID,scope==null?PersonId.LOCAL.value():scope.person.value());
        if(scope!=null)try{EditorBinding binding=scope.bindings.get(activity);if(binding!=null){binding.autosave.flush();scope.remember(binding);}intent.putExtra(TOKEN,scope.token).putExtra(BASE,scope.editBase.toString()).putExtra(DOCUMENT,scope.snapshot().toString())
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
            EditorBinding old=scope.bindings.remove(activity);if(old!=null){old.close();scope.remember(old);}
            EditorBinding binding=new EditorBinding();scope.bindings.put(activity,binding);
            if(scope.internal){
                binding.internalAutosave=new InternalEditorAutosave(activity,body,scope.internalData,scope.internalDraft,
                        scope.internalGeneration,scope.controllerRevision,scope.internalSaves,()->{
                    if(!canEdit(activity,section))throw new SecurityException("Editor unavailable");
                    scope.flushing=true;try{return flush.getAsBoolean();}finally{scope.flushing=false;}
                },()->{scope.invalidated=true;DRAFTS.remove(scope.token);restart(activity);}, !(activity instanceof PersonActivity));
                scope.watchText(root,binding);binding.active=activity.getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.RESUMED);return;
            }
            binding.autosave=new RemoteConfigurationAutosave(activity,body,scope.feature,scope.peer,scope.editBase,()->{
                if(!(GagContractFields.SECTION.equals(section)?canEditGag(activity):canEdit(activity,section)))
                    throw new SecurityException("Editor unavailable");
                scope.flushing=true;
                try{if(!flush.getAsBoolean())throw new IllegalArgumentException("Check the edited fields");return scope.snapshot();}
                finally{scope.flushing=false;}
            },()->{
                scope.invalidated=true;DRAFTS.remove(scope.token);
                restart(activity);
            });
            scope.watchText(root,binding);
            binding.active=activity.getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.RESUMED);
        }catch(Exception invalid){throw new IllegalStateException("Could not start automatic editing",invalid);}
    }
    private static final class EditorBinding {
        RemoteConfigurationAutosave autosave;
        InternalEditorAutosave internalAutosave;
        boolean active;
        void changed(){if(internalAutosave!=null)internalAutosave.changed();else if(autosave!=null)autosave.changed();}
        void flush(){if(internalAutosave!=null)internalAutosave.flush();else if(autosave!=null)autosave.flush();}
        void close(){if(internalAutosave!=null)internalAutosave.close();else if(autosave!=null)autosave.close();}
    }

    /** Stage the current duration widgets and wait for their exact broker acknowledgement. */
    public static java.util.concurrent.CompletableFuture<InternalSubRecord> acknowledgeInternalDuration(AppCompatActivity activity) {
        FeatureEditorSession scope = scope(activity);
        EditorBinding binding = scope == null ? null : scope.bindings.get(activity);
        if (scope == null || !scope.internal || binding == null || binding.internalAutosave == null
                || !canEdit(activity, "duration"))
            throw new SecurityException("Internal duration editor unavailable");
        Context physical = activity.getApplicationContext();
        return binding.internalAutosave.acknowledge().thenApply(ignored -> {
            InternalSubRecord saved = scope.internalDraft.acknowledgedBase();
            if (!DummySubPreview.current(physical, scope.internalGeneration, scope.controllerRevision)
                    || scope.internalDraft.hasUnflushedChanges() || !saved.equals(scope.internalData.acknowledged()))
                throw new SecurityException("Internal duration acknowledgement changed");
            return saved;
        });
    }
    private void remember(EditorBinding binding){
        if(binding.autosave==null)return;
        try{editBase=binding.autosave.base();}catch(Exception ignored){/* Keep the prior acknowledged base. */}
    }
    private void draftChanged(String store,String key){
        if(!internal&&!configurationKey(store,key))return;
        if(internal)internalEditEpoch.incrementAndGet();
        if(android.os.Looper.myLooper()!=android.os.Looper.getMainLooper()){main.post(this::markDraftChanged);return;}
        markDraftChanged();
    }
    private void markDraftChanged(){
        if(flushing||invalidated)return;
        for(EditorBinding binding:bindings.values())if(binding.active){userDirty=true;binding.changed();}
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
            public void onTextChanged(CharSequence value,int start,int before,int count){if(binding.active&&!flushing&&!invalidated){userDirty=true;binding.changed();}}
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
        RemoteStore.Peer current=scope.internal?null:scope.feature.store.peer(scope.peer.identity,scope.peer.generation);
        if(scope.internal){if(subtitle!=null){subtitle.setText(scope.internalData.acknowledged().profile.name+" · "+activity.getString(R.string.internal_sub_device));subtitle.setVisibility(View.VISIBLE);}}
        else if(subtitle!=null){subtitle.setText((current==null?scope.peer.name:current.name)+" · "+ProfileViews.targetDiscriminator(activity,scope.person,""));subtitle.setVisibility(View.VISIBLE);}
        View nav=root.findViewById(R.id.bottom_navigation);if(nav!=null)nav.setVisibility(View.GONE);
        View edit=PrimaryHeader.editLockButton(root);if(edit!=null){edit.setOnClickListener(null);edit.setVisibility(View.GONE);}
        View back=PrimaryHeader.backButton(root);if(back!=null){back.setVisibility(View.VISIBLE);if(!(scope.internal&&activity instanceof PersonActivity))back.setOnClickListener(v->activity.finish());}
    }
    private static LinearLayout body(View root){
        if(root instanceof ScrollView){View child=((ScrollView)root).getChildAt(0);if(child instanceof LinearLayout)return (LinearLayout)child;}
        if(root instanceof ViewGroup)for(int index=0;index<((ViewGroup)root).getChildCount();index++){
            LinearLayout found=body(((ViewGroup)root).getChildAt(index));if(found!=null)return found;
        }
        return null;
    }
    public synchronized JSONObject snapshot() throws Exception {
        if(internal)throw new UnsupportedOperationException("Internal editors publish acknowledged typed configuration, never a remote contract");
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
        if(internal){
            requireAuthority();
            android.content.Intent launcher=new android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_LAUNCHER);
            android.content.pm.PackageManager packages=physical.getPackageManager();
            for(android.content.pm.ResolveInfo info:packages.queryIntentActivities(launcher,android.content.pm.PackageManager.MATCH_ALL))
                if(info.activityInfo!=null&&!physical.getPackageName().equals(info.activityInfo.packageName))
                    result.put(info.activityInfo.packageName,info.loadLabel(packages).toString());
            return result;
        }
        for(RemoteProtocol.Change change:feature.store.mirror(peer.identity,peer.generation,RemoteProtocol.Stream.APP_CATALOG))if(!change.deleted)
            try{JSONObject app=new JSONObject(change.body);result.put(change.key,app.optString("label",change.key));}catch(JSONException malformed){throw new IllegalStateException("Invalid app catalog",malformed);}
        return result;
    }
    public String appLabel(String name){return catalog().getOrDefault(name,name);}
    public PersonSnapshot person(){if(internal){requireAuthority();PersonSnapshot value=internalData.peekPresentation();if(value==null)internalData.requestPresentation(physical);return value;}try{return new RemotePersonRepository(context.getBaseContext(),feature).load(person);}catch(Exception unavailable){return null;}}
    public synchronized List<String> artwork(String group){
        if(internal)return internalArtwork.list(group);List<String> result=new ArrayList<>();JSONArray refs=document.optJSONObject("contract").optJSONArray("assets");
        for(int index=0;index<refs.length();index++){String path=refs.optJSONObject(index).optString("path");if(path.startsWith("assets/"+group+"/"))result.add(path);}
        return result;
    }
    public synchronized boolean artworkEnabled(String path){return internal?internalArtwork.enabled(path):!disabledArtwork.contains(path);}
    public synchronized void enableArtwork(String path,boolean enabled){if(internal){internalArtwork.enable(path,enabled);return;}if(enabled)disabledArtwork.remove(path);else disabledArtwork.add(path);artworkChanged();}
    public synchronized void deleteArtwork(String path){if(internal){internalArtwork.delete(path);return;}JSONArray refs=document.optJSONObject("contract").optJSONArray("assets");for(int index=refs.length()-1;index>=0;index--)if(path.equals(refs.optJSONObject(index).optString("path")))refs.remove(index);disabledArtwork.remove(path);artworkChanged();}
    private void artworkChanged(){context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME,0).edit().putLong(com.subhub.app.capture.CustomImageManager.REVISION_KEY,System.nanoTime()).apply();}
    public Bitmap thumbnail(String path,int maximum){
        if(internal)return internalArtwork.thumbnail(path,maximum);
        try{JSONObject ref=null; synchronized(this){for(Object item:artworkRefs()){JSONObject candidate=(JSONObject)item;if(path.equals(candidate.optString("path"))){ref=candidate;break;}}}
            if(ref==null)return null;RemoteAssets assets=feature.contracts.assets(peer);String hash=ref.getString("hash");if(!assets.isComplete(hash))return null;
            BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;try(InputStream input=assets.open(hash)){BitmapFactory.decodeStream(input,null,bounds);}
            BitmapFactory.Options sample=new BitmapFactory.Options();sample.inSampleSize=1;while(Math.max(bounds.outWidth,bounds.outHeight)/sample.inSampleSize>maximum&&sample.inSampleSize>0)sample.inSampleSize=sample.inSampleSize==0?2:sample.inSampleSize*2;
            if(sample.inSampleSize==0)sample.inSampleSize=1;try(InputStream input=assets.open(hash)){return BitmapFactory.decodeStream(input,null,sample);}
        }catch(Exception unavailable){return null;}
    }
    private synchronized List<Object> artworkRefs(){List<Object> refs=new ArrayList<>();JSONArray array=document.optJSONObject("contract").optJSONArray("assets");for(int index=0;index<array.length();index++)refs.add(array.optJSONObject(index));return refs;}
    public int addArtwork(String group,List<Uri> uris){
        if(internal)return internalArtwork.add(group,uris);
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
        private void forbidPreviewAccess(){if(owner.internal)throw new SecurityException("Preview has no device data access");}
        @Override public android.content.ContentResolver getContentResolver(){forbidPreviewAccess();return super.getContentResolver();}
        @Override public File getDir(String name,int mode){if(owner.internal){if(name==null||!name.matches("[A-Za-z0-9_-]+"))throw new SecurityException("Invalid preview directory");return directory(name);}return super.getDir(name,mode);}
        @Override public File getFileStreamPath(String name){forbidPreviewAccess();return super.getFileStreamPath(name);}
        @Override public boolean deleteFile(String name){forbidPreviewAccess();return super.deleteFile(name);}
        @Override public boolean deleteDatabase(String name){forbidPreviewAccess();return super.deleteDatabase(name);}
        @Override public File getDatabasePath(String name){forbidPreviewAccess();return super.getDatabasePath(name);}
        @Override public android.database.sqlite.SQLiteDatabase openOrCreateDatabase(String name,int mode,android.database.sqlite.SQLiteDatabase.CursorFactory factory){forbidPreviewAccess();return super.openOrCreateDatabase(name,mode,factory);}
        @Override public android.database.sqlite.SQLiteDatabase openOrCreateDatabase(String name,int mode,android.database.sqlite.SQLiteDatabase.CursorFactory factory,android.database.DatabaseErrorHandler handler){forbidPreviewAccess();return super.openOrCreateDatabase(name,mode,factory,handler);}
        @Override public File getExternalFilesDir(String type){forbidPreviewAccess();return super.getExternalFilesDir(type);}
        @Override public File[] getExternalFilesDirs(String type){forbidPreviewAccess();return super.getExternalFilesDirs(type);}
        @Override public File getExternalCacheDir(){forbidPreviewAccess();return super.getExternalCacheDir();}
        @Override public File[] getExternalCacheDirs(){forbidPreviewAccess();return super.getExternalCacheDirs();}
        @Override public FileInputStream openFileInput(String name)throws FileNotFoundException{forbidPreviewAccess();return super.openFileInput(name);}
        @Override public FileOutputStream openFileOutput(String name,int mode)throws FileNotFoundException{forbidPreviewAccess();return super.openFileOutput(name,mode);}
        @Override public android.content.ComponentName startService(Intent service){forbidPreviewAccess();return super.startService(service);}
        @Override public boolean bindService(Intent service,ServiceConnection connection,int flags){forbidPreviewAccess();return super.bindService(service,connection,flags);}
        @Override public File getFilesDir(){return directory("files");}
        @Override public File getNoBackupFilesDir(){return directory("private");}
        @Override public File getCacheDir(){return directory("cache");}
    }
}
