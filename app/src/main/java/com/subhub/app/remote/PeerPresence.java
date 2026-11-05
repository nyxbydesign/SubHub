package com.subhub.app.remote;

import android.os.SystemClock;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.UUID;

/** Authenticated foreground observations; transport reachability never grants user presence. */
public final class PeerPresence {
    public static final long RECENT_CONTACT_MILLIS = 90_000L;
    private static final String PROCESS = UUID.randomUUID().toString();
    private static final String KEY = "foreground_presence_v1";
    private PeerPresence() { }

    public static final class Observation {
        public final boolean online;
        public final long lastOnlineAt;
        public final long expiresInMillis;
        private Observation(boolean online,long lastOnlineAt,long expiresInMillis) {
            this.online=online;this.lastOnlineAt=lastOnlineAt;this.expiresInMillis=expiresInMillis;
        }
    }

    /** A live socket and last transport contact are deliberately insufficient. */
    public static boolean online(boolean foreground,long observedElapsed,long nowElapsed) {
        return foreground && observedElapsed>=0 && nowElapsed>=observedElapsed
                && nowElapsed-observedElapsed<RECENT_CONTACT_MILLIS;
    }

    public static Observation read(RemoteStore store,RemoteStore.Peer peer) {
        synchronized(store) {
            RemoteStore.Peer current=store.peer(peer.identity,peer.generation);
            if(current==null || current.state!=RemoteStore.State.ACTIVE)return new Observation(false,0,0);
            return decode(store.metadata(peer.identity,peer.generation,KEY,""),System.currentTimeMillis(),
                    SystemClock.elapsedRealtime(),PROCESS);
        }
    }

    static Observation decode(String value,long nowWall,long nowElapsed,String process) {
        try {
            JSONObject saved=new JSONObject(value);
            long last=saved.getLong("lastOnlineAt");
            if(last<0 || last>nowWall)last=0;
            long received=saved.getLong("receivedElapsed");
            boolean live=process.equals(saved.getString("process")) && last>0
                    && online(saved.getBoolean("foreground"),received,nowElapsed);
            return new Observation(live,last,live?RECENT_CONTACT_MILLIS-(nowElapsed-received):0);
        } catch(JSONException invalid) { return new Observation(false,0,0); }
    }

    static boolean receive(RemoteStore store,RemoteStore.Peer peer,JSONObject payload) throws JSONException {
        if(RemoteProtocol.integer(payload,"version")!=1)throw new JSONException("Unsupported presence");
        long sequence=RemoteProtocol.integer(payload,"sequence");
        Object flag=payload.get("foreground");
        if(sequence<=0 || !(flag instanceof Boolean))throw new JSONException("Invalid presence");
        return store.authorityAccess(()->{
            synchronized(store) {
                RemoteStore.Peer current=store.peer(peer.identity,peer.generation);
                if(current==null || current.state!=RemoteStore.State.ACTIVE || current.localRole!=peer.localRole)
                    throw new SecurityException("Presence relationship changed");
                JSONObject previous;
                try { previous=new JSONObject(store.metadata(peer.identity,peer.generation,KEY,"")); }
                catch(JSONException absent) { previous=new JSONObject(); }
                if(sequence<=previous.optLong("sequence",0))return false;
                long wall=System.currentTimeMillis();
                long seen=previous.optLong("lastOnlineAt",0);
                if(seen<0 || seen>wall)seen=0;
                if((Boolean)flag)seen=wall;
                JSONObject saved=new JSONObject().put("sequence",sequence).put("foreground",flag)
                        .put("receivedElapsed",SystemClock.elapsedRealtime()).put("process",PROCESS)
                        .put("lastOnlineAt",seen);
                store.putMetadata(peer.identity,peer.generation,KEY,saved.toString());
                return true;
            }
        });
    }
}
