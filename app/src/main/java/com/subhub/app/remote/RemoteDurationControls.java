package com.subhub.app.remote;

import android.app.Activity;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.*;
import androidx.lifecycle.*;
import com.subhub.app.R;
import com.subhub.app.people.*;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.security.DeviceRolePreferences;
import com.subhub.app.util.AsyncUiScope;
import org.json.JSONObject;

/** Inline duration edits use the same durable automatic queue as the full profile editor. */
public final class RemoteDurationControls implements DefaultLifecycleObserver {
    private static final long[] OPTIONS = {-1, -2, 3_600_000, 86_400_000, 604_800_000, 2_592_000_000L};
    public static final class Draft extends ViewModel {
        final SavedStateHandle state;
        public Draft(SavedStateHandle state) { this.state = state; }
    }
    private final Activity activity;
    private final android.content.Context application;
    private final LinearLayout parent;
    private final RemoteFeature feature;
    private final RemoteStore.Peer peer;
    private final AsyncUiScope work;
    private final Draft draft;
    private final String request;
    private final Lifecycle lifecycle;
    private JSONObject configuration;
    private RemoteConfigurationAutosave autosave;
    private boolean rendering;

    RemoteDurationControls(Activity activity, LinearLayout parent, PersonSnapshot person) {
        this.activity = activity;this.application=activity.getApplicationContext();this.parent = parent;
        feature = RemoteFeature.get(activity);peer = new RemotePersonRepository(activity, feature).peer(person.id);
        work = AsyncUiScope.forContext(activity);request = "inline-duration:" + person.id.value();
        draft = new ViewModelProvider((ViewModelStoreOwner) activity).get(request, Draft.class);
        lifecycle = ((LifecycleOwner)activity).getLifecycle();
    }

    void bind() {
        LinearLayout controls = PersonUi.column(activity);controls.setTag("remote_session_duration");parent.addView(controls);
        if (!editable()) { PersonUi.text(controls, activity.getString(R.string.remote_read_only), false);return; }
        controls.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener(){
            public void onViewAttachedToWindow(View view){}
            public void onViewDetachedFromWindow(View view){close();}
        });
        lifecycle.addObserver(this);
        load(controls);
    }

    private boolean editable() { return editable(application,feature,peer); }
    private static boolean editable(android.content.Context context,RemoteFeature feature,RemoteStore.Peer peer) {
        if(peer==null)return false;
        RemoteStore.Peer current = feature.store.peer(peer.identity, peer.generation);
        return feature.recoveryReady() && DeviceRolePreferences.supportsDom(context) && ControllerPinManager.isDomWorkspaceUnlocked() && (ControllerPinManager.isLocalDomWorkspaceUnlocked()||ControllerPinManager.hasCredentials(context))
                && current != null && current.state == RemoteStore.State.ACTIVE && current.localRole == RemoteProtocol.Role.DOM;
    }

    private void load(LinearLayout controls) {
        if(autosave!=null){autosave.close();autosave=null;}
        controls.removeAllViews();PersonUi.text(controls, activity.getString(R.string.role_loading), false);
        RemoteFeature backend = feature;RemoteStore.Peer selected = peer;android.content.Context app=application;
        long revision=ControllerPinManager.domWorkspaceRevision();
        work.load(request, () -> backend.store.authorityObservation(()->{
            if(revision!=ControllerPinManager.domWorkspaceRevision()||!editable(app,backend,selected))throw new SecurityException("Dom workspace changed");
            return backend.contracts.editing(selected);
        }), value -> {
            if (!attached(controls) || !editable()||revision!=ControllerPinManager.domWorkspaceRevision()) return;
            try {
                configuration=value;
                String old=draft.state.get("base");
                if(old!=null&&hasRawChange(new JSONObject(old)))configuration=new JSONObject(old);
                else clearDraft();
                render(controls);
            } catch (Exception invalid) { unavailable(controls); }
        }, failure -> { if (attached(controls)) unavailable(controls); });
    }

    private boolean attached(View controls) { return !activity.isFinishing() && !activity.isDestroyed() && controls.getParent() == parent && parent.isAttachedToWindow(); }

    private void render(LinearLayout controls) throws Exception {
        rendering=true;
        try {
            controls.removeAllViews();JSONObject service = configuration.getJSONObject("service");
            boolean restored=draft.state.get("choice")!=null;
            if (!restored) {
                int selected = 0;for (int i=0;i<OPTIONS.length;i++)if (OPTIONS[i] == service.getLong("duration"))selected=i;
                draft.state.set("choice", selected);draft.state.set("minimum", Long.toString(service.getLong("minimum")/60_000));
                draft.state.set("maximum", Long.toString(service.getLong("maximum")/60_000));draft.state.set("hidden", service.getBoolean("hidden"));
            }
            draft.state.set("base",configuration.toString());
            com.subhub.app.commitment.DurationChoicesView choice=new com.subhub.app.commitment.DurationChoicesView(activity);
            choice.setTag("remote_duration_choice");choice.setDuration(OPTIONS[(Integer)draft.state.get("choice")]);controls.addView(choice);
            LinearLayout range=PersonUi.column(activity);controls.addView(range);
            EditText minimum=number(range,R.string.remote_minimum_minutes,"minimum"),maximum=number(range,R.string.remote_maximum_minutes,"maximum");
            range.setVisibility(choice.duration()==-2?View.VISIBLE:View.GONE);
            choice.setOnDurationChanged(value->{
                if(!editable())return;
                for(int index=0;index<OPTIONS.length;index++)if(OPTIONS[index]==value)draft.state.set("choice",index);
                range.setVisibility(value==-2?View.VISIBLE:View.GONE);changed();
            });
            com.subhub.app.util.StateToggle hidden=new com.subhub.app.util.StateToggle(activity);hidden.setTag("remote_duration_hidden");hidden.setSaveEnabled(false);hidden.setText(R.string.remote_hide_duration);
            hidden.setChecked(Boolean.TRUE.equals(draft.state.get("hidden")));hidden.setOnCheckedChangeListener((button,checked)->{draft.state.set("hidden",checked);changed();});controls.addView(hidden);
            autosave=new RemoteConfigurationAutosave(activity,controls,feature,peer,configuration,()->{
                if(!editable())throw new SecurityException("Duration unavailable");
                try{JSONObject current=snapshot();minimum.setError(null);maximum.setError(null);return current;}catch(Exception invalid){minimum.setError(activity.getString(R.string.remote_invalid_value));maximum.setError(activity.getString(R.string.remote_invalid_value));throw invalid;}
            },()->{clearDraft();load(controls);});
            if(restored)autosave.changed();
        }finally{rendering=false;}
    }

    private void changed(){if(!rendering&&autosave!=null)autosave.changed();}
    private JSONObject snapshot() throws Exception {
        JSONObject result=new JSONObject(configuration.toString()),service=result.getJSONObject("service");
        Integer selected=draft.state.get("choice");if(selected==null||selected<0||selected>=OPTIONS.length)throw new IllegalArgumentException();
        long value=OPTIONS[selected];service.put("duration",value).put("hidden",Boolean.TRUE.equals(draft.state.get("hidden")));
        if(value==-2){long min=Long.parseLong(draft.state.get("minimum")),max=Long.parseLong(draft.state.get("maximum"));
            if(min<30||max>43200||max<min)throw new IllegalArgumentException();service.put("minimum",Math.multiplyExact(min,60_000)).put("maximum",Math.multiplyExact(max,60_000));}
        return RemoteConfiguration.active(result);
    }
    private boolean hasRawChange(JSONObject base){
        if(draft.state.get("choice")==null)return false;
        try{
            JSONObject previous=configuration;configuration=base;
            try{return !RemoteProtocol.canonical(snapshot().getJSONObject("service")).equals(RemoteProtocol.canonical(base.getJSONObject("service")));}
            finally{configuration=previous;}
        }catch(Exception invalid){return true;}
    }
    boolean hasUnappliedDraft() {
        if(configuration==null||autosave==null||autosave.busy())return true;
        // Session controls already refresh this predicate. Never enable Start from a local save receipt.
        try{
            RemoteStore.Command command=feature.store.latestCommand(peer.identity,peer.generation);
            if(command!=null&&command.pending())return true;
            if(!feature.contracts.editFailure(peer).isEmpty())return true;
            JSONObject applied=feature.contracts.current(peer);
            return applied==null||!RemoteProtocol.canonical(snapshot().getJSONObject("service")).equals(RemoteProtocol.canonical(applied.getJSONObject("service")));
        }catch(Exception unavailable){return true;}
    }
    @Override public void onPause(LifecycleOwner owner){remember();}
    @Override public void onDestroy(LifecycleOwner owner){close();}
    private void remember(){if(autosave!=null)try{autosave.flush();draft.state.set("base",autosave.base().toString());}catch(Exception ignored){}}
    private void close(){remember();if(autosave!=null){autosave.close();autosave=null;}lifecycle.removeObserver(this);}
    private EditText number(LinearLayout range,int label,String key) {
        PersonUi.text(range,activity.getString(label),false);EditText editor=new EditText(activity);editor.setTag("remote_duration_"+key);
        editor.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);com.subhub.app.util.InputBounds.limit(editor,10);editor.setText((String)draft.state.get(key));editor.setSaveEnabled(false);range.addView(editor);
        editor.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){}public void onTextChanged(CharSequence s,int start,int before,int count){draft.state.set(key,s.toString());changed();}public void afterTextChanged(Editable value){}});return editor;
    }
    private void clearDraft(){for(String key:new String[]{"fingerprint","base","choice","minimum","maximum","hidden"})draft.state.remove(key);}
    private void unavailable(LinearLayout controls){controls.removeAllViews();PersonUi.text(controls,activity.getString(R.string.remote_unknown),false);PersonUi.action(controls,R.string.role_retry,()->load(controls),false);}
}
