package com.subhub.app.remote;

import android.content.*;
import android.os.Bundle;
import android.widget.*;
import com.subhub.app.R;
import com.subhub.app.pack.*;
import com.subhub.app.people.PersonUi;
import org.json.*;
import java.io.*;
import java.util.*;

/** Portable drafts never instantiate local setting managers for a remote target. */
public final class RemoteContractActivity extends RemotePage {
    private JSONObject draft;private long desired,applied;private String section="modules",artGroup="censor";
    private final List<Runnable> collect=new ArrayList<>();
    private final Map<String,android.view.View> controls=new LinkedHashMap<>();
    private Bundle restoreControls;
    private int fileAction;
    @Override protected void onCreate(Bundle state){super.onCreate(state);
        String requested=getIntent().getStringExtra("section");if("atmosphere".equals(requested))requested="subliminal";
        if(SubHubPackSchema.SECTIONS.contains(requested)||"apps".equals(requested)||"duration".equals(requested))section=requested;
        if(state!=null)section=state.getString("section",section);
        page(sectionTitle(section));if(!selectPeer())return;
        if(state!=null && state.containsKey("draft"))try{draft=new JSONObject(state.getString("draft"));desired=state.getLong("desired");applied=state.getLong("applied");section=state.getString("section",section);artGroup=state.getString("artGroup","censor");fileAction=state.getInt("fileAction");restoreControls=state.getBundle("controls");render();return;}catch(JSONException invalid){notice(R.string.remote_reload);}
        load();
    }
    @Override protected void onSaveInstanceState(Bundle state){
        Bundle raw=new Bundle();for(Map.Entry<String,android.view.View> control:controls.entrySet()){android.view.View value=control.getValue();if(value instanceof EditText)raw.putString(control.getKey(),((EditText)value).getText().toString());else if(value instanceof CompoundButton)raw.putBoolean(control.getKey(),((CompoundButton)value).isChecked());else if(value instanceof Spinner)raw.putInt(control.getKey(),((Spinner)value).getSelectedItemPosition());}
        try{for(Runnable item:collect)item.run();}catch(RuntimeException incomplete){/* Preserve raw input as well as the last structured values. */}
        if(draft!=null)state.putString("draft",draft.toString());state.putLong("desired",desired);state.putLong("applied",applied);state.putString("section",section);state.putString("artGroup",artGroup);state.putInt("fileAction",fileAction);state.putBundle("controls",raw);super.onSaveInstanceState(state);
    }
    private void restoreInputs(){if(restoreControls==null)return;for(Map.Entry<String,android.view.View> control:controls.entrySet()){String key=control.getKey();if(!restoreControls.containsKey(key))continue;android.view.View value=control.getValue();if(value instanceof EditText)((EditText)value).setText(restoreControls.getString(key));else if(value instanceof CompoundButton)((CompoundButton)value).setChecked(restoreControls.getBoolean(key));else if(value instanceof Spinner)((Spinner)value).setSelection(restoreControls.getInt(key));}restoreControls=null;}

    private void load() {
        body.removeAllViews();collect.clear();controls.clear();text(body,getString(R.string.role_loading),false);
        work(()->{JSONObject doc=feature.contracts.current(peer);if(doc==null)throw new IOException("No baseline");
            long want=Long.parseLong(feature.store.metadata(peer.identity,peer.generation,"desired_revision","0"));
            long done=Long.parseLong(feature.store.metadata(peer.identity,peer.generation,"applied_revision","0"));
            return new Object[]{doc,want,done};},result->{draft=(JSONObject)result[0];desired=(Long)result[1];applied=(Long)result[2];render();});
    }
    private boolean gather(){try{for(Runnable item:collect)item.run();draft=RemoteConfiguration.active(draft);return true;}
        catch(RuntimeException|JSONException invalid){notice(R.string.remote_check_fields);return false;}}
    private void render() {
        if(draft==null)return;body.removeAllViews();collect.clear();controls.clear();
        text(body,peer.name,true);text(body,observed(RemoteProtocol.Stream.BASELINE),false);
        RemoteStore.Command latest=feature.store.latestCommand(peer.identity,peer.generation);
        if(latest!=null)text(body,commandState(latest),false);
        if(!mayEdit()){text(body,getString(R.string.remote_read_only),false);return;}
        try {
            EditText name=input(body,R.string.remote_name,draft.getJSONObject("contract").getString("name"));
            controls.put("name",name);name.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(160)});
            collect.add(()->{String value=name.getText().toString().trim();if(value.isEmpty()){name.setError(getString(R.string.remote_invalid_value));throw new IllegalArgumentException();}put(draft.optJSONObject("contract"),"name",value);});
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
                    for(PackSettingCatalog.Field field:PackSettingCatalog.fields("modules"))if(field.key.equals(module))field(values,sections.getJSONObject("modules"),field);
                }
                for(PackSettingCatalog.Field field:PackSettingCatalog.fields(section))field(values,config,field);
            }
            button(body,R.string.remote_save_contract,()->save(false));button(body,R.string.remote_apply,()->save(true));
            button(body,R.string.role_retry,this::load);
            if("censor".equals(section)||"popup".equals(section)) {
                String group="popup".equals(section)?"popup":"censor";
                button(body,"popup".equals(group)?R.string.remote_add_popup_image:R.string.remote_add_censor_image,()->{if(gather()){artGroup=group;choose(3,"image/*");}});
                JSONArray images=draft.getJSONObject("contract").getJSONArray("assets");int count=0;
                for(int i=0;i<images.length();i++)if(images.getJSONObject(i).getString("path").startsWith("assets/"+group+"/"))count++;
                text(body,getString(R.string.remote_artwork_count,count),false);
                if(count>0)button(body,R.string.remote_remove_images,()->{if(gather())try{
                    JSONArray kept=new JSONArray(),all=draft.getJSONObject("contract").getJSONArray("assets");
                    for(int i=0;i<all.length();i++)if(!all.getJSONObject(i).getString("path").startsWith("assets/"+group+"/"))kept.put(all.getJSONObject(i));
                    put(draft.optJSONObject("contract"),"assets",kept);render();
                }catch(JSONException invalid){notice(R.string.remote_reload);}});
            }
            restoreInputs();
        }catch(Exception invalid){notice(R.string.remote_reload);}
    }
    private String commandState(RemoteStore.Command command){
        switch(command.state){case APPLIED:return getString(R.string.remote_applied);case CONFLICT:return getString(R.string.remote_conflict);case REJECTED:return getString(R.string.remote_rejected);default:return getString(R.string.role_changes_pending);}
    }
    private void switchAtmosphere(String target){if(gather()){section=target;pageTitle();render();}}
    private void pageTitle(){com.subhub.app.util.PrimaryHeader.bindSecondary(findViewById(android.R.id.content),sectionTitle(section),false);}
    private int sectionTitle(String key){switch(key){case "apps":return R.string.remote_apps;case "duration":return R.string.remote_duration;case "modules":return R.string.pack_group_features;case "censor":return R.string.sub_censor_title;case "limits":return R.string.sub_limits_title;case "wallet":return R.string.sub_wallet_title;case "subliminal":return R.string.subliminal_title;default:return R.string.popup_title;}}
    private void field(LinearLayout target,JSONObject config,PackSettingCatalog.Field field)throws Exception {
        Object value=config.get(field.key);
        if(field.kind==PackSettingCatalog.Kind.BOOLEAN){Switch toggle=new Switch(this);toggle.setText(field.label);toggle.setChecked((Boolean)value);target.addView(toggle);controls.put(field.key,toggle);collect.add(()->put(config,field.key,toggle.isChecked()));return;}
        text(target,getString(field.label),false);
        if(field.kind==PackSettingCatalog.Kind.CHOICE){Spinner picker=new Spinner(this);List<String> labels=new ArrayList<>();for(String choice:field.choices)labels.add(getString(PackSettingCatalog.choiceLabel(field,choice)));ArrayAdapter<String> adapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,labels);adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);picker.setAdapter(adapter);picker.setSelection(Math.max(0,field.choices.indexOf(String.valueOf(value))));target.addView(picker);controls.put(field.key,picker);collect.add(()->put(config,field.key,field.choices.get(picker.getSelectedItemPosition())));return;}
        if(field.kind==PackSettingCatalog.Kind.SELECTION && !field.choices.isEmpty()){
            Set<String> selected=new LinkedHashSet<>();JSONArray items=(JSONArray)value;for(int i=0;i<items.length();i++)selected.add(items.getString(i));
            for(String choice:field.choices){CheckBox box=new CheckBox(this);box.setText(PackSettingCatalog.choiceLabel(field,choice));box.setChecked(selected.contains(choice));target.addView(box);controls.put(field.key+":"+choice,box);box.setOnCheckedChangeListener((b,checked)->{if(checked)selected.add(choice);else selected.remove(choice);});}
            collect.add(()->put(config,field.key,new JSONArray(selected)));return;
        }
        EditText editor=new EditText(this);editor.setSaveEnabled(false);editor.setText(field.kind==PackSettingCatalog.Kind.SELECTION?join((JSONArray)value):field.displayText(value));target.addView(editor);controls.put(field.key,editor);
        if(field.kind!=PackSettingCatalog.Kind.TEXT && field.kind!=PackSettingCatalog.Kind.COLOR && field.kind!=PackSettingCatalog.Kind.SELECTION)editor.setInputType(android.text.InputType.TYPE_CLASS_NUMBER|android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL|android.text.InputType.TYPE_NUMBER_FLAG_SIGNED);
        collect.add(()->{try{Object parsed=field.kind==PackSettingCatalog.Kind.SELECTION?field.normalize(new JSONArray(Arrays.asList(editor.getText().toString().split("\n")))):field.parseText(editor.getText().toString());put(config,field.key,parsed);}catch(RuntimeException invalid){editor.setError(getString(R.string.remote_invalid_value));throw invalid;}});
    }
    private void apps(LinearLayout target)throws Exception {
        JSONObject apps=draft.getJSONObject("apps"),budgets=apps.getJSONObject("allowances");Set<String> selected=RemoteConfiguration.selected(draft);
        Switch all=new Switch(this);all.setText(R.string.remote_all_apps);all.setChecked(apps.getBoolean("all"));target.addView(all);controls.put("apps_all",all);collect.add(()->put(apps,"all",all.isChecked()));
        List<RemoteProtocol.Change> catalog=feature.store.mirror(peer.identity,peer.generation,RemoteProtocol.Stream.APP_CATALOG);
        if(catalog.isEmpty())text(target,getString(R.string.remote_unknown),false);
        all.setOnCheckedChangeListener((button,on)->{for(Map.Entry<String,android.view.View> control:controls.entrySet())if(control.getKey().startsWith("budget:")){CheckBox box=(CheckBox)controls.get("app:"+control.getKey().substring(7));control.getValue().setEnabled(on||box!=null&&box.isChecked());}});
        Set<String> shown=new HashSet<>();
        for(RemoteProtocol.Change entry:catalog)if(!entry.deleted){JSONObject data=new JSONObject(entry.body);String id=entry.key;shown.add(id);app(target,selected,budgets,id,data.getString("label"));}
        for(String id:new TreeSet<>(selected))if(!shown.contains(id))app(target,selected,budgets,id,id);
        collect.add(()->{put(apps,"packages",new JSONArray(new TreeSet<>(selected)));Iterator<String> names=budgets.keys();List<String> remove=new ArrayList<>();while(names.hasNext()){String key=names.next();if(!selected.contains(key)&&!all.isChecked())remove.add(key);}for(String key:remove)budgets.remove(key);});
    }
    private void app(LinearLayout target,Set<String> selected,JSONObject budgets,String id,String label){
        CheckBox box=new CheckBox(this);box.setText(label);box.setChecked(selected.contains(id));target.addView(box);controls.put("app:"+id,box);box.setOnCheckedChangeListener((b,on)->{if(on)selected.add(id);else selected.remove(id);});
        int inherited=draft.optJSONObject("contract").optJSONObject("sections").optJSONObject("limits").optInt("app_timer_per_app_minutes",30);boolean custom=budgets.has(id);
        EditText minutes=input(target,R.string.remote_allowance_minutes,Integer.toString(budgets.optInt(id,inherited)));minutes.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);controls.put("budget:"+id,minutes);
        minutes.setEnabled(selected.contains(id)||draft.optJSONObject("apps").optBoolean("all"));box.setOnCheckedChangeListener((b,on)->{if(on)selected.add(id);else selected.remove(id);minutes.setEnabled(on||((Switch)controls.get("apps_all")).isChecked());});
        collect.add(()->{if(!selected.contains(id)&&!draft.optJSONObject("apps").optBoolean("all"))return;try{int amount=Integer.parseInt(minutes.getText().toString());if(amount<1||amount>1440)throw new IllegalArgumentException();if(custom||amount!=inherited)put(budgets,id,amount);}catch(IllegalArgumentException invalid){minutes.setError(getString(R.string.remote_invalid_value));throw invalid;}});
    }
    private void duration(LinearLayout target)throws Exception {
        JSONObject service=draft.getJSONObject("service");long[] options={-1,-2,3_600_000,86_400_000,604_800_000,2_592_000_000L};
        Spinner choice=new Spinner(this);ArrayAdapter<CharSequence> adapter=ArrayAdapter.createFromResource(this,R.array.remote_duration_choices,android.R.layout.simple_spinner_item);adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);choice.setAdapter(adapter);for(int i=0;i<options.length;i++)if(options[i]==service.getLong("duration"))choice.setSelection(i);target.addView(choice);controls.put("service_duration",choice);collect.add(()->put(service,"duration",options[choice.getSelectedItemPosition()]));
        EditText min=input(target,R.string.remote_minimum_minutes,Long.toString(service.getLong("minimum")/60_000)),max=input(target,R.string.remote_maximum_minutes,Long.toString(service.getLong("maximum")/60_000));min.setInputType(2);max.setInputType(2);controls.put("service_minimum",min);controls.put("service_maximum",max);
        collect.add(()->{try{long minimum=Long.parseLong(min.getText().toString()),maximum=Long.parseLong(max.getText().toString());if(minimum<30||maximum>43200||maximum<minimum)throw new IllegalArgumentException();put(service,"minimum",Math.multiplyExact(minimum,60_000));put(service,"maximum",Math.multiplyExact(maximum,60_000));}catch(IllegalArgumentException|ArithmeticException invalid){min.setError(getString(R.string.remote_invalid_value));max.setError(getString(R.string.remote_invalid_value));throw invalid;}});
        Switch hidden=new Switch(this);hidden.setText(R.string.remote_hide_duration);hidden.setChecked(service.getBoolean("hidden"));target.addView(hidden);controls.put("service_hidden",hidden);collect.add(()->put(service,"hidden",hidden.isChecked()));
    }
    private void save(boolean apply){if(!mayEdit()||!gather()){notice(R.string.remote_reload);return;}authorize(()->{try{JSONObject snapshot=new JSONObject(draft.toString());work(()->{
        if(!mayEdit())throw new SecurityException();
        if(apply)feature.store.queueConfiguration(peer.identity,peer.generation,desired,applied,snapshot);
        else{JSONObject contract=snapshot.getJSONObject("contract");contract.put("id",UUID.randomUUID().toString()).put("updatedAt",System.currentTimeMillis());feature.contracts.save(peer,snapshot);}
        feature.changed();return null;
    },v->{notice(R.string.remote_saved);load();});}catch(JSONException invalid){notice(R.string.remote_reload);}});}
    private void choose(int action,String mime){if(!mayEdit())return;fileAction=action;startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType(mime).addCategory(Intent.CATEGORY_OPENABLE),action);}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request!=3||result!=RESULT_OK||data==null||data.getData()==null)return;
        if(!mayEdit()||draft==null){notice(R.string.remote_reload);return;}android.net.Uri uri=data.getData();String group=artGroup;
        if(!"censor".equals(group)&&!"popup".equals(group)){notice(R.string.remote_reload);return;}
        authorize(()->work(()->{if(!mayEdit())throw new SecurityException();
            File temporary=File.createTempFile("peer-import-",".part",getCacheDir());
            try{try(InputStream in=getContentResolver().openInputStream(uri);OutputStream out=new FileOutputStream(temporary)){if(in==null)throw new IOException();byte[] buffer=new byte[8192];long total=0;int count;while((count=in.read(buffer))!=-1){total+=count;if(total>RemoteAssets.MAX_ASSET_BYTES)throw new IOException();out.write(buffer,0,count);}}
                android.graphics.BitmapFactory.Options check=bounds();android.graphics.BitmapFactory.decodeFile(temporary.getPath(),check);if(check.outWidth<=0||check.outHeight<=0)throw new IOException();
                String hash=RemoteAssets.digest(temporary);try(InputStream in=new FileInputStream(temporary)){RemoteContracts.ingest(feature.contracts.assets(peer),hash,temporary.length(),in);}
                return new JSONObject().put("path","assets/"+group+"/"+UUID.randomUUID()+".png").put("hash",hash).put("length",temporary.length());
            }finally{temporary.delete();}
        },asset->{if(asset instanceof JSONObject){try{draft.getJSONObject("contract").getJSONArray("assets").put(asset);}catch(JSONException invalid){notice(R.string.remote_reload);}}render();}));
    }
    private static android.graphics.BitmapFactory.Options bounds(){android.graphics.BitmapFactory.Options value=new android.graphics.BitmapFactory.Options();value.inJustDecodeBounds=true;return value;}
    private static String join(JSONArray items)throws JSONException{List<String> result=new ArrayList<>();for(int i=0;i<items.length();i++)result.add(items.getString(i));return String.join("\n",result);}
    private static void put(JSONObject target,String key,Object value){try{target.put(key,value);}catch(JSONException invalid){throw new IllegalArgumentException(invalid);}}
}
