package com.subhub.app.remote;

import android.content.Context;
import com.subhub.app.appmode.*;
import com.subhub.app.commitment.CommitmentManager;
import com.subhub.app.penance.PaidPauseManager;
import org.json.*;
import java.util.*;

/** Only End is supported; each durable request names one observed runtime generation. */
public final class RemoteEndCommands {
    private static final String PREFIX="end:";
    public static final long LIFETIME=120_000;
    private final Context context;private final RemoteStore store;
    public RemoteEndCommands(Context context,RemoteStore store){this.context=context.getApplicationContext();this.store=store;}
    public String queue(RemoteStore.Peer peer,String expected,long now)throws Exception {
        synchronized(store) {
            requireRole(peer,RemoteProtocol.Role.DOM);token(expected);
            JSONObject runtime=store.mirrored(peer.identity,peer.generation,RemoteProtocol.Stream.RUNTIME,"current");
            long confirmed=Long.parseLong(store.metadata(peer.identity,peer.generation,"confirmed_RUNTIME","0"));
            if(!RemoteTelemetry.validRuntime(runtime) || !runtime.optBoolean("endSessionSupported") || !expected.equals(runtime.optString("sessionIdentity"))
                || confirmed<=0 || now-confirmed<0 || now-confirmed>90_000 || !runtime.getBoolean("serviceActive"))throw new IllegalStateException("Reload the current session");
            String pending=store.metadata(peer.identity,peer.generation,"latest_end","");
            if(!pending.isEmpty()) {
                JSONObject old=local(peer,pending);String state=old.optString("state");
                if(!terminal(state) && old.getJSONObject("request").getLong("expiresAt")>now)throw new IllegalStateException("An End request is pending");
            }
            String id=UUID.randomUUID().toString();JSONObject request=new JSONObject().put("schema",1).put("type","end_session").put("id",id)
                .put("sessionIdentity",expected).put("issuedAt",now).put("expiresAt",Math.addExact(now,LIFETIME));
            save(peer,id,new JSONObject().put("request",request).put("state","QUEUED"));store.putMetadata(peer.identity,peer.generation,"latest_end",id);
            store.publish(peer.identity,peer.generation,RemoteProtocol.Stream.CONFIGURATION,PREFIX+id,request,false,now);return id;
        }
    }
    public void process(RemoteStore.Peer supplied,long now)throws Exception {
        synchronized(store) {
            RemoteStore.Peer peer=requireRole(supplied,supplied.localRole);
            if(peer.localRole==RemoteProtocol.Role.DOM){receiveResults(peer);return;}
            for(RemoteProtocol.Change row:store.mirror(peer.identity,peer.generation,RemoteProtocol.Stream.CONFIGURATION)) {
                if(row.deleted || !row.key.startsWith(PREFIX))continue;
                try {
                JSONObject request=new JSONObject(row.body);validate(request);String id=request.getString("id");if(!row.key.equals(PREFIX+id))throw new SecurityException("End identity mismatch");
                String encoded=store.metadata(peer.identity,peer.generation,PREFIX+id,"");JSONObject state=encoded.isEmpty()?new JSONObject().put("request",request).put("state","RECEIVED"):new JSONObject(encoded);
                if(!RemoteProtocol.canonical(state.getJSONObject("request")).equals(RemoteProtocol.canonical(request)))throw new SecurityException("End identity reused");
                String status=state.getString("state");if(terminal(status)){publish(peer,id,state,now);continue;}
                save(peer,id,state); // Durable receipt precedes runtime mutation.
                synchronized(RuntimeSessionIdentity.LOCK) {
                    requireRole(peer,RemoteProtocol.Role.SUB);
                    String target=request.getString("sessionIdentity"),current=RuntimeSessionIdentity.current(context);
                    if(!target.equals(current))status="DISPATCHED".equals(status)&&!RuntimeSessionIdentity.active(context)?"ENDED":"REJECTED";
                    else if(now<request.getLong("issuedAt")-30_000 || now>request.getLong("expiresAt"))status="EXPIRED";
                    else {
                        ProtectionSessionManager.markMediaProjectionExplicitlyStopped(context);CommitmentManager.emergencyRelease(context);
                        new PaidPauseManager(context).finish();new AppModeManager(context).setArmed(false);ResumeNotificationManager.cancel(context);
                        status=RuntimeSessionIdentity.active(context)?"DISPATCHED":"ENDED";
                    }
                }
                state.put("state",status);save(peer,id,state);publish(peer,id,state,now);
                }catch(JSONException|IllegalArgumentException|SecurityException invalid){store.putMetadata(peer.identity,peer.generation,"end_error","End request rejected");}
            }
        }
    }
    private void receiveResults(RemoteStore.Peer peer)throws Exception {
        for(RemoteProtocol.Change row:store.mirror(peer.identity,peer.generation,RemoteProtocol.Stream.RUNTIME)) {
            if(row.deleted || !row.key.startsWith(PREFIX))continue;JSONObject result=new JSONObject(row.body);String id=RemoteProtocol.identifier(result.getString("id"));
            String existing=store.metadata(peer.identity,peer.generation,PREFIX+id,"");if(existing.isEmpty())continue;
            JSONObject state=new JSONObject(existing);String status=result.getString("state");
            if(RemoteProtocol.integer(result,"schema")!=1 || !row.key.equals(PREFIX+id) || !Set.of("RECEIVED","DISPATCHED","ENDED","REJECTED","EXPIRED").contains(status)
                || !state.getJSONObject("request").getString("sessionIdentity").equals(result.getString("sessionIdentity")))throw new SecurityException("Invalid End result");
            if(!terminal(state.optString("state")) || state.optString("state").equals(status)){state.put("state",status);save(peer,id,state);}
        }
    }
    public JSONObject latest(RemoteStore.Peer peer)throws JSONException {String id=store.metadata(peer.identity,peer.generation,"latest_end","");return id.isEmpty()?null:local(peer,id);}
    private JSONObject local(RemoteStore.Peer peer,String id)throws JSONException{return new JSONObject(store.metadata(peer.identity,peer.generation,PREFIX+id,"{}"));}
    private void save(RemoteStore.Peer peer,String id,JSONObject state){store.putMetadata(peer.identity,peer.generation,PREFIX+id,RemoteProtocol.canonical(state));}
    private void publish(RemoteStore.Peer peer,String id,JSONObject state,long now)throws JSONException {
        JSONObject result=new JSONObject().put("schema",1).put("id",id).put("sessionIdentity",state.getJSONObject("request").getString("sessionIdentity")).put("state",state.getString("state"));
        store.publish(peer.identity,peer.generation,RemoteProtocol.Stream.RUNTIME,PREFIX+id,result,false,now);
    }
    private RemoteStore.Peer requireRole(RemoteStore.Peer old,RemoteProtocol.Role role){
        RemoteStore.Peer peer=store.peer(old.identity,old.generation);if(peer==null || peer.state!=RemoteStore.State.ACTIVE || peer.localRole!=role)throw new SecurityException("Pairing is not authorized");return peer;
    }
    static void markSent(RemoteStore store,String identity,String generation,String id)throws JSONException {
        String encoded=store.metadata(identity,generation,PREFIX+id,"");if(encoded.isEmpty())return;JSONObject state=new JSONObject(encoded);
        if("QUEUED".equals(state.optString("state")))store.putMetadata(identity,generation,PREFIX+id,RemoteProtocol.canonical(state.put("state","SENT")));
    }
    private static boolean terminal(String state){return Set.of("ENDED","REJECTED","EXPIRED").contains(state);}
    private static void token(String value){if(value==null||!value.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("Session identity unavailable");}
    private static void validate(JSONObject request)throws JSONException {
        RemoteConfiguration.keys(request,"schema","type","id","sessionIdentity","issuedAt","expiresAt");
        if(RemoteProtocol.integer(request,"schema")!=1||!"end_session".equals(request.getString("type")))throw new IllegalArgumentException("Unsupported operation");
        RemoteProtocol.identifier(request.getString("id"));token(request.getString("sessionIdentity"));long issued=RemoteProtocol.integer(request,"issuedAt"),expires=RemoteProtocol.integer(request,"expiresAt");
        if(issued<=0||expires<=issued||expires-issued>LIFETIME)throw new IllegalArgumentException("Invalid End expiry");
    }
}
