package com.subhub.app.remote;

import static org.junit.Assert.*;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import com.subhub.app.relationships.RelationshipUi;
import org.junit.Test;

public final class FormalRelationshipMigrationAndroidTest extends FormalRelationshipFixture {
    @Override void beforeOpen() {
        // Build a real v6 table inventory, then remove only the not-yet-shipped additive v7 tables.
        RemoteStore seed=open(database);SQLiteDatabase db=seed.getWritableDatabase();db.beginTransaction();
        try {
            for(String table:new String[]{"formal_governing_slots","formal_operations","formal_relationships","formal_participants","formal_authority"})db.execSQL("DROP TABLE "+table);
            db.setVersion(6);
            for(int i=1;i<=145;i++)db.execSQL("INSERT INTO peers(peer,generation,name,local_role,state) VALUES(?,?,'Legacy pending','DOM','PENDING')",new Object[]{identity(i),"legacy_"+i});
            db.execSQL("INSERT INTO remote_metadata(peer,generation,name,value) VALUES(?,?,'retained','raw legacy value')",new Object[]{identity(1),"legacy_1"});
            db.execSQL("INSERT INTO session_control_journal(peer,generation,id,direction,request_digest,request,result,state,updated,sequence,received_wall,received_elapsed,received_boot,deadline_elapsed) "
                    + "VALUES(?,?,'original','OUTGOING','original-digest','raw/request','raw/result','QUEUED',7,1,1,2,3,4)",new Object[]{identity(1),"legacy_1"});
            db.setTransactionSuccessful();
        } finally {db.endTransaction();seed.close();}
    }
    @Test public void interruptedBoundedMigrationPreservesBacklogAndRawSessionData() {
        assertEquals(32,count("formal_relationships"));assertEquals(0,FormalRelationshipStore.number(db(),"migration_complete"));
        assertEquals(145,store.additionOrder(identity(145),"legacy_145"));
        while(store.migrateFormalRelationships()!=0){ }
        assertEquals(145,count("formal_relationships"));assertEquals(0,count("formal_operations"));
        for(int i=1;i<=145;i++){store.activate(identity(i),"legacy_"+i);assertEquals(i,store.additionOrder(identity(i),"legacy_"+i));}
        assertEquals(145,count("formal_governing_slots"));
        assertEquals("raw legacy value",store.metadata(identity(1),"legacy_1","retained",""));
        try(Cursor row=db().rawQuery("SELECT request_digest,request,result,updated,received_boot FROM session_control_journal",null)){
            assertTrue(row.moveToFirst());assertEquals("original-digest",row.getString(0));assertEquals("raw/request",row.getString(1));assertEquals("raw/result",row.getString(2));assertEquals(7,row.getLong(3));assertEquals(3,row.getInt(4));
        }
        assertEquals(0,store.migrateFormalRelationships());
    }
    @Test public void newPeersMapImmediatelyAcrossInterruptedMigrationAndLocalInterleaving() {
        assertThrows(IllegalStateException.class,()->coordinator().internalSnapshot());
        PairingInvite first=PairingInvite.create(DOM,"fixture",1000);store.saveInvite(first);store.bindInvite(first.id,SUB,first.tokenHash(),1001);store.approveInvite(first.id,SUB,"Sub",1001);
        assertEquals(146,store.additionOrder(SUB,first.id));
        while(store.migrateFormalRelationships()!=0){ }
        FormalRelationshipCoordinator engine=coordinator();FormalRelationshipCoordinator.InternalSnapshot s=engine.internalSnapshot();RelationshipUi.LocalReview local=engine.prepareLocal("interleaved",s.dom,s.sub,s.changeCounter);
        String next=repeat('c');PairingInvite second=PairingInvite.create(DOM,"fixture",1000);store.saveInvite(second);store.bindInvite(second.id,next,second.tokenHash(),1001);store.approveInvite(second.id,next,"Next Sub",1001);
        assertEquals(148,store.additionOrder(next,second.id));
        try(Cursor row=db().rawQuery("SELECT rowid FROM peers WHERE peer=?",new String[]{next})){assertTrue(row.moveToFirst());assertEquals(147,row.getLong(0));}
        assertEquals(RelationshipUi.OperationState.ACTIVE,engine.confirmLocal(local.operationId,local.requestDigest).state);
        assertEquals(1,store.additionOrder(identity(1),"legacy_1"));
    }
    @Test public void failedMigrationBatchRollsBackItsRowsAndCursor() {
        long cursor=FormalRelationshipStore.number(db(),"migration_cursor");
        db().execSQL("CREATE TRIGGER fail_mapping BEFORE INSERT ON formal_relationships BEGIN SELECT RAISE(ABORT,'owned migration failure'); END");
        assertThrows(android.database.sqlite.SQLiteException.class,()->store.migrateFormalRelationships());
        assertEquals(cursor,FormalRelationshipStore.number(db(),"migration_cursor"));assertEquals(32,count("formal_relationships"));
        db().execSQL("DROP TRIGGER fail_mapping");assertEquals(32,store.migrateFormalRelationships());
    }
    @Test public void versionFourAndFiveUpgradePreserveEveryOriginalTableRow() {
        for(int version:new int[]{4,5}){
            String name=prefix+"version-"+version;RemoteStore seed=open(name);SQLiteDatabase original=seed.getWritableDatabase();original.beginTransaction();
            try{
                for(String table:new String[]{"formal_governing_slots","formal_operations","formal_relationships","formal_participants","formal_authority","session_control_journal"})original.execSQL("DROP TABLE "+table);
                if(version==4)original.execSQL("DROP TABLE session_controls");
                else original.execSQL("INSERT INTO session_controls(peer,generation,id,direction,request_digest,request,result,state,updated) VALUES('legacy','generation','old','OUTGOING','digest','original/raw','original/result','QUEUED',99)");
                original.execSQL("INSERT INTO peers(peer,generation,name,local_role,state) VALUES(?,?,'Original','DOM','REVOKED')",new Object[]{identity(version),"v"+version});
                original.execSQL("INSERT INTO remote_metadata(peer,generation,name,value) VALUES(?,?,'retained','original/raw')",new Object[]{identity(version),"v"+version});
                original.setVersion(version);original.setTransactionSuccessful();
            }finally{original.endTransaction();}
            java.util.Map<String,String> retained=fingerprint(original);seed.close();RemoteStore upgraded=open(name);SQLiteDatabase next=upgraded.getWritableDatabase();
            assertEquals(7,next.getVersion());upgraded.migrateFormalRelationships();
            java.util.Map<String,String> after=fingerprint(next);for(java.util.Map.Entry<String,String> entry:retained.entrySet())assertEquals(entry.getKey(),entry.getValue(),after.get(entry.getKey()));
        }
    }
    @Test public void unknownRetainedRoleCannotBeReinterpretedAsLocalSub() {
        long cursor=FormalRelationshipStore.number(db(),"migration_cursor");
        db().execSQL("UPDATE peers SET local_role='UNKNOWN' WHERE generation='legacy_33'");
        assertThrows(SecurityException.class,()->store.migrateFormalRelationships());
        assertEquals(cursor,FormalRelationshipStore.number(db(),"migration_cursor"));
        assertEquals(32,count("formal_relationships"));assertEquals(0,count("formal_governing_slots"));
    }
    private static java.util.Map<String,String> fingerprint(SQLiteDatabase db){
        java.util.Map<String,String> result=new java.util.LinkedHashMap<>();java.util.List<String> tables=new java.util.ArrayList<>();
        try(Cursor rows=db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'formal_%' ORDER BY name",null)){while(rows.moveToNext())tables.add(rows.getString(0));}
        for(String table:tables){StringBuilder body=new StringBuilder();try(Cursor rows=db.rawQuery("SELECT * FROM \""+table+"\" ORDER BY rowid",null)){
            while(rows.moveToNext()){for(int column=0;column<rows.getColumnCount();column++){
                body.append(rows.getType(column)).append(':');
                if(rows.isNull(column))body.append("null");
                else if(rows.getType(column)==Cursor.FIELD_TYPE_BLOB)body.append(android.util.Base64.encodeToString(rows.getBlob(column),android.util.Base64.NO_WRAP));
                else body.append(rows.getString(column));
                body.append(';');
            }body.append('\n');}
        }result.put(table,body.toString());}return result;
    }
    private static String identity(int i){return String.format(java.util.Locale.ROOT,"%064x",i);}
}
