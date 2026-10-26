package com.subhub.app.remote;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Per-relationship mirrors and acknowledgements never share the device's live preference stores. */
public final class RemoteStore extends SQLiteOpenHelper {
    public enum State { PENDING, ACTIVE, REVOKED }
    public static final class Peer {
        public final String identity, generation, name;
        public final String route;
        public final RemoteProtocol.Role localRole;
        public final State state;
        public final long lastSyncedAt;
        Peer(String identity, String generation, String name, RemoteProtocol.Role localRole,
                State state, long lastSyncedAt, String route) {
            this.identity = identity; this.generation = generation; this.name = name;
            this.localRole = localRole; this.state = state; this.lastSyncedAt = lastSyncedAt;
            this.route = route;
        }
        public RemoteProtocol.Role remoteRole() {
            return localRole == RemoteProtocol.Role.DOM ? RemoteProtocol.Role.SUB : RemoteProtocol.Role.DOM;
        }
    }

    public RemoteStore(Context context) { this(context, "remote_sync_v1.db"); }
    public RemoteStore(Context context, String databaseName) {
        super(context.getApplicationContext(), databaseName, null, 2);
        setWriteAheadLoggingEnabled(true);
    }
    @Override public void onConfigure(SQLiteDatabase db) { db.setForeignKeyConstraintsEnabled(true); }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE peers(peer TEXT NOT NULL,generation TEXT NOT NULL,name TEXT NOT NULL,"
                + "local_role TEXT NOT NULL,state TEXT NOT NULL,last_sync INTEGER NOT NULL DEFAULT 0,"
                + "route TEXT NOT NULL DEFAULT '',PRIMARY KEY(peer,generation))");
        db.execSQL("CREATE UNIQUE INDEX active_remote_identity ON peers(peer) WHERE state IN ('PENDING','ACTIVE')");
        db.execSQL("CREATE UNIQUE INDEX one_dom_per_sub ON peers(local_role) WHERE local_role='SUB' AND state IN ('PENDING','ACTIVE')");
        db.execSQL("CREATE TABLE invites(id TEXT PRIMARY KEY,token_hash TEXT NOT NULL,expires INTEGER NOT NULL,"
                + "candidate TEXT,candidate_name TEXT NOT NULL DEFAULT '',approved INTEGER NOT NULL DEFAULT 0,consumed INTEGER NOT NULL DEFAULT 0)");
        for (String table : new String[] {"local_objects", "outbox", "remote_objects"}) {
            db.execSQL("CREATE TABLE " + table + "(peer TEXT NOT NULL,generation TEXT NOT NULL,"
                    + "stream TEXT NOT NULL,item TEXT NOT NULL,seq INTEGER NOT NULL,body TEXT NOT NULL,"
                    + "deleted INTEGER NOT NULL,observed INTEGER NOT NULL,PRIMARY KEY(peer,generation,stream,item))");
        }
        db.execSQL("CREATE TABLE outgoing(peer TEXT NOT NULL,generation TEXT NOT NULL,stream TEXT NOT NULL,"
                + "next_seq INTEGER NOT NULL DEFAULT 0,acked INTEGER NOT NULL DEFAULT 0,"
                + "offered INTEGER NOT NULL DEFAULT 0,digest TEXT,PRIMARY KEY(peer,generation,stream))");
        db.execSQL("CREATE TABLE incoming(peer TEXT NOT NULL,generation TEXT NOT NULL,stream TEXT NOT NULL,"
                + "through_seq INTEGER NOT NULL,PRIMARY KEY(peer,generation,stream))");
        db.execSQL("CREATE TABLE commands(peer TEXT NOT NULL,generation TEXT NOT NULL,id TEXT NOT NULL,"
                + "revision INTEGER NOT NULL,status TEXT NOT NULL,body TEXT NOT NULL,"
                + "PRIMARY KEY(peer,generation,id))");
        createMetadata(db);
    }
    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion != 1 || newVersion != 2) throw new IllegalStateException("Unsupported remote database version");
        db.execSQL("ALTER TABLE peers ADD COLUMN route TEXT NOT NULL DEFAULT ''");
        db.execSQL("ALTER TABLE invites ADD COLUMN candidate_name TEXT NOT NULL DEFAULT ''");
        createMetadata(db);
    }

    private static void createMetadata(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE remote_metadata(peer TEXT NOT NULL,generation TEXT NOT NULL,name TEXT NOT NULL,value TEXT NOT NULL,PRIMARY KEY(peer,generation,name))");
    }

    public synchronized List<Peer> peers(boolean includeArchived) {
        List<Peer> peers = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT peer,generation,name,local_role,state,last_sync,route FROM peers"
                + (includeArchived ? "" : " WHERE state!='REVOKED'") + " ORDER BY name,peer", null)) {
            while (c.moveToNext()) peers.add(readPeer(c));
        }
        return peers;
    }

    public synchronized Peer peer(String identity, String generation) {
        RemoteProtocol.peerId(identity); RemoteProtocol.identifier(generation);
        return peer(getReadableDatabase(), identity, generation);
    }

    public synchronized boolean isRemotelyControlled() {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT 1 FROM peers WHERE local_role='SUB' AND state='ACTIVE' LIMIT 1", null)) {
            return c.moveToFirst();
        }
    }

    synchronized void saveInvite(PairingInvite invite) {
        ContentValues values = new ContentValues();
        values.put("id", invite.id); values.put("token_hash", invite.tokenHash());
        values.put("expires", invite.expiresAt);
        getWritableDatabase().insertOrThrow("invites", null, values);
    }

    synchronized String bindInvite(String id, String candidate, String tokenHash, long now) {
        RemoteProtocol.identifier(id); RemoteProtocol.peerId(candidate);
        SQLiteDatabase db = getWritableDatabase(); db.beginTransaction();
        try (Cursor c = db.rawQuery("SELECT token_hash,expires,candidate,consumed FROM invites WHERE id=?", new String[]{id})) {
            if (!c.moveToFirst() || now <= 0 || now >= c.getLong(1) || c.getInt(3) != 0
                    || !java.security.MessageDigest.isEqual(c.getString(0).getBytes(StandardCharsets.US_ASCII),
                    tokenHash.getBytes(StandardCharsets.US_ASCII))
                    || (!c.isNull(2) && !candidate.equals(c.getString(2)))) {
                throw new SecurityException("Invitation unavailable");
            }
            db.execSQL("UPDATE invites SET candidate=? WHERE id=?", new Object[]{candidate, id});
            db.setTransactionSuccessful();
            return id;
        } finally { db.endTransaction(); }
    }

    synchronized void describeCandidate(String id,String candidate,String name,String route) {
        name=RemoteProtocol.deviceName(name);
        if (route == null || route.length()>4096) {
            throw new IllegalArgumentException("Invalid device details");
        }
        getWritableDatabase().execSQL("UPDATE invites SET candidate_name=? WHERE id=? AND candidate=? AND approved=0 AND consumed=0",
                new Object[]{name.trim(),id,candidate});
        setRoute(candidate,id,route);
    }

    public static final class Invitation {
        public final String id,candidate,name;
        public final long expiresAt;
        public final boolean approved;
        Invitation(String id,String candidate,String name,long expiresAt,boolean approved) {
            this.id=id;this.candidate=candidate;this.name=name;this.expiresAt=expiresAt;this.approved=approved;
        }
    }

    public synchronized List<Invitation> invitations(long now) {
        List<Invitation> result=new ArrayList<>();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT id,candidate,candidate_name,expires,approved FROM invites WHERE expires>? AND consumed=0 ORDER BY expires DESC LIMIT 20",new String[]{Long.toString(now)})) {
            while(c.moveToNext()) result.add(new Invitation(c.getString(0),c.getString(1),c.getString(2),c.getLong(3),c.getInt(4)!=0));
        }
        return result;
    }

    synchronized boolean approvedInvite(String id,String candidate,String tokenHash) {
        try(Cursor c=getReadableDatabase().rawQuery("SELECT token_hash FROM invites WHERE id=? AND candidate=? AND approved=1 AND consumed=1",new String[]{id,candidate})) {
            Peer peer=peer(getReadableDatabase(),candidate,id);
            return c.moveToFirst() && peer!=null && peer.localRole==RemoteProtocol.Role.DOM && peer.state!=State.REVOKED
                    && java.security.MessageDigest.isEqual(c.getString(0).getBytes(StandardCharsets.US_ASCII),tokenHash.getBytes(StandardCharsets.US_ASCII));
        }
    }

    synchronized void approveInvite(String id, String candidate, String name, long now) {
        SQLiteDatabase db = getWritableDatabase(); db.beginTransaction();
        try (Cursor c = db.rawQuery("SELECT expires,candidate,consumed FROM invites WHERE id=?", new String[]{id})) {
            if (!c.moveToFirst() || now <= 0 || now >= c.getLong(0) || !candidate.equals(c.getString(1))
                    || c.getInt(2) != 0) throw new SecurityException("Invitation unavailable");
            putPending(db, candidate, id, name, RemoteProtocol.Role.DOM);
            db.execSQL("UPDATE invites SET approved=1,consumed=1 WHERE id=?", new Object[]{id});
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    synchronized void acceptInvite(PairingInvite invite, String name) {
        SQLiteDatabase db = getWritableDatabase(); db.beginTransaction();
        try {
            if (peer(db, invite.domIdentity, invite.id) != null) throw new SecurityException("Invitation already used");
            putPending(db, invite.domIdentity, invite.id, name, RemoteProtocol.Role.SUB);
            db.execSQL("UPDATE peers SET route=? WHERE peer=? AND generation=?",new Object[]{invite.route,invite.domIdentity,invite.id});
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    synchronized void activate(String identity, String generation) {
        ContentValues values = new ContentValues(); values.put("state", State.ACTIVE.name());
        SQLiteDatabase db = getWritableDatabase();
        Peer peer = peer(db, identity, generation);
        if (peer == null || peer.state == State.REVOKED) throw new SecurityException("Pairing unavailable");
        db.update("peers", values, "peer=? AND generation=? AND state='PENDING'", new String[]{identity,generation});
    }

    public synchronized void cancelInvite(String id) {
        getWritableDatabase().execSQL("UPDATE invites SET consumed=1 WHERE id=?", new Object[]{RemoteProtocol.identifier(id)});
    }

    synchronized void setRoute(String identity,String generation,String route) {
        if (route == null || route.length()>4096) throw new IllegalArgumentException("Invalid peer route");
        getWritableDatabase().execSQL("UPDATE peers SET route=? WHERE peer=? AND generation=?",new Object[]{route,identity,generation});
    }

    public synchronized String metadata(String identity,String generation,String name,String fallback) {
        try(Cursor c=getReadableDatabase().rawQuery("SELECT value FROM remote_metadata WHERE peer=? AND generation=? AND name=?",new String[]{identity,generation,name})) {
            return c.moveToFirst()?c.getString(0):fallback;
        }
    }

    synchronized void putMetadata(String identity,String generation,String name,String value) {
        getWritableDatabase().execSQL("INSERT OR REPLACE INTO remote_metadata(peer,generation,name,value) VALUES(?,?,?,?)",new Object[]{identity,generation,name,value});
    }

    synchronized void revoke(String identity, String generation) {
        SQLiteDatabase db = getWritableDatabase(); db.beginTransaction();
        try {
            db.execSQL("UPDATE peers SET state='REVOKED' WHERE peer=? AND generation=?", new Object[]{identity,generation});
            db.delete("outbox", "peer=? AND generation=?", new String[]{identity,generation});
            db.execSQL("UPDATE outgoing SET offered=0,digest=NULL WHERE peer=? AND generation=?", new Object[]{identity,generation});
            db.execSQL("UPDATE commands SET status='REJECTED' WHERE peer=? AND generation=? AND status IN ('QUEUED','SENT','APPLYING')",
                    new Object[]{identity,generation});
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    public synchronized long publish(String identity, String generation, RemoteProtocol.Stream stream,
            String key, JSONObject body, boolean deleted, long observedAt) {
        SQLiteDatabase db = getWritableDatabase(); db.beginTransaction();
        try {
            Peer peer = active(db, identity, generation);
            if (stream.writer != peer.localRole) throw new SecurityException("Not this stream's authority");
            String encoded = deleted ? "{}" : RemoteProtocol.canonical(body);
            String[] itemArgs = {identity,generation,stream.name(),key};
            try (Cursor c = db.rawQuery("SELECT seq,body,deleted,observed FROM local_objects WHERE peer=? AND generation=? AND stream=? AND item=?", itemArgs)) {
                if (c.moveToFirst() && encoded.equals(c.getString(1)) && deleted == (c.getInt(2) != 0)
                        && (stream != RemoteProtocol.Stream.RUNTIME || observedAt == c.getLong(3))) {
                    db.setTransactionSuccessful(); return c.getLong(0);
                }
            }
            ensureOutgoing(db, identity, generation, stream);
            long next;
            try (Cursor c = db.rawQuery("SELECT next_seq FROM outgoing WHERE peer=? AND generation=? AND stream=?", streamArgs(identity,generation,stream))) {
                if (!c.moveToFirst() || c.getLong(0) == Long.MAX_VALUE) throw new IllegalStateException("Revision exhausted");
                next = c.getLong(0) + 1;
            }
            RemoteProtocol.Change change = new RemoteProtocol.Change(next,key,encoded,deleted,observedAt);
            putObject(db,"local_objects",identity,generation,stream,change);
            putObject(db,"outbox",identity,generation,stream,change);
            db.execSQL("UPDATE outgoing SET next_seq=? WHERE peer=? AND generation=? AND stream=?", new Object[]{next,identity,generation,stream.name()});
            db.setTransactionSuccessful(); return next;
        } finally { db.endTransaction(); }
    }

    public synchronized RemoteProtocol.Batch nextBatch(String identity, String generation, RemoteProtocol.Stream stream) {
        SQLiteDatabase db = getWritableDatabase(); db.beginTransaction();
        try {
            Peer peer = active(db,identity,generation);
            if (stream.writer != peer.localRole) throw new SecurityException("Not this stream's authority");
            ensureOutgoing(db,identity,generation,stream);
            long after;
            try (Cursor c = db.rawQuery("SELECT acked FROM outgoing WHERE peer=? AND generation=? AND stream=?",streamArgs(identity,generation,stream))) {
                c.moveToFirst(); after = c.getLong(0);
            }
            List<RemoteProtocol.Change> changes = new ArrayList<>();
            long through = after;
            int bytes = 0;
            try (Cursor c = db.rawQuery("SELECT seq,item,body,deleted,observed FROM outbox WHERE peer=? AND generation=? AND stream=? AND seq>? ORDER BY seq LIMIT "
                    + RemoteProtocol.MAX_BATCH_RECORDS, new String[]{identity,generation,stream.name(),Long.toString(after)})) {
                while (c.moveToNext()) {
                    int size = c.getString(2).getBytes(StandardCharsets.UTF_8).length;
                    if (!changes.isEmpty() && bytes + size > 512 * 1024) break;
                    changes.add(new RemoteProtocol.Change(c.getLong(0),c.getString(1),c.getString(2),c.getInt(3)!=0,c.getLong(4)));
                    through = c.getLong(0); bytes += size;
                }
            }
            RemoteProtocol.Batch batch = new RemoteProtocol.Batch(generation,stream,after,through,changes);
            db.execSQL("UPDATE outgoing SET offered=?,digest=? WHERE peer=? AND generation=? AND stream=?",
                    new Object[]{through,batch.digest(),identity,generation,stream.name()});
            db.setTransactionSuccessful(); return batch;
        } finally { db.endTransaction(); }
    }

    public synchronized void acknowledge(String identity, RemoteProtocol.Ack ack) {
        SQLiteDatabase db = getWritableDatabase(); db.beginTransaction();
        try {
            active(db,identity,ack.generation);
            try (Cursor c = db.rawQuery("SELECT offered,digest,acked FROM outgoing WHERE peer=? AND generation=? AND stream=?",
                    streamArgs(identity,ack.generation,ack.stream))) {
                if (!c.moveToFirst() || ack.through != c.getLong(0) || !ack.digest.equals(c.getString(1))) {
                    throw new SecurityException("Unmatched acknowledgement");
                }
                if (ack.through < c.getLong(2)) throw new SecurityException("Regressing acknowledgement");
            }
            db.execSQL("UPDATE outgoing SET acked=? WHERE peer=? AND generation=? AND stream=?",new Object[]{ack.through,identity,ack.generation,ack.stream.name()});
            db.delete("outbox","peer=? AND generation=? AND stream=? AND seq<=?",new String[]{identity,ack.generation,ack.stream.name(),Long.toString(ack.through)});
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    public synchronized RemoteProtocol.Ack receive(String identity, RemoteProtocol.Batch batch) {
        SQLiteDatabase db = getWritableDatabase(); db.beginTransaction();
        try {
            Peer peer = active(db,identity,batch.generation);
            if (peer.remoteRole() != batch.stream.writer) throw new SecurityException("Peer cannot write this stream");
            long current = 0;
            try (Cursor c = db.rawQuery("SELECT through_seq FROM incoming WHERE peer=? AND generation=? AND stream=?",streamArgs(identity,batch.generation,batch.stream))) {
                if (c.moveToFirst()) current = c.getLong(0);
            }
            if (batch.after > current) throw new IllegalStateException("Sync checkpoint gap");
            if (batch.through > current) {
                for (RemoteProtocol.Change change : batch.changes) {
                    if (change.sequence > current) putObject(db,"remote_objects",identity,batch.generation,batch.stream,change);
                }
                db.execSQL("INSERT OR REPLACE INTO incoming(peer,generation,stream,through_seq) VALUES(?,?,?,?)",
                        new Object[]{identity,batch.generation,batch.stream.name(),batch.through});
            }
            RemoteProtocol.Ack ack = new RemoteProtocol.Ack(batch);
            db.setTransactionSuccessful(); return ack;
        } finally { db.endTransaction(); }
    }

    public synchronized List<RemoteProtocol.Change> mirror(String identity, String generation, RemoteProtocol.Stream stream) {
        List<RemoteProtocol.Change> result = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT seq,item,body,deleted,observed FROM remote_objects WHERE peer=? AND generation=? AND stream=? ORDER BY seq",streamArgs(identity,generation,stream))) {
            while (c.moveToNext()) result.add(new RemoteProtocol.Change(c.getLong(0),c.getString(1),c.getString(2),c.getInt(3)!=0,c.getLong(4)));
        }
        return result;
    }

    public synchronized JSONObject mirrored(String identity, String generation, RemoteProtocol.Stream stream, String key) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT body,deleted FROM remote_objects WHERE peer=? AND generation=? AND stream=? AND item=?",new String[]{identity,generation,stream.name(),key})) {
            if (!c.moveToFirst() || c.getInt(1)!=0) return null;
            try { return new JSONObject(c.getString(0)); }
            catch (JSONException invalid) { throw new IllegalStateException("Invalid local mirror",invalid); }
        }
    }

    public synchronized void synced(String identity, String generation, long now) {
        active(getWritableDatabase(),identity,generation);
        getWritableDatabase().execSQL("UPDATE peers SET last_sync=? WHERE peer=? AND generation=?",new Object[]{now,identity,generation});
    }

    public synchronized JSONObject outgoingHeads(String identity,String generation) throws JSONException {
        Peer peer=active(getReadableDatabase(),identity,generation);JSONObject result=new JSONObject();
        for(RemoteProtocol.Stream stream:RemoteProtocol.Stream.values()) {
            if(stream.writer!=peer.localRole)continue;long head=0;
            try(Cursor c=getReadableDatabase().rawQuery("SELECT next_seq FROM outgoing WHERE peer=? AND generation=? AND stream=?",streamArgs(identity,generation,stream))) {
                if(c.moveToFirst())head=c.getLong(0);
            }
            result.put(stream.name(),"true".equals(metadata(identity,generation,"unavailable_"+stream.name(),""))?-1:head);
        }
        return result;
    }

    synchronized void confirmHeads(String identity,String generation,JSONObject heads) throws JSONException {
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try {
            Peer peer=active(db,identity,generation);int expected=0;
            for(RemoteProtocol.Stream stream:RemoteProtocol.Stream.values()) {
                if(stream.writer!=peer.remoteRole())continue;expected++;
                long head=RemoteProtocol.integer(heads,stream.name()),received=0;
                if(head==-1){putMetadata(identity,generation,"confirmed_"+stream.name(),"0");continue;}
                try(Cursor c=db.rawQuery("SELECT through_seq FROM incoming WHERE peer=? AND generation=? AND stream=?",streamArgs(identity,generation,stream))) {
                    if(c.moveToFirst())received=c.getLong(0);
                }
                if(head<received || head<0)throw new SecurityException("Regressing source checkpoint");
                if(head==received)putMetadata(identity,generation,"confirmed_"+stream.name(),Long.toString(System.currentTimeMillis()));
            }
            if(heads.length()!=expected)throw new JSONException("Unsupported source streams");
            db.setTransactionSuccessful();
        } finally {db.endTransaction();}
    }

    public synchronized List<RemoteProtocol.Change> published(String identity,String generation,RemoteProtocol.Stream stream) {
        List<RemoteProtocol.Change> result=new ArrayList<>();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT seq,item,body,deleted,observed FROM local_objects WHERE peer=? AND generation=? AND stream=? ORDER BY seq",streamArgs(identity,generation,stream))) {
            while(c.moveToNext())result.add(new RemoteProtocol.Change(c.getLong(0),c.getString(1),c.getString(2),c.getInt(3)!=0,c.getLong(4)));
        }
        return result;
    }

    public enum CommandState { QUEUED,SENT,APPLYING,APPLIED,REJECTED,CONFLICT }
    public static final class Command {
        public final String id,body;
        public final long revision;
        public final CommandState state;
        Command(String id,long revision,CommandState state,String body){this.id=id;this.revision=revision;this.state=state;this.body=body;}
        public boolean pending(){return state==CommandState.QUEUED || state==CommandState.SENT || state==CommandState.APPLYING;}
    }

    public synchronized Command latestCommand(String identity,String generation) {
        try(Cursor c=getReadableDatabase().rawQuery("SELECT id,revision,status,body FROM commands WHERE peer=? AND generation=? ORDER BY revision DESC LIMIT 1",new String[]{identity,generation})) {
            return c.moveToFirst()?readCommand(c):null;
        }
    }
    synchronized Command command(String identity,String generation,String id) {
        try(Cursor c=getReadableDatabase().rawQuery("SELECT id,revision,status,body FROM commands WHERE peer=? AND generation=? AND id=?",new String[]{identity,generation,id})) {
            return c.moveToFirst()?readCommand(c):null;
        }
    }

    public synchronized Command queueConfiguration(String identity,String generation,long expectedDesired,long expectedApplied,JSONObject configuration) throws JSONException {
        JSONObject doc=RemoteConfiguration.active(configuration);SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try {
            Peer peer=active(db,identity,generation);if(peer.localRole!=RemoteProtocol.Role.DOM)throw new SecurityException("Only the paired Dom controls configuration");
            long previous=Long.parseLong(metadata(identity,generation,"desired_revision","0"));
            if(previous!=expectedDesired || previous==Long.MAX_VALUE || expectedApplied<0)throw new IllegalStateException("Configuration changed; reload before saving");
            Command pending=latestCommand(identity,generation);if(pending!=null && pending.pending())throw new IllegalStateException("A configuration change is pending");
            String id=java.util.UUID.randomUUID().toString();long revision=previous+1;
            JSONObject body=new JSONObject().put("id",id).put("revision",revision).put("base",expectedApplied).put("configuration",doc);
            insertCommand(db,identity,generation,id,revision,CommandState.QUEUED,RemoteProtocol.canonical(body));
            putMetadata(identity,generation,"desired_revision",Long.toString(revision));
            publish(identity,generation,RemoteProtocol.Stream.CONFIGURATION,"desired",body,false,System.currentTimeMillis());
            db.setTransactionSuccessful();return command(identity,generation,id);
        } finally {db.endTransaction();}
    }

    synchronized Command stageCommand(String identity,String generation,JSONObject body) throws JSONException {
        Peer peer=active(getWritableDatabase(),identity,generation);if(peer.localRole!=RemoteProtocol.Role.SUB)throw new SecurityException("Not a managed device");
        RemoteConfiguration.keys(body,"id","revision","base","configuration");String id=RemoteProtocol.identifier(body.getString("id"));
        long revision=RemoteProtocol.integer(body,"revision"),base=RemoteProtocol.integer(body,"base");
        if(base<0 || revision<=base)throw new IllegalArgumentException("Invalid configuration revision");
        body.getJSONObject("configuration");String encoded=RemoteProtocol.canonical(body);
        Command old=command(identity,generation,id);
        if(old!=null) {if(!old.body.equals(encoded))throw new SecurityException("Command identity reused");return old;}
        insertCommand(getWritableDatabase(),identity,generation,id,revision,CommandState.QUEUED,encoded);
        return command(identity,generation,id);
    }

    synchronized void markApplying(String identity,String generation,String id) {
        active(getWritableDatabase(),identity,generation);
        getWritableDatabase().execSQL("UPDATE commands SET status='APPLYING' WHERE peer=? AND generation=? AND id=? AND status='QUEUED'",new Object[]{identity,generation,id});
    }

    synchronized void commandResult(String identity,String generation,Command command,CommandState state,JSONObject applied,String reason) throws JSONException {
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try {
            Peer peer=active(db,identity,generation);if(peer.localRole!=RemoteProtocol.Role.SUB)throw new SecurityException("Not a managed device");
            if(state!=CommandState.APPLIED && state!=CommandState.CONFLICT && state!=CommandState.REJECTED)throw new IllegalArgumentException("Not a terminal command result");
            Command current=command(identity,generation,command.id);
            if(current==null || !current.body.equals(command.body))throw new SecurityException("Unknown configuration command");
            if(!current.pending()) {
                if(current.state!=state)throw new SecurityException("Configuration result changed");
                db.setTransactionSuccessful();return;
            }
            if(state==CommandState.APPLIED) {
                putMetadata(identity,generation,"applied_revision",Long.toString(command.revision));
                putMetadata(identity,generation,"applied_configuration",RemoteProtocol.canonical(RemoteConfiguration.active(applied)));
            }
            db.execSQL("UPDATE commands SET status=? WHERE peer=? AND generation=? AND id=?",new Object[]{state.name(),identity,generation,command.id});
            JSONObject result=new JSONObject().put("schema",1).put("id",command.id).put("revision",command.revision).put("state",state.name())
                    .put("appliedRevision",Long.parseLong(metadata(identity,generation,"applied_revision","0"))).put("reason",reason);
            publish(identity,generation,RemoteProtocol.Stream.COMMAND_RESULT,command.id,result,false,System.currentTimeMillis());
            db.setTransactionSuccessful();
        } finally {db.endTransaction();}
    }

    synchronized void markSent(String identity,RemoteProtocol.Batch batch) throws JSONException {
        if(batch.stream!=RemoteProtocol.Stream.CONFIGURATION)return;
        for(RemoteProtocol.Change row:batch.changes) {
            String id=new JSONObject(row.body).optString("id","");
            if(row.key.startsWith("end:"))RemoteEndCommands.markSent(this,identity,batch.generation,id);
            getWritableDatabase().execSQL("UPDATE commands SET status='SENT' WHERE peer=? AND generation=? AND id=? AND status='QUEUED'",new Object[]{identity,batch.generation,id});
        }
    }

    synchronized void acceptResult(String identity,String generation,JSONObject result) throws JSONException {
        Peer peer=active(getWritableDatabase(),identity,generation);if(peer.localRole!=RemoteProtocol.Role.DOM)throw new SecurityException("Unexpected command result");
        if(RemoteProtocol.integer(result,"schema")!=1)throw new IllegalArgumentException("Unsupported command result");
        Command old=command(identity,generation,result.getString("id"));if(old==null)return;
        CommandState state=CommandState.valueOf(result.getString("state"));long applied=RemoteProtocol.integer(result,"appliedRevision");
        if(RemoteProtocol.integer(result,"revision")!=old.revision || applied<0 || state==CommandState.APPLIED && applied!=old.revision
                || state!=CommandState.APPLIED && state!=CommandState.REJECTED && state!=CommandState.CONFLICT)throw new SecurityException("Invalid command result");
        if(!old.pending() && old.state!=state)throw new SecurityException("Configuration result changed");
        getWritableDatabase().execSQL("UPDATE commands SET status=? WHERE peer=? AND generation=? AND id=?",new Object[]{state.name(),identity,generation,old.id});
        long previous=Long.parseLong(metadata(identity,generation,"applied_revision","0"));
        if(applied>=previous)putMetadata(identity,generation,"applied_revision",Long.toString(applied));
        putMetadata(identity,generation,"last_command_reason",result.optString("reason",""));
    }

    private static Command readCommand(Cursor c){return new Command(c.getString(0),c.getLong(1),CommandState.valueOf(c.getString(2)),c.getString(3));}
    private static void insertCommand(SQLiteDatabase db,String peer,String generation,String id,long revision,CommandState state,String body) {
        db.execSQL("INSERT INTO commands(peer,generation,id,revision,status,body) VALUES(?,?,?,?,?,?)",new Object[]{peer,generation,id,revision,state.name(),body});
    }

    private static void putPending(SQLiteDatabase db,String identity,String generation,String name,RemoteProtocol.Role role) {
        RemoteProtocol.peerId(identity); RemoteProtocol.identifier(generation);
        name=RemoteProtocol.deviceName(name);
        db.execSQL("INSERT INTO peers(peer,generation,name,local_role,state) VALUES(?,?,?,?,'PENDING')",new Object[]{identity,generation,name.trim(),role.name()});
    }
    private static Peer active(SQLiteDatabase db,String identity,String generation) {
        Peer peer = peer(db,identity,generation);
        if (peer == null || peer.state != State.ACTIVE) throw new SecurityException("Pairing not active");
        return peer;
    }
    private static Peer peer(SQLiteDatabase db,String identity,String generation) {
        try (Cursor c = db.rawQuery("SELECT peer,generation,name,local_role,state,last_sync,route FROM peers WHERE peer=? AND generation=?",new String[]{identity,generation})) {
            return c.moveToFirst() ? readPeer(c) : null;
        }
    }
    private static Peer readPeer(Cursor c) {
        return new Peer(c.getString(0),c.getString(1),c.getString(2),RemoteProtocol.Role.valueOf(c.getString(3)),State.valueOf(c.getString(4)),c.getLong(5),c.getString(6));
    }
    private static String[] streamArgs(String identity,String generation,RemoteProtocol.Stream stream) {
        return new String[]{identity,generation,stream.name()};
    }
    private static void ensureOutgoing(SQLiteDatabase db,String identity,String generation,RemoteProtocol.Stream stream) {
        db.execSQL("INSERT OR IGNORE INTO outgoing(peer,generation,stream) VALUES(?,?,?)",new Object[]{identity,generation,stream.name()});
    }
    private static void putObject(SQLiteDatabase db,String table,String identity,String generation,RemoteProtocol.Stream stream,RemoteProtocol.Change change) {
        ContentValues v = new ContentValues();
        v.put("peer",identity);v.put("generation",generation);v.put("stream",stream.name());v.put("item",change.key);
        v.put("seq",change.sequence);v.put("body",change.body);v.put("deleted",change.deleted ? 1 : 0);v.put("observed",change.observedAt);
        if (db.insertWithOnConflict(table,null,v,SQLiteDatabase.CONFLICT_REPLACE) == -1) {
            throw new android.database.sqlite.SQLiteException("Could not persist sync record");
        }
    }
}
