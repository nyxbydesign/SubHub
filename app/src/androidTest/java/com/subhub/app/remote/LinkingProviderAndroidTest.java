package com.subhub.app.remote;

import static org.junit.Assert.*;
import android.database.Cursor;
import com.subhub.app.relationships.RelationshipUi;
import com.subhub.app.relationships.RelationshipRecoveryUi;
import com.subhub.app.relationships.RelationshipPreparationKey;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.security.DeviceRolePreferences;
import com.subhub.app.settings.SettingsRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Test;

public final class LinkingProviderAndroidTest extends LinkingFixture {
    @Test public void actualProviderRecreationRetainsIntentThenConfirmsAndExplicitlyUnlinks()throws Exception{
        FormalRelationshipProvider first=provider();RelationshipUi.Snapshot initial=await(first.snapshot());
        assertTrue(initial.supported);assertTrue(initial.revision.startsWith("fr1:"));assertEquals(2,initial.localParticipants.size());
        String dom=ref(initial,RelationshipUi.Role.DOM),sub=ref(initial,RelationshipUi.Role.SUB);
        String key=RelationshipPreparationKey.create(initial.revision);
        assertEquals(RelationshipRecoveryUi.PreparationLookup.Kind.ABSENT_NEVER_CREATED,await(first.lookupPreparation(key,dom,sub,initial.revision)).kind);
        assertEquals(RelationshipRecoveryUi.PreparationLookup.Kind.UNKNOWN,await(first.lookupPreparation("legacy-missing",dom,sub,initial.revision)).kind);
        RelationshipUi.LocalReview original=await(first.prepareLocal(key,dom,sub,initial.revision));
        first.close();FormalRelationshipProvider recreated=provider();
        RelationshipUi.LocalReview replay=await(recreated.prepareLocal(key,dom,sub,initial.revision));
        assertEquals(original.operationId,replay.operationId);assertEquals(original.requestDigest,replay.requestDigest);assertEquals(original.originalDeadline,replay.originalDeadline);
        assertEquals(RelationshipRecoveryUi.PreparationLookup.Kind.FOUND,await(recreated.lookupPreparation(key,dom,sub,initial.revision)).kind);
        assertEquals(RelationshipRecoveryUi.ReviewResult.Kind.REVIEW,await(recreated.reviewLocal(original.operationId,original.requestDigest)).kind);
        RelationshipUi.Operation active=await(recreated.confirmLocal(original.operationId,original.requestDigest));
        assertEquals(RelationshipUi.OperationState.ACTIVE,active.state);assertEquals(1,count("formal_governing_slots"));
        assertEquals(RelationshipUi.OperationState.ACTIVE,await(recreated.cancel(original.operationId)).state);
        RelationshipUi.Snapshot linked=await(recreated.snapshot());assertEquals(1,linked.eligibleSubs.size());assertNotNull(linked.governingDom);
        assertTrue(linked.eligibleSubs.get(0).personId.isLocal());
        await(recreated.unlink(active.relationshipId,active.generation));
        assertEquals(0,count("formal_governing_slots"));assertTrue(await(recreated.snapshot()).eligibleSubs.isEmpty());
        assertEquals(RelationshipUi.OperationState.ACTIVE,await(recreated.resume(original.operationId)).state);
        String unchanged=await(recreated.snapshot()).revision;await(recreated.unlink(active.relationshipId,active.generation));
        assertEquals(unchanged,await(recreated.snapshot()).revision);
    }
    @Test public void lockedSubViewDoesNotReadOrExposeDomProfileOrLocalJournal()throws Exception{
        FormalRelationshipProvider real=provider();RelationshipUi.Snapshot initial=await(real.snapshot());
        RelationshipUi.LocalReview prepared=await(real.prepareLocal(RelationshipPreparationKey.create(initial.revision),ref(initial,RelationshipUi.Role.DOM),ref(initial,RelationshipUi.Role.SUB),initial.revision));
        ControllerPinManager.enterSubMode();
        RelationshipRecoveryUi.RecoverySnapshot locked=await(real.recoverySnapshot());
        assertEquals(1,locked.snapshot.localParticipants.size());assertEquals(RelationshipUi.Role.SUB,locked.snapshot.localParticipants.get(0).role);
        assertTrue(locked.summaries.isEmpty());assertTrue(locked.snapshot.operations.isEmpty());assertTrue(locked.snapshot.eligibleSubs.isEmpty());
        assertThrows(ExecutionException.class,()->await(real.confirmLocal(prepared.operationId,prepared.requestDigest)));
        assertTrue(ControllerPinManager.verify(context,"624871"));
        assertEquals(RelationshipRecoveryUi.ReviewResult.Kind.REVIEW,await(real.reviewLocal(prepared.operationId,prepared.requestDigest)).kind);
    }
    @Test public void roleChangeAddsOnlyCurrentLocalRefsAndOldMissingKeyStaysUnknown()throws Exception{
        context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME,0).edit().putString(DeviceRolePreferences.KEY_MODE,"DOM_ONLY").commit();
        FormalRelationshipProvider real=provider();RelationshipUi.Snapshot domOnly=await(real.snapshot());assertEquals(1,domOnly.localParticipants.size());
        context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME,0).edit().putString(DeviceRolePreferences.KEY_MODE,"BOTH").commit();
        RelationshipUi.Snapshot both=await(real.snapshot());assertEquals(2,both.localParticipants.size());assertNotEquals(domOnly.revision,both.revision);
        String dom=ref(both,RelationshipUi.Role.DOM),sub=ref(both,RelationshipUi.Role.SUB),key=RelationshipPreparationKey.create(both.revision);
        context.getSharedPreferences(com.subhub.app.security.SubFeaturePolicy.STORE,0).edit().putBoolean("tasks",false).commit();
        assertEquals(RelationshipRecoveryUi.PreparationLookup.Kind.UNKNOWN,await(real.lookupPreparation(key,dom,sub,both.revision)).kind);
        assertThrows(ExecutionException.class,()->await(real.prepareLocal(key,dom,sub,both.revision)));
        assertEquals(0,count("formal_operations"));
    }
    @Test public void scopedProfileReadsNeverCopyDomDataAndRejectLockBeforeLoading()throws Exception{
        java.util.concurrent.atomic.AtomicBoolean lockAtCapture=new java.util.concurrent.atomic.AtomicBoolean(true);
        int[] all={0},dom={0},sub={0};
        android.content.Context observed=new android.content.ContextWrapper(context){
            @Override public android.content.Context getApplicationContext(){return this;}
            @Override public android.content.SharedPreferences getSharedPreferences(String name,int mode){
                android.content.SharedPreferences actual=super.getSharedPreferences(name,mode);
                if(!"subhub_profile_names".equals(name))return actual;
                return (android.content.SharedPreferences)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{android.content.SharedPreferences.class},(proxy,method,args)->{
                    if("getAll".equals(method.getName())){all[0]++;throw new AssertionError("Hidden profile map copied");}
                    if(args!=null&&args.length>0&&args[0] instanceof String){
                        String key=(String)args[0];
                        if(key.startsWith("dom")){dom[0]++;assertTrue(ControllerPinManager.isDomModeActive());}
                        if(key.startsWith("sub"))sub[0]++;
                        if("__subhub_profile_read_warmup__".equals(key)&&lockAtCapture.compareAndSet(true,false))ControllerPinManager.enterSubMode();
                    }
                    try{return method.invoke(actual,args);}catch(java.lang.reflect.InvocationTargetException failure){throw failure.getCause();}
                });
            }
        };
        FormalRelationshipProvider real=new FormalRelationshipProvider(observed,store);providers.add(real);
        assertThrows(ExecutionException.class,()->await(real.snapshot()));assertEquals(0,dom[0]);assertEquals(0,all[0]);
        RelationshipUi.Snapshot locked=await(real.snapshot());assertEquals(1,locked.localParticipants.size());
        assertEquals(RelationshipUi.Role.SUB,locked.localParticipants.get(0).role);assertTrue(sub[0]>=5);assertEquals(0,dom[0]);assertEquals(0,all[0]);
    }
    @Test public void freshLegacyAuthorizationRejectsLockAndRoleChangesAfterReceiptAcknowledgement()throws Exception{
        FormalRelationshipProvider real=provider();await(real.snapshot());
        com.subhub.app.security.KeyholderProvider actual=com.subhub.app.people.People.keyholder();
        java.util.concurrent.atomic.AtomicInteger change=new java.util.concurrent.atomic.AtomicInteger();int[] observations={0},forbidden={0};
        com.subhub.app.people.People.installKeyholder(new com.subhub.app.security.KeyholderProvider(){
            public State state(){
                int selected=change.getAndSet(0);
                if(selected!=0){
                    assertFalse(Thread.holdsLock(store));assertFalse(db().inTransaction());
                    try{
                        org.json.JSONObject record=new org.json.JSONObject(new String(java.nio.file.Files.readAllBytes(marker().toPath()),java.nio.charset.StandardCharsets.UTF_8));
                        assertEquals("RESERVED",record.getString("phase"));
                        LinkingReceiptStore.Receipt current=new LinkingReceiptStore(store.resolvedDatabaseFile().getPath()).read(LinkingReceiptStore.CURRENT);
                        assertEquals(record.getLong("revision"),current.revision);
                        try(Cursor row=db().rawQuery("SELECT revision FROM linking_state",null)){assertTrue(row.moveToFirst());assertEquals(row.getLong(0)+1,current.revision);}
                        assertThrows(IllegalStateException.class,()->store.authorityAccess(()->{forbidden[0]++;return true;}));
                    }catch(Exception failure){throw new AssertionError(failure);}
                    observations[0]++;
                    if(selected==1)ControllerPinManager.enterSubMode();
                    else context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME,0).edit().putString(DeviceRolePreferences.KEY_MODE,"SUB_ONLY").commit();
                }
                return actual.state();
            }
            public String assignedDomName(){return actual.assignedDomName();}
            public com.subhub.app.security.ControllerAuthenticator.Result verifyCode(String code){return actual.verifyCode(code);}
        });
        try{
            change.set(1);SecurityException lock=assertThrows(SecurityException.class,()->store.saveInvite(PairingInvite.create(DOM,"fixture",System.currentTimeMillis())));
            assertTrue(LinkingWitness.acknowledgedRejection(lock));assertEquals(0,count("invites"));
            assertTrue(ControllerPinManager.verify(context,"624871"));change.set(2);
            assertThrows(SecurityException.class,()->store.saveInvite(PairingInvite.create(DOM,"fixture",System.currentTimeMillis())));
            assertEquals(0,count("invites"));assertEquals(0,count("peers"));assertEquals(0,count("formal_governing_slots"));assertEquals(2,observations[0]);assertEquals(0,forbidden[0]);
        }finally{com.subhub.app.people.People.installKeyholder(actual);}
    }
    @Test public void staleSecretCleanupCannotDeleteNewPeerWinnerWithSameGeneration()throws Exception{
        FormalRelationshipProvider real=provider();await(real.snapshot());
        RemoteSecrets secrets=new RemoteSecrets(context,"owned-secrets","subhub.test.formal."+prefix);
        RemoteFeature feature=new RemoteFeature(context,store,secrets);
        java.util.concurrent.ExecutorService worker=java.util.concurrent.Executors.newSingleThreadExecutor();
        try{
            PairingInvite first=PairingInvite.create(DOM,"fixture",System.currentTimeMillis());
            store.authorityAccess(()->{secrets.savePending(first);store.acceptInvite(first,"First");return null;});
            java.util.concurrent.CountDownLatch captured=new java.util.concurrent.CountDownLatch(1),replacement=new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.Future<Boolean> delayed=worker.submit(()->{
                String expected=store.authorityAccess(()->secrets.pendingLink(first.id));captured.countDown();
                assertTrue(replacement.await(10,TimeUnit.SECONDS));return store.authorityAccess(()->secrets.removePendingIfMatches(first.id,expected));
            });
            assertTrue(captured.await(10,TimeUnit.SECONDS));assertTrue(store.cancelPendingPeer(DOM,first.id));assertNull(secrets.pendingLink(first.id));
            assertTrue(ControllerPinManager.verify(context,"624871"));
            PairingInvite second=PairingInvite.restore(first.link().replace("&peer="+DOM,"&peer="+SUB));
            store.authorityAccess(()->{secrets.savePending(second);store.acceptInvite(second,"Second");return null;});
            replacement.countDown();assertFalse(delayed.get(10,TimeUnit.SECONDS));assertEquals(second.link(),secrets.pendingLink(second.id));
            assertEquals(RemoteStore.State.PENDING,store.peer(SUB,second.id).state);
            PairingService pairing=new PairingService(store,secrets,endpoint(repeat('c')),feature.keyholder,()->ControllerPinManager.isDomModeActive()&&ControllerPinManager.isConfigured(context),"Owned local");
            pairing.revoke(DOM,first.id); // A delayed repeat must prove A owns the captured value, not capture B's token.
            assertEquals(second.link(),secrets.pendingLink(second.id));assertEquals(RemoteStore.State.PENDING,store.peer(SUB,second.id).state);assertEquals(1,count("formal_governing_slots"));
        }finally{worker.shutdownNow();feature.shutdownForTest();}
    }
    @Test public void defaultDatabaseAutoattachmentFencesActualWrapperAndProvisioningAfterRollback()throws Exception{
        java.io.File owned=store.resolvedDatabaseFile();
        android.content.Context mapped=new android.content.ContextWrapper(context){
            @Override public android.content.Context getApplicationContext(){return this;}
            @Override public java.io.File getDatabasePath(String name){return "remote_sync_v1.db".equals(name)?owned:super.getDatabasePath(name);}
            @Override public android.database.sqlite.SQLiteDatabase openOrCreateDatabase(String name,int mode,android.database.sqlite.SQLiteDatabase.CursorFactory factory){return android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name),factory);}
            @Override public android.database.sqlite.SQLiteDatabase openOrCreateDatabase(String name,int mode,android.database.sqlite.SQLiteDatabase.CursorFactory factory,android.database.DatabaseErrorHandler handler){return android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).getPath(),factory,handler);}
        };
        RemoteStore applicationStore=new RemoteStore(mapped);handles.add(applicationStore);assertTrue(applicationStore.isApplicationDatabase());
        // No relationship adapter was attached: the default-path writer itself must install continuity.
        PairingInvite invite=PairingInvite.create(DOM,"fixture",System.currentTimeMillis());applicationStore.acceptInvite(invite,"Owned Dom");
        assertNotNull(applicationStore.attachedWitness);assertEquals(1,count("formal_governing_slots"));
        FormalRelationshipProvider cleanup=new FormalRelationshipProvider(context,store);providers.add(cleanup);
        RemoteSecrets secrets=new RemoteSecrets(context,"owned-secrets","subhub.test.formal."+prefix);secrets.savePending(invite);
        assertTrue(com.subhub.app.configuration.LegacyPackMigration.recoverAndMigrate(mapped));
        RemoteFeature feature=new RemoteFeature(mapped,applicationStore,secrets);
        com.subhub.app.security.KeyholderProvider actual=com.subhub.app.people.People.keyholder();
        com.subhub.app.security.KeyholderProvider installed=RemoteUi.keyholderProvider(feature);com.subhub.app.people.People.installKeyholder(installed);
        try{
            assertTrue(feature.recoveryReady());assertEquals(com.subhub.app.security.KeyholderProvider.State.NONE,installed.state());
            for(RemoteStore handle:handles)handle.close();
            byte[] databaseImage=java.nio.file.Files.readAllBytes(owned.toPath()),markerImage=java.nio.file.Files.readAllBytes(marker().toPath());
            applicationStore.activate(DOM,invite.id);assertEquals(RemoteStore.State.ACTIVE,applicationStore.peer(DOM,invite.id).state);
            for(RemoteStore handle:handles)handle.close();
            try(java.io.FileOutputStream output=new java.io.FileOutputStream(owned)){output.write(databaseImage);output.getFD().sync();}
            java.nio.file.Files.write(marker().toPath(),markerImage);
            assertEquals(com.subhub.app.security.KeyholderProvider.State.NONE,feature.keyholder.state()); // Raw restored legacy state is insufficient.
            assertEquals(com.subhub.app.security.KeyholderProvider.State.UNAVAILABLE,installed.state());assertTrue(feature.controlsLocal());
            int[] granted={0};assertFalse(installed.withLocalAuthority(()->{granted[0]++;return true;}));assertEquals(0,granted[0]);
            String original=context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME,0).getString("controller_pin_hash","");
            assertFalse(ControllerPinManager.setPin(context,"735982"));assertEquals(original,context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME,0).getString("controller_pin_hash",""));
            PeerTransport endpoint=new PeerTransport(){public String localIdentity(){return SUB;}public String inviteRoute(){return "fixture";}public Connection connect(String identity,String route,long timeout){throw new AssertionError();}public Connection accept(long timeout){throw new AssertionError();}public void close(){}};
            PairingService pairing=new PairingService(applicationStore,secrets,endpoint,feature.keyholder,ControllerPinManager::isDomModeActive,"Owned Sub");
            java.io.ByteArrayOutputStream replies=new java.io.ByteArrayOutputStream();
            RemoteProtocol.write(replies,new org.json.JSONObject().put("version",1).put("authVersion",1).put("type","pair_approved").put("generation",invite.id).put("fingerprint",PairingInvite.fingerprint(DOM,SUB,invite.id)));
            PeerTransport.Connection connection=new PeerTransport.Connection(){
                final java.io.InputStream input=new java.io.ByteArrayInputStream(replies.toByteArray());
                public String authenticatedPeerIdentity(){return DOM;}public java.io.InputStream input(){return input;}public java.io.OutputStream output(){return new java.io.ByteArrayOutputStream();}public void setReadTimeoutMillis(long timeout){}public boolean isDirect(){return true;}public void close(){}
            };
            assertThrows(IllegalStateException.class,()->pairing.resume(connection,applicationStore.peer(DOM,invite.id)));
            assertNull(secrets.controllerSecret(RemoteProtocol.Role.SUB,DOM,invite.id));assertEquals("0",applicationStore.metadata(DOM,invite.id,"controller_auth_v1_provisioned","0"));
            assertEquals(invite.link(),secrets.pendingLink(invite.id));
        }finally{
            com.subhub.app.people.People.installKeyholder(actual);feature.shutdownForTest();
            assertTrue(com.subhub.app.configuration.LegacyPackMigration.recoverAndMigrate(target));
        }
    }
    @Test public void retainedApprovedBoundPairingCompletesWhileControllerIsLocked()throws Exception{
        FormalRelationshipProvider primary=provider();await(primary.snapshot());
        RemoteStore second=FormalRelationshipCoordinator.createOwnedFixture(context);FormalRelationshipProvider secondary=new FormalRelationshipProvider(context,second);
        RemoteSecrets domSecrets=new RemoteSecrets(context,"owned-secrets","subhub.test.formal."+prefix),subSecrets=new RemoteSecrets(context,"owned-secrets-second","subhub.test.formal."+prefix+"second");
        java.util.concurrent.ExecutorService server=java.util.concurrent.Executors.newSingleThreadExecutor();
        try{
            await(secondary.snapshot());byte[] domSeed=new byte[32],subSeed=new byte[32];new java.security.SecureRandom().nextBytes(domSeed);new java.security.SecureRandom().nextBytes(subSeed);
            try(PeerTransport domEndpoint=IrohPeerTransport.open(context,domSeed,new String[0],10_000);PeerTransport subEndpoint=IrohPeerTransport.open(context,subSeed,new String[0],10_000)){
                java.util.function.BooleanSupplier access=()->ControllerPinManager.isDomModeActive()&&ControllerPinManager.isConfigured(context);
                PairingService domPair=new PairingService(store,domSecrets,domEndpoint,new RemoteKeyholder(context,store,domSecrets),access,"Owned Dom");
                PairingService subPair=new PairingService(second,subSecrets,subEndpoint,new RemoteKeyholder(context,second,subSecrets),access,"Owned Sub");
                long now=System.currentTimeMillis();PairingInvite invite=domPair.create(now);subPair.accept(invite.link(),"Owned Dom",now+1);
                assertFalse(handshake(server,domEndpoint,subEndpoint,domPair,subPair,second,invite,now+2));
                domPair.approve(invite.id,subEndpoint.localIdentity(),PairingInvite.fingerprint(domEndpoint.localIdentity(),subEndpoint.localIdentity(),invite.id),now+3);
                ControllerPinManager.enterSubMode();
                assertTrue(handshake(server,domEndpoint,subEndpoint,domPair,subPair,second,invite,now+4));
                assertEquals(RemoteStore.State.ACTIVE,store.peer(subEndpoint.localIdentity(),invite.id).state);assertEquals(RemoteStore.State.ACTIVE,second.peer(domEndpoint.localIdentity(),invite.id).state);
                assertFalse(ControllerPinManager.isDomModeActive());assertThrows(SecurityException.class,()->domPair.create(System.currentTimeMillis()));
            }
        }finally{
            server.shutdownNow();secondary.close();secondary.cleanupOwnedFixture();FormalRelationshipCoordinator.releaseOwnedFixture(second);second.close();context.deleteDatabase(second.getDatabaseName());
            context.getSharedPreferences("owned-secrets-second",0).edit().clear().commit();java.security.KeyStore keys=java.security.KeyStore.getInstance("AndroidKeyStore");keys.load(null);String alias="subhub.test.formal."+prefix+"second";if(keys.containsAlias(alias))keys.deleteEntry(alias);
        }
    }
    private static boolean handshake(java.util.concurrent.ExecutorService server,PeerTransport domEndpoint,PeerTransport subEndpoint,PairingService domPair,PairingService subPair,RemoteStore subStore,PairingInvite invite,long now)throws Exception{
        java.util.concurrent.Future<Boolean> incoming=server.submit(()->{try(PeerTransport.Connection connection=domEndpoint.accept(10_000)){return domPair.handle(connection,RemoteProtocol.read(connection.input()),now);}});
        try(PeerTransport.Connection connection=subEndpoint.connect(domEndpoint.localIdentity(),invite.route,10_000)){
            boolean result=subPair.resume(connection,subStore.peer(domEndpoint.localIdentity(),invite.id));assertEquals(result,incoming.get(15,TimeUnit.SECONDS));return result;
        }
    }
    @Test public void descriptorFlagsNoFollowAndExclusiveCreateAreObservedOnActualNativeFd()throws Exception{
        FormalRelationshipProvider real=provider();await(real.snapshot());
        Class<?> type=Class.forName("com.subhub.app.remote.LinkingWitness$OwnedDescriptor");
        java.lang.reflect.Method open=type.getDeclaredMethod("open",String.class,int.class,int.class),close=type.getDeclaredMethod("close");open.setAccessible(true);close.setAccessible(true);
        java.lang.reflect.Field owner=type.getDeclaredField("owner");owner.setAccessible(true);
        java.io.File file=java.io.File.createTempFile(prefix,".fd",context.getCacheDir()),link=new java.io.File(file.getPath()+"-link");Object opened=null;
        try{
            opened=open.invoke(null,file.getPath(),android.system.OsConstants.O_RDONLY|android.system.OsConstants.O_NOFOLLOW,0);
            android.os.ParcelFileDescriptor descriptor=(android.os.ParcelFileDescriptor)owner.get(opened);
            LinkingWitness.DescriptorOps libc=com.sun.jna.Native.load("c",LinkingWitness.DescriptorOps.class);
            int flags=libc.fcntl(descriptor.getFd(),android.system.OsConstants.F_GETFD,0);assertTrue(flags>=0);assertTrue((flags&android.system.OsConstants.FD_CLOEXEC)!=0);
            close.invoke(opened);assertNull(owner.get(opened));opened=null;
            java.lang.reflect.InvocationTargetException collision=assertThrows(java.lang.reflect.InvocationTargetException.class,()->open.invoke(null,file.getPath(),android.system.OsConstants.O_CREAT|android.system.OsConstants.O_EXCL|android.system.OsConstants.O_WRONLY|android.system.OsConstants.O_NOFOLLOW,0600));
            assertTrue(collision.getCause() instanceof android.system.ErrnoException);assertEquals(android.system.OsConstants.EEXIST,((android.system.ErrnoException)collision.getCause()).errno);
            android.system.Os.symlink(file.getPath(),link.getPath());
            java.lang.reflect.InvocationTargetException symlink=assertThrows(java.lang.reflect.InvocationTargetException.class,()->open.invoke(null,link.getPath(),android.system.OsConstants.O_RDONLY|android.system.OsConstants.O_NOFOLLOW,0));
            assertTrue(symlink.getCause() instanceof android.system.ErrnoException);assertEquals(android.system.OsConstants.ELOOP,((android.system.ErrnoException)symlink.getCause()).errno);
        }finally{if(opened!=null)close.invoke(opened);if(link.exists()||java.nio.file.Files.isSymbolicLink(link.toPath()))assertTrue(link.delete());assertTrue(file.delete());}
    }
    @Test public void authenticatedRevokedResponseCommitsAndAcknowledgesWhileControllerIsLocked()throws Exception{
        FormalRelationshipProvider real=provider();await(real.snapshot());
        PairingInvite invite=PairingInvite.create(DOM,"fixture",System.currentTimeMillis());store.saveInvite(invite);store.bindInvite(invite.id,SUB,invite.tokenHash(),System.currentTimeMillis());store.approveInvite(invite.id,SUB,"Owned Sub",System.currentTimeMillis());store.activate(SUB,invite.id);
        RemoteStore.Peer peer=store.peer(SUB,invite.id);int[] revoked={0};store.onRevoked((identity,generation)->{assertFalse(Thread.holdsLock(store));assertEquals(RemoteStore.State.REVOKED,store.peer(identity,generation).state);revoked[0]++;});
        RemoteSession session=new RemoteSession(store,endpoint(DOM),new RemoteSession.Hooks(){
            public void prepare(RemoteStore.Peer current){throw new AssertionError("Revoked peer reached protected exchange");}public void received(RemoteStore.Peer current,RemoteProtocol.Stream stream){}public java.util.List<RemoteSession.AssetNeed> missingAssets(RemoteStore.Peer current){return java.util.Collections.emptyList();}public RemoteAssets assets(RemoteStore.Peer current){return null;}public void complete(RemoteStore.Peer current){}
        });
        ControllerPinManager.enterSubMode();java.io.ByteArrayOutputStream output=new java.io.ByteArrayOutputStream();
        PeerTransport.Connection connection=connection(SUB,frames(new org.json.JSONObject().put("version",1).put("type","revoked").put("generation",invite.id)),output);
        session.outgoing(connection,peer,System.nanoTime()+TimeUnit.SECONDS.toNanos(20));
        assertFalse(ControllerPinManager.isDomModeActive());assertEquals(RemoteStore.State.REVOKED,store.peer(SUB,invite.id).state);assertEquals(0,count("formal_governing_slots"));assertEquals(1,revoked[0]);
        java.io.ByteArrayInputStream written=new java.io.ByteArrayInputStream(output.toByteArray());assertEquals("hello",RemoteProtocol.read(written).getString("type"));assertEquals("revoked_ack",RemoteProtocol.read(written).getString("type"));assertEquals(0,written.available());
    }
    @Test public void restoredRevokedBackgroundPeerCannotReachActualProfileOrCredentialHooks()throws Exception{
        FormalRelationshipProvider real=provider();await(real.snapshot());
        RemoteSecrets secrets=new RemoteSecrets(context,"owned-secrets","subhub.test.formal."+prefix);
        PairingInvite invite=PairingInvite.create(DOM,"fixture",System.currentTimeMillis());store.acceptInvite(invite,"Legacy active Dom");store.activate(DOM,invite.id);
        RemoteFeature feature=new RemoteFeature(context,store,secrets);PeerTransport endpoint=endpoint(SUB);
        java.lang.reflect.Method factory=RemoteFeature.class.getDeclaredMethod("session",PeerTransport.class);factory.setAccessible(true);RemoteSession session=(RemoteSession)factory.invoke(feature,endpoint);
        java.lang.reflect.Field callbacks=RemoteSession.class.getDeclaredField("hooks");callbacks.setAccessible(true);RemoteSession.Hooks hooks=(RemoteSession.Hooks)callbacks.get(session);
        try{
            real.close();for(RemoteStore handle:handles)handle.close();java.io.File file=store.resolvedDatabaseFile();byte[] before=java.nio.file.Files.readAllBytes(file.toPath()),beforeMarker=java.nio.file.Files.readAllBytes(marker().toPath());
            ControllerPinManager.enterSubMode();java.io.ByteArrayOutputStream acknowledged=new java.io.ByteArrayOutputStream();
            PeerTransport.Connection revoke=connection(DOM,frames(new org.json.JSONObject().put("version",1).put("type","revoke_complete").put("generation",invite.id)),acknowledged);
            session.receiveRevocation(revoke,new org.json.JSONObject().put("version",1).put("type","revoke").put("generation",invite.id),System.nanoTime()+TimeUnit.SECONDS.toNanos(20));
            assertEquals(RemoteStore.State.REVOKED,store.peer(DOM,invite.id).state);assertEquals(0,count("formal_governing_slots"));assertEquals("revoke_ack",RemoteProtocol.read(new java.io.ByteArrayInputStream(acknowledged.toByteArray())).getString("type"));assertFalse(ControllerPinManager.isDomModeActive());
            for(RemoteStore handle:handles)handle.close();try(java.io.FileOutputStream restore=new java.io.FileOutputStream(file)){restore.write(before);restore.getFD().sync();}java.nio.file.Files.write(marker().toPath(),beforeMarker);
            RemoteStore.Peer restored=store.peer(DOM,invite.id);assertEquals(RemoteStore.State.ACTIVE,restored.state);assertEquals("0",store.metadata(DOM,invite.id,"controller_auth_v1_provisioned","0"));
            java.io.ByteArrayOutputStream output=new java.io.ByteArrayOutputStream();PeerTransport.Connection selected=connection(DOM,new byte[0],output);
            assertThrows(IllegalStateException.class,()->hooks.profile(restored));assertThrows(IllegalStateException.class,()->hooks.displayName(restored));assertThrows(IllegalStateException.class,()->hooks.prepare(restored));
            assertThrows(IllegalStateException.class,()->hooks.authenticate(selected,restored,System.nanoTime()+TimeUnit.SECONDS.toNanos(20)));assertEquals(0,output.size());
            assertNull(secrets.controllerSecret(RemoteProtocol.Role.SUB,DOM,invite.id));assertEquals("0",store.metadata(DOM,invite.id,"controller_auth_v1_provisioned","0"));
            java.lang.reflect.Method background=RemoteFeature.class.getDeclaredMethod("runPeer",RemoteStore.Peer.class,long.class);background.setAccessible(true);assertEquals(Boolean.FALSE,background.invoke(feature,restored,System.nanoTime()+TimeUnit.SECONDS.toNanos(20)));
            java.lang.reflect.Field active=RemoteFeature.class.getDeclaredField("endpoint");active.setAccessible(true);assertNull(active.get(feature));
        }finally{feature.shutdownForTest();}
    }
    @Test public void legacyPendingCancelCannotRevokeActiveRelationship()throws Exception{
        FormalRelationshipProvider real=provider();await(real.snapshot());
        PairingInvite invite=PairingInvite.create(DOM,"fixture",1000);store.acceptInvite(invite,"Dom");
        RemoteSecrets secrets=new RemoteSecrets(context,"owned-secrets","subhub.test.formal."+prefix);
        RemoteKeyholder authenticator=new RemoteKeyholder(context,store,secrets);
        String seed=store.authorityAccess(()->authenticator.provisionSub(store.peer(DOM,invite.id),SUB));
        store.activate(DOM,invite.id);ControllerPinManager.enterSubMode();
        assertEquals(com.subhub.app.security.ControllerAuthenticator.Result.SUCCESS,store.authorityAccess(()->authenticator.verifyCode(com.subhub.app.security.Totp.code(seed,System.currentTimeMillis()/com.subhub.app.security.Totp.STEP_MILLIS))));
        assertFalse(store.cancelPendingPeer(DOM,invite.id));
        assertThrows(SecurityException.class,()->store.cancelInvite(invite.id));
        assertEquals(RemoteStore.State.ACTIVE,store.peer(DOM,invite.id).state);assertEquals(1,count("formal_governing_slots"));
        store.revoke(DOM,invite.id);assertEquals(RemoteStore.State.REVOKED,store.peer(DOM,invite.id).state);assertEquals(0,count("formal_governing_slots"));
    }
}

abstract class LinkingFixture extends FormalRelationshipFixture {
    final List<FormalRelationshipProvider> providers=new ArrayList<>();
    FormalRelationshipProvider provider(){FormalRelationshipProvider result=new FormalRelationshipProvider(context,store);providers.add(result);return result;}
    static <T>T await(CompletableFuture<T> future)throws Exception{return future.get(30,TimeUnit.SECONDS);}
    static String ref(RelationshipUi.Snapshot snapshot,RelationshipUi.Role role){for(RelationshipUi.Participant participant:snapshot.localParticipants)if(participant.role==role)return participant.ref;throw new AssertionError("Missing local role "+role);}
    static PeerTransport endpoint(String local){return new PeerTransport(){public String localIdentity(){return local;}public String inviteRoute(){return "owned fixture";}public Connection connect(String identity,String route,long timeout){throw new AssertionError("Unexpected connection");}public Connection accept(long timeout){throw new AssertionError("Unexpected accept");}public void close(){}};}
    static byte[] frames(org.json.JSONObject... values)throws Exception{java.io.ByteArrayOutputStream output=new java.io.ByteArrayOutputStream();for(org.json.JSONObject value:values)RemoteProtocol.write(output,value);return output.toByteArray();}
    static PeerTransport.Connection connection(String identity,byte[] received,java.io.ByteArrayOutputStream output){return new PeerTransport.Connection(){final java.io.InputStream input=new java.io.ByteArrayInputStream(received);public String authenticatedPeerIdentity(){return identity;}public java.io.InputStream input(){return input;}public java.io.OutputStream output(){return output;}public void setReadTimeoutMillis(long timeout){}public boolean isDirect(){return true;}public void close(){}};}
    java.io.File marker()throws Exception{return new java.io.File(new java.io.File(context.getNoBackupFilesDir(),"linking-"+LocalRelationshipAuthority.digest(store.resolvedDatabaseFile().getPath())),"current.json");}
    @After public void cleanupLinkingFixture(){
        for(FormalRelationshipProvider provider:providers)provider.close();
        if(!providers.isEmpty())providers.get(providers.size()-1).cleanupOwnedFixture();
        try{java.security.KeyStore keys=java.security.KeyStore.getInstance("AndroidKeyStore");keys.load(null);String alias="subhub.test.formal."+prefix;if(keys.containsAlias(alias))keys.deleteEntry(alias);}
        catch(Exception failure){throw new AssertionError("Owned fixture wrapping-key cleanup failed",failure);}
    }
}
