package com.subhub.app.remote;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import com.subhub.app.appmode.RuntimeSessionIdentity;
import com.subhub.app.relationships.RelationshipRecoveryUi;
import com.subhub.app.relationships.RelationshipUi;
import com.subhub.app.security.SubFeaturePolicy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;

/** Internal qualification engine, not an application provider or a witnessed authority initializer. */
final class FormalRelationshipCoordinator {
    private final Context context;
    private final RemoteStore store;
    private final LocalRelationshipAuthority authority;
    private final FormalRelationshipClock.Source clock;
    private static final Map<String, long[]> OWNED_FIXTURES = new LinkedHashMap<>();
    private final Map<String, LocalRelationshipAuthority.Observation> reviews = new LinkedHashMap<>();
    static final class InternalSnapshot {
        final String dom, sub, changeCounter;
        InternalSnapshot(String dom, String sub, String counter) { this.dom = dom; this.sub = sub; this.changeCounter = counter; }
    }
    FormalRelationshipCoordinator(Context context, RemoteStore store) {
        this(context, store, FormalRelationshipClock.source(context));
    }
    FormalRelationshipCoordinator(Context context, RemoteStore store, FormalRelationshipClock.Source clock) {
        requireOwnedFixture(store);
        this.context = context.getApplicationContext(); this.store = store;
        this.clock = java.util.Objects.requireNonNull(clock);
        authority = new LocalRelationshipAuthority(context, store, clock);
    }
    /** Allocate a new physical fixture file; existing databases cannot be promoted by name or a flag. */
    static RemoteStore createOwnedFixture(Context context) {
        java.io.File file = null;
        try {
            java.io.File directory = context.getDatabasePath("formal-fixture-location").getCanonicalFile().getParentFile();
            if (directory == null || !directory.isDirectory() && !directory.mkdirs()) throw new java.io.IOException("Fixture directory unavailable");
            file = java.io.File.createTempFile("formal-fixture-", ".db", directory).getCanonicalFile();
            RemoteStore result = new RemoteStore(context, file.getName());
            if (!file.equals(result.resolvedDatabaseFile())) throw new java.io.IOException("Fixture path was redirected");
            android.system.StructStat stat = android.system.Os.stat(file.getPath());
            synchronized (OWNED_FIXTURES) {
                if (OWNED_FIXTURES.size() >= 64) throw new java.io.IOException("Fixture scope capacity");
                OWNED_FIXTURES.put(file.getPath(), new long[]{stat.st_dev, stat.st_ino});
            }
            return result;
        } catch (java.io.IOException | android.system.ErrnoException failure) {
            if (file != null && !file.delete()) throw new IllegalStateException("Owned fixture cleanup failed", failure);
            throw new IllegalArgumentException("Could not allocate owned fixture", failure);
        }
    }
    static void releaseOwnedFixture(RemoteStore store) {
        rejectHeldEntry(store);
        try { synchronized (OWNED_FIXTURES) { OWNED_FIXTURES.remove(store.resolvedDatabaseFile().getPath()); } }
        catch (java.io.IOException failure) { throw new IllegalStateException("Fixture identity unavailable", failure); }
    }
    private static void requireOwnedFixture(RemoteStore store) {
        rejectHeldEntry(store);
        try {
            java.io.File file = store.resolvedDatabaseFile();
            android.system.StructStat stat = android.system.Os.stat(file.getPath());
            synchronized (OWNED_FIXTURES) {
                long[] owned = OWNED_FIXTURES.get(file.getPath());
                if (owned == null || owned[0] != stat.st_dev || owned[1] != stat.st_ino)
                    throw new IllegalArgumentException("Internal qualification requires a freshly allocated owned fixture");
            }
        } catch (java.io.IOException | android.system.ErrnoException failure) {
            throw new IllegalArgumentException("Fixture database identity unavailable", failure);
        }
    }
    private static void rejectHeldEntry(RemoteStore store) {
        if (Thread.holdsLock(store) || Thread.holdsLock(SubFeaturePolicy.LOCK)
                || Thread.holdsLock(RuntimeSessionIdentity.LOCK) || store.hasCurrentFormalTransaction())
            throw new IllegalStateException("Formal entry requires no outer transaction or authority lock");
    }
    private interface Work<T> { T run(SQLiteDatabase db, LocalRelationshipAuthority.Observation auth, FormalRelationshipClock.Sample now); }
    private <T> T authorized(Work<T> work) {
        requireOwnedFixture(store); // Canonical path/inode observation is outside SQL and authority locks.
        LocalRelationshipAuthority.Observation auth = authority.observe();
        synchronized (SubFeaturePolicy.LOCK) { synchronized (store) { synchronized (RuntimeSessionIdentity.LOCK) {
            SQLiteDatabase db = store.getWritableDatabase();
            if (db.inTransaction()) throw new IllegalStateException("Independent formal transaction required");
            db.beginTransaction();
            try {
                FormalRelationshipClock.Sample now = clock.transition(authority.anchor(auth));
                now.requireValid();
                authority.require(auth, now);
                if (FormalRelationshipStore.number(db, "migration_complete") != 1)
                    throw new IllegalStateException("Legacy adaptation incomplete");
                FormalRelationshipStore.incarnation(db); // Enforces INERT; never promotes readiness.
                T result = work.run(db, auth, now); db.setTransactionSuccessful(); return result;
            } finally { db.endTransaction(); }
        } } }
    }
    InternalSnapshot internalSnapshot() {
        return authorized((db, auth, now) -> {
            long count;
            try (Cursor row = db.rawQuery("SELECT COUNT(*) FROM formal_participants WHERE local=1", null)) { row.moveToFirst(); count = row.getLong(0); }
            String dom = FormalRelationshipStore.local(db, "DOM"), sub = FormalRelationshipStore.local(db, "SUB");
            if (count != 2) FormalRelationshipStore.changed(db);
            return new InternalSnapshot(dom, sub, FormalRelationshipStore.internalRevision(db));
        });
    }
    RelationshipUi.LocalReview prepareLocal(String key, String dom, String sub, String originalRevision) {
        requireText(key); requireText(dom); requireText(sub); requireText(originalRevision);
        return authorized((db, auth, now) -> {
            Row retained = by(db, "operation_key", key);
            if (retained != null) {
                if (!retained.dom.equals(dom) || !retained.sub.equals(sub) || !retained.revision.equals(originalRevision))
                    throw new SecurityException("Preparation operands changed");
                return mint(db, retained, auth, now);
            }
            if (!originalRevision.equals(FormalRelationshipStore.internalRevision(db)))
                throw new SecurityException("Internal snapshot changed");
            requireLocalRef(db, dom, "DOM"); requireLocalRef(db, sub, "SUB");
            if (dom.equals(sub)) throw new SecurityException("Distinct participants required");
            prune(db, now);
            long unresolved, total;
            try (Cursor row = db.rawQuery("SELECT COUNT(*),COALESCE(SUM(CASE WHEN state='PREPARED' THEN 1 ELSE 0 END),0) FROM formal_operations", null)) {
                row.moveToFirst(); total = row.getLong(0); unresolved = row.getLong(1);
            }
            if (unresolved >= FormalRelationshipStore.MAX_UNRESOLVED || total >= FormalRelationshipStore.MAX_RECORDS)
                throw new IllegalStateException("Formal journal capacity reserved");
            now.requireValid();
            long sequence = Math.addExact(FormalRelationshipStore.number(db, "sequence"), 1);
            String id = "fo:" + FormalRelationshipStore.incarnation(db) + ":" + sequence;
            long deadline = Math.addExact(now.wall, FormalRelationshipClock.REVIEW_MILLIS);
            long elapsedDeadline = Math.addExact(now.elapsed, FormalRelationshipClock.REVIEW_MILLIS);
            String scope = authority.observedScope(auth);
            String digest = LocalRelationshipAuthority.digest(new JSONArray().put(1).put("LOCAL_LINK").put(key)
                    .put(originalRevision).put(dom).put(sub).put(scope).put(now.wall).put(now.elapsed).put(now.boot).put(deadline).toString());
            String relationship = "local:" + id;
            FormalRelationshipStore.claim(db, sub, dom, "op:" + id, null, null);
            db.execSQL("INSERT INTO formal_operations(id,operation_key,original_revision,request_digest,scope_digest,dom_ref,sub_ref,state,"
                    + "created_wall,created_elapsed,created_boot,deadline_wall,deadline_elapsed) VALUES(?,?,?,?,?,?,?,'PREPARED',?,?,?,?,?)",
                    new Object[]{id,key,originalRevision,digest,scope,dom,sub,now.wall,now.elapsed,now.boot,deadline,elapsedDeadline});
            db.execSQL("INSERT INTO formal_relationships(id,generation,dom_ref,sub_ref,delivery,state,addition_order,operation_id,scope_digest) "
                    + "VALUES(?,?,?,?,'LOCAL','PREPARED',?,?,?)", new Object[]{relationship,id,dom,sub,FormalRelationshipStore.allocateOrder(db),id,scope});
            db.execSQL("UPDATE formal_authority SET sequence=? WHERE id=1", new Object[]{sequence});
            FormalRelationshipStore.changed(db);
            return mint(db, by(db,"id",id), auth, now);
        });
    }
    private RelationshipUi.LocalReview mint(SQLiteDatabase db, Row row, LocalRelationshipAuthority.Observation auth, FormalRelationshipClock.Sample now) {
        if (!"PREPARED".equals(row.state) || !FormalRelationshipClock.live(row.began, now)
                || !row.scope.equals(authority.observedScope(auth))) throw new SecurityException("Original review unavailable; observe status");
        java.util.Iterator<String> retained = reviews.keySet().iterator();
        while (retained.hasNext()) {
            Row previous = by(db, "id", retained.next());
            if (previous == null || !"PREPARED".equals(previous.state)) retained.remove();
        }
        if (reviews.size() >= 32 && !reviews.containsKey(row.id)) throw new IllegalStateException("Review registry full");
        reviews.put(row.id, auth);
        return new RelationshipUi.LocalReview(row.id,row.digest,row.dom,row.sub,row.scope,row.deadline,"Local Dom and Sub; existing feature consent remains unchanged");
    }
    RelationshipRecoveryUi.ReviewResult reviewLocal(String id, String digest) {
        return authorized((db, auth, now) -> {
            Row row = by(db,"id",id);
            if (row == null) return new RelationshipRecoveryUi.ReviewResult(RelationshipRecoveryUi.ReviewResult.Kind.UNKNOWN,null,null);
            requireDigest(row,digest);
            RelationshipRecoveryUi.OperationSummary summary = summary(row);
            if (!"PREPARED".equals(row.state) || !FormalRelationshipClock.live(row.began,now)
                    || !row.scope.equals(authority.observedScope(auth)))
                return new RelationshipRecoveryUi.ReviewResult(RelationshipRecoveryUi.ReviewResult.Kind.STATUS,summary,null);
            return new RelationshipRecoveryUi.ReviewResult(RelationshipRecoveryUi.ReviewResult.Kind.REVIEW,summary,mint(db,row,auth,now));
        });
    }
    RelationshipUi.Operation confirmLocal(String id, String digest) {
        return authorized((db, auth, now) -> {
            Row row = required(db,id); requireDigest(row,digest);
            if (!"PREPARED".equals(row.state)) return row.operation();
            if (!authority.sameSource(reviews.get(id),auth) || !row.scope.equals(authority.observedScope(auth)))
                throw new SecurityException("Fresh original review required");
            if (FormalRelationshipClock.expired(row.began,now)) { terminal(db,row,"EXPIRED",now); return required(db,id).operation(); }
            if (!FormalRelationshipClock.live(row.began,now)) throw new SecurityException("Review clock uncertain");
            String relationship = "local:" + id;
            try (Cursor slot = db.rawQuery("SELECT owner FROM formal_governing_slots WHERE sub_ref=?",new String[]{row.sub})) {
                if (!slot.moveToFirst() || !("op:"+id).equals(slot.getString(0))) throw new SecurityException("Reservation changed");
            }
            db.execSQL("UPDATE formal_operations SET state='ACTIVE',relationship_id=?,generation=?,approval_digest=?,"
                    + "terminal_wall=?,terminal_elapsed=?,terminal_boot=? WHERE id=? AND state='PREPARED'",
                    new Object[]{relationship,id,row.digest,now.wall,now.elapsed,now.boot,id});
            db.execSQL("UPDATE formal_relationships SET state='ACTIVE' WHERE operation_id=? AND state='PREPARED'",new Object[]{id});
            db.execSQL("UPDATE formal_governing_slots SET owner=?,relationship_id=?,generation=? WHERE sub_ref=? AND owner=?",
                    new Object[]{"edge:"+relationship+":"+id,relationship,id,row.sub,"op:"+id});
            FormalRelationshipStore.changed(db); return required(db,id).operation();
        });
    }
    RelationshipUi.Operation cancel(String id) {
        return authorized((db,auth,now) -> {
            Row row = required(db,id);
            if ("PREPARED".equals(row.state)) terminal(db,row,"CANCELLED",now);
            return required(db,id).operation(); // Cancelling an operation is never an implicit ACTIVE unlink.
        });
    }
    RelationshipUi.Operation resume(String id) { return authorized((db,auth,now) -> required(db,id).operation()); }
    RelationshipRecoveryUi.PreparationLookup lookupPreparation(String key,String dom,String sub,String revision) {
        return authorized((db,auth,now) -> {
            Row row = by(db,"operation_key",key);
            if (row == null) return new RelationshipRecoveryUi.PreparationLookup(RelationshipRecoveryUi.PreparationLookup.Kind.UNKNOWN,null);
            if (!row.dom.equals(dom) || !row.sub.equals(sub) || !row.revision.equals(revision))
                return new RelationshipRecoveryUi.PreparationLookup(RelationshipRecoveryUi.PreparationLookup.Kind.REFUSED,null);
            return new RelationshipRecoveryUi.PreparationLookup(RelationshipRecoveryUi.PreparationLookup.Kind.FOUND,summary(row));
        });
    }
    private static void terminal(SQLiteDatabase db, Row row, String state, FormalRelationshipClock.Sample now) {
        now.requireValid();
        db.execSQL("UPDATE formal_operations SET state=?,terminal_wall=?,terminal_elapsed=?,terminal_boot=? WHERE id=? AND state='PREPARED'",
                new Object[]{state,now.wall,now.elapsed,now.boot,row.id});
        db.execSQL("UPDATE formal_relationships SET state='REVOKED' WHERE operation_id=? AND state='PREPARED'",new Object[]{row.id});
        db.delete("formal_governing_slots","owner=?",new String[]{"op:"+row.id});
        FormalRelationshipStore.changed(db);
    }
    static void prune(SQLiteDatabase db, FormalRelationshipClock.Sample now) {
        FormalRelationshipStore.transaction(db);
        List<String> retired = new ArrayList<>();
        try (Cursor rows = db.rawQuery("SELECT id,terminal_wall,terminal_elapsed,terminal_boot FROM formal_operations WHERE state!='PREPARED'",null)) {
            while (rows.moveToNext()) {
                if (rows.isNull(1)||rows.isNull(2)||rows.isNull(3)) continue;
                if (FormalRelationshipClock.oldTerminal(new FormalRelationshipClock.Sample(rows.getLong(1),rows.getLong(2),rows.getInt(3)),now)) retired.add(rows.getString(0));
            }
        }
        for(String id:retired) db.delete("formal_operations","id=?",new String[]{id});
        if (!retired.isEmpty()) FormalRelationshipStore.changed(db);
    }
    private static void requireText(String value) {
        if (value==null||value.isEmpty()||value.length()>256) throw new IllegalArgumentException("Bounded original operand required");
    }
    private static void requireLocalRef(SQLiteDatabase db,String ref,String role) {
        try(Cursor row=db.rawQuery("SELECT 1 FROM formal_participants WHERE ref=? AND local=1 AND role=?",new String[]{ref,role})) {
            if(!row.moveToFirst()) throw new SecurityException("Local participant unavailable");
        }
    }
    private static void requireDigest(Row row,String digest) { if(!row.digest.equals(digest)) throw new SecurityException("Operation digest changed"); }
    private static Row required(SQLiteDatabase db,String id) {
        Row row=by(db,"id",id); if(row==null) throw new IllegalStateException("Unknown retained operation"); return row;
    }
    private static Row by(SQLiteDatabase db,String column,String value) {
        try(Cursor row=db.rawQuery("SELECT id,operation_key,original_revision,request_digest,scope_digest,dom_ref,sub_ref,state,"
                + "created_wall,created_elapsed,created_boot,deadline_wall,relationship_id,generation FROM formal_operations WHERE "+column+"=?",new String[]{value})) {
            return row.moveToFirst()?new Row(row):null;
        }
    }
    private static final class Row {
        final String id,key,revision,digest,scope,dom,sub,state,relationship,generation;
        final FormalRelationshipClock.Sample began; final long deadline;
        Row(Cursor r) {
            id=r.getString(0);key=r.getString(1);revision=r.getString(2);digest=r.getString(3);scope=r.getString(4);
            dom=r.getString(5);sub=r.getString(6);state=r.getString(7);
            began=new FormalRelationshipClock.Sample(r.getLong(8),r.getLong(9),r.getInt(10)); deadline=r.getLong(11);
            relationship=r.getString(12);generation=r.getString(13);
        }
        RelationshipUi.Operation operation() {
            RelationshipUi.OperationState typed=RelationshipUi.OperationState.valueOf(state);
            RelationshipUi.Reason reason="PREPARED".equals(state)||"ACTIVE".equals(state)?null:RelationshipUi.Reason.valueOf(state);
            return new RelationshipUi.Operation(id,digest,typed,deadline,relationship,generation,reason);
        }
    }
    private static RelationshipRecoveryUi.OperationSummary summary(Row row) {
        return new RelationshipRecoveryUi.OperationSummary(row.operation(),RelationshipRecoveryUi.OperationKind.LOCAL_LINK,
                RelationshipUi.Delivery.LOCAL,row.dom,row.sub,null,null);
    }
}
