package com.subhub.app.remote;

import android.os.*;
import com.subhub.app.R;
import com.subhub.app.people.PersonUi;

public final class RemoteConnectionActivity extends RemotePage {
    private boolean resumed;private long codeRequest,codeRevision;
    private AutoCloseable codeAuthority, presenceObserver;
    private String rendered="";
    private androidx.appcompat.app.AlertDialog codeDialog;
    private final Handler clock=new Handler(Looper.getMainLooper());
    private final Runnable poll=new Runnable(){public void run(){render();clock.postDelayed(this,3000);}};
    @Override protected boolean requiresDom(){return false;}
    @Override protected void onCreate(Bundle state){super.onCreate(state);page(R.string.role_connection);selectPeer();}
    @Override protected void onResume(){super.onResume();resumed=true;
        codeAuthority=com.subhub.app.security.ControllerPinManager.observeSession(()->{
            if(codeDialog!=null&&(!com.subhub.app.security.ControllerPinManager.isDomWorkspaceUnlocked()
                    ||codeRevision!=com.subhub.app.security.ControllerPinManager.domWorkspaceRevision()))codeDialog.dismiss();
        });
        if(peer!=null && !isFinishing()){
            presenceObserver = RemoteUi.observePresence(() -> {
                if (resumed && !isFinishing() && !isDestroyed() && body.isAttachedToWindow()) render();
            });
            feature.requestAutomaticSync();clock.post(poll);
        }
    }
    @Override protected void onPause(){resumed=false;codeRequest++;
        if(presenceObserver!=null)try{presenceObserver.close();}catch(Exception ignored){}finally{presenceObserver=null;}
        if(codeAuthority!=null)try{codeAuthority.close();}catch(Exception ignored){/* Memory-only observer. */}finally{codeAuthority=null;}
        clock.removeCallbacks(poll);if(codeDialog!=null)codeDialog.dismiss();super.onPause();}
    private void render(){
        RemoteStore.Peer current=feature.store.peer(peer.identity,peer.generation);if(current==null){finish();return;}peer=current;
        PeerPresence.Observation presence=PeerPresence.read(feature.store,peer);
        boolean online=presence.online;
        com.subhub.app.people.PersonSnapshot.Connection state=peer.state==RemoteStore.State.REVOKED?com.subhub.app.people.PersonSnapshot.Connection.REVOKED:
                peer.state==RemoteStore.State.PENDING?com.subhub.app.people.PersonSnapshot.Connection.PENDING:
                online?com.subhub.app.people.PersonSnapshot.Connection.CONNECTED:com.subhub.app.people.PersonSnapshot.Connection.OFFLINE;
        String status=PersonUi.connection(this,state,presence.lastOnlineAt);
        if(codeDialog!=null&&(peer.state!=RemoteStore.State.ACTIVE||peer.localRole!=RemoteProtocol.Role.DOM||codeRevision!=com.subhub.app.security.ControllerPinManager.domWorkspaceRevision()||!com.subhub.app.security.ControllerPinManager.isDomWorkspaceUnlocked()))codeDialog.dismiss();
        String model=getString(R.string.remote_unknown);
        if(peer.localRole==RemoteProtocol.Role.DOM)try{
            org.json.JSONObject runtime=feature.store.mirrored(peer.identity,peer.generation,RemoteProtocol.Stream.RUNTIME,"current");
            if(RemoteTelemetry.validRuntime(runtime))model=runtime.optString("device",model);
        }catch(Exception unavailable){/* Preserve unknown remote device identity. */}
        String signature=peer.name+":"+peer.state+":"+model+":"+com.subhub.app.security.ControllerPinManager.isDomWorkspaceUnlocked()+":"+com.subhub.app.security.ControllerPinManager.domWorkspaceRevision();
        if(signature.equals(rendered)){
            android.widget.TextView presenceText=body.findViewWithTag("connection_presence");
            if(presenceText!=null)presenceText.setText(status);
            for(RemoteProtocol.Stream stream:observedStreams(peer)){
                android.widget.TextView value=body.findViewWithTag("connection_observed_"+stream.name());
                if(value!=null)value.setText(observed(stream));
            }
            return;
        }
        rendered=signature;
        com.subhub.app.util.PageScrollView scroll=findViewById(R.id.role_page_scroll);
        int position=scroll.position();
        body.removeAllViews();
        android.widget.LinearLayout card=PersonUi.card(body);text(card,peer.name,true);
        text(card,com.subhub.app.people.ProfileViews.targetDiscriminator(this,RemotePersonRepository.id(peer),""),false);
        text(card,status,false).setTag("connection_presence");
        if(peer.localRole==RemoteProtocol.Role.DOM){
            PersonUi.row(card,0,getString(R.string.role_device),model,null).setTag("connection_device_model");
        }
        if(peer.state==RemoteStore.State.ACTIVE){
            text(card,getString(R.string.remote_shared_controls),true);
            text(card,getString(R.string.remote_shared_scope),false);
            text(card,getString(R.string.remote_activity_pairing_scope),false);
            if(peer.localRole==RemoteProtocol.Role.DOM)button(card,R.string.remote_keyholder_code,this::showCode);
        }
        for(RemoteProtocol.Stream stream:observedStreams(peer)){
            int label=stream==RemoteProtocol.Stream.BASELINE||stream==RemoteProtocol.Stream.CONFIGURATION?R.string.role_contracts:stream==RemoteProtocol.Stream.STATS?R.string.role_stats:R.string.remote_runtime;
            android.widget.LinearLayout row=PersonUi.row(card,0,getString(label),observed(stream),null);
            row.setTag("connection_stream_"+stream.name());
            ((android.view.ViewGroup)row.getChildAt(0)).getChildAt(1).setTag("connection_observed_"+stream.name());
        }
        if(peer.state!=RemoteStore.State.REVOKED)button(card,R.string.remote_unpair,()->authorize(()->{feature.revoke(peer);render();}));
        scroll.restorePosition(position);
    }
    static RemoteProtocol.Stream[] observedStreams(RemoteStore.Peer peer){
        return peer.localRole==RemoteProtocol.Role.SUB?new RemoteProtocol.Stream[]{RemoteProtocol.Stream.CONFIGURATION}
                :new RemoteProtocol.Stream[]{RemoteProtocol.Stream.BASELINE,RemoteProtocol.Stream.STATS,RemoteProtocol.Stream.RUNTIME};
    }
    private void showCode(){
        com.subhub.app.security.ControllerPinGate.authenticateDomWorkspace(this,()->{
            if(!resumed)return;long request=++codeRequest;RemoteStore.Peer selected=peer;long authorizedAt=SystemClock.elapsedRealtime();long revision=com.subhub.app.security.ControllerPinManager.domWorkspaceRevision();
            work(()->{if(revision!=com.subhub.app.security.ControllerPinManager.domWorkspaceRevision()||SystemClock.elapsedRealtime()-authorizedAt>30_000)throw new SecurityException("Authentication expired");return feature.keyholder.code(selected);},code->{
                RemoteStore.Peer current=feature.store.peer(selected.identity,selected.generation);
                if(!resumed||request!=codeRequest||revision!=com.subhub.app.security.ControllerPinManager.domWorkspaceRevision())return;
                if(SystemClock.elapsedRealtime()-authorizedAt>30_000||current==null||current.state!=RemoteStore.State.ACTIVE||current.localRole!=RemoteProtocol.Role.DOM||!com.subhub.app.security.ControllerPinManager.isDomWorkspaceUnlocked()||code.expiresAt<=System.currentTimeMillis()){notice(R.string.remote_failure);return;}
                android.widget.TextView value=new android.widget.TextView(this);value.setText(code.value);value.setTextSize(32);value.setGravity(android.view.Gravity.CENTER);value.setSaveEnabled(false);
                androidx.appcompat.app.AlertDialog dialog=com.subhub.app.util.ThemedDialogs.builder(this).setTitle(R.string.remote_keyholder_code).setView(value).setPositiveButton(android.R.string.ok,null).create();
                codeDialog=dialog;codeRevision=revision;dialog.setOnDismissListener(ignored->{value.setText("");if(codeDialog==dialog)codeDialog=null;});
                if(dialog.getWindow()!=null)dialog.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);dialog.show();
                clock.postDelayed(dialog::dismiss,Math.max(1,code.expiresAt-System.currentTimeMillis()));
            });
        },false);
    }
}
