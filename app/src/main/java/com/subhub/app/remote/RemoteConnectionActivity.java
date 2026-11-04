package com.subhub.app.remote;

import android.os.*;
import com.subhub.app.R;
import com.subhub.app.people.PersonUi;

public final class RemoteConnectionActivity extends RemotePage {
    private boolean details,resumed;private long codeRequest,codeRevision;
    private AutoCloseable codeAuthority;
    private String rendered="";
    private androidx.appcompat.app.AlertDialog codeDialog;
    private final Handler clock=new Handler(Looper.getMainLooper());
    private final Runnable poll=new Runnable(){public void run(){render();clock.postDelayed(this,3000);}};
    @Override protected boolean requiresDom(){return false;}
    @Override protected void onCreate(Bundle state){super.onCreate(state);page(R.string.role_connection);if(state!=null)details=state.getBoolean("details");selectPeer();}
    @Override protected void onSaveInstanceState(Bundle state){state.putBoolean("details",details);super.onSaveInstanceState(state);}
    @Override protected void onResume(){super.onResume();resumed=true;
        codeAuthority=com.subhub.app.security.ControllerPinManager.observeSession(()->{
            if(codeDialog!=null&&(!com.subhub.app.security.ControllerPinManager.isDomWorkspaceUnlocked()
                    ||codeRevision!=com.subhub.app.security.ControllerPinManager.domWorkspaceRevision()))codeDialog.dismiss();
        });
        if(peer!=null){feature.requestAutomaticSync();clock.post(poll);}
    }
    @Override protected void onPause(){resumed=false;codeRequest++;
        if(codeAuthority!=null)try{codeAuthority.close();}catch(Exception ignored){/* Memory-only observer. */}finally{codeAuthority=null;}
        clock.removeCallbacks(poll);if(codeDialog!=null)codeDialog.dismiss();super.onPause();}
    private void render(){
        RemoteStore.Peer current=feature.store.peer(peer.identity,peer.generation);if(current==null){finish();return;}peer=current;
        boolean online=PeerPresence.online(feature.isConnected(peer),peer.lastSyncedAt,System.currentTimeMillis());
        String status=getString(peer.state==RemoteStore.State.REVOKED?R.string.role_connection_revoked:peer.state==RemoteStore.State.PENDING?R.string.role_connection_pending:online?R.string.role_connection_connected:R.string.role_connection_offline);
        if(codeDialog!=null&&(peer.state!=RemoteStore.State.ACTIVE||peer.localRole!=RemoteProtocol.Role.DOM||codeRevision!=com.subhub.app.security.ControllerPinManager.domWorkspaceRevision()||!com.subhub.app.security.ControllerPinManager.isDomWorkspaceUnlocked()))codeDialog.dismiss();
        String error=feature.store.metadata(peer.identity,peer.generation,"connection_error","");
        StringBuilder observations=new StringBuilder(error);
        for(RemoteProtocol.Stream stream:observedStreams(peer))observations.append(':').append(feature.store.metadata(peer.identity,peer.generation,"confirmed_"+stream.name(),"0"));
        String signature=observations+":"+peer.state+":"+status+":"+peer.lastSyncedAt+":"+details+":"+com.subhub.app.security.ControllerPinManager.isDomWorkspaceUnlocked()+":"+com.subhub.app.security.ControllerPinManager.domWorkspaceRevision();
        if(signature.equals(rendered))return;rendered=signature;body.removeAllViews();
        android.widget.LinearLayout identity=PersonUi.card(body);text(identity,peer.name,true);
        text(identity,com.subhub.app.people.ProfileViews.targetDiscriminator(this,RemotePersonRepository.id(peer),""),false);text(identity,status,false);
        android.widget.LinearLayout device=PersonUi.card(body);
        if(peer.localRole==RemoteProtocol.Role.DOM){
            String model=getString(R.string.remote_unknown);
            try{org.json.JSONObject runtime=feature.store.mirrored(peer.identity,peer.generation,RemoteProtocol.Stream.RUNTIME,"current");if(RemoteTelemetry.validRuntime(runtime))model=runtime.optString("device",model);}catch(Exception unavailable){/* Keep unavailable source observations distinct from this phone. */}
            PersonUi.row(device,0,getString(R.string.role_device),model,null).setTag("connection_device_model");
        }
        text(device,getString(R.string.role_last_online,peer.lastSyncedAt<=0 || peer.lastSyncedAt>System.currentTimeMillis()?getString(R.string.remote_unknown):com.subhub.app.people.ProfileViews.formatLastSeen(peer.lastSyncedAt)),false);
        if(!error.isEmpty())text(device,getString(R.string.remote_failure),false);
        if(peer.state==RemoteStore.State.ACTIVE){
            android.widget.LinearLayout shared=PersonUi.card(body);text(shared,getString(R.string.remote_shared_controls),true);
            text(shared,getString(R.string.remote_shared_scope),false);
            text(shared,getString(R.string.remote_activity_pairing_scope),false);
            if(peer.localRole==RemoteProtocol.Role.DOM)button(shared,R.string.remote_keyholder_code,this::showCode);
        }
        button(body,R.string.remote_sync_details,()->{details=!details;render();});
        if(details){android.widget.LinearLayout detail=PersonUi.card(body);text(detail,peer.identity,false);
            for(RemoteProtocol.Stream stream:observedStreams(peer)){
                int label=stream==RemoteProtocol.Stream.BASELINE||stream==RemoteProtocol.Stream.CONFIGURATION?R.string.role_contracts:stream==RemoteProtocol.Stream.STATS?R.string.role_stats:R.string.remote_runtime;
                PersonUi.row(detail,0,getString(label),observed(stream),null).setTag("connection_stream_"+stream.name());
            }
        }
        if(peer.state!=RemoteStore.State.REVOKED)button(body,R.string.remote_unpair,()->authorize(()->{feature.revoke(peer);render();}));
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
