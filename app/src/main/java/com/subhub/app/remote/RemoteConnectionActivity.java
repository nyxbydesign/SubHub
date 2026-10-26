package com.subhub.app.remote;

import android.os.*;
import com.subhub.app.R;
import com.subhub.app.people.PersonUi;

public final class RemoteConnectionActivity extends RemotePage {
    private final Handler clock=new Handler(Looper.getMainLooper());
    private final Runnable poll=new Runnable(){public void run(){render();clock.postDelayed(this,3000);}};
    @Override protected boolean requiresDom(){return false;}
    @Override protected void onCreate(Bundle state){super.onCreate(state);page(R.string.role_connection);selectPeer();}
    @Override protected void onResume(){super.onResume();if(peer!=null)clock.post(poll);}
    @Override protected void onPause(){clock.removeCallbacks(poll);super.onPause();}
    private void render(){
        RemoteStore.Peer current=feature.store.peer(peer.identity,peer.generation);if(current==null){finish();return;}peer=current;
        body.removeAllViews();text(body,peer.name,true);text(body,peer.identity,false);
        text(body,getString(feature.isConnected(peer)?R.string.role_connection_connected:peer.state==RemoteStore.State.REVOKED?R.string.role_connection_revoked:peer.state==RemoteStore.State.PENDING?R.string.role_connection_pending:R.string.remote_paired),false);
        text(body,peer.lastSyncedAt==0?getString(R.string.remote_unknown):java.text.DateFormat.getDateTimeInstance().format(new java.util.Date(peer.lastSyncedAt)),false);
        String error=feature.store.metadata(peer.identity,peer.generation,"connection_error","");if(!error.isEmpty())text(body,getString(R.string.remote_failure),false);
        for(RemoteProtocol.Stream stream:new RemoteProtocol.Stream[]{RemoteProtocol.Stream.BASELINE,RemoteProtocol.Stream.STATS,RemoteProtocol.Stream.RUNTIME}) {
            int label=stream==RemoteProtocol.Stream.BASELINE?R.string.role_contracts:stream==RemoteProtocol.Stream.STATS?R.string.role_stats:R.string.remote_runtime;
            text(body,getString(label)+": "+observed(stream),false);
        }
        if(peer.state!=RemoteStore.State.REVOKED){button(body,R.string.remote_sync_now,feature::changed);button(body,R.string.remote_unpair,()->authorize(()->{feature.revoke(peer);render();}));}
    }
}
