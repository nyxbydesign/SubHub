package com.subhub.app.remote;

import android.app.Activity;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import androidx.lifecycle.*;
import com.subhub.app.R;
import com.subhub.app.people.*;
import com.subhub.app.security.ControllerPinGate;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.security.DeviceRolePreferences;
import com.subhub.app.util.AsyncUiScope;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.json.JSONObject;

/** An inline duration draft queues the same revision-checked command as the full profile editor. */
public final class RemoteDurationControls {
    private static final long[] OPTIONS = {-1, -2, 3_600_000, 86_400_000, 604_800_000, 2_592_000_000L};
    public static final class Draft extends ViewModel {
        final SavedStateHandle state;
        public Draft(SavedStateHandle state) { this.state = state; }
    }
    private final Activity activity;
    private final android.content.Context application;
    private final LinearLayout parent;
    private final PersonId person;
    private final RemoteFeature feature;
    private final RemoteStore.Peer peer;
    private final AsyncUiScope work;
    private final Draft draft;
    private final String request;
    private JSONObject configuration;
    private String fingerprint;
    private long desired, applied;

    RemoteDurationControls(Activity activity, LinearLayout parent, PersonSnapshot person) {
        this.activity = activity;this.application=activity.getApplicationContext();this.parent = parent;this.person = person.id;
        feature = RemoteFeature.get(activity);peer = new RemotePersonRepository(activity, feature).peer(person.id);
        work = AsyncUiScope.forContext(activity);request = "inline-duration:" + person.id.value();
        draft = new ViewModelProvider((ViewModelStoreOwner) activity).get(request, Draft.class);
    }

    void bind() {
        LinearLayout controls = PersonUi.column(activity);controls.setTag("remote_session_duration");parent.addView(controls);
        if (!editable()) { PersonUi.text(controls, activity.getString(R.string.remote_read_only), false);return; }
        load(controls);
    }

    private boolean editable() {
        return editable(application,feature,peer);
    }
    private static boolean editable(android.content.Context context,RemoteFeature feature,RemoteStore.Peer peer) {
        RemoteStore.Peer current = feature.store.peer(peer.identity, peer.generation);
        return feature.recoveryReady() && DeviceRolePreferences.supportsDom(context) && ControllerPinManager.isDomModeActive() && ControllerPinManager.hasCredentials(context)
                && current != null && current.state == RemoteStore.State.ACTIVE && current.localRole == RemoteProtocol.Role.DOM;
    }

    private void load(LinearLayout controls) {
        controls.removeAllViews();PersonUi.text(controls, activity.getString(R.string.role_loading), false);
        RemoteStore store = feature.store;RemoteFeature backend = feature;RemoteStore.Peer selected = peer;android.content.Context app=application;
        work.load(request, () -> {
            synchronized (store) {
                if(!editable(app,backend,selected))throw new SecurityException("Dom access required");
                RemoteStore.Peer current = store.peer(selected.identity, selected.generation);
                if (current == null || current.state != RemoteStore.State.ACTIVE || current.localRole != RemoteProtocol.Role.DOM)
                    throw new SecurityException("Relationship unavailable");
                JSONObject value = backend.contracts.current(current);if (value == null) throw new IllegalStateException("Source configuration unavailable");
                RemoteStore.Command command=store.latestCommand(current.identity,current.generation);
                if(command!=null&&command.pending())value=RemoteConfiguration.active(new JSONObject(command.body).getJSONObject("configuration"));
                return new Object[]{value, Long.parseLong(store.metadata(current.identity,current.generation,"desired_revision","0")),
                        Long.parseLong(store.metadata(current.identity,current.generation,"applied_revision","0")), command};
            }
        }, result -> {
            if (!attached(controls) || !editable()) return;
            configuration = (JSONObject) result[0];desired = (Long) result[1];applied = (Long) result[2];
            try {
                fingerprint = RemoteProtocol.sha256(RemoteProtocol.canonical(configuration).getBytes(StandardCharsets.UTF_8));
                String previous = draft.state.get("fingerprint");
                if (previous != null && !previous.equals(fingerprint)) { changed(controls);return; }
                draft.state.set("fingerprint", fingerprint);
                RemoteStore.Command command = (RemoteStore.Command) result[3];
                render(controls, command != null && command.pending());
            } catch (Exception invalid) { unavailable(controls); }
        }, failure -> { if (attached(controls)) unavailable(controls); });
    }

    private boolean attached(View controls) { return !activity.isFinishing() && !activity.isDestroyed() && controls.getParent() == parent && parent.isAttachedToWindow(); }

    private void render(LinearLayout controls, boolean pending) throws Exception {
        controls.removeAllViews();JSONObject service = configuration.getJSONObject("service");
        if (draft.state.get("choice") == null) {
            int selected = 0;for (int i=0;i<OPTIONS.length;i++)if (OPTIONS[i] == service.getLong("duration"))selected=i;
            draft.state.set("choice", selected);draft.state.set("minimum", Long.toString(service.getLong("minimum")/60_000));
            draft.state.set("maximum", Long.toString(service.getLong("maximum")/60_000));draft.state.set("hidden", service.getBoolean("hidden"));
        }
        Spinner choice = new Spinner(activity);choice.setTag("remote_duration_choice");
        choice.setAdapter(new ArrayAdapter<String>(activity, android.R.layout.simple_spinner_item, Arrays.asList(activity.getResources().getStringArray(R.array.remote_duration_choices))) {
            private View fit(View value) { TextView text=(TextView)value;text.setSingleLine(false);text.setEllipsize(null);text.setFocusable(false);text.setClickable(false);return value; }
            @Override public View getView(int p,View recycled,ViewGroup parent){return fit(super.getView(p,recycled,parent));}
            @Override public View getDropDownView(int p,View recycled,ViewGroup parent){setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);return fit(super.getDropDownView(p,recycled,parent));}
        });
        choice.setSelection((Integer) draft.state.get("choice"));controls.addView(choice);
        LinearLayout range=PersonUi.column(activity);controls.addView(range);
        EditText minimum=number(range,R.string.remote_minimum_minutes,"minimum"),maximum=number(range,R.string.remote_maximum_minutes,"maximum");
        range.setVisibility(OPTIONS[choice.getSelectedItemPosition()]==-2?View.VISIBLE:View.GONE);
        choice.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){
            public void onNothingSelected(AdapterView<?> source){}
            public void onItemSelected(AdapterView<?> source,View view,int position,long id){draft.state.set("choice",position);range.setVisibility(OPTIONS[position]==-2?View.VISIBLE:View.GONE);}
        });
        Switch hidden=new Switch(activity);hidden.setTag("remote_duration_hidden");hidden.setText(R.string.remote_hide_duration);
        hidden.setChecked(Boolean.TRUE.equals(draft.state.get("hidden")));hidden.setOnCheckedChangeListener((button,checked)->draft.state.set("hidden",checked));controls.addView(hidden);
        TextView status=PersonUi.text(controls, pending?activity.getString(R.string.role_changes_pending):"",false);status.setVisibility(pending?View.VISIBLE:View.GONE);
        TextView apply=PersonUi.action(controls,R.string.remote_apply,()->{
            if(!editable()||pending)return;
            try {
                JSONObject snapshot=new JSONObject(configuration.toString()), duration=snapshot.getJSONObject("service");long value=OPTIONS[choice.getSelectedItemPosition()];
                duration.put("duration",value).put("hidden",hidden.isChecked());
                if(value==-2){long min=Long.parseLong(minimum.getText().toString()),max=Long.parseLong(maximum.getText().toString());
                    if(min<30||max>43200||max<min)throw new IllegalArgumentException();duration.put("minimum",Math.multiplyExact(min,60_000)).put("maximum",Math.multiplyExact(max,60_000));}
                RemoteConfiguration.active(snapshot);
                ControllerPinGate.require(activity,()->save(controls,snapshot,status),false);
            } catch(Exception invalid){minimum.setError(activity.getString(R.string.remote_invalid_value));maximum.setError(activity.getString(R.string.remote_invalid_value));}
        },true);apply.setTag("remote_duration_apply");apply.setEnabled(!pending);
    }

    private void save(LinearLayout controls,JSONObject snapshot,TextView status) {
        String expected=fingerprint;long expectedDesired=desired,expectedApplied=applied;RemoteFeature backend=feature;RemoteStore.Peer selected=peer;android.content.Context app=application;
        work.load(request+":save",()->{
            synchronized(backend.store){
                if(!editable(app,backend,selected))throw new SecurityException("Relationship unavailable");
                JSONObject current=backend.contracts.current(selected);
                if(current==null||!expected.equals(RemoteProtocol.sha256(RemoteProtocol.canonical(current).getBytes(StandardCharsets.UTF_8))))
                    throw new IllegalStateException("Configuration changed");
                backend.store.queueConfiguration(selected.identity,selected.generation,expectedDesired,expectedApplied,snapshot);
            }
            backend.changed();return true;
        },saved->{if(!attached(controls)||!editable())return;clearDraft();load(controls);},failure->{if(attached(controls)){status.setText(R.string.remote_reload);status.setVisibility(View.VISIBLE);}});
    }
    private EditText number(LinearLayout range,int label,String key) {
        PersonUi.text(range,activity.getString(label),false);EditText editor=new EditText(activity);editor.setTag("remote_duration_"+key);
        editor.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);editor.setText((String)draft.state.get(key));editor.setSaveEnabled(false);range.addView(editor);
        editor.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){}public void onTextChanged(CharSequence s,int start,int before,int count){draft.state.set(key,s.toString());}public void afterTextChanged(Editable value){}});return editor;
    }
    private void changed(LinearLayout controls) { controls.removeAllViews();PersonUi.text(controls,activity.getString(R.string.remote_reload),false);PersonUi.action(controls,R.string.remote_duration_reload,()->{clearDraft();load(controls);},false); }
    private void clearDraft(){for(String key:new String[]{"fingerprint","choice","minimum","maximum","hidden"})draft.state.remove(key);}
    private void unavailable(LinearLayout controls){controls.removeAllViews();PersonUi.text(controls,activity.getString(R.string.remote_unknown),false);PersonUi.action(controls,R.string.role_retry,()->load(controls),false);}
}
