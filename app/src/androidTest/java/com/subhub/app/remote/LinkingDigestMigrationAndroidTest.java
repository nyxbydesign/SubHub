package com.subhub.app.remote;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.security.DeviceRolePreferences;
import com.subhub.app.settings.SettingsRepository;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.json.JSONObject;
import org.junit.Test;

/** Owned historical SQLite/Keystore fixtures; no app singleton, controller, transport or device permission changes. */
public final class LinkingDigestMigrationAndroidTest {
    @Test public void versionEightCoverageUpgradesOnceAcrossSignedStructuralCutpointsWithoutAdoptingOtherState() throws Exception {
        Context target=InstrumentationRegistry.getInstrumentation().getTargetContext();List<LegacyCase> cases=new ArrayList<>();
        Throwable primary=null;
        try {
            LegacyCase beforeReceipt=create(target,cases),otherDatabase=create(target,cases);
            beforeReceipt.openNine();otherDatabase.openNine();
            LinkingStructuralReservation reservation=beforeReceipt.reservation();
            byte[] signature=beforeReceipt.receipts.signStructural(reservation);
            JSONObject valid=structural(reservation,signature);
            beforeReceipt.attach();
            assertThrows(IllegalStateException.class,()->PairingLifecycleLedger.requireCovered(beforeReceipt.db()));
            byte[] corrupt=signature.clone();corrupt[corrupt.length-1]^=1;
            beforeReceipt.refuseWithoutWrites(structural(reservation,corrupt));
            JSONObject unsigned=copy(valid);unsigned.remove("signature");beforeReceipt.refuseWithoutWrites(unsigned);
            JSONObject coerced=copy(valid).put("revision",Long.toString(reservation.nextRevision));beforeReceipt.refuseWithoutWrites(coerced);
            JSONObject unknown=copy(valid).put("unexpected",true);beforeReceipt.refuseWithoutWrites(unknown);
            LinkingStructuralReservation foreign=otherDatabase.reservation();
            byte[] foreignSignature=otherDatabase.receipts.signStructural(foreign);
            beforeReceipt.refuseWithoutWrites(structural(foreign,foreignSignature));
            beforeReceipt.refuseWithoutWrites(structural(reservation,foreignSignature));
            LinkingStructuralReservation wrongDigest=new LinkingStructuralReservation(reservation.database,reservation.epoch,
                    reservation.oldRevision,reservation.oldCommand,(reservation.oldDigest.charAt(0)=='0'?"1":"0")+reservation.oldDigest.substring(1),reservation.initial,true,
                    reservation.readyRevision,reservation.snapshotScope,1);
            beforeReceipt.refuseWithoutWrites(structural(wrongDigest,beforeReceipt.receipts.signStructural(wrongDigest)));
            LinkingStructuralReservation wrongInitial=new LinkingStructuralReservation(reservation.database,reservation.epoch,
                    reservation.oldRevision,reservation.oldCommand,reservation.oldDigest,
                    (reservation.initial.charAt(0)=='0'?"1":"0")+reservation.initial.substring(1),true,
                    reservation.readyRevision,reservation.snapshotScope,1);
            beforeReceipt.refuseWithoutWrites(structural(wrongInitial,beforeReceipt.receipts.signStructural(wrongInitial)));
            beforeReceipt.db().execSQL("UPDATE linking_state SET ready_revision=ready_revision+1 WHERE id=1");
            beforeReceipt.refuseWithoutWrites(valid);
            beforeReceipt.db().execSQL("UPDATE linking_state SET ready_revision=? WHERE id=1",new Object[]{reservation.readyRevision});
            beforeReceipt.db().execSQL("UPDATE formal_authority SET migration_complete=0 WHERE id=1");
            beforeReceipt.refuseWithoutWrites(valid,IllegalArgumentException.class);
            beforeReceipt.db().execSQL("UPDATE formal_authority SET migration_complete=1 WHERE id=1");
            beforeReceipt.insertLedger("uncovered");beforeReceipt.refuseWithoutWrites(valid);
            beforeReceipt.db().execSQL("DELETE FROM pairing_lifecycle");
            beforeReceipt.saveMarker(valid);
            assertEquals("verified",beforeReceipt.read());beforeReceipt.assertMigrated(reservation);

            LegacyCase beforeSql=create(target,cases);beforeSql.openNine();
            LinkingStructuralReservation reserved=beforeSql.reservation();byte[] retiredSignature=beforeSql.receipts.signStructural(reserved);
            beforeSql.saveMarker(structural(reserved,retiredSignature));beforeSql.advance(reserved);
            byte[] acknowledgedNext=beforeSql.certificate(LinkingReceiptStore.CURRENT);
            assertFalse(beforeSql.currentKeyAccepts(reserved.signingBytes(),retiredSignature));
            assertThrows(java.security.GeneralSecurityException.class,()->beforeSql.receipts.verifyStructural(reserved,retiredSignature));
            assertEquals("verified",beforeSql.read());beforeSql.assertMigrated(reserved);
            assertArrayEquals(acknowledgedNext,beforeSql.certificate(LinkingReceiptStore.CURRENT));

            LegacyCase beforeSeal=create(target,cases);beforeSeal.openNine();
            LinkingStructuralReservation committed=beforeSeal.reservation();
            beforeSeal.saveMarker(structural(committed,beforeSeal.receipts.signStructural(committed)));beforeSeal.advance(committed);
            beforeSeal.db().beginTransaction();
            try {
                beforeSeal.db().execSQL("UPDATE linking_state SET digest_version=2 WHERE id=1");
                beforeSeal.db().execSQL("UPDATE linking_state SET revision=?,command=?,state_digest=? WHERE id=1",
                        new Object[]{committed.nextRevision,committed.command,currentDigest(beforeSeal.db())});
                beforeSeal.db().setTransactionSuccessful();
            } finally {beforeSeal.db().endTransaction();}
            byte[] committedCertificate=beforeSeal.certificate(LinkingReceiptStore.CURRENT);
            assertEquals("verified",beforeSeal.read());beforeSeal.assertMigrated(committed);
            assertArrayEquals(committedCertificate,beforeSeal.certificate(LinkingReceiptStore.CURRENT));

            LegacyCase uninterrupted=create(target,cases);uninterrupted.openNine();
            LinkingStructuralReservation ordinaryUpgrade=uninterrupted.reservation();
            assertEquals("verified",uninterrupted.read());uninterrupted.assertMigrated(ordinaryUpgrade);
            // A raw format change never creates coverage or a new reservation.
            uninterrupted.db().execSQL("PRAGMA ignore_check_constraints=ON");
            try {uninterrupted.db().execSQL("UPDATE linking_state SET digest_version=3 WHERE id=1");}
            finally {uninterrupted.db().execSQL("PRAGMA ignore_check_constraints=OFF");}
            uninterrupted.refuseWithoutWrites(uninterrupted.markerValue());
            uninterrupted.db().execSQL("UPDATE linking_state SET digest_version=2 WHERE id=1");
            assertEquals("verified",uninterrupted.read());
            String emptyDigest=currentDigest(uninterrupted.db());
            uninterrupted.witness.mutate(uninterrupted.store,"owned-ledger-fixture","two-bounded-records",db->{
                PairingLifecycleLedger.requireCovered(db);uninterrupted.insertLedger("second");uninterrupted.insertLedger("first");return null;
            });
            String withRows=currentDigest(uninterrupted.db());assertNotEquals(emptyDigest,withRows);
            assertEquals("second",firstPhysicalOperation(uninterrupted.db()));
            // Reinsert exactly the same rows in a different physical order; BINARY key order is canonical.
            uninterrupted.db().beginTransaction();
            try {
                uninterrupted.db().execSQL("CREATE TEMP TABLE owned_lifecycle_copy AS SELECT * FROM pairing_lifecycle");
                uninterrupted.db().execSQL("DELETE FROM pairing_lifecycle");
                uninterrupted.db().execSQL("INSERT INTO pairing_lifecycle SELECT * FROM owned_lifecycle_copy ORDER BY operation_id ASC");
                uninterrupted.db().execSQL("DROP TABLE owned_lifecycle_copy");uninterrupted.db().setTransactionSuccessful();
            } finally {uninterrupted.db().endTransaction();}
            assertEquals("first",firstPhysicalOperation(uninterrupted.db()));
            assertEquals(withRows,currentDigest(uninterrupted.db()));assertEquals("verified",uninterrupted.read());
            uninterrupted.db().execSQL("UPDATE pairing_lifecycle SET token_hash=? WHERE operation_id='first'",new Object[]{repeat('e')});
            uninterrupted.refuseWithoutWrites(uninterrupted.markerValue());

            LegacyCase ordinary=create(target,cases);ordinary.openNine();
            LinkingStructuralReservation original=ordinary.reservation();String command=LocalRelationshipAuthority.digest("owned-ordinary-command");
            ordinary.saveMarker(new JSONObject().put("version",1).put("epoch",original.epoch).put("revision",original.nextRevision)
                    .put("command",command).put("phase","RESERVED"));
            ordinary.receipts.write(LinkingReceiptStore.CURRENT,new LinkingReceiptStore.Receipt(original.epoch,original.nextRevision,command));
            ordinary.refuseWithoutWrites(ordinary.markerValue());

            LegacyCase afterUpdate=create(target,cases);afterUpdate.openNine();
            LinkingStructuralReservation rejected=afterUpdate.reservation();String oldSql=stateBytes(afterUpdate.db());
            afterUpdate.db().execSQL("CREATE TRIGGER owned_structural_after AFTER UPDATE OF command ON linking_state"
                    +" WHEN NEW.digest_version=2 BEGIN UPDATE formal_authority SET revision=revision+1 WHERE id=1; END");
            assertThrows(IllegalStateException.class,afterUpdate::read);
            assertEquals(oldSql,stateBytes(afterUpdate.db()));assertEquals(afterUpdate.retained,retainedTables(afterUpdate.db()));
            assertEquals(rejected.nextRevision,afterUpdate.receipts.read(LinkingReceiptStore.CURRENT).revision);
            assertEquals(rejected.command,afterUpdate.receipts.read(LinkingReceiptStore.CURRENT).digest);
            assertEquals(2,afterUpdate.markerValue().getInt("version"));assertEquals("RESERVED",afterUpdate.markerValue().getString("phase"));
            byte[] reservedMarker=Files.readAllBytes(afterUpdate.marker.toPath());
            assertThrows(IllegalStateException.class,afterUpdate::read);
            assertEquals(oldSql,stateBytes(afterUpdate.db()));assertArrayEquals(reservedMarker,Files.readAllBytes(afterUpdate.marker.toPath()));

            LegacyCase fresh=new LegacyCase(target);cases.add(fresh);fresh.allocate();
            assertEquals("verified",fresh.read());assertEquals(2,number(fresh.db(),"digest_version"));
            assertEquals(0,count(fresh.db(),"pairing_lifecycle"));
        } catch(Throwable failure) {primary=failure;throw failure;}
        finally {
            Throwable cleanup=null;
            for(int i=cases.size()-1;i>=0;i--)try{cases.get(i).close();}catch(Throwable failure){if(cleanup==null)cleanup=failure;else cleanup.addSuppressed(failure);}
            if(cleanup!=null){if(primary!=null)primary.addSuppressed(cleanup);else throw new AssertionError("Owned migration fixture cleanup failed",cleanup);}
        }
    }

    private static LegacyCase create(Context target,List<LegacyCase> cases)throws Exception {
        LegacyCase value=new LegacyCase(target);cases.add(value);value.allocate();value.initializeEight();return value;
    }
    private static JSONObject copy(JSONObject value)throws Exception{return new JSONObject(value.toString());}
    private static JSONObject structural(LinkingStructuralReservation reservation,byte[] signature)throws Exception {
        return new JSONObject().put("version",2).put("epoch",reservation.epoch).put("revision",reservation.nextRevision)
                .put("command",reservation.command).put("phase","RESERVED")
                .put("payload",Base64.getUrlEncoder().withoutPadding().encodeToString(reservation.signingBytes()))
                .put("signature",Base64.getUrlEncoder().withoutPadding().encodeToString(signature));
    }
    private static String currentDigest(SQLiteDatabase db)throws Exception {
        Method method=LinkingWitness.class.getDeclaredMethod("digest",SQLiteDatabase.class);method.setAccessible(true);
        return (String)method.invoke(null,db);
    }
    private static String repeat(char value){return new String(new char[64]).replace('\0',value);}
    private static long number(SQLiteDatabase db,String column){try(Cursor row=db.rawQuery("SELECT "+column+" FROM linking_state WHERE id=1",null)){assertTrue(row.moveToFirst());return row.getLong(0);}}
    private static long count(SQLiteDatabase db,String table){try(Cursor row=db.rawQuery("SELECT COUNT(*) FROM "+table,null)){assertTrue(row.moveToFirst());return row.getLong(0);}}
    private static String firstPhysicalOperation(SQLiteDatabase db){try(Cursor row=db.rawQuery("SELECT operation_id FROM pairing_lifecycle ORDER BY rowid LIMIT 1",null)){assertTrue(row.moveToFirst());return row.getString(0);}}

    private static final class LegacyCase implements AutoCloseable {
        final Context target,context;final String prefix,wrappingAlias;LinkingReceiptStore receipts;
        RemoteSecrets secrets;RemoteStore store;LinkingWitness witness;
        boolean ownsDatabase,ownsPreferences,ownsWitnessDirectory,ownsWrappingAlias;
        final Map<String,byte[]> originalCertificates=new LinkedHashMap<>();
        Map<String,String> retained;Map<String,?> originalSecrets;String pendingLink,identityDigest,controller;
        PairingInvite invite;File directory,marker;
        LegacyCase(Context target) {
            this.target=target;prefix="pairing-migration-"+UUID.randomUUID()+"-";wrappingAlias="subhub.test."+prefix;
            context=new ContextWrapper(target){
                @Override public Context getApplicationContext(){return this;}
                @Override public SharedPreferences getSharedPreferences(String name,int mode){return target.getSharedPreferences(prefix+name,mode);}
            };
        }
        void allocate()throws Exception {
            assertTrue(context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME,0).getAll().isEmpty());
            assertTrue(context.getSharedPreferences("secrets",0).getAll().isEmpty());ownsPreferences=true;
            KeyStore keys=KeyStore.getInstance("AndroidKeyStore");keys.load(null);assertFalse(keys.containsAlias(wrappingAlias));ownsWrappingAlias=true;
            assertTrue(context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME,0).edit().putString(DeviceRolePreferences.KEY_MODE,"BOTH").commit());
            store=FormalRelationshipCoordinator.createOwnedFixture(context);assertFalse(store.isApplicationDatabase());
            FormalRelationshipCoordinator.requireOwnedFixture(store);ownsDatabase=true;
            receipts=new LinkingReceiptStore(store.resolvedDatabaseFile().getPath());
            secrets=new RemoteSecrets(context,"secrets",wrappingAlias);
            directory=new File(context.getNoBackupFilesDir(),"linking-"+LocalRelationshipAuthority.digest(store.resolvedDatabaseFile().getPath()));
            assertFalse(directory.exists());ownsWitnessDirectory=true;marker=new File(directory,"current.json");
        }
        void initializeEight()throws Exception {
            assertEquals(0,store.resolvedDatabaseFile().length());
            try(SQLiteDatabase raw=SQLiteDatabase.openDatabase(store.resolvedDatabaseFile().getPath(),null,SQLiteDatabase.OPEN_READWRITE|SQLiteDatabase.CREATE_IF_NECESSARY)){
                raw.setForeignKeyConstraintsEnabled(true);raw.beginTransaction();
                try {
                    // The other tables are unchanged from published 2fb5bf6. Reconstruct its exact V8 linking DDL.
                    store.onCreate(raw);raw.execSQL("DROP TABLE pairing_lifecycle");raw.execSQL("DROP TABLE linking_state");
                    raw.execSQL("CREATE TABLE linking_state(id INTEGER PRIMARY KEY CHECK(id=1),epoch TEXT,revision INTEGER NOT NULL DEFAULT 0,"
                            +"command TEXT,state_digest TEXT,init_digest TEXT,initialized INTEGER NOT NULL DEFAULT 0 CHECK(initialized IN(0,1)),ready_revision INTEGER NOT NULL DEFAULT 0,snapshot_scope TEXT)");
                    raw.execSQL("INSERT INTO linking_state(id) VALUES(1)");String initial=legacyDigest(raw);
                    invite=PairingInvite.create(repeat('a'),"owned",1000);PairingInvite pending=PairingInvite.create(repeat('a'),"owned",1000);
                    for(PairingInvite value:new PairingInvite[]{invite,pending}){
                        String peer=value==invite?repeat('b'):repeat('c'),state=value==invite?"ACTIVE":"PENDING";
                        raw.execSQL("INSERT INTO peers(peer,generation,name,local_role,state,last_sync,route) VALUES(?,?,?,'DOM',?,1234,'owned')",
                                new Object[]{peer,value.id,"Synthetic device",state});FormalRelationshipStore.mapPeer(raw,peer,value.id,true);
                        raw.execSQL("INSERT INTO invites(id,token_hash,expires,candidate,approved,consumed) VALUES(?,?,?,?,1,1)",
                                new Object[]{value.id,value.tokenHash(),value.expiresAt,peer});
                    }
                    raw.execSQL("UPDATE formal_authority SET migration_complete=1 WHERE id=1");
                    raw.execSQL("INSERT INTO outbox(peer,generation,stream,item,seq,body,deleted,observed) VALUES(?,?,'STATS','summary',4,?,0,1234)",
                            new Object[]{repeat('b'),invite.id,"{\"schema\":1,\"events\":7}"});
                    raw.execSQL("INSERT INTO remote_metadata(peer,generation,name,value) VALUES(?,?,'retained_fixture','unchanged')",new Object[]{repeat('b'),invite.id});
                    String scope=LocalRelationshipAuthority.digest("owned-scope:"+prefix);
                    raw.execSQL("UPDATE linking_state SET snapshot_scope=? WHERE id=1",new Object[]{scope});
                    String digest=legacyDigest(raw),epoch=UUID.randomUUID().toString().replace("-","");
                    String command=LocalRelationshipAuthority.digest("owned-v8-command:"+epoch);
                    raw.execSQL("UPDATE linking_state SET epoch=?,revision=7,command=?,state_digest=?,init_digest=?,initialized=1,ready_revision=2 WHERE id=1",
                            new Object[]{epoch,command,digest,initial});
                    raw.setVersion(8);raw.setTransactionSuccessful();
                } finally {raw.endTransaction();}
                assertEquals(8,raw.getVersion());retained=retainedTables(raw);
                try(Cursor row=raw.rawQuery("SELECT epoch,command,state_digest,init_digest FROM linking_state",null)){
                    assertTrue(row.moveToFirst());assertEquals(row.getString(2),legacyDigest(raw));
                    receipts.write(LinkingReceiptStore.INIT,new LinkingReceiptStore.Receipt(row.getString(0),0,row.getString(3)));
                    receipts.write(LinkingReceiptStore.EVER,new LinkingReceiptStore.Receipt(row.getString(0),0,row.getString(3)));
                    receipts.write(LinkingReceiptStore.CURRENT,new LinkingReceiptStore.Receipt(row.getString(0),7,row.getString(1)));
                    saveMarker(new JSONObject().put("version",1).put("epoch",row.getString(0)).put("revision",7).put("command",row.getString(1)).put("phase","SEALED"));
                }
            }
            secrets.savePending(invite);pendingLink=secrets.pendingLink(invite.id);
            identityDigest=LinkingStructuralReservation.sha256(secrets.identitySeed());
            controller=new String(new char[32]).replace('\0','A');
            secrets.saveControllerSecret(RemoteProtocol.Role.DOM,repeat('a'),repeat('b'),invite.id,controller);
            originalSecrets=new LinkedHashMap<>(context.getSharedPreferences("secrets",0).getAll());
            for(String kind:new String[]{LinkingReceiptStore.INIT,LinkingReceiptStore.EVER,LinkingReceiptStore.CURRENT})originalCertificates.put(kind,certificate(kind));
        }
        void openNine()throws Exception {
            assertEquals(9,db().getVersion());assertEquals(1,number(db(),"digest_version"));
            assertEquals(0,count(db(),"pairing_lifecycle"));assertEquals(retained,retainedTables(db()));
            for(String kind:originalCertificates.keySet())assertArrayEquals(originalCertificates.get(kind),certificate(kind));
        }
        SQLiteDatabase db(){return store.getWritableDatabase();}
        void attach(){if(witness==null)witness=LinkingWitness.attach(context,store);}
        String read(){attach();return witness.readCurrent(store,()->"verified");}
        LinkingStructuralReservation reservation(){
            try(Cursor row=db().rawQuery("SELECT epoch,revision,command,state_digest,init_digest,initialized,ready_revision,snapshot_scope FROM linking_state WHERE id=1",null)){
                assertTrue(row.moveToFirst());return new LinkingStructuralReservation(LocalRelationshipAuthority.digest(path()),row.getString(0),row.getLong(1),row.getString(2),row.getString(3),row.getString(4),row.getInt(5)==1,row.getLong(6),row.getString(7),FormalRelationshipStore.number(db(),"migration_complete"));
            }
        }
        String path(){try{return store.resolvedDatabaseFile().getPath();}catch(Exception failure){throw new AssertionError(failure);}}
        void advance(LinkingStructuralReservation reservation)throws Exception{receipts.write(LinkingReceiptStore.CURRENT,new LinkingReceiptStore.Receipt(reservation.epoch,reservation.nextRevision,reservation.command));}
        void saveMarker(JSONObject value)throws Exception {
            if(!directory.exists())assertTrue(directory.mkdir());
            assertEquals(context.getNoBackupFilesDir().getCanonicalFile(),directory.getCanonicalFile().getParentFile());
            try(FileOutputStream output=new FileOutputStream(marker)){output.write(value.toString().getBytes(StandardCharsets.UTF_8));output.getFD().sync();}
        }
        JSONObject markerValue()throws Exception{return new JSONObject(new String(Files.readAllBytes(marker.toPath()),StandardCharsets.UTF_8));}
        byte[] certificate(String kind)throws Exception {
            KeyStore keys=KeyStore.getInstance("AndroidKeyStore");keys.load(null);
            return keys.getCertificate("subhub.linking.v1."+LocalRelationshipAuthority.digest(path())+"."+kind).getEncoded();
        }
        boolean currentKeyAccepts(byte[] payload,byte[] signature)throws Exception {
            KeyStore keys=KeyStore.getInstance("AndroidKeyStore");keys.load(null);
            java.security.Signature verifier=java.security.Signature.getInstance("SHA256withECDSA");
            verifier.initVerify(keys.getCertificate("subhub.linking.v1."+LocalRelationshipAuthority.digest(path())+"."+LinkingReceiptStore.CURRENT).getPublicKey());
            verifier.update(payload);return verifier.verify(signature);
        }
        void refuseWithoutWrites(JSONObject value)throws Exception {
            refuseWithoutWrites(value,IllegalStateException.class);
        }
        void refuseWithoutWrites(JSONObject value,Class<? extends RuntimeException> expected)throws Exception {
            saveMarker(value);String state=stateBytes(db());Map<String,String> tables=retainedTables(db());
            String ledger=rowsDigest(db(),"pairing_lifecycle");
            byte[] bytes=Files.readAllBytes(marker.toPath()),current=certificate(LinkingReceiptStore.CURRENT);
            assertThrows(expected,this::read);
            assertEquals(state,stateBytes(db()));assertEquals(tables,retainedTables(db()));
            assertEquals(ledger,rowsDigest(db(),"pairing_lifecycle"));
            assertArrayEquals(bytes,Files.readAllBytes(marker.toPath()));assertArrayEquals(current,certificate(LinkingReceiptStore.CURRENT));
        }
        void assertMigrated(LinkingStructuralReservation before)throws Exception {
            assertEquals(2,number(db(),"digest_version"));assertEquals(before.nextRevision,number(db(),"revision"));
            assertEquals(before.oldDigest,legacyDigest(db()));assertEquals(retained,retainedTables(db()));
            assertArrayEquals(originalCertificates.get(LinkingReceiptStore.INIT),certificate(LinkingReceiptStore.INIT));
            assertArrayEquals(originalCertificates.get(LinkingReceiptStore.EVER),certificate(LinkingReceiptStore.EVER));
            assertEquals(0,receipts.read(LinkingReceiptStore.INIT).revision);assertEquals(0,receipts.read(LinkingReceiptStore.EVER).revision);
            LinkingReceiptStore.Receipt current=receipts.read(LinkingReceiptStore.CURRENT);
            assertEquals(before.epoch,current.epoch);assertEquals(before.command,current.digest);assertEquals(before.nextRevision,current.revision);
            try(Cursor row=db().rawQuery("SELECT epoch,init_digest,ready_revision,snapshot_scope,state_digest FROM linking_state",null)){
                assertTrue(row.moveToFirst());assertEquals(before.epoch,row.getString(0));assertEquals(before.initial,row.getString(1));
                assertEquals(before.readyRevision,row.getLong(2));assertEquals(before.snapshotScope,row.getString(3));
                assertEquals(currentDigest(db()),row.getString(4));assertNotEquals(before.oldDigest,row.getString(4));
            }
            assertEquals(0,count(db(),"pairing_lifecycle"));assertEquals("SEALED",markerValue().getString("phase"));
            assertTrue(pendingLink.equals(secrets.pendingLink(invite.id)));
            assertEquals(identityDigest,LinkingStructuralReservation.sha256(secrets.identitySeed()));
            assertTrue(controller.equals(secrets.controllerSecret(RemoteProtocol.Role.DOM,repeat('a'),repeat('b'),invite.id)));
            assertTrue(originalSecrets.equals(context.getSharedPreferences("secrets",0).getAll()));
            byte[] certificate=certificate(LinkingReceiptStore.CURRENT);String state=stateBytes(db());
            store.close();store=new RemoteStore(context,store.getDatabaseName());assertEquals("verified",read());
            assertEquals(state,stateBytes(db()));assertArrayEquals(certificate,certificate(LinkingReceiptStore.CURRENT));
        }
        void insertLedger(String id){
            db().execSQL("INSERT INTO pairing_lifecycle(operation_id,generation,direction,token_hash,state,created_wall,created_elapsed,created_boot,public_deadline_wall,public_deadline_elapsed)"
                    +" VALUES(?,?,'CREATE',?,'PENDING',1000,100,1,601000,600100)",new Object[]{id,id,repeat('d')});
        }
        @Override public void close()throws Exception {
            Throwable failed=null;
            for(Cleanup action:new Cleanup[]{
                    ()->{if(ownsWitnessDirectory){LinkingWitness attached=LinkingWitness.attach(context,store);attached.cleanupOwnedFixture(store);}},
                    ()->{if(store!=null)store.close();},()->{if(ownsDatabase)FormalRelationshipCoordinator.releaseOwnedFixture(store);},
                    ()->{if(ownsDatabase)assertTrue(target.deleteDatabase(store.getDatabaseName()));},
                    ()->{if(ownsWrappingAlias){KeyStore keys=KeyStore.getInstance("AndroidKeyStore");keys.load(null);if(keys.containsAlias(wrappingAlias))keys.deleteEntry(wrappingAlias);assertFalse(keys.containsAlias(wrappingAlias));}},
                    ()->{if(ownsPreferences)for(String name:new String[]{"secrets",SettingsRepository.PREFERENCES_NAME})assertTrue(context.getSharedPreferences(name,0).edit().clear().commit());}}){
                try{action.run();}catch(Throwable failure){if(failed==null)failed=failure;else failed.addSuppressed(failure);}
            }
            if(failed!=null)throw new AssertionError("Owned historical fixture cleanup failed",failed);
        }
    }
    private interface Cleanup{void run()throws Exception;}
    private static String stateBytes(SQLiteDatabase db){
        try(Cursor row=db.rawQuery("SELECT * FROM linking_state",null)){
            MessageDigest hash=digest();while(row.moveToNext())for(int i=0;i<row.getColumnCount();i++)field(hash,row,i);
            return hex(hash.digest());
        }
    }
    private static Map<String,String> retainedTables(SQLiteDatabase db){
        Map<String,String> result=new LinkedHashMap<>();
        try(Cursor tables=db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'"
                +" AND name NOT IN('android_metadata','linking_state','pairing_lifecycle') ORDER BY name",null)){
            while(tables.moveToNext()){
                String name=tables.getString(0);result.put(name,rowsDigest(db,name));
            }
        }
        return result;
    }
    private static String rowsDigest(SQLiteDatabase db,String name){
        assertTrue(name.matches("[a-z_]+"));MessageDigest hash=digest();
        try(Cursor rows=db.rawQuery("SELECT * FROM "+name+" ORDER BY rowid",null)){
            while(rows.moveToNext())for(int i=0;i<rows.getColumnCount();i++)field(hash,rows,i);
        }
        return hex(hash.digest());
    }
    /** Frozen published V1 oracle: 2fb5bf6, before the additive coverage upgrade. */
    private static String legacyDigest(SQLiteDatabase db){
        MessageDigest hash=digest();
        String[][] tables={{"formal_authority","*","id"},{"formal_participants","*","ref"},{"formal_relationships","*","id,generation"},
                {"formal_governing_slots","*","sub_ref"},{"formal_operations","*","id"},{"linking_unlinks","*","relationship_id,generation"},
                {"peers","peer,generation,local_role,state","peer,generation"},{"invites","id,token_hash,expires,candidate,approved,consumed","id"},
                {"linking_state","snapshot_scope","id"}};
        for(String[] table:tables){
            hash.update(table[0].getBytes(StandardCharsets.US_ASCII));
            try(Cursor rows=db.rawQuery("SELECT "+table[1]+" FROM "+table[0]+" ORDER BY "+table[2],null)){
                while(rows.moveToNext())for(int i=0;i<rows.getColumnCount();i++)field(hash,rows,i);
            }
        }
        return hex(hash.digest());
    }
    private static void field(MessageDigest hash,Cursor row,int index){
        byte[] bytes=row.isNull(index)?new byte[0]:row.getType(index)==Cursor.FIELD_TYPE_BLOB?row.getBlob(index):row.getString(index).getBytes(StandardCharsets.UTF_8);
        hash.update((byte)row.getType(index));hash.update(ByteBuffer.allocate(4).putInt(bytes.length).array());hash.update(bytes);
    }
    private static MessageDigest digest(){try{return MessageDigest.getInstance("SHA-256");}catch(Exception impossible){throw new AssertionError(impossible);}}
    private static String hex(byte[] bytes){StringBuilder value=new StringBuilder();for(byte b:bytes)value.append(Character.forDigit((b>>>4)&15,16)).append(Character.forDigit(b&15,16));return value.toString();}
}
