package com.subhub.app.remote;

import android.os.*;
import com.subhub.app.R;
import com.subhub.app.people.PersonUi;

public final class RemoteConnectionActivity extends RemotePage {
    private boolean details,resumed;private long codeRequest;
    private String rendered="";
    private androidx.appcompat.app.AlertDialog codeDialog;
    private final Handler clock=new Handler(Looper.getMainLooper());
    private final Runnable poll=new Runnable(){public void run(){render();clock.postDelayed(this,3000);}};
    @Override protected boolean requiresDom(){return false;}
    @Override protected void onCreate(Bundle state){super.onCreate(state);page(R.string.role_connection);if(state!=null)details=state.getBoolean("details");selectPeer();}
    @Override protected void onSaveInstanceState(Bundle state){state.putBoolean("details",details);super.onSaveInstanceState(state);}
    @Override protected void onResume(){super.onResume();resumed=true;if(peer!=null)clock.post(poll);}
    @Override protected void onPause(){resumed=false;codeRequest++;clock.removeCallbacks(poll);if(codeDialog!=null)codeDialog.dismiss();super.onPause();}
    private void render(){
        RemoteStore.Peer current=feature.store.peer(peer.identity,peer.generation);if(current==null){finish();return;}peer=current;
        String status=getString(feature.isConnected(peer)?R.string.role_connection_connected:peer.state==RemoteStore.State.REVOKED?R.string.role_connection_revoked:peer.state==RemoteStore.State.PENDING?R.string.role_connection_pending:R.string.remote_paired);
        if(codeDialog!=null&&(peer.state!=RemoteStore.State.ACTIVE||!com.subhub.app.security.ControllerPinManager.isDomModeActive()))codeDialog.dismiss();
        String signature=peer.state+":"+status+":"+peer.lastSyncedAt+":"+details+":"+com.subhub.app.security.ControllerPinManager.isDomModeActive();
        if(signature.equals(rendered))return;rendered=signature;body.removeAllViews();
        android.widget.LinearLayout identity=PersonUi.card(body);text(identity,peer.name,true);
        text(identity,com.subhub.app.people.ProfileViews.targetDiscriminator(this,RemotePersonRepository.id(peer),""),false);text(identity,status,false);
        android.widget.LinearLayout device=PersonUi.card(body);
        String model=getString(R.string.remote_unknown);
        try{org.json.JSONObject runtime=feature.store.mirrored(peer.identity,peer.generation,RemoteProtocol.Stream.RUNTIME,"current");if(RemoteTelemetry.validRuntime(runtime))model=runtime.optString("device",model);}catch(Exception unavailable){/* Keep unavailable source observations distinct from this phone. */}
        PersonUi.row(device,0,getString(R.string.role_device),model,null);
        text(device,getString(R.string.role_last_synced,peer.lastSyncedAt==0?getString(R.string.remote_unknown):java.text.DateFormat.getDateTimeInstance().format(new java.util.Date(peer.lastSyncedAt))),false);
        String error=feature.store.metadata(peer.identity,peer.generation,"connection_error","");if(!error.isEmpty())text(device,getString(R.string.remote_failure),false);
        if(peer.state==RemoteStore.State.ACTIVE){
            android.widget.LinearLayout shared=PersonUi.card(body);text(shared,getString(R.string.remote_shared_controls),true);
            text(shared,getString(R.string.remote_shared_scope),false);
            text(shared,getString(R.string.remote_activity_pairing_scope),false);
            if(peer.localRole==RemoteProtocol.Role.DOM)button(shared,R.string.remote_keyholder_code,this::showCode);
            PersonUi.action(body,R.string.remote_sync_now,feature::changed,true);
        }
        button(body,R.string.remote_sync_details,()->{details=!details;render();});
        if(details){android.widget.LinearLayout detail=PersonUi.card(body);text(detail,peer.identity,false);
            for(RemoteProtocol.Stream stream:new RemoteProtocol.Stream[]{RemoteProtocol.Stream.BASELINE,RemoteProtocol.Stream.STATS,RemoteProtocol.Stream.RUNTIME}){
                int label=stream==RemoteProtocol.Stream.BASELINE?R.string.role_contracts:stream==RemoteProtocol.Stream.STATS?R.string.role_stats:R.string.remote_runtime;
                PersonUi.row(detail,0,getString(label),observed(stream),null);
            }
        }
        if(peer.state!=RemoteStore.State.REVOKED)button(body,R.string.remote_unpair,()->authorize(()->{feature.revoke(peer);render();}));
    }
    private void showCode(){
        com.subhub.app.security.ControllerPinGate.authenticate(this,()->{
            if(!resumed)return;long request=++codeRequest;RemoteStore.Peer selected=peer;long authorizedAt=SystemClock.elapsedRealtime();
            work(()->{if(SystemClock.elapsedRealtime()-authorizedAt>30_000)throw new SecurityException("Authentication expired");return feature.keyholder.code(selected);},code->{
                RemoteStore.Peer current=feature.store.peer(selected.identity,selected.generation);
                if(!resumed||request!=codeRequest)return;
                if(SystemClock.elapsedRealtime()-authorizedAt>30_000||current==null||current.state!=RemoteStore.State.ACTIVE||current.localRole!=RemoteProtocol.Role.DOM||!com.subhub.app.security.ControllerPinManager.isDomModeActive()||code.expiresAt<=System.currentTimeMillis()){notice(R.string.remote_failure);return;}
                android.widget.TextView value=new android.widget.TextView(this);value.setText(code.value);value.setTextSize(32);value.setGravity(android.view.Gravity.CENTER);value.setSaveEnabled(false);
                androidx.appcompat.app.AlertDialog dialog=com.subhub.app.util.ThemedDialogs.builder(this).setTitle(R.string.remote_keyholder_code).setView(value).setPositiveButton(android.R.string.ok,null).create();
                codeDialog=dialog;dialog.setOnDismissListener(ignored->{value.setText("");if(codeDialog==dialog)codeDialog=null;});
                if(dialog.getWindow()!=null)dialog.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);dialog.show();
                clock.postDelayed(dialog::dismiss,Math.max(1,code.expiresAt-System.currentTimeMillis()));
            });
        },false);
    }
}
