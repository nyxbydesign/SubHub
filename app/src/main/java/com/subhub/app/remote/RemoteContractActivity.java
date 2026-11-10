package com.subhub.app.remote;

import android.content.*;
import android.os.Bundle;
import android.widget.*;
import com.subhub.app.R;
import com.subhub.app.configuration.*;
import com.subhub.app.people.PersonUi;
import org.json.*;
import java.io.*;
import java.util.*;

/** Profile drafts never instantiate local setting managers for a remote target. */
public final class RemoteContractActivity extends RemotePage {
    private JSONObject draft,editBase;private long desired,applied;private String section="modules",artGroup="censor";
    private final List<Runnable> collect=new ArrayList<>();
    private final Map<String,android.view.View> controls=new LinkedHashMap<>();
    private Bundle restoreControls;
    private int fileAction;
    private final Map<String,ImageView> iconViews=new LinkedHashMap<>();
    private AutoCloseable appIconObserver;
    private long appIconEpoch;
    private boolean iconsResumed,rendering;
    private RemoteConfigurationAutosave autosave;
    @Override protected void onCreate(Bundle state){super.onCreate(state);
        String requested=getIntent().getStringExtra("section");if("atmosphere".equals(requested))requested="subliminal";
        if(ConfigurationSchema.SECTIONS.contains(requested)||"apps".equals(requested)||"duration".equals(requested))section=requested;
        if(state!=null)section=state.getString("section",section);
        page(sectionTitle(section));if(!selectPeer())return;
        body.addOnAttachStateChangeListener(new android.view.View.OnAttachStateChangeListener(){
            @Override public void onViewAttachedToWindow(android.view.View view){observeAppIcons();}
            @Override public void onViewDetachedFromWindow(android.view.View view){stopAppIcons();}
        });
        if(state!=null && state.containsKey("draft"))try{draft=new JSONObject(state.getString("draft"));editBase=new JSONObject(state.getString("editBase",draft.toString()));desired=state.getLong("desired");applied=state.getLong("applied");section=state.getString("section",section);artGroup=state.getString("artGroup","censor");fileAction=state.getInt("fileAction");restoreControls=state.getBundle("controls");render();if(autosave!=null)autosave.changed();return;}catch(JSONException invalid){notice(R.string.remote_reload);}
        load();
    }
    @Override protected void onResume(){super.onResume();iconsResumed=true;observeAppIcons();rebindAppIcons();}
    @Override protected void onPause(){iconsResumed=false;stopAppIcons();super.onPause();}
    @Override protected void onDestroy(){if(autosave!=null)autosave.close();stopAppIcons();iconViews.clear();super.onDestroy();}
    private void observeAppIcons(){
        if(appIconObserver!=null||!iconsResumed||peer==null||body==null||!body.isAttachedToWindow()||isFinishing())return;
        long request=++appIconEpoch;String identity=peer.identity,generation=peer.generation;
        long revision=com.subhub.app.security.ControllerPinManager.domWorkspaceRevision();
        String route=getIntent().getStringExtra(com.subhub.app.people.PersonRoutes.EXTRA_PERSON_ID);
        com.subhub.app.security.DeviceRolePreferences.Mode role=com.subhub.app.security.DeviceRolePreferences.get(this);
        com.subhub.app.security.KeyholderProvider authority=com.subhub.app.people.People.keyholder();
        com.subhub.app.relationships.RelationshipUi.Provider provider=com.subhub.app.relationships.RelationshipUi.provider();
        appIconObserver=RemoteUi.observeAppIcons(()->{
            if(revision!=com.subhub.app.security.ControllerPinManager.domWorkspaceRevision()||request!=appIconEpoch||!iconsResumed||isFinishing()||isDestroyed()||peer==null
                    ||!identity.equals(peer.identity)||!generation.equals(peer.generation)||!body.isAttachedToWindow()
                    ||!getLifecycle().getCurrentState().isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
                    ||!Objects.equals(route,getIntent().getStringExtra(com.subhub.app.people.PersonRoutes.EXTRA_PERSON_ID))
                    ||role!=com.subhub.app.security.DeviceRolePreferences.get(this)
                    ||!com.subhub.app.security.ControllerPinManager.isDomWorkspaceUnlocked()
                    ||authority!=com.subhub.app.people.People.keyholder()
                    ||provider!=com.subhub.app.relationships.RelationshipUi.provider())return;
            rebindAppIcons();
        });
    }
    private void stopAppIcons(){
        ++appIconEpoch;AutoCloseable old=appIconObserver;appIconObserver=null;
        if(old!=null)try{old.close();}catch(Exception ignored){/* Queued callbacks are fenced above. */}
    }
    private void rebindAppIcons(){
        if(!iconsResumed||peer==null||isFinishing()||!com.subhub.app.security.ControllerPinManager.isDomWorkspaceUnlocked())return;
        for(Map.Entry<String,ImageView> entry:iconViews.entrySet())if(entry.getValue().isAttachedToWindow())bindAppIcon(entry.getValue(),entry.getKey());
    }
    private void bindAppIcon(ImageView icon,String id){
        com.subhub.app.util.RemoteAppIcon remoteIcon=peer!=null&&id!=null&&id.length()<=255
                &&id.matches("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)*")?RemoteUi.appIcon(this,peer.identity,peer.generation,id):null;
        RemoteUi.bindAppIcon(icon,remoteIcon,R.drawable.ic_settings_apps);
    }
    @Override protected void onSaveInstanceState(Bundle state){
        Bundle raw=new Bundle();for(Map.Entry<String,android.view.View> control:controls.entrySet()){android.view.View value=control.getValue();if(value instanceof EditText)raw.putString(control.getKey(),((EditText)value).getText().toString());else if(value instanceof CompoundButton)raw.putBoolean(control.getKey(),((CompoundButton)value).isChecked());else if(value instanceof Spinner)raw.putInt(control.getKey(),((Spinner)value).getSelectedItemPosition());}
        try{for(Runnable item:collect)item.run();}catch(RuntimeException incomplete){/* Preserve raw input as well as the last structured values. */}
        if(autosave!=null)try{editBase=autosave.base();}catch(Exception ignored){}
        if(editBase!=null)state.putString("editBase",editBase.toString());
        if(draft!=null)state.putString("draft",draft.toString());state.putLong("desired",desired);state.putLong("applied",applied);state.putString("section",section);state.putString("artGroup",artGroup);state.putInt("fileAction",fileAction);state.putBundle("controls",raw);super.onSaveInstanceState(state);
    }
    private void restoreInputs(){if(restoreControls==null)return;for(Map.Entry<String,android.view.View> control:controls.entrySet()){String key=control.getKey();if(!restoreControls.containsKey(key))continue;android.view.View value=control.getValue();if(value instanceof EditText)((EditText)value).setText(restoreControls.getString(key));else if(value instanceof CompoundButton)((CompoundButton)value).setChecked(restoreControls.getBoolean(key));else if(value instanceof Spinner)((Spinner)value).setSelection(restoreControls.getInt(key));}restoreControls=null;}

    @Override protected boolean mayEdit() {
        return super.mayEdit() && RemoteFeaturePolicy.remoteAllowed(feature.store, peer, section);
    }

    private void load() {
        if(autosave!=null){autosave.close();autosave=null;}
        body.removeAllViews();collect.clear();controls.clear();iconViews.clear();text(body,getString(R.string.role_loading),false);
        work(()->{JSONObject doc=feature.contracts.editing(peer);if(doc==null)throw new IOException("No baseline");
            long want=Long.parseLong(feature.store.metadata(peer.identity,peer.generation,"desired_revision","0"));
            long done=Long.parseLong(feature.store.metadata(peer.identity,peer.generation,"applied_revision","0"));
            return new Object[]{doc,want,done};},result->{draft=(JSONObject)result[0];try{editBase=new JSONObject(draft.toString());}catch(JSONException invalid){notice(R.string.remote_reload);return;}desired=(Long)result[1];applied=(Long)result[2];render();},()->{body.removeAllViews();text(body,getString(R.string.remote_failure),false);button(body,R.string.role_retry,this::load);});
    }
    private boolean gather(){try{for(Runnable item:collect)item.run();RemoteConfiguration.active(draft);return true;}
        catch(RuntimeException|JSONException invalid){notice(R.string.remote_check_fields);return false;}}
    private void render() {
        if(draft==null)return;rendering=true;
        if(autosave!=null)try{autosave.flush();editBase=autosave.base();autosave.close();autosave=null;}catch(Exception invalid){notice(R.string.remote_reload);}
        body.removeAllViews();collect.clear();controls.clear();iconViews.clear();
        target();text(body,observed(RemoteProtocol.Stream.BASELINE),false);
        if(!mayEdit()){rendering=false;return;}
        try {
            if("apps".equals(section)||"duration".equals(section))text(body,draft.getJSONObject("contract").getString("name"),false);
            else {
                EditText name=input(body,R.string.remote_name,draft.getJSONObject("contract").getString("name"));
                controls.put("name",name);name.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(160)});
                collect.add(()->{String value=name.getText().toString().trim();if(value.isEmpty()){name.setError(getString(R.string.remote_invalid_value));throw new IllegalArgumentException();}name.setError(null);put(draft.optJSONObject("contract"),"name",value);});
            }
            if("subliminal".equals(section)||"popup".equals(section)) {
                button(body,R.string.subliminal_title,()->switchAtmosphere("subliminal"));
                button(body,R.string.popup_title,()->switchAtmosphere("popup"));
            }
            LinearLayout values=PersonUi.card(body);
            if("apps".equals(section))apps(values);
            else if("duration".equals(section))duration(values);
            else {
                JSONObject sections=draft.getJSONObject("contract").getJSONObject("sections"),config=sections.getJSONObject(section);
                if("censor".equals(section)||"subliminal".equals(section)) {
                    String module="censor".equals(section)?"module_censor_enabled":"module_subliminal_enabled";
                    for(ConfigurationSettingCatalog.Field field:ConfigurationSettingCatalog.fields("modules"))if(field.key.equals(module))field(values,sections.getJSONObject("modules"),field);
                }
                Map<Integer,LinearLayout> groups=new LinkedHashMap<>();
                for(ConfigurationSettingCatalog.Field field:ConfigurationSettingCatalog.fields(section)){
                    LinearLayout group=groups.get(field.group);
                    if(group==null){group=PersonUi.card(body);text(group,getString(field.group),true);groups.put(field.group,group);}
                    field(group,config,field);
                }
            }
            if("censor".equals(section)||"popup".equals(section)) {
                String group="popup".equals(section)?"popup":"censor";
                button(body,"popup".equals(group)?R.string.remote_add_popup_image:R.string.remote_add_censor_image,()->{if(gather()){artGroup=group;choose(3,"image/*");}});
                JSONArray images=draft.getJSONObject("contract").getJSONArray("assets");int count=0;
                for(int i=0;i<images.length();i++)if(images.getJSONObject(i).getString("path").startsWith("assets/"+group+"/"))count++;
                text(body,getString(R.string.remote_artwork_count,count),false);
                if(count>0)button(body,R.string.remote_remove_images,()->{if(gather())try{
                    JSONArray kept=new JSONArray(),all=draft.getJSONObject("contract").getJSONArray("assets");
                    for(int i=0;i<all.length();i++)if(!all.getJSONObject(i).getString("path").startsWith("assets/"+group+"/"))kept.put(all.getJSONObject(i));
                    put(draft.optJSONObject("contract"),"assets",kept);changed();render();
                }catch(JSONException invalid){notice(R.string.remote_reload);}});
            }
            restoreInputs();
            autosave=new RemoteConfigurationAutosave(this,body,feature,peer,editBase,()->{
                if(!mayEdit())throw new SecurityException("Editor unavailable");
                for(Runnable item:collect)item.run();
                return draft;
            },this::load);
            for(android.view.View value:controls.values()){
                // Preferences/drafts own state. Framework restoration must never submit changes.
                value.setSaveEnabled(false);
                if(value instanceof EditText)((EditText)value).addTextChangedListener(new android.text.TextWatcher(){
                    public void beforeTextChanged(CharSequence value,int start,int count,int after){}
                    public void onTextChanged(CharSequence value,int start,int before,int count){changed();}
                    public void afterTextChanged(android.text.Editable value){}
                });
                else if(value instanceof Spinner){
                    Spinner picker=(Spinner)value;int initial=picker.getSelectedItemPosition();
                    AdapterView.OnItemSelectedListener previous=picker.getOnItemSelectedListener();
                    picker.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){
                        private int selected=initial;
                        public void onNothingSelected(AdapterView<?> parent){if(previous!=null)previous.onNothingSelected(parent);}
                        public void onItemSelected(AdapterView<?> parent,android.view.View view,int position,long id){
                            if(previous!=null)previous.onItemSelected(parent,view,position,id);
                            if(selected!=position){selected=position;changed();}
                        }
                    });
                }
            }
        }catch(Exception invalid){notice(R.string.remote_reload);}finally{rendering=false;}
    }
    private void changed(){if(!rendering&&autosave!=null)autosave.changed();}
    private void switchAtmosphere(String target){if(gather()){section=target;pageTitle();render();}}
    private void pageTitle(){com.subhub.app.util.PrimaryHeader.bindSecondary(findViewById(android.R.id.content),sectionTitle(section),false);}
    private int sectionTitle(String key){switch(key){case "apps":return R.string.remote_apps;case "duration":return R.string.remote_duration;case "modules":return R.string.pack_group_features;case "censor":return R.string.sub_censor_title;case "limits":return R.string.sub_limits_title;case "wallet":return R.string.sub_wallet_title;case "subliminal":return R.string.subliminal_title;default:return R.string.popup_title;}}
    private void field(LinearLayout target,JSONObject config,ConfigurationSettingCatalog.Field field)throws Exception {
        Object value=config.get(field.key);
        if(field.kind==ConfigurationSettingCatalog.Kind.BOOLEAN){Switch toggle=new Switch(this);toggle.setText(field.label);toggle.setChecked((Boolean)value);target.addView(toggle);controls.put(field.key,toggle);collect.add(()->put(config,field.key,toggle.isChecked()));toggle.setOnCheckedChangeListener((button,on)->changed());return;}
        text(target,getString(field.label),false);
        if(field.kind==ConfigurationSettingCatalog.Kind.CHOICE){Spinner picker=new Spinner(this);List<String> labels=new ArrayList<>();for(String choice:field.choices)labels.add(getString(ConfigurationSettingCatalog.choiceLabel(field,choice)));picker.setAdapter(choices(labels));picker.setSelection(Math.max(0,field.choices.indexOf(String.valueOf(value))));target.addView(picker);controls.put(field.key,picker);collect.add(()->put(config,field.key,field.choices.get(picker.getSelectedItemPosition())));return;}
        if(field.kind==ConfigurationSettingCatalog.Kind.SELECTION && !field.choices.isEmpty()){
            Set<String> selected=new LinkedHashSet<>();JSONArray items=(JSONArray)value;for(int i=0;i<items.length();i++)selected.add(items.getString(i));
            for(String choice:field.choices){CheckBox box=new CheckBox(this);box.setText(ConfigurationSettingCatalog.choiceLabel(field,choice));box.setChecked(selected.contains(choice));target.addView(box);controls.put(field.key+":"+choice,box);box.setOnCheckedChangeListener((b,checked)->{if(checked)selected.add(choice);else selected.remove(choice);changed();});}
            collect.add(()->put(config,field.key,new JSONArray(selected)));return;
        }
        EditText editor=new EditText(this);editor.setSaveEnabled(false);
        int maximum=field.kind==ConfigurationSettingCatalog.Kind.SELECTION?200*(field.maximumLength+1)-1:
                field.kind==ConfigurationSettingCatalog.Kind.TEXT?field.maximumLength:field.kind==ConfigurationSettingCatalog.Kind.COLOR?9:32;
        com.subhub.app.util.InputBounds.limit(editor,maximum);
        editor.setText(field.kind==ConfigurationSettingCatalog.Kind.SELECTION?join((JSONArray)value):field.displayText(value));target.addView(editor);controls.put(field.key,editor);
        if(field.kind!=ConfigurationSettingCatalog.Kind.TEXT && field.kind!=ConfigurationSettingCatalog.Kind.COLOR && field.kind!=ConfigurationSettingCatalog.Kind.SELECTION)editor.setInputType(android.text.InputType.TYPE_CLASS_NUMBER|android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL|android.text.InputType.TYPE_NUMBER_FLAG_SIGNED);
        collect.add(()->{try{Object parsed=field.kind==ConfigurationSettingCatalog.Kind.SELECTION?field.normalize(new JSONArray(Arrays.asList(editor.getText().toString().split("\n")))):field.parseText(editor.getText().toString());editor.setError(null);put(config,field.key,parsed);}catch(RuntimeException invalid){editor.setError(getString(R.string.remote_invalid_value));throw invalid;}});
    }
    private void apps(LinearLayout target)throws Exception {
        JSONObject apps=draft.getJSONObject("apps"),budgets=apps.getJSONObject("allowances");Set<String> selected=RemoteConfiguration.selected(draft);
        Switch all=new Switch(this);all.setText(R.string.remote_all_apps);all.setChecked(apps.getBoolean("all"));target.addView(all);controls.put("apps_all",all);collect.add(()->put(apps,"all",all.isChecked()));
        List<RemoteProtocol.Change> catalog=feature.store.mirror(peer.identity,peer.generation,RemoteProtocol.Stream.APP_CATALOG);
        if(catalog.isEmpty())text(target,getString(R.string.remote_unknown),false);
        all.setOnCheckedChangeListener((button,on)->{for(Map.Entry<String,android.view.View> control:controls.entrySet())if(control.getKey().startsWith("budget:")){CheckBox box=(CheckBox)controls.get("app:"+control.getKey().substring(7));boolean enabled=on||box!=null&&box.isChecked();control.getValue().setEnabled(enabled);((android.view.View)control.getValue().getParent()).setVisibility(enabled?android.view.View.VISIBLE:android.view.View.GONE);}changed();});
        Set<String> shown=new HashSet<>();
        for(RemoteProtocol.Change entry:catalog)if(!entry.deleted){JSONObject data=new JSONObject(entry.body);String id=entry.key;shown.add(id);app(target,selected,budgets,id,data.getString("label"));}
        for(String id:new TreeSet<>(selected))if(!shown.contains(id))app(target,selected,budgets,id,id);
        collect.add(()->{put(apps,"packages",new JSONArray(new TreeSet<>(selected)));Iterator<String> names=budgets.keys();List<String> remove=new ArrayList<>();while(names.hasNext()){String key=names.next();if(!selected.contains(key)&&!all.isChecked())remove.add(key);}for(String key:remove)budgets.remove(key);});
    }
    private void app(LinearLayout target,Set<String> selected,JSONObject budgets,String id,String label){
        LinearLayout row=new LinearLayout(this);row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        android.widget.ImageView icon=new android.widget.ImageView(this);icon.setTag("app_icon:"+id);
        iconViews.put(id,icon);bindAppIcon(icon,id);
        LinearLayout.LayoutParams size=new LinearLayout.LayoutParams(PersonUi.dp(this,28),PersonUi.dp(this,28));size.setMarginEnd(PersonUi.dp(this,8));row.addView(icon,size);
        CheckBox box=new CheckBox(this);box.setText(label);box.setMinHeight(PersonUi.dp(this,48));box.setChecked(selected.contains(id));row.addView(box,new LinearLayout.LayoutParams(0,-2,1));target.addView(row);controls.put("app:"+id,box);box.setOnCheckedChangeListener((b,on)->{if(on)selected.add(id);else selected.remove(id);});
        int inherited=draft.optJSONObject("contract").optJSONObject("sections").optJSONObject("limits").optInt("app_timer_per_app_minutes",30);boolean custom=budgets.has(id);
        LinearLayout allowance=PersonUi.column(this);target.addView(allowance);allowance.setVisibility(selected.contains(id)||draft.optJSONObject("apps").optBoolean("all")?android.view.View.VISIBLE:android.view.View.GONE);
        EditText minutes=input(allowance,R.string.remote_allowance_minutes,Integer.toString(budgets.optInt(id,inherited)));minutes.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);controls.put("budget:"+id,minutes);
        minutes.setEnabled(selected.contains(id)||draft.optJSONObject("apps").optBoolean("all"));box.setOnCheckedChangeListener((b,on)->{if(on)selected.add(id);else selected.remove(id);boolean enabled=on||((Switch)controls.get("apps_all")).isChecked();minutes.setEnabled(enabled);allowance.setVisibility(enabled?android.view.View.VISIBLE:android.view.View.GONE);changed();});
        collect.add(()->{if(!selected.contains(id)&&!draft.optJSONObject("apps").optBoolean("all"))return;try{int amount=Integer.parseInt(minutes.getText().toString());if(amount<1||amount>1440)throw new IllegalArgumentException();minutes.setError(null);if(custom||amount!=inherited)put(budgets,id,amount);else budgets.remove(id);}catch(IllegalArgumentException invalid){minutes.setError(getString(R.string.remote_invalid_value));throw invalid;}});
    }
    private ArrayAdapter<String> choices(List<String> labels) {
        return new ArrayAdapter<String>(this,android.R.layout.simple_spinner_item,labels) {
            private android.view.View fit(android.view.View view){TextView text=(TextView)view;text.setSingleLine(false);text.setMaxLines(Integer.MAX_VALUE);text.setEllipsize(null);text.setFocusable(false);text.setClickable(false);return view;}
            @Override public android.view.View getView(int position,android.view.View recycled,android.view.ViewGroup parent){return fit(super.getView(position,recycled,parent));}
            @Override public android.view.View getDropDownView(int position,android.view.View recycled,android.view.ViewGroup parent){setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);return fit(super.getDropDownView(position,recycled,parent));}
        };
    }
    private void duration(LinearLayout target)throws Exception {
        JSONObject service=draft.getJSONObject("service");long[] options={-1,-2,3_600_000,86_400_000,604_800_000,2_592_000_000L};
        Spinner choice=new Spinner(this);choice.setAdapter(choices(Arrays.asList(getResources().getStringArray(R.array.remote_duration_choices))));for(int i=0;i<options.length;i++)if(options[i]==service.getLong("duration"))choice.setSelection(i);target.addView(choice);controls.put("service_duration",choice);collect.add(()->put(service,"duration",options[choice.getSelectedItemPosition()]));
        LinearLayout bounds=PersonUi.column(this);target.addView(bounds);
        EditText min=input(bounds,R.string.remote_minimum_minutes,Long.toString(service.getLong("minimum")/60_000)),max=input(bounds,R.string.remote_maximum_minutes,Long.toString(service.getLong("maximum")/60_000));min.setInputType(2);max.setInputType(2);controls.put("service_minimum",min);controls.put("service_maximum",max);
        bounds.setVisibility(options[choice.getSelectedItemPosition()]==-2?android.view.View.VISIBLE:android.view.View.GONE);
        choice.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onNothingSelected(AdapterView<?> parent){}public void onItemSelected(AdapterView<?> parent,android.view.View view,int position,long id){bounds.setVisibility(options[position]==-2?android.view.View.VISIBLE:android.view.View.GONE);}});
        collect.add(()->{if(options[choice.getSelectedItemPosition()]!=-2)return;try{long minimum=Long.parseLong(min.getText().toString()),maximum=Long.parseLong(max.getText().toString());if(minimum<30||maximum>43200||maximum<minimum)throw new IllegalArgumentException();min.setError(null);max.setError(null);put(service,"minimum",Math.multiplyExact(minimum,60_000));put(service,"maximum",Math.multiplyExact(maximum,60_000));}catch(IllegalArgumentException|ArithmeticException invalid){min.setError(getString(R.string.remote_invalid_value));max.setError(getString(R.string.remote_invalid_value));throw invalid;}});
        Switch hidden=new Switch(this);hidden.setText(R.string.remote_hide_duration);hidden.setChecked(service.getBoolean("hidden"));target.addView(hidden);controls.put("service_hidden",hidden);collect.add(()->put(service,"hidden",hidden.isChecked()));hidden.setOnCheckedChangeListener((button,on)->changed());
    }
    private void choose(int action,String mime){if(!mayEdit())return;fileAction=action;startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType(mime).addCategory(Intent.CATEGORY_OPENABLE),action);}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request!=3||result!=RESULT_OK||data==null||data.getData()==null)return;
        if(!mayEdit()||draft==null){notice(R.string.remote_reload);return;}android.net.Uri uri=data.getData();String group=artGroup;
        if(!"censor".equals(group)&&!"popup".equals(group)){notice(R.string.remote_reload);return;}
        authorize(()->work(()->{if(!mayEdit())throw new SecurityException();
            File temporary=com.subhub.app.storage.DisposableCache.temporary(getCacheDir(),"peer-import-");
            try{try(InputStream in=getContentResolver().openInputStream(uri);OutputStream out=new FileOutputStream(temporary)){if(in==null)throw new IOException();byte[] buffer=new byte[8192];long total=0;int count;while((count=in.read(buffer))!=-1){total+=count;if(total>RemoteAssets.MAX_ASSET_BYTES)throw new IOException();out.write(buffer,0,count);}}
                android.graphics.BitmapFactory.Options check=bounds();android.graphics.BitmapFactory.decodeFile(temporary.getPath(),check);if(check.outWidth<=0||check.outHeight<=0)throw new IOException();
                String hash=RemoteAssets.digest(temporary);try(InputStream in=new FileInputStream(temporary)){RemoteContracts.ingest(feature.contracts.assets(peer),hash,temporary.length(),in);}
                return new JSONObject().put("path","assets/"+group+"/"+UUID.randomUUID()+".png").put("hash",hash).put("length",temporary.length());
            }finally{com.subhub.app.storage.DisposableCache.releaseTemporary(temporary);}
        },asset->{if(asset instanceof JSONObject){try{draft.getJSONObject("contract").getJSONArray("assets").put(asset);}catch(JSONException invalid){notice(R.string.remote_reload);}}changed();render();}));
    }
    private static android.graphics.BitmapFactory.Options bounds(){android.graphics.BitmapFactory.Options value=new android.graphics.BitmapFactory.Options();value.inJustDecodeBounds=true;return value;}
    private static String join(JSONArray items)throws JSONException{List<String> result=new ArrayList<>();for(int i=0;i<items.length();i++)result.add(items.getString(i));return String.join("\n",result);}
    private static void put(JSONObject target,String key,Object value){try{target.put(key,value);}catch(JSONException invalid){throw new IllegalArgumentException(invalid);}}
}
