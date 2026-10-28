package com.subhub.app.remote;

import static org.junit.Assert.*;
import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import android.os.Build;
import android.system.Os;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.activity.*;
import com.subhub.app.people.PersonId;
import com.subhub.app.security.*;
import com.subhub.app.settings.SettingsRepository;
import com.subhub.app.storage.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class StorageCompressionAndroidTest {
    static final String DOM=String.join("",Collections.nCopies(64,"a")), SUB=String.join("",Collections.nCopies(64,"b")), GENERATION="storage_fixture";
    static final String[] TABLES={"peers","invites","local_objects","outbox","remote_objects","outgoing","incoming","commands","remote_metadata","activity_source","activity_receipts","activity_state"};
    Context context;final List<String> databases=new ArrayList<>();Map<String,?> original;
    @Before public void setup() {
        Assume.assumeTrue(Build.HARDWARE.contains("ranchu")||Build.HARDWARE.contains("goldfish"));
        context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        original=new HashMap<>(context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME,0).getAll());
        context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME,0).edit().putString(DeviceRolePreferences.KEY_MODE,DeviceRolePreferences.Mode.BOTH.name()).commit();
        ControllerPinManager.initialize(context);ControllerPinManager.enterDomMode();
    }
    @After public void cleanup() {
        if(context==null)return;
        for(String name:databases)context.deleteDatabase(name);
        SharedPreferences.Editor edit=context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME,0).edit().clear();
        for(Map.Entry<String,?> row:original.entrySet()){
            Object v=row.getValue();String k=row.getKey();
            if(v instanceof Boolean)edit.putBoolean(k,(Boolean)v);else if(v instanceof String)edit.putString(k,(String)v);else if(v instanceof Long)edit.putLong(k,(Long)v);else if(v instanceof Integer)edit.putInt(k,(Integer)v);else if(v instanceof Float)edit.putFloat(k,(Float)v);else if(v instanceof Set)edit.putStringSet(k,new HashSet<>((Set<String>)v));else throw new AssertionError("Unsupported preference fixture");
        }
        assertTrue(edit.commit());ControllerPinManager.enterSubMode();
    }
    String name(){String name="storage-check-"+UUID.randomUUID()+".db";databases.add(name);return name;}
    static String fact(int index)throws Exception {
        String id="app_"+UUID.nameUUIDFromBytes(("storage-"+index).getBytes(StandardCharsets.UTF_8));
        return RemoteProtocol.canonical(RemoteActivityWire.encode(new ActivityEvent(id,ActivityEvent.Kind.APP_OPENED,1767225600000L+index*1000L,"com.example.selected",0,"","",0),"Europe/Berlin",""));
    }
    static void legacy(Context context,String name,boolean sub,int records)throws Exception {
        RemoteStore schema=new RemoteStore(context,name);SQLiteDatabase db=SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name),null);
        try {
            schema.onCreate(db);db.execSQL("DROP TABLE payload_maintenance");
            db.execSQL("INSERT INTO peers(peer,generation,name,local_role,state) VALUES(?,?,?,?,?)",new Object[]{sub?DOM:SUB,GENERATION,"Synthetic",sub?"SUB":"DOM","ACTIVE"});
            for(int i=1;i<=records;i++){
                String body=fact(i),id=new JSONObject(body).getString("id");
                if(sub){db.execSQL("INSERT INTO activity_source(event_id,source_key,body,observed,peer,generation,published) VALUES(?,?,?,?,?,?,1)",new Object[]{id,id,body,1767225600000L+i*1000L,DOM,GENERATION});
                    for(String table:new String[]{"local_objects","outbox"})db.execSQL("INSERT INTO "+table+" VALUES(?,?,?,?,?,?,?,?)",new Object[]{DOM,GENERATION,"EVENTS",id,i,body,0,1767225600000L+i*1000L});}
            }
            if(sub)db.execSQL("INSERT INTO outgoing(peer,generation,stream,next_seq) VALUES(?,?,?,?)",new Object[]{DOM,GENERATION,"EVENTS",records});
            db.setVersion(3);
        }finally{db.close();schema.close();}
    }
    static int count(SQLiteDatabase db,String table,String predicate) {
        try(Cursor c=db.rawQuery("SELECT COUNT(*) FROM "+table+(predicate==null?"":" WHERE "+predicate),null)){assertTrue(c.moveToFirst());return c.getInt(0);}
    }
    static String digest(SQLiteDatabase db)throws Exception {
        MessageDigest hash=MessageDigest.getInstance("SHA-256");
        for(String table:TABLES)try(Cursor rows=db.rawQuery("SELECT * FROM "+table+" ORDER BY rowid",null)){
            hash.update(table.getBytes(StandardCharsets.UTF_8));while(rows.moveToNext())for(int c=0;c<rows.getColumnCount();c++){
                String value=rows.isNull(c)?"<null>":"body".equals(rows.getColumnName(c))?StoredPayloads.read(rows,c):rows.getString(c);
                byte[] bytes=value.getBytes(StandardCharsets.UTF_8);hash.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array());hash.update(bytes);
            }
        }
        return hex(hash.digest());
    }
    static String hex(byte[] bytes){StringBuilder text=new StringBuilder();for(byte b:bytes)text.append(String.format(Locale.ROOT,"%02x",b&255));return text.toString();}
    @Test public void legacyAndNewPayloadsKeepLocalFactsAndOfflineReplayAcrossReopen()throws Exception {
        String sourceName=name(),targetName=name();legacy(context,sourceName,true,1);legacy(context,targetName,false,0);
        try(RemoteStore source=new RemoteStore(context,sourceName);RemoteStore target=new RemoteStore(context,targetName)){
            assertEquals(4,source.getReadableDatabase().getVersion());assertEquals(1,count(source.getReadableDatabase(),"activity_source","typeof(body)='text'"));
            String second=fact(2);source.publish(DOM,GENERATION,RemoteProtocol.Stream.EVENTS,new JSONObject(second).getString("id"),new JSONObject(second),false,1767225602000L);
            assertEquals(1,count(source.getReadableDatabase(),"local_objects","typeof(body)='blob'"));
            RemoteProtocol.Batch batch=source.nextBatch(DOM,GENERATION,RemoteProtocol.Stream.EVENTS);assertEquals(2,batch.changes.size());assertEquals(fact(1),batch.changes.get(0).body);assertEquals(second,batch.changes.get(1).body);
            String wireDigest=batch.digest();RemoteProtocol.Ack ack=target.receive(SUB,batch);assertEquals(wireDigest,ack.digest);target.receive(SUB,batch);assertEquals(2,count(target.getReadableDatabase(),"activity_receipts",null));
            source.acknowledge(DOM,ack);assertTrue(source.nextBatch(DOM,GENERATION,RemoteProtocol.Stream.EVENTS).changes.isEmpty());
            assertEquals(2,target.mirror(SUB,GENERATION,RemoteProtocol.Stream.EVENTS).size());
        }
        try(RemoteStore source=new RemoteStore(context,sourceName);RemoteActivityRepository local=new RemoteActivityRepository(context,source,()->{});
                RemoteStore target=new RemoteStore(context,targetName);RemoteActivityRepository received=new RemoteActivityRepository(context,target,()->{})) {
            assertEquals(1,local.recent(PersonId.LOCAL,10).size());assertEquals(2,received.recent(RemotePersonRepository.id(target.peer(SUB,GENERATION)),10).size());
            assertEquals(fact(2),target.mirror(SUB,GENERATION,RemoteProtocol.Stream.EVENTS).get(1).body);
        }
    }
    @Test public void boundedMaintenanceAndForwardRollbackPreserveEveryLogicalField()throws Exception {
        String name=name();legacy(context,name,true,20);
        try(RemoteStore store=new RemoteStore(context,name)){
            SQLiteDatabase db=store.getWritableDatabase();String before=digest(db);assertEquals(0,count(db,"activity_source","typeof(body)='blob'"));
            int visited=0,n;do{n=store.compactStoredPayloads(7);assertTrue(n>=0&&n<=7);visited+=n;}while(n>0);
            assertEquals(60,visited);assertEquals(before,digest(db));assertEquals(20,count(db,"activity_source","typeof(body)='blob'"));assertEquals(0,store.compactStoredPayloads(7));
            InstrumentationRegistry.getInstrumentation().runOnMainSync(()->assertThrows(IllegalStateException.class,()->store.compactStoredPayloads(7)));
            db.beginTransaction();try{assertEquals(60,StoredPayloads.restorePlaintext(db));db.setTransactionSuccessful();}finally{db.endTransaction();}
            assertEquals(before,digest(db));assertEquals(20,count(db,"activity_source","typeof(body)='text'"));assertEquals(0,count(db,"payload_maintenance",null));
        }
        try(RemoteStore reopened=new RemoteStore(context,name)){assertEquals(20,count(reopened.getReadableDatabase(),"activity_source","typeof(body)='text'"));}
    }
    @Test public void failedMaintenanceRollsBackPayloadsAndProgressTogether()throws Exception {
        String name=name();legacy(context,name,true,8);
        try(RemoteStore store=new RemoteStore(context,name)){
            SQLiteDatabase db=store.getWritableDatabase();String before=digest(db);
            db.execSQL("CREATE TRIGGER fail_payload BEFORE UPDATE OF body ON activity_source WHEN old.id=3 BEGIN SELECT RAISE(ABORT,'fixture storage failure'); END");
            assertThrows(SQLiteException.class,()->store.compactStoredPayloads(64));
            assertEquals(before,digest(db));assertEquals(0,count(db,"local_objects","typeof(body)='blob'"));assertEquals(0,count(db,"payload_maintenance",null));
            db.execSQL("DROP TRIGGER fail_payload");assertEquals(24,store.compactStoredPayloads(64));assertEquals(before,digest(db));
        }
    }
    @Test public void maintenanceBoundsDecodedBytesAsWellAsRecordCount()throws Exception {
        String name=name();legacy(context,name,true,0);
        try(RemoteStore store=new RemoteStore(context,name)) {
            SQLiteDatabase db=store.getWritableDatabase();char[] padding=new char[192*1024];Arrays.fill(padding,'x');
            String body=new JSONObject().put("schema",1).put("payload",new String(padding)).toString();
            for(int i=1;i<=8;i++)db.execSQL("INSERT INTO local_objects VALUES(?,?,?,?,?,?,?,?)",new Object[]{DOM,GENERATION,"BASELINE","large_"+i,i,body,0,1767225600000L+i});
            String before=digest(db);assertEquals(5,store.compactStoredPayloads(256));
            assertEquals(5,count(db,"local_objects","typeof(body)='blob'"));assertEquals(before,digest(db));
            assertEquals(3,store.compactStoredPayloads(256));assertEquals(0,store.compactStoredPayloads(256));assertEquals(before,digest(db));
        }
    }
    @Test public void corruptStoredReceiptCannotAdvanceTheIncomingCursor()throws Exception {
        String sourceName=name(),targetName=name();legacy(context,sourceName,true,1);legacy(context,targetName,false,0);
        try(RemoteStore source=new RemoteStore(context,sourceName);RemoteStore target=new RemoteStore(context,targetName)){
            RemoteProtocol.Batch batch=source.nextBatch(DOM,GENERATION,RemoteProtocol.Stream.EVENTS);target.receive(SUB,batch);
            target.getWritableDatabase().execSQL("UPDATE activity_receipts SET body=?",new Object[]{new byte[]{83,72,90,99,0,0,0,0,1}});
            assertThrows(IllegalStateException.class,()->target.receive(SUB,batch));
            try(Cursor c=target.getReadableDatabase().rawQuery("SELECT through_seq FROM incoming WHERE stream='EVENTS'",null)){assertTrue(c.moveToFirst());assertEquals(1,c.getLong(0));}
            assertEquals(1,count(target.getReadableDatabase(),"activity_receipts",null));assertEquals(1,count(source.getReadableDatabase(),"outbox",null));
        }
    }
    @Test public void measuredRetainedFixtureKeepsFactsAndReducesNewDatabaseFootprint()throws Exception {
        String[] baseline={"storage-local.db","storage-sub.db","storage-dom.db"};
        for(String name:baseline)Assume.assumeTrue("Retained v3 measurement fixture required",context.getDatabasePath(name).exists());
        JSONObject report=new JSONObject().put("phoneAttribution",false);JSONArray stores=new JSONArray();
        for(String baselineName:baseline){
            String compactName=name();JSONObject record=new JSONObject().put("source",baselineName);long totalMicros=0,maxMicros=0;int visited=0,calls=0;
            try(RemoteStore original=new RemoteStore(context,baselineName);RemoteStore packed=new RemoteStore(context,compactName)){
                SQLiteDatabase db=original.getWritableDatabase();
                // Rebuild only this synthetic plaintext control when qualifying a changed codec/maintenance candidate.
                db.beginTransaction();try{record.put("plaintextControlRows",StoredPayloads.restorePlaintext(db));db.setTransactionSuccessful();}finally{db.endTransaction();}
                checkpoint(db);
                String before=digest(db);record.put("before",fileBytes(context,baselineName)).put("logicalBefore",before);
                int n;do{long started=System.nanoTime();n=original.compactStoredPayloads(128);long elapsed=(System.nanoTime()-started)/1000L;totalMicros+=elapsed;maxMicros=Math.max(maxMicros,elapsed);visited+=n;calls++;}while(n>0);
                assertEquals(before,digest(db));record.put("maintenanceVisited",visited).put("maintenanceCalls",calls).put("maintenanceMicros",totalMicros).put("maximumBatchMicros",maxMicros);
                copy(db,packed.getWritableDatabase());assertEquals(before,digest(packed.getReadableDatabase()));
                record.put("logicalAfter",digest(db));
                if(baselineName.equals("storage-local.db"))record.put("page50Latency",pageLatency(packed.getReadableDatabase()));
                checkpoint(db);checkpoint(packed.getWritableDatabase());
                record.put("retainedInPlace",fileBytes(context,baselineName)).put("newDatabase",fileBytes(context,compactName));
                if(baselineName.equals("storage-sub.db")){RemoteProtocol.Batch pending=original.nextBatch(DOM,original.peers(false).get(0).generation,RemoteProtocol.Stream.EVENTS);assertEquals(128,pending.changes.size());assertEquals(10800,pending.after);record.put("offlineQueueStillReadable",true);}
            }
            record.put("retainedClosed",fileBytes(context,baselineName)).put("newClosed",fileBytes(context,compactName));
            assertTrue(record.getJSONObject("newClosed").getLong("allocated")<record.getJSONObject("retainedClosed").getLong("allocated"));stores.put(record);
        }
        report.put("stores",stores);File result=new File(context.getFilesDir(),"storage-compression-result.json");try(Writer writer=new OutputStreamWriter(new FileOutputStream(result),StandardCharsets.UTF_8)){writer.write(report.toString(2));}
        System.out.println("STORAGE_COMPRESSION "+report);
    }
    static void copy(SQLiteDatabase source,SQLiteDatabase target)throws Exception {
        target.beginTransaction();try{for(String table:TABLES)try(Cursor rows=source.rawQuery("SELECT rowid AS __rowid,* FROM "+table+" ORDER BY rowid",null)){
            while(rows.moveToNext()){
                ContentValues values=new ContentValues();values.put("rowid",rows.getLong(0));
                for(int c=1;c<rows.getColumnCount();c++){
                    String name=rows.getColumnName(c);
                    if("body".equals(name))StoredPayloads.put(values,name,StoredPayloads.read(rows,c));
                    else switch(rows.getType(c)){case Cursor.FIELD_TYPE_NULL:values.putNull(name);break;case Cursor.FIELD_TYPE_INTEGER:values.put(name,rows.getLong(c));break;case Cursor.FIELD_TYPE_FLOAT:values.put(name,rows.getDouble(c));break;case Cursor.FIELD_TYPE_BLOB:values.put(name,rows.getBlob(c));break;default:values.put(name,rows.getString(c));}
                }
                assertTrue(target.insertOrThrow(table,null,values)>0);
            }
        }target.setTransactionSuccessful();}finally{target.endTransaction();}
    }
    static JSONObject fileBytes(Context context,String name)throws Exception {
        long bytes=0,allocated=0;for(String suffix:new String[]{"","-wal","-shm"}){File file=new File(context.getDatabasePath(name).getPath()+suffix);if(file.exists()){bytes+=file.length();allocated+=Os.stat(file.getPath()).st_blocks*512L;}}
        return new JSONObject().put("logical",bytes).put("allocated",allocated);
    }
    static void checkpoint(SQLiteDatabase db){try(Cursor c=db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)",null)){assertTrue(c.moveToFirst());assertEquals(0,c.getInt(0));}}
    static JSONObject pageLatency(SQLiteDatabase db)throws Exception {
        long[] times=new long[31];for(int i=0;i<times.length;i++){long started=System.nanoTime();try(Cursor rows=db.rawQuery("SELECT body FROM activity_source WHERE id<? ORDER BY id DESC LIMIT 50",new String[]{Integer.toString(12001-i*50)})){int count=0;while(rows.moveToNext()){new JSONObject(StoredPayloads.read(rows,0));count++;}assertEquals(50,count);}times[i]=(System.nanoTime()-started)/1000L;}
        Arrays.sort(times);return new JSONObject().put("medianMicros",times[15]).put("p95Micros",times[29]);
    }
}
