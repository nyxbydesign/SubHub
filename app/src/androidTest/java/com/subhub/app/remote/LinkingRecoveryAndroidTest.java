package com.subhub.app.remote;

import static org.junit.Assert.*;
import com.subhub.app.relationships.RelationshipUi;
import com.subhub.app.relationships.RelationshipRecoveryUi;
import com.subhub.app.relationships.RelationshipPreparationKey;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.ExecutionException;
import org.json.JSONObject;
import org.junit.Test;

public final class LinkingRecoveryAndroidTest extends LinkingFixture {
    @Test public void committedReservedMarkerResealsExactRecordWithoutRenewingIntent()throws Exception{
        FormalRelationshipProvider first=provider();RelationshipUi.Snapshot snapshot=await(first.snapshot());
        String dom=ref(snapshot,RelationshipUi.Role.DOM),sub=ref(snapshot,RelationshipUi.Role.SUB),key=RelationshipPreparationKey.create(snapshot.revision);
        RelationshipUi.LocalReview original=await(first.prepareLocal(key,dom,sub,snapshot.revision));
        first.close();JSONObject sealed=new JSONObject(new String(Files.readAllBytes(marker().toPath()),StandardCharsets.UTF_8));
        long revision=sealed.getLong("revision");sealed.put("phase","RESERVED");Files.write(marker().toPath(),sealed.toString().getBytes(StandardCharsets.UTF_8));
        FormalRelationshipProvider recreated=provider();
        RelationshipRecoveryUi.ReviewResult reviewed=await(recreated.reviewLocal(original.operationId,original.requestDigest));
        assertEquals(RelationshipRecoveryUi.ReviewResult.Kind.REVIEW,reviewed.kind);assertEquals(original.originalDeadline,reviewed.review.originalDeadline);
        JSONObject reconciled=new JSONObject(new String(Files.readAllBytes(marker().toPath()),StandardCharsets.UTF_8));
        assertEquals("SEALED",reconciled.getString("phase"));assertEquals(revision,reconciled.getLong("revision"));assertEquals(1,count("formal_operations"));
    }
    @Test public void missingEstablishedMarkerPreservesRetainedStatusButRefusesReviewAndNewAuthority()throws Exception{
        FormalRelationshipProvider real=provider();RelationshipUi.Snapshot snapshot=await(real.snapshot());
        String dom=ref(snapshot,RelationshipUi.Role.DOM),sub=ref(snapshot,RelationshipUi.Role.SUB),key=RelationshipPreparationKey.create(snapshot.revision);
        RelationshipUi.LocalReview original=await(real.prepareLocal(key,dom,sub,snapshot.revision));
        assertTrue(marker().delete());
        assertEquals(RelationshipUi.OperationState.PREPARED,await(real.resume(original.operationId)).state);
        assertEquals(RelationshipRecoveryUi.ReviewResult.Kind.STATUS,await(real.reviewLocal(original.operationId,original.requestDigest)).kind);
        assertEquals(RelationshipRecoveryUi.PreparationLookup.Kind.FOUND,await(real.lookupPreparation(key,dom,sub,snapshot.revision)).kind);
        assertEquals(RelationshipRecoveryUi.PreparationLookup.Kind.UNKNOWN,await(real.lookupPreparation(RelationshipPreparationKey.create(snapshot.revision),dom,sub,snapshot.revision)).kind);
        assertThrows(ExecutionException.class,()->await(real.prepareLocal(key,dom,sub,snapshot.revision)));
        assertThrows(ExecutionException.class,()->await(real.confirmLocal(original.operationId,original.requestDigest)));
        assertThrows(ExecutionException.class,()->await(real.snapshot()));assertEquals(1,count("formal_governing_slots"));assertFalse(marker().exists());
    }
    @Test public void sqlRejectionSealsNoopAndCannotCreateAReplacementIntent()throws Exception{
        FormalRelationshipProvider real=provider();RelationshipUi.Snapshot snapshot=await(real.snapshot());
        String dom=ref(snapshot,RelationshipUi.Role.DOM),sub=ref(snapshot,RelationshipUi.Role.SUB),key=RelationshipPreparationKey.create(snapshot.revision);
        db().execSQL("CREATE TRIGGER fail_preparation BEFORE INSERT ON formal_operations BEGIN SELECT RAISE(ABORT,'owned failure'); END");
        assertThrows(ExecutionException.class,()->await(real.prepareLocal(key,dom,sub,snapshot.revision)));
        assertEquals(0,count("formal_operations"));assertEquals(0,count("formal_governing_slots"));
        db().execSQL("DROP TRIGGER fail_preparation");
        RelationshipUi.Snapshot current=await(real.snapshot());assertTrue(current.supported);assertNotEquals(snapshot.revision,current.revision);
        assertEquals(RelationshipRecoveryUi.PreparationLookup.Kind.UNKNOWN,await(real.lookupPreparation(key,dom,sub,snapshot.revision)).kind);
        assertThrows(ExecutionException.class,()->await(real.prepareLocal(key,dom,sub,snapshot.revision)));
        assertEquals(0,count("formal_operations"));
    }
    @Test public void uncertainTransactionEndCannotPromoteObservedBeforeImageToNoop()throws Exception{
        FormalRelationshipProvider real=provider();await(real.snapshot());
        long beforeRevision,authorityRevision;
        try(android.database.Cursor row=db().rawQuery("SELECT revision FROM linking_state",null)){assertTrue(row.moveToFirst());beforeRevision=row.getLong(0);}
        authorityRevision=FormalRelationshipStore.number(db(),"revision");
        LinkingWitness witness=LinkingWitness.registered(store);assertNotNull(witness);
        assertThrows(IllegalStateException.class,()->witness.mutate(store,"owned-end-fault","original",database->{
            database.execSQL("UPDATE formal_authority SET revision=revision+1 WHERE id=1");
            // Force the real outer endTransaction to fail after a real rollback. A before-image alone
            // must not let the witness classify that unacknowledged outer completion as NOOP.
            database.endTransaction();throw new IllegalStateException("owned uncertain outer end");
        }));
        assertFalse(db().inTransaction());assertEquals(authorityRevision,FormalRelationshipStore.number(db(),"revision"));
        try(android.database.Cursor row=db().rawQuery("SELECT revision FROM linking_state",null)){assertTrue(row.moveToFirst());assertEquals(beforeRevision,row.getLong(0));}
        JSONObject marker=new JSONObject(new String(Files.readAllBytes(marker().toPath()),StandardCharsets.UTF_8));
        assertEquals("RESERVED",marker.getString("phase"));assertEquals(beforeRevision+1,marker.getLong("revision"));
        assertThrows(ExecutionException.class,()->await(real.snapshot()));
    }
    @Test public void databaseAndMarkerRollbackCannotHideCurrentReceiptOrReleaseAuthority()throws Exception{
        FormalRelationshipProvider first=provider();RelationshipUi.Snapshot snapshot=await(first.snapshot());first.close();
        for(RemoteStore handle:handles)handle.close();
        byte[] beforeDatabase=Files.readAllBytes(store.resolvedDatabaseFile().toPath()),beforeMarker=Files.readAllBytes(marker().toPath());
        FormalRelationshipProvider second=provider();String dom=ref(snapshot,RelationshipUi.Role.DOM),sub=ref(snapshot,RelationshipUi.Role.SUB);
        RelationshipUi.LocalReview review=await(second.prepareLocal(RelationshipPreparationKey.create(snapshot.revision),dom,sub,snapshot.revision));
        RelationshipUi.Operation active=await(second.confirmLocal(review.operationId,review.requestDigest));assertEquals(1,count("formal_governing_slots"));
        second.close();for(RemoteStore handle:handles)handle.close();
        // Only these freshly allocated synthetic fixture bytes are restored; the feature keys stay current.
        try(java.io.FileOutputStream output=new java.io.FileOutputStream(store.resolvedDatabaseFile())){output.write(beforeDatabase);output.getFD().sync();}
        Files.write(marker().toPath(),beforeMarker);
        FormalRelationshipProvider restored=provider();assertThrows(ExecutionException.class,()->await(restored.snapshot()));
        assertEquals(RelationshipRecoveryUi.PreparationLookup.Kind.UNKNOWN,await(restored.lookupPreparation(RelationshipPreparationKey.create(snapshot.revision),dom,sub,snapshot.revision)).kind);
        assertThrows(ExecutionException.class,()->await(restored.unlink(active.relationshipId,active.generation)));
        assertEquals(0,count("formal_operations")); // Missing restored rows never become absence evidence.
    }
    @Test public void legacyRetainedKeyRemainsRecoverableBeforeStrictNewKeyValidation()throws Exception{
        FormalRelationshipCoordinator old=coordinator();FormalRelationshipCoordinator.InternalSnapshot snapshot=old.internalSnapshot();
        RelationshipUi.LocalReview original=old.prepareLocal("legacy-owned-key",snapshot.dom,snapshot.sub,snapshot.changeCounter);
        FormalRelationshipProvider real=provider();await(real.snapshot());
        RelationshipUi.LocalReview replay=await(real.prepareLocal("legacy-owned-key",snapshot.dom,snapshot.sub,snapshot.changeCounter));
        assertEquals(original.operationId,replay.operationId);assertEquals(original.requestDigest,replay.requestDigest);assertEquals(original.originalDeadline,replay.originalDeadline);
        assertEquals(RelationshipRecoveryUi.PreparationLookup.Kind.FOUND,await(real.lookupPreparation("legacy-owned-key",snapshot.dom,snapshot.sub,snapshot.changeCounter)).kind);
        assertEquals(RelationshipRecoveryUi.PreparationLookup.Kind.UNKNOWN,await(real.lookupPreparation("missing-old-key",snapshot.dom,snapshot.sub,snapshot.changeCounter)).kind);
    }
    @Test public void markerWriteFailureNeverAdmitsSqlAndLeavesRetainedReservationIntact()throws Exception{
        FormalRelationshipProvider real=provider();RelationshipUi.Snapshot snapshot=await(real.snapshot());
        String dom=ref(snapshot,RelationshipUi.Role.DOM),sub=ref(snapshot,RelationshipUi.Role.SUB);
        // Revoke only this owned fixture directory's write bit, forcing reservation-file allocation to fail.
        android.system.Os.chmod(marker().getParent(),0500);
        try{
            assertThrows(ExecutionException.class,()->await(real.prepareLocal(RelationshipPreparationKey.create(snapshot.revision),dom,sub,snapshot.revision)));
            assertEquals(0,count("formal_operations"));assertEquals(0,count("formal_governing_slots"));
        }finally{android.system.Os.chmod(marker().getParent(),0700);}
        // An uncertain write is not silently repaired in the same process.
        assertThrows(ExecutionException.class,()->await(real.snapshot()));
    }
    /** Host runs seed, observes stopped PID, then observe in a new target process on the same APK pair. */
    public static final class ProcessPhases {
        @Test public void persistAndInspect()throws Exception{
            android.os.Bundle args=androidx.test.platform.app.InstrumentationRegistry.getArguments();
            String id=args.getString("linking_fixture"),phase=args.getString("linking_phase");
            assertNotNull(id);assertTrue(id.matches("[0-9a-f]{32}"));assertTrue("seed".equals(phase)||"observe".equals(phase));
            android.content.Context target=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().getTargetContext();
            String prefix="linking-process-"+id+"-";
            android.content.Context owned=new android.content.ContextWrapper(target){
                @Override public android.content.Context getApplicationContext(){return this;}
                @Override public android.content.SharedPreferences getSharedPreferences(String name,int mode){return target.getSharedPreferences(prefix+name,mode);}
            };
            java.io.File evidence=new java.io.File(target.getCacheDir(),"linking-process-"+id+".json");
            com.subhub.app.security.KeyholderProvider original=com.subhub.app.people.People.keyholder();boolean originalMode=com.subhub.app.security.ControllerPinManager.isDomModeActive();
            java.lang.reflect.Field application=com.subhub.app.security.ControllerPinManager.class.getDeclaredField("application");application.setAccessible(true);Object originalApplication=application.get(null);
            RemoteStore store=null;FormalRelationshipProvider provider=null;JSONObject saved=null;boolean observed=false;
            try{
                if("seed".equals(phase)){
                    assertFalse(evidence.exists());owned.getSharedPreferences(com.subhub.app.settings.SettingsRepository.PREFERENCES_NAME,0).edit().putString(com.subhub.app.security.DeviceRolePreferences.KEY_MODE,"BOTH").commit();
                    store=FormalRelationshipCoordinator.createOwnedFixture(owned);
                }else{
                    saved=new JSONObject(new String(Files.readAllBytes(evidence.toPath()),StandardCharsets.UTF_8));assertEquals(id,saved.getString("fixture"));assertEquals("linking-process-v1",saved.getString("kind"));
                    String name=saved.getString("database");assertTrue(name.matches("formal-fixture-[a-zA-Z0-9._-]+\\.db"));
                    java.io.File file=target.getDatabasePath(name).getCanonicalFile();assertEquals(saved.getString("path"),file.getPath());
                    assertEquals(target.getDatabasePath("owned-location").getCanonicalFile().getParentFile(),file.getParentFile());
                    android.system.StructStat stat=android.system.Os.stat(file.getPath());assertEquals(saved.getLong("dev"),stat.st_dev);assertEquals(saved.getLong("inode"),stat.st_ino);
                    store=new RemoteStore(owned,name);assertNotEquals(saved.getInt("seedPid"),android.os.Process.myPid());
                }
                RemoteSecrets secrets=new RemoteSecrets(owned,"owned-secrets","subhub.test.linking-process."+id);
                com.subhub.app.people.People.installKeyholder(new RemoteKeyholder(owned,store,secrets));
                if("seed".equals(phase))assertTrue(com.subhub.app.security.ControllerPinManager.setPin(owned,"624871"));
                assertTrue(com.subhub.app.security.ControllerPinManager.verify(owned,"624871"));
                provider=new FormalRelationshipProvider(owned,store);
                java.io.File marker=new java.io.File(new java.io.File(owned.getNoBackupFilesDir(),"linking-"+LocalRelationshipAuthority.digest(store.resolvedDatabaseFile().getPath())),"current.json");
                if("seed".equals(phase)){
                    RelationshipUi.Snapshot snapshot=provider.snapshot().get(30,java.util.concurrent.TimeUnit.SECONDS);
                    String dom=LinkingFixture.ref(snapshot,RelationshipUi.Role.DOM),sub=LinkingFixture.ref(snapshot,RelationshipUi.Role.SUB),key=RelationshipPreparationKey.create(snapshot.revision);
                    RelationshipUi.LocalReview review=provider.prepareLocal(key,dom,sub,snapshot.revision).get(30,java.util.concurrent.TimeUnit.SECONDS);
                    RemoteStore exact=store;LinkingWitness witness=LinkingWitness.registered(store);
                    try{
                        assertThrows(IllegalStateException.class,()->witness.mutate(exact,"owned-seal-fault",id,database->{
                            database.execSQL("UPDATE formal_authority SET revision=revision+1 WHERE id=1");
                            try{android.system.Os.chmod(marker.getParent(),0500);}catch(android.system.ErrnoException failure){throw new IllegalStateException(failure);}
                            return null;
                        }));
                    }finally{android.system.Os.chmod(marker.getParent(),0700);}
                    JSONObject record=new JSONObject(new String(Files.readAllBytes(marker.toPath()),StandardCharsets.UTF_8));assertEquals("RESERVED",record.getString("phase"));
                    LinkingReceiptStore.Receipt current=new LinkingReceiptStore(store.resolvedDatabaseFile().getPath()).read(LinkingReceiptStore.CURRENT);assertEquals(record.getLong("revision"),current.revision);
                    android.system.StructStat stat=android.system.Os.stat(store.resolvedDatabaseFile().getPath());
                    saved=new JSONObject().put("kind","linking-process-v1").put("fixture",id).put("database",store.getDatabaseName()).put("path",store.resolvedDatabaseFile().getPath()).put("dev",stat.st_dev).put("inode",stat.st_ino)
                            .put("seedPid",android.os.Process.myPid()).put("key",key).put("dom",dom).put("sub",sub).put("originalRevision",snapshot.revision).put("operation",review.operationId).put("digest",review.requestDigest).put("deadline",review.originalDeadline)
                            .put("revision",record.getLong("revision")).put("epoch",record.getString("epoch")).put("authorityRevision",FormalRelationshipStore.number(store.getReadableDatabase(),"revision"))
                            .put("phase","COMMITTED_UNSEALED_SEED_ARRANGED");
                    assertEquals(saved.getLong("revision"),readRevision(store));writeEvidence(evidence,saved);
                    FormalRelationshipCoordinator.releaseOwnedFixture(store); // The new PID gets no factory/enrollment capability.
                    System.out.println("LINKING_PROCESS_SEED_ARRANGED pid="+android.os.Process.myPid()+" fixture="+id);
                }else{
                    RelationshipUi.Snapshot snapshot=provider.snapshot().get(30,java.util.concurrent.TimeUnit.SECONDS);
                    assertEquals("fr1:"+saved.getString("epoch")+":"+saved.getLong("revision"),snapshot.revision);
                    assertEquals(saved.getLong("authorityRevision"),FormalRelationshipStore.number(store.getReadableDatabase(),"revision"));
                    RelationshipRecoveryUi.ReviewResult review=provider.reviewLocal(saved.getString("operation"),saved.getString("digest")).get(30,java.util.concurrent.TimeUnit.SECONDS);
                    assertEquals(RelationshipRecoveryUi.ReviewResult.Kind.REVIEW,review.kind);assertEquals(saved.getLong("deadline"),review.review.originalDeadline);
                    assertEquals(saved.getString("operation"),review.review.operationId);assertEquals(saved.getString("digest"),review.review.requestDigest);
                    JSONObject record=new JSONObject(new String(Files.readAllBytes(marker.toPath()),StandardCharsets.UTF_8));assertEquals("SEALED",record.getString("phase"));assertEquals(saved.getLong("revision"),record.getLong("revision"));
                    saved.put("observePid",android.os.Process.myPid()).put("observedRevision",snapshot.revision).put("phase","EXACT_COMMITTED_RESULT_RESEALED_IN_NEW_PID");writeEvidence(evidence,saved);observed=true;
                    System.out.println("LINKING_PROCESS_OBSERVED pid="+android.os.Process.myPid()+" fixture="+id);
                }
            }finally{
                if(provider!=null)provider.close();if(store!=null)store.close();
                com.subhub.app.people.People.installKeyholder(original);if(originalMode)com.subhub.app.security.ControllerPinManager.enterDomMode();else com.subhub.app.security.ControllerPinManager.enterSubMode();application.set(null,originalApplication);
                if(observed&&saved!=null){
                    java.io.File file=target.getDatabasePath(saved.getString("database")).getCanonicalFile();android.system.StructStat stat=android.system.Os.stat(file.getPath());assertEquals(saved.getLong("dev"),stat.st_dev);assertEquals(saved.getLong("inode"),stat.st_ino);
                    // Cleanup authority is this host-owned synthetic fixture manifest, never application bootstrap provenance.
                    new LinkingReceiptStore(file.getPath()).deleteOwnedFixtureReceipts();
                    java.io.File directory=new java.io.File(target.getNoBackupFilesDir(),"linking-"+LocalRelationshipAuthority.digest(file.getPath())).getCanonicalFile();
                    assertEquals(target.getNoBackupFilesDir().getCanonicalFile(),directory.getParentFile());
                    java.io.File[] children=directory.listFiles();if(children!=null)for(java.io.File child:children){assertEquals(directory,child.getCanonicalFile().getParentFile());assertTrue(child.isFile());assertTrue(child.getName().equals("current.json")||child.getName().equals("operation.lock")||child.getName().startsWith("pending-"));assertTrue(child.delete());}
                    assertTrue(directory.delete());assertTrue(target.deleteDatabase(saved.getString("database")));
                    for(String name:new String[]{com.subhub.app.settings.SettingsRepository.PREFERENCES_NAME,"subhub_controller_auth","subhub_profile_names","subhub_local_feature_consent","owned-secrets"})target.getSharedPreferences(prefix+name,0).edit().clear().commit();
                }
            }
        }
        private static long readRevision(RemoteStore store){try(android.database.Cursor row=store.getReadableDatabase().rawQuery("SELECT revision FROM linking_state",null)){assertTrue(row.moveToFirst());return row.getLong(0);}}
        private static void writeEvidence(java.io.File file,JSONObject value)throws Exception{try(java.io.FileOutputStream output=new java.io.FileOutputStream(file)){output.write(value.toString().getBytes(StandardCharsets.UTF_8));output.getFD().sync();}}
    }
}
