package com.subhub.app.remote;

import android.os.Bundle;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import com.subhub.app.R;
import com.subhub.app.people.*;
import com.subhub.app.security.*;
import com.subhub.app.util.PrimaryHeader;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

/** Shared lifecycle and authorization for identity-bound peer screens. */
public abstract class RemotePage extends AppCompatActivity {
    protected LinearLayout body;
    protected RemoteFeature feature;
    protected RemotePersonRepository repository;
    protected RemoteStore.Peer peer;
    protected void page(int title) {
        feature=RemoteFeature.get(this);repository=new RemotePersonRepository(this,feature);
        setContentView(R.layout.activity_role_page);body=findViewById(R.id.role_page_body);
        PrimaryHeader.bindSecondary(findViewById(android.R.id.content),title,false);
        PrimaryHeader.backButton(findViewById(android.R.id.content)).setOnClickListener(v->finish());
        findViewById(R.id.bottom_navigation).setVisibility(android.view.View.GONE);
    }
    protected boolean requiresDom(){return true;}
    @Override protected void onResume(){super.onResume();
        if(requiresDom()&&!ControllerPinManager.isDomModeActive()
                ||(requiresDom()||peer!=null&&peer.localRole==RemoteProtocol.Role.DOM)&&!DeviceRolePreferences.supportsDom(this)){finish();return;}
        if(peer!=null){RemoteStore.Peer current=feature.store.peer(peer.identity,peer.generation);if(current==null||current.localRole!=peer.localRole){finish();return;}peer=current;}
    }
    protected boolean selectPeer() {
        try {peer=repository.peer(PersonRoutes.requireId(getIntent()));return true;}
        catch(RuntimeException unavailable){finish();return false;}
    }
    protected boolean mayEdit() {
        RemoteStore.Peer current=peer==null?null:feature.store.peer(peer.identity,peer.generation);
        return feature.recoveryReady() && DeviceRolePreferences.supportsDom(this) && current!=null && current.localRole==RemoteProtocol.Role.DOM && current.state==RemoteStore.State.ACTIVE
                && ControllerPinManager.isDomModeActive() && ControllerPinManager.hasCredentials(this);
    }
    protected void authorize(Runnable action) {
        if((requiresDom()||peer!=null&&peer.localRole==RemoteProtocol.Role.DOM)&&!DeviceRolePreferences.supportsDom(this)){notice(R.string.remote_read_only);return;}
        if(!ControllerPinManager.hasCredentials(this)){notice(R.string.remote_credentials);return;}
        ControllerPinGate.unlock(this,()->{if(ControllerPinManager.isSessionUnlocked()
                &&(!(requiresDom()||peer!=null&&peer.localRole==RemoteProtocol.Role.DOM)||DeviceRolePreferences.supportsDom(this)))action.run();},false);
    }
    protected <T> void work(Callable<T> action,Consumer<T> success) { work(action,success,()->notice(R.string.remote_failure)); }
    protected <T> void work(Callable<T> action,Consumer<T> success,Runnable failure) {
        boolean domPage=requiresDom()||peer!=null&&peer.localRole==RemoteProtocol.Role.DOM;android.content.Context app=getApplicationContext();
        feature.submit(()->{if(domPage&&!DeviceRolePreferences.supportsDom(app))throw new SecurityException("Dom capability unavailable");return action.call();}).whenComplete((result,error)->runOnUiThread(()->{
            if(isFinishing() || isDestroyed())return;
            if(requiresDom()&&!ControllerPinManager.isDomModeActive()||domPage&&!DeviceRolePreferences.supportsDom(this)){finish();return;}
            if(error!=null)failure.run();else success.accept(result);
        }));
    }
    protected void notice(int text){Toast.makeText(this,text,Toast.LENGTH_LONG).show();}
    protected TextView text(LinearLayout target,CharSequence value,boolean heading){return PersonUi.text(target,value,heading);}
    protected TextView button(LinearLayout target,int label,Runnable action){return PersonUi.action(target,label,action,false);}
    protected EditText input(LinearLayout target,int label,String value) {
        text(target,getString(label),false);EditText editor=new EditText(this);editor.setText(value);
        editor.setSaveEnabled(false);editor.setSingleLine(false);target.addView(editor,new LinearLayout.LayoutParams(-1,-2));return editor;
    }
    protected void target(){
        text(body,peer.name,true);
        text(body,ProfileViews.targetDiscriminator(this,RemotePersonRepository.id(peer),""),false);
    }
    protected String observed(RemoteProtocol.Stream stream) {
        long time=Long.parseLong(feature.store.metadata(peer.identity,peer.generation,"confirmed_"+stream.name(),"0"));
        return time==0?getString(R.string.remote_unknown):java.text.DateFormat.getDateTimeInstance().format(new java.util.Date(time));
    }
}
