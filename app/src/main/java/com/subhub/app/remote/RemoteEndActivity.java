package com.subhub.app.remote;

import android.os.*;
import com.subhub.app.R;
import org.json.*;

/** Command receipts and observed runtime remain separate throughout a targeted End request. */
public final class RemoteEndActivity extends RemotePage {
    private String expected;private boolean started;
    private final Handler clock=new Handler(Looper.getMainLooper());
    private final Runnable poll=new Runnable(){public void run(){render();clock.postDelayed(this,1500);}};
    @Override protected void onCreate(Bundle state){super.onCreate(state);page(R.string.remote_end_title);
        if(!selectPeer()||!mayEdit()){finish();return;}
        expected=getIntent().getStringExtra("expectedSession");if(expected==null||!expected.matches("[0-9a-f]{64}")){finish();return;}
        started=state!=null&&state.getBoolean("started");if(!started)request();
    }
    @Override protected void onSaveInstanceState(Bundle state){state.putBoolean("started",started);super.onSaveInstanceState(state);}
    @Override protected void onResume(){super.onResume();if(peer!=null&&expected!=null)clock.post(poll);}
    @Override protected void onPause(){clock.removeCallbacks(poll);super.onPause();}
    private void request(){if(!mayEdit())return;started=true;authorize(()->feature.endSession(peer,expected).whenComplete((id,error)->runOnUiThread(()->{
        if(isDestroyed()||isFinishing())return;if(error!=null){started=false;notice(R.string.remote_failure);}render();
    })));}
    private void render(){
        if(!mayEdit()){finish();return;}body.removeAllViews();text(body,peer.name,true);
        try {
            JSONObject latest=feature.endCommands.latest(peer);
            if(latest!=null && expected.equals(latest.getJSONObject("request").getString("sessionIdentity"))) {
                String state=latest.getString("state");int label="ENDED".equals(state)?R.string.remote_target_ended:"DISPATCHED".equals(state)?R.string.remote_end_dispatched:"EXPIRED".equals(state)?R.string.remote_end_expired:"REJECTED".equals(state)?R.string.remote_end_rejected:"SENT".equals(state)?R.string.remote_end_received:R.string.remote_end_requested;
                text(body,getString(label),true);
            }else {text(body,getString(R.string.remote_end_requested),false);if(!started)button(body,R.string.role_retry,this::request);}
            JSONObject runtime=feature.store.mirrored(peer.identity,peer.generation,RemoteProtocol.Stream.RUNTIME,"current");
            text(body,observed(RemoteProtocol.Stream.RUNTIME),false);
            if(RemoteTelemetry.validRuntime(runtime))text(body,getString(R.string.remote_last_observed,getString(runtime.getBoolean("serviceActive")?R.string.remote_active:R.string.remote_inactive)),false);
            else text(body,getString(R.string.remote_unknown),false);
        }catch(JSONException invalid){text(body,getString(R.string.remote_incomplete),false);}
        button(body,R.string.remote_sync_now,feature::changed);
    }
}
