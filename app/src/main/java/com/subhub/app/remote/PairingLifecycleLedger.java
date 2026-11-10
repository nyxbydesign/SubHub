package com.subhub.app.remote;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.content.ContentValues;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Clockless legacy invitations acquire no rows here. All future writes require V2 coverage. */
final class PairingLifecycleLedger {
    static final String TABLE = "pairing_lifecycle";
    static final int MAX_RECORDS = 128;
    static final int MAX_UNRESOLVED = 16;
    enum Stage { PENDING, APPROVED, COMPLETE, CANCELLED, EXPIRED }
    private static final String[] COLUMNS = {
            "operation_id", "generation", "direction", "peer", "token_hash", "state",
            "created_wall", "created_elapsed", "created_boot", "public_deadline_wall", "public_deadline_elapsed",
            "approved_wall", "approved_elapsed", "approved_boot", "approval_digest",
            "completion_deadline_wall", "completion_deadline_elapsed", "cancel_capability_hash",
            "terminal_wall", "terminal_elapsed", "terminal_boot"
    };

    private PairingLifecycleLedger() { }

    static void addCoverage(SQLiteDatabase db) {
        db.execSQL("ALTER TABLE linking_state ADD COLUMN digest_version INTEGER NOT NULL DEFAULT 1 CHECK(digest_version IN(1,2))");
        create(db);
    }

    static void create(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE + "("
                + "operation_id TEXT NOT NULL CHECK(length(operation_id) BETWEEN 1 AND 128 AND operation_id NOT GLOB '*[^A-Za-z0-9_-]*'),"
                + "generation TEXT NOT NULL CHECK(length(generation) BETWEEN 1 AND 128 AND generation NOT GLOB '*[^A-Za-z0-9_-]*'),"
                + "direction TEXT NOT NULL CHECK(direction IN('CREATE','ACCEPT')),"
                + "peer TEXT CHECK(peer IS NULL OR (length(peer)=64 AND peer NOT GLOB '*[^0-9a-f]*')),"
                + "token_hash TEXT NOT NULL CHECK(length(token_hash)=64 AND token_hash NOT GLOB '*[^0-9a-f]*'),"
                + "state TEXT NOT NULL CHECK(state IN('PENDING','APPROVED','COMPLETE','CANCELLED','EXPIRED')),"
                + "created_wall INTEGER NOT NULL CHECK(typeof(created_wall)='integer' AND created_wall>0),"
                + "created_elapsed INTEGER NOT NULL CHECK(typeof(created_elapsed)='integer' AND created_elapsed>=0),"
                + "created_boot INTEGER NOT NULL CHECK(typeof(created_boot)='integer' AND created_boot>=0),"
                + "public_deadline_wall INTEGER NOT NULL CHECK(typeof(public_deadline_wall)='integer'),"
                + "public_deadline_elapsed INTEGER NOT NULL CHECK(typeof(public_deadline_elapsed)='integer' AND public_deadline_elapsed<=9223372036854175807),"
                + "approved_wall INTEGER,approved_elapsed INTEGER,approved_boot INTEGER,approval_digest TEXT,"
                + "completion_deadline_wall INTEGER,completion_deadline_elapsed INTEGER,cancel_capability_hash TEXT,"
                + "terminal_wall INTEGER,terminal_elapsed INTEGER,terminal_boot INTEGER,"
                + "PRIMARY KEY(operation_id,generation,direction),UNIQUE(generation,direction),"
                + "CHECK(direction!='ACCEPT' OR peer IS NOT NULL),"
                + "CHECK(state NOT IN('APPROVED','COMPLETE') OR peer IS NOT NULL),"
                + "CHECK(public_deadline_elapsed-created_elapsed BETWEEN 1 AND 600000),"
                + "CHECK(public_deadline_wall-created_wall=public_deadline_elapsed-created_elapsed),"
                + "CHECK((approved_wall IS NULL AND approved_elapsed IS NULL AND approved_boot IS NULL AND approval_digest IS NULL"
                + " AND completion_deadline_wall IS NULL AND completion_deadline_elapsed IS NULL) OR"
                + " (typeof(approved_wall)='integer' AND approved_wall>0 AND typeof(approved_elapsed)='integer'"
                + " AND approved_elapsed>=created_elapsed AND typeof(approved_boot)='integer' AND approved_boot=created_boot"
                + " AND approval_digest IS NOT NULL AND length(approval_digest)=64 AND approval_digest NOT GLOB '*[^0-9a-f]*'"
                + " AND typeof(completion_deadline_wall)='integer' AND typeof(completion_deadline_elapsed)='integer'"
                + " AND completion_deadline_elapsed-approved_elapsed BETWEEN 1 AND 600000"
                + " AND completion_deadline_elapsed-public_deadline_elapsed<=600000"
                + " AND completion_deadline_wall-approved_wall=completion_deadline_elapsed-approved_elapsed"
                + " AND (direction!='CREATE' OR approved_elapsed<public_deadline_elapsed))),"
                + "CHECK(state!='APPROVED' OR approved_wall IS NOT NULL),"
                + "CHECK(cancel_capability_hash IS NULL OR (length(cancel_capability_hash)=64 AND cancel_capability_hash NOT GLOB '*[^0-9a-f]*')),"
                + "CHECK((terminal_wall IS NULL AND terminal_elapsed IS NULL AND terminal_boot IS NULL) OR"
                + " (typeof(terminal_wall)='integer' AND terminal_wall>0 AND typeof(terminal_elapsed)='integer'"
                + " AND terminal_elapsed>=created_elapsed AND typeof(terminal_boot)='integer' AND terminal_boot=created_boot)),"
                + "CHECK(state IN('COMPLETE','CANCELLED','EXPIRED') OR terminal_wall IS NULL),"
                + "CHECK(state!='EXPIRED' OR terminal_wall IS NOT NULL))");
    }

    static void requireEmpty(SQLiteDatabase db) {
        try (Cursor row = db.rawQuery("SELECT 1 FROM " + TABLE + " LIMIT 1", null)) {
            if (row.moveToFirst()) throw new IllegalStateException("Uncovered pairing lifecycle data");
        }
    }

    static void requireCovered(SQLiteDatabase db) {
        try (Cursor row = db.rawQuery("SELECT digest_version FROM linking_state WHERE id=1", null)) {
            if (!row.moveToFirst() || row.getType(0) != Cursor.FIELD_TYPE_INTEGER || row.getLong(0) != 2)
                throw new IllegalStateException("Pairing lifecycle coverage required");
        }
    }

    static final class Operation {
        final String id,generation,peer,tokenHash,approvalDigest,cancelHash;
        final PairingLifecycleClock.Direction direction;final Stage stage;
        final PairingLifecycleClock.Bounds bounds;final PairingLifecycleClock.Approval approval;
        private Operation(Cursor row){
            id=RemoteProtocol.identifier(text(row,0));generation=RemoteProtocol.identifier(text(row,1));
            direction=PairingLifecycleClock.Direction.valueOf(text(row,2));
            peer=row.isNull(3)?null:RemoteProtocol.peerId(text(row,3));tokenHash=hash(text(row,4));stage=Stage.valueOf(text(row,5));
            FormalRelationshipClock.Sample created=sample(row,6,7,8);
            bounds=new PairingLifecycleClock.Bounds(created,integer(row,9),integer(row,10));
            if(row.isNull(11)){
                for(int i=12;i<=16;i++)if(!row.isNull(i))throw new IllegalStateException("Incomplete approval clock");
                approval=null;approvalDigest=null;
            }else{
                approval=new PairingLifecycleClock.Approval(bounds,sample(row,11,12,13),integer(row,15),integer(row,16));
                approvalDigest=hash(text(row,14));
            }
            cancelHash=row.isNull(17)?null:hash(text(row,17));
            if(row.isNull(18)){
                if(!row.isNull(19)||!row.isNull(20)||stage==Stage.EXPIRED)throw new IllegalStateException("Incomplete terminal clock");
            }else{
                if(stage==Stage.PENDING||stage==Stage.APPROVED||!FormalRelationshipClock.consistent(created,sample(row,18,19,20)))
                    throw new IllegalStateException("Invalid terminal clock");
            }
            if(direction==PairingLifecycleClock.Direction.ACCEPT&&peer==null
                    ||(stage==Stage.APPROVED||stage==Stage.COMPLETE)&&peer==null
                    ||stage==Stage.APPROVED&&approval==null)throw new IllegalStateException("Incomplete pairing operation");
        }
        PairingLifecycleClock.Verdict verdict(FormalRelationshipClock.Sample now){
            if(stage!=Stage.PENDING&&stage!=Stage.APPROVED)return PairingLifecycleClock.Verdict.EXPIRED;
            return approval==null?PairingLifecycleClock.publicVerdict(bounds,now):PairingLifecycleClock.completionVerdict(bounds,approval,now);
        }
        long remaining(FormalRelationshipClock.Sample now){
            return stage==Stage.PENDING||stage==Stage.APPROVED?PairingLifecycleClock.remaining(bounds,approval,now):0;
        }
        void requireToken(String supplied){
            if(supplied==null||!supplied.matches("[0-9a-f]{64}")||!MessageDigest.isEqual(tokenHash.getBytes(StandardCharsets.US_ASCII),supplied.getBytes(StandardCharsets.US_ASCII)))
                throw new SecurityException("Pairing token differs");
        }
        void requirePeer(String supplied){if(peer==null||!peer.equals(supplied))throw new SecurityException("Pairing participant differs");}
        void requireLive(FormalRelationshipClock.Sample now){
            if(verdict(now)!=PairingLifecycleClock.Verdict.LIVE)throw new SecurityException("Original pairing lifetime unavailable");
        }
    }

    static Operation find(SQLiteDatabase db,String id,String generation,PairingLifecycleClock.Direction direction){
        requireCovered(db);RemoteProtocol.identifier(id);RemoteProtocol.identifier(generation);
        try(Cursor row=db.rawQuery("SELECT "+String.join(",",COLUMNS)+" FROM "+TABLE+" WHERE operation_id=? AND generation=? AND direction=?",
                new String[]{id,generation,direction.name()})){return row.moveToFirst()?new Operation(row):null;}
    }
    static Operation require(SQLiteDatabase db,String id,String generation,PairingLifecycleClock.Direction direction){
        Operation operation=find(db,id,generation,direction);if(operation==null)throw new SecurityException("Original pairing clock unavailable");return operation;
    }
    static Operation current(SQLiteDatabase db,PairingLifecycleClock.Direction direction){
        requireCovered(db);Operation result=null;
        try(Cursor rows=db.rawQuery("SELECT "+String.join(",",COLUMNS)+" FROM "+TABLE+" WHERE direction=? AND state IN('PENDING','APPROVED')"
                +" AND NOT EXISTS(SELECT 1 FROM peers p WHERE p.peer="+TABLE+".peer AND p.generation="+TABLE+".generation AND p.state IN('ACTIVE','REVOKED'))"
                +" ORDER BY created_wall,operation_id LIMIT 2",new String[]{direction.name()})){
            while(rows.moveToNext()){if(result!=null)throw new IllegalStateException("Ambiguous current pairing operation");result=new Operation(rows);}
        }
        return result;
    }
    static Operation insert(SQLiteDatabase db,PairingInvite invite,PairingLifecycleClock.Direction direction,FormalRelationshipClock.Sample original){
        write(db);if(current(db,direction)!=null)throw new SecurityException("Resolve the current pairing operation first");
        try(Cursor row=db.rawQuery("SELECT COUNT(*),COALESCE(SUM(CASE WHEN state IN('PENDING','APPROVED') THEN 1 ELSE 0 END),0) FROM "+TABLE,null)){
            if(!row.moveToFirst()||row.getLong(0)>=MAX_RECORDS||row.getLong(1)>=MAX_UNRESOLVED)throw new SecurityException("Pairing operation capacity reached");
        }
        PairingLifecycleClock.Bounds bounds=PairingLifecycleClock.begin(original,invite.expiresAt);ContentValues values=new ContentValues();
        values.put("operation_id",invite.id);values.put("generation",invite.id);values.put("direction",direction.name());
        if(direction==PairingLifecycleClock.Direction.ACCEPT)values.put("peer",invite.domIdentity);
        values.put("token_hash",invite.tokenHash());values.put("state",Stage.PENDING.name());
        values.put("created_wall",original.wall);values.put("created_elapsed",original.elapsed);values.put("created_boot",original.boot);
        values.put("public_deadline_wall",bounds.publicWall);values.put("public_deadline_elapsed",bounds.publicElapsed);
        db.insertOrThrow(TABLE,null,values);return require(db,invite.id,invite.id,direction);
    }
    static Operation bind(SQLiteDatabase db,Operation operation,String peer,FormalRelationshipClock.Sample now){
        write(db);RemoteProtocol.peerId(peer);operation.requireLive(now);
        if(operation.stage!=Stage.PENDING||operation.approval!=null)throw new SecurityException("Invitation no longer public");
        if(operation.peer!=null){operation.requirePeer(peer);return operation;}
        ContentValues values=new ContentValues();values.put("peer",peer);update(db,operation,values);
        return require(db,operation.id,operation.generation,operation.direction);
    }
    static Operation approve(SQLiteDatabase db,Operation operation,String digest,FormalRelationshipClock.Sample now){
        write(db);hash(digest);if(operation.peer==null)throw new SecurityException("Pairing participant unavailable");
        if(operation.stage==Stage.APPROVED){
            operation.requireLive(now);if(!digest.equals(operation.approvalDigest))throw new SecurityException("Pairing approval changed");return operation;
        }
        if(operation.stage!=Stage.PENDING)throw new SecurityException("Pairing operation is terminal");
        PairingLifecycleClock.Approval approval=PairingLifecycleClock.firstApproval(operation.bounds,operation.direction,now);
        ContentValues values=new ContentValues();values.put("state",Stage.APPROVED.name());values.put("approval_digest",digest);
        values.put("approved_wall",approval.first.wall);values.put("approved_elapsed",approval.first.elapsed);values.put("approved_boot",approval.first.boot);
        values.put("completion_deadline_wall",approval.deadlineWall);values.put("completion_deadline_elapsed",approval.deadlineElapsed);update(db,operation,values);
        return require(db,operation.id,operation.generation,operation.direction);
    }
    static void setCancelCapability(SQLiteDatabase db,Operation operation,String digest,FormalRelationshipClock.Sample now){
        write(db);operation.requireLive(now);ContentValues values=new ContentValues();values.put("cancel_capability_hash",hash(digest));update(db,operation,values);
    }
    static void complete(SQLiteDatabase db,Operation operation,FormalRelationshipClock.Sample now,boolean recovered){
        write(db);if(operation.stage==Stage.CANCELLED)throw new SecurityException("Pairing was cancelled");
        // An authenticated ACTIVE counterpart does not resurrect the expired bearer operation.
        if(operation.stage==Stage.EXPIRED||operation.stage==Stage.COMPLETE)return;
        if(!recovered&&(operation.stage!=Stage.APPROVED||operation.approval==null))throw new SecurityException("Pairing approval required");
        if(!recovered)operation.requireLive(now);
        terminal(db,operation,Stage.COMPLETE,now);
    }
    static boolean expire(SQLiteDatabase db,Operation operation,FormalRelationshipClock.Sample now){
        write(db);if(operation.stage!=Stage.PENDING&&operation.stage!=Stage.APPROVED)return false;
        if(operation.verdict(now)!=PairingLifecycleClock.Verdict.EXPIRED)return false;
        terminal(db,operation,Stage.EXPIRED,now);return true;
    }
    static void cancel(SQLiteDatabase db,Operation operation,FormalRelationshipClock.Sample now){
        write(db);if(operation.stage==Stage.COMPLETE||operation.stage==Stage.CANCELLED||operation.stage==Stage.EXPIRED)return;
        terminal(db,operation,Stage.CANCELLED,now);
    }
    private static void terminal(SQLiteDatabase db,Operation operation,Stage stage,FormalRelationshipClock.Sample now){
        ContentValues values=new ContentValues();values.put("state",stage.name());
        if(FormalRelationshipClock.consistent(operation.bounds.created,now)){
            values.put("terminal_wall",now.wall);values.put("terminal_elapsed",now.elapsed);values.put("terminal_boot",now.boot);
        }else{
            if(stage==Stage.EXPIRED)throw new SecurityException("Pairing expiry clock unavailable");
            values.putNull("terminal_wall");values.putNull("terminal_elapsed");values.putNull("terminal_boot");
        }
        update(db,operation,values);
    }
    private static void update(SQLiteDatabase db,Operation operation,ContentValues values){
        if(db.update(TABLE,values,"operation_id=? AND generation=? AND direction=? AND state=?",
                new String[]{operation.id,operation.generation,operation.direction.name(),operation.stage.name()})!=1)
            throw new SecurityException("Pairing operation changed");
    }
    private static void write(SQLiteDatabase db){FormalRelationshipStore.transaction(db);requireCovered(db);}
    private static FormalRelationshipClock.Sample sample(Cursor row,int wall,int elapsed,int boot){
        long observedBoot=integer(row,boot);if(observedBoot<0||observedBoot>Integer.MAX_VALUE)throw new IllegalStateException("Invalid pairing boot");
        FormalRelationshipClock.Sample sample=new FormalRelationshipClock.Sample(integer(row,wall),integer(row,elapsed),(int)observedBoot);sample.requireValid();return sample;
    }
    private static long integer(Cursor row,int column){if(row.getType(column)!=Cursor.FIELD_TYPE_INTEGER)throw new IllegalStateException("Integer pairing field required");return row.getLong(column);}
    private static String text(Cursor row,int column){if(row.getType(column)!=Cursor.FIELD_TYPE_STRING)throw new IllegalStateException("Text pairing field required");return row.getString(column);}
    private static String hash(String value){if(value==null||!value.matches("[0-9a-f]{64}"))throw new IllegalStateException("Invalid pairing digest");return value;}

    /** V2 hashes explicit columns, SQLite types, lengths and BINARY-key order, including an empty table. */
    static void digestInto(MessageDigest hash, SQLiteDatabase db) {
        StringBuilder selected = new StringBuilder();
        field(hash, TABLE.getBytes(StandardCharsets.US_ASCII));
        for (String column : COLUMNS) {
            if (selected.length() != 0) selected.append(',');
            selected.append(column); field(hash, column.getBytes(StandardCharsets.US_ASCII));
        }
        try (Cursor rows = db.rawQuery("SELECT " + selected + " FROM " + TABLE
                + " ORDER BY operation_id COLLATE BINARY,generation COLLATE BINARY,direction COLLATE BINARY", null)) {
            if (rows.getCount() > MAX_RECORDS) throw new IllegalStateException("Pairing lifecycle capacity exceeded");
            hash.update(ByteBuffer.allocate(4).putInt(rows.getCount()).array());
            while (rows.moveToNext()) for (int i = 0; i < COLUMNS.length; i++) {
                int type = rows.getType(i);
                if (type != Cursor.FIELD_TYPE_NULL && type != Cursor.FIELD_TYPE_INTEGER && type != Cursor.FIELD_TYPE_STRING)
                    throw new IllegalStateException("Invalid pairing lifecycle field type");
                hash.update((byte) type);
                field(hash, type == Cursor.FIELD_TYPE_NULL ? new byte[0]
                        : rows.getString(i).getBytes(StandardCharsets.UTF_8));
            }
        }
    }

    private static void field(MessageDigest hash, byte[] value) {
        hash.update(ByteBuffer.allocate(4).putInt(value.length).array()); hash.update(value);
    }
}
