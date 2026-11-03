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

/** Serializes original local-link intent and projects only current authorized relationship facts. */
final class FormalRelationshipCoordinator {
    private final Context context;
    private final RemoteStore store;
    private final LocalRelationshipAuthority authority;
    private final FormalRelationshipClock.Source clock;
    private final LinkingWitness applicationWitness;
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
        this(context,store,clock,null);
    }
    private FormalRelationshipCoordinator(Context context,RemoteStore store,FormalRelationshipClock.Source clock,LinkingWitness witness){
        if(witness==null)requireOwnedFixture(store);else LinkingWitness.outside(store);
        this.context = context.getApplicationContext(); this.store = store;
        this.clock = java.util.Objects.requireNonNull(clock);
        this.applicationWitness=witness;
        authority = new LocalRelationshipAuthority(context, store, clock);
    }
    static FormalRelationshipCoordinator application(Context context,RemoteStore store,LinkingWitness witness){
        return new FormalRelationshipCoordinator(context,store,FormalRelationshipClock.source(context),java.util.Objects.requireNonNull(witness));
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
    static void requireOwnedFixture(RemoteStore store) {
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
        if(applicationWitness!=null)return applicationWitness.readRetained(store,()->authorizedTransaction(work));
        requireOwnedFixture(store);return authorizedTransaction(work);
    }
    private <T>T authorizedTransaction(Work<T> work){
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
    private <T>T mutating(String kind,String original,Work<T> work){
        if(applicationWitness==null)return authorized(work);
        return applicationWitness.mutateObserved(store,kind,original,()->{
            LocalRelationshipAuthority.Observation auth=authority.observe();
            return db->{
                FormalRelationshipClock.Sample now=clock.transition(authority.anchor(auth));
                authority.require(auth,now);return work.run(db,auth,now);
            };
        });
    }
    InternalSnapshot internalSnapshot() {
        return mutating("participants","local",(db, auth, now) -> {
            long count;
            try (Cursor row = db.rawQuery("SELECT COUNT(*) FROM formal_participants WHERE local=1", null)) { row.moveToFirst(); count = row.getLong(0); }
            String dom = FormalRelationshipStore.local(db, "DOM"), sub = FormalRelationshipStore.local(db, "SUB");
            if (count != 2) FormalRelationshipStore.changed(db);
            return new InternalSnapshot(dom, sub, FormalRelationshipStore.internalRevision(db));
        });
    }
    RelationshipUi.LocalReview prepareLocal(String key, String dom, String sub, String originalRevision) {
        if(applicationWitness!=null)return applicationWitness.exclusive(store,()->prepareLocalLocked(key,dom,sub,originalRevision));
        return prepareLocalLocked(key,dom,sub,originalRevision);
    }
    private RelationshipUi.LocalReview prepareLocalLocked(String key,String dom,String sub,String originalRevision){
        requireText(key); requireText(dom); requireText(sub); requireText(originalRevision);
        boolean permitted=applicationWitness==null||applicationWitness.consistent(store);
        RelationshipUi.LocalReview replay=authorized((db,auth,now)->{
            if(!permitted)throw new SecurityException("Original review unavailable; observe retained status");
            Row existing=by(db,"operation_key",key);if(existing==null){
                if(applicationWitness!=null&&(!originalRevision.equals(LinkingWitness.transactionRevision(db))
                        ||!authority.snapshotScope(auth).equals(LinkingWitness.snapshotScope(db))))throw new SecurityException("Snapshot scope changed");
                requireLocalRef(db,dom,"DOM");requireLocalRef(db,sub,"SUB");
                try(Cursor slot=db.rawQuery("SELECT 1 FROM formal_governing_slots WHERE sub_ref=?",new String[]{sub})){
                    if(slot.moveToFirst())throw new SecurityException("A Dom is already assigned or pending");
                }
                return null;
            }
            if(!existing.dom.equals(dom)||!existing.sub.equals(sub)||!existing.revision.equals(originalRevision))throw new SecurityException("Preparation operands changed");
            return mint(db,existing,auth,now);
        });
        if(replay!=null)return replay;
        if(applicationWitness!=null&&!com.subhub.app.relationships.RelationshipPreparationKey.matches(key,originalRevision))throw new SecurityException("Original preparation key unavailable");
        return mutating("local-prepare",key+":"+dom+":"+sub+":"+originalRevision,(db, auth, now) -> {
            Row retained = by(db, "operation_key", key);
            if (retained != null) {
                if (!retained.dom.equals(dom) || !retained.sub.equals(sub) || !retained.revision.equals(originalRevision))
                    throw new SecurityException("Preparation operands changed");
                return mint(db, retained, auth, now);
            }
            if (!originalRevision.equals(applicationWitness==null?FormalRelationshipStore.internalRevision(db):LinkingWitness.transactionRevision(db)))
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
        boolean permitted=applicationWitness==null||applicationWitness.consistent(store);
        return authorized((db, auth, now) -> {
            Row row = by(db,"id",id);
            if (row == null) return new RelationshipRecoveryUi.ReviewResult(RelationshipRecoveryUi.ReviewResult.Kind.UNKNOWN,null,null);
            requireDigest(row,digest);
            RelationshipRecoveryUi.OperationSummary summary = summary(row);
            if (!permitted || !"PREPARED".equals(row.state) || !FormalRelationshipClock.live(row.began,now)
                    || !row.scope.equals(authority.observedScope(auth)))
                return new RelationshipRecoveryUi.ReviewResult(RelationshipRecoveryUi.ReviewResult.Kind.STATUS,summary,null);
            return new RelationshipRecoveryUi.ReviewResult(RelationshipRecoveryUi.ReviewResult.Kind.REVIEW,summary,mint(db,row,auth,now));
        });
    }
    RelationshipUi.Operation confirmLocal(String id, String digest) {
        if(applicationWitness!=null)return applicationWitness.exclusive(store,()->confirmLocalLocked(id,digest));
        return confirmLocalLocked(id,digest);
    }
    private RelationshipUi.Operation confirmLocalLocked(String id,String digest){
        RelationshipUi.Operation known=authorized((db,auth,now)->{
            Row row=required(db,id);requireDigest(row,digest);
            if(!"PREPARED".equals(row.state))return row.operation();
            if(!authority.sameSource(reviews.get(id),auth)||!row.scope.equals(authority.observedScope(auth)))throw new SecurityException("Fresh original review required");
            return null;
        });
        if(known!=null)return known;
        return mutating("local-confirm",id+":"+digest,(db, auth, now) -> {
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
        if(applicationWitness!=null)return applicationWitness.exclusive(store,()->cancelLocked(id));
        return cancelLocked(id);
    }
    private RelationshipUi.Operation cancelLocked(String id){
        RelationshipUi.Operation known=authorized((db,auth,now)->{Row row=required(db,id);return "PREPARED".equals(row.state)?null:row.operation();});
        if(known!=null)return known;
        return mutating("local-cancel",id,(db,auth,now) -> {
            Row row = required(db,id);
            if ("PREPARED".equals(row.state)) terminal(db,row,"CANCELLED",now);
            return required(db,id).operation(); // Cancelling an operation is never an implicit ACTIVE unlink.
        });
    }
    RelationshipUi.Operation resume(String id) { return authorized((db,auth,now) -> required(db,id).operation()); }
    RelationshipRecoveryUi.PreparationLookup lookupPreparation(String key,String dom,String sub,String revision) {
        boolean permitted=applicationWitness!=null&&applicationWitness.consistent(store);
        return authorized((db,auth,now) -> {
            Row row = by(db,"operation_key",key);
            if (row == null) {
                if(permitted&&com.subhub.app.relationships.RelationshipPreparationKey.matches(key,revision)
                        &&revision.equals(LinkingWitness.transactionRevision(db))&&authority.snapshotScope(auth).equals(LinkingWitness.snapshotScope(db))){
                    requireLocalRef(db,dom,"DOM");requireLocalRef(db,sub,"SUB");
                    return new RelationshipRecoveryUi.PreparationLookup(RelationshipRecoveryUi.PreparationLookup.Kind.ABSENT_NEVER_CREATED,null);
                }
                return new RelationshipRecoveryUi.PreparationLookup(RelationshipRecoveryUi.PreparationLookup.Kind.UNKNOWN,null);
            }
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

    RelationshipRecoveryUi.RecoverySnapshot recoverySnapshot(){
        if(applicationWitness==null)throw new IllegalStateException("Application persistence required");
        return applicationWitness.readCurrent(store,()->{
            LocalRelationshipAuthority.Observation observed=authority.observeRead();String scope=authority.snapshotScope(observed);
            boolean changed;synchronized(store){changed=!scope.equals(LinkingWitness.snapshotScope(store.getWritableDatabase()));}
            if(changed)applicationWitness.mutateObserved(store,"snapshot-scope",scope,()->{
                LocalRelationshipAuthority.Observation fresh=authority.observeRead();
                return db->{authority.requireRead(fresh,clock.transition(authority.anchor(fresh)));
                    if(!scope.equals(authority.snapshotScope(fresh)))throw new SecurityException("Snapshot context changed");
                    // A role change may expose a previously absent local participant; retain existing identities.
                    if(authority.supportsDom(fresh))FormalRelationshipStore.local(db,"DOM");
                    if(authority.supportsSub(fresh))FormalRelationshipStore.local(db,"SUB");
                    db.execSQL("UPDATE linking_state SET snapshot_scope=? WHERE id=1",new Object[]{scope});return null;};
            });
            LocalRelationshipAuthority.Observation fresh=authority.observeRead();
            boolean domView=authority.domView(fresh);
            com.subhub.app.people.ProfileStore.PreparedRead domRead=domView&&authority.supportsDom(fresh)?com.subhub.app.people.ProfileStore.prepareRead(context,true):null;
            com.subhub.app.people.ProfileStore.PreparedRead subRead=authority.supportsSub(fresh)?com.subhub.app.people.ProfileStore.prepareRead(context,false):null;
            LocalRelationshipAuthority.ProfileReadFence profileFence=authority.prepareProfileFence(fresh);
            com.subhub.app.people.ProfileData dom,sub;
            // Session transitions and supported role changes cooperate on this monitor. No Store, policy,
            // provider, Context, Keystore, filesystem or SQL acquisition occurs in the profile-read boundary.
            synchronized(RuntimeSessionIdentity.LOCK){
                profileFence.requireCurrent();dom=domRead==null?null:domRead.load();sub=subRead==null?null:subRead.load();
            }
            synchronized(SubFeaturePolicy.LOCK){synchronized(store){synchronized(RuntimeSessionIdentity.LOCK){
                SQLiteDatabase db=store.getReadableDatabase();db.beginTransaction();
                try{
                    authority.requireRead(fresh,clock.transition(authority.anchor(fresh)));
                    if(!authority.snapshotScope(fresh).equals(LinkingWitness.snapshotScope(db)))throw new SecurityException("Snapshot scope changed");
                    List<RelationshipUi.Participant> participants=new ArrayList<>();String domRef=null,subRef=null;
                    try(Cursor rows=db.rawQuery("SELECT ref,role FROM formal_participants WHERE local=1",null)){
                        while(rows.moveToNext()){
                            if("DOM".equals(rows.getString(1))){domRef=rows.getString(0);if(dom!=null)participants.add(new RelationshipUi.Participant(domRef,RelationshipUi.Role.DOM,dom));}
                            else{subRef=rows.getString(0);if(sub!=null)participants.add(new RelationshipUi.Participant(subRef,RelationshipUi.Role.SUB,sub));}
                        }
                    }
                    List<RelationshipUi.Edge> eligible=new ArrayList<>();RelationshipUi.Edge governing=null;
                    try(Cursor rows=db.rawQuery("SELECT id,generation,dom_ref,sub_ref,delivery,addition_order,remote_peer,remote_generation FROM formal_relationships WHERE state='ACTIVE' ORDER BY addition_order",null)){
                        while(rows.moveToNext()){
                            boolean local="LOCAL".equals(rows.getString(4));
                            com.subhub.app.people.PersonId person=local?com.subhub.app.people.PersonId.LOCAL:new com.subhub.app.people.PersonId("remote_"+rows.getString(6)+"_"+rows.getString(7));
                            RelationshipUi.Edge edge=new RelationshipUi.Edge(rows.getString(0),rows.getString(1),rows.getString(2),rows.getString(3),local?RelationshipUi.Delivery.LOCAL:RelationshipUi.Delivery.PEER,"ACTIVE",rows.getLong(5),person,
                                    local&&sub!=null?RelationshipUi.Availability.AVAILABLE:RelationshipUi.Availability.UNKNOWN,local&&sub!=null?com.subhub.app.people.PersonSnapshot.Freshness.CURRENT:com.subhub.app.people.PersonSnapshot.Freshness.UNKNOWN);
                            if(domView&&domRef!=null&&domRef.equals(edge.domParticipantRef)&&(!local||sub!=null))eligible.add(edge);
                            if(sub!=null&&subRef!=null&&subRef.equals(edge.subParticipantRef))governing=edge;
                        }
                    }
                    List<RelationshipUi.Operation> operations=new ArrayList<>();List<RelationshipRecoveryUi.OperationSummary> summaries=new ArrayList<>();
                    if(domView){
                        try(Cursor rows=db.rawQuery("SELECT id FROM formal_operations ORDER BY rowid",null)){
                            while(rows.moveToNext()){Row row=required(db,rows.getString(0));operations.add(row.operation());
                                summaries.add(new RelationshipRecoveryUi.OperationSummary(row.operation(),RelationshipRecoveryUi.OperationKind.LOCAL_LINK,RelationshipUi.Delivery.LOCAL,row.dom,row.sub,dom==null?null:dom.name,sub==null?null:sub.name));}
                        }
                    }
                    RelationshipUi.Snapshot snapshot=new RelationshipUi.Snapshot(true,LinkingWitness.transactionRevision(db),participants,eligible,governing,operations);
                    db.setTransactionSuccessful();return new RelationshipRecoveryUi.RecoverySnapshot(snapshot,summaries);
                }finally{db.endTransaction();}
            }}}
        });
    }

    void unlink(String relationship,String generation){
        requireText(relationship);requireText(generation);
        LinkingWitness.CheckedWork<Void> work=()->{
            boolean active=authorized((db,auth,now)->{
                try(Cursor row=db.rawQuery("SELECT delivery,state,dom_ref FROM formal_relationships WHERE id=? AND generation=?",new String[]{relationship,generation})){
                    if(!row.moveToFirst()||!"LOCAL".equals(row.getString(0)))throw new IllegalStateException("Unknown local relationship");
                    requireLocalRef(db,row.getString(2),"DOM");
                    if("ACTIVE".equals(row.getString(1)))return true;
                    if(!"REVOKED".equals(row.getString(1)))throw new SecurityException("Active local relationship required");
                    try(Cursor outcome=db.rawQuery("SELECT request_digest,outcome FROM linking_unlinks WHERE relationship_id=? AND generation=?",new String[]{relationship,generation})){
                        if(!outcome.moveToFirst()||!LocalRelationshipAuthority.digest("local-unlink:"+relationship+":"+generation).equals(outcome.getString(0))
                                ||!"UNLINKED".equals(outcome.getString(1)))throw new IllegalStateException("Unlink outcome unavailable");
                    }
                    return false;
                }
            });
            if(!active)return null;
            mutating("local-unlink",relationship+":"+generation,(db,auth,now)->{
                String sub;
                try(Cursor row=db.rawQuery("SELECT sub_ref,dom_ref,delivery,state FROM formal_relationships WHERE id=? AND generation=?",new String[]{relationship,generation})){
                    if(!row.moveToFirst()||!"LOCAL".equals(row.getString(2))||!"ACTIVE".equals(row.getString(3)))throw new SecurityException("Relationship changed");
                    requireLocalRef(db,row.getString(1),"DOM");sub=row.getString(0);
                }
                String owner="edge:"+relationship+":"+generation;
                try(Cursor slot=db.rawQuery("SELECT owner FROM formal_governing_slots WHERE sub_ref=?",new String[]{sub})){
                    if(!slot.moveToFirst()||!owner.equals(slot.getString(0)))throw new SecurityException("Relationship ownership changed");
                }
                db.execSQL("INSERT INTO linking_unlinks(relationship_id,generation,epoch,revision,request_digest,outcome) "
                        +"SELECT ?,?,epoch,revision+1,?,'UNLINKED' FROM linking_state WHERE id=1",
                        new Object[]{relationship,generation,LocalRelationshipAuthority.digest("local-unlink:"+relationship+":"+generation)});
                db.execSQL("UPDATE formal_relationships SET state='REVOKED' WHERE id=? AND generation=? AND state='ACTIVE'",new Object[]{relationship,generation});
                db.delete("formal_governing_slots","sub_ref=? AND owner=?",new String[]{sub,owner});FormalRelationshipStore.changed(db);return null;
            });return null;
        };
        if(applicationWitness!=null)applicationWitness.exclusive(store,work);else try{work.run();}catch(RuntimeException e){throw e;}catch(Exception e){throw new IllegalStateException(e);}
    }
}
