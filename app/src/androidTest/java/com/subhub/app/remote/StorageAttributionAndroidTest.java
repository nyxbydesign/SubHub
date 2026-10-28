package com.subhub.app.remote;

import static org.junit.Assert.*;
import android.app.usage.StorageStats;
import android.app.usage.StorageStatsManager;
import android.content.*;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Process;
import android.os.storage.StorageManager;
import android.system.Os;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.MainActivity;
import com.subhub.app.activity.ActivityEvent;
import com.subhub.app.security.*;
import com.subhub.app.settings.SettingsRepository;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;

/** A synthetic disk/latency witness; no personal records, permissions or remote traffic. */
@RunWith(AndroidJUnit4.class)
public class StorageAttributionAndroidTest {
    private static final int EVENTS=12000, RECEIVED=10800;
    private static final String DOM=String.join("",Collections.nCopies(64,"a"));
    private static final String SUB=String.join("",Collections.nCopies(64,"b"));
    private String[] names;
    private static final long DAY=86400000L, ORIGIN=1767225600000L;
    @Test public void representativeDiskAndQueryAttribution() throws Exception {
        org.junit.Assume.assumeTrue(android.os.Build.HARDWARE.contains("ranchu")||android.os.Build.HARDWARE.contains("goldfish"));
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        String prefix="storage-attribution-"+UUID.randomUUID();names=new String[]{prefix+"-local.db",prefix+"-sub.db",prefix+"-dom.db"};
        context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME,0).edit().putString(DeviceRolePreferences.KEY_MODE,DeviceRolePreferences.Mode.BOTH.name()).commit();
        ControllerPinManager.initialize(context);ControllerPinManager.enterDomMode();
        try(ActivityScenario<MainActivity> page=ActivityScenario.launch(MainActivity.class)) {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        }
        JSONObject report=new JSONObject().put("fixture","one synthetic Sub;60days;200selected-app opens/day;1200pending peer events")
                .put("events",EVENTS).put("received",RECEIVED).put("phoneAttribution",false).put("initialApplication",tree(context.getDataDir(),context.getDataDir()));
        try { StorageStats stats=context.getSystemService(StorageStatsManager.class).queryStatsForPackage(StorageManager.UUID_DEFAULT,context.getPackageName(),Process.myUserHandle());
            report.put("androidAccounting",new JSONObject().put("app",stats.getAppBytes()).put("data",stats.getDataBytes()).put("cache",stats.getCacheBytes()));
        } catch(Exception unavailable) {report.put("androidAccountingUnavailable",unavailable.getClass().getSimpleName());}
        for(String name:names)assertFalse("Fresh owned fixture database required",context.getDatabasePath(name).exists());
        RemoteStore local=new RemoteStore(context,names[0]),sub=new RemoteStore(context,names[1]),dom=new RemoteStore(context,names[2]);
        try {
            PairingInvite invite=PairingInvite.create(DOM,"fixture",System.currentTimeMillis());String generation=invite.id;
            dom.saveInvite(invite);dom.bindInvite(generation,SUB,invite.tokenHash(),System.currentTimeMillis());dom.approveInvite(generation,SUB,"Synthetic Sub",System.currentTimeMillis());dom.activate(SUB,generation);
            sub.acceptInvite(invite,"Synthetic Dom");sub.activate(DOM,generation);
            long started=System.nanoTime();
            seed(local.getWritableDatabase(),null,null,true,false);seed(sub.getWritableDatabase(),DOM,generation,true,true);seed(dom.getWritableDatabase(),SUB,generation,false,false);
            report.put("seedMillis",(System.nanoTime()-started)/1000000L);
            report.put("local",inspect(local,context,names[0])).put("pairedSub",inspect(sub,context,names[1])).put("pairedDom",inspect(dom,context,names[2]));
            report.put("openWalFiles",databaseFiles(context));
            report.put("compressionCandidates",compressionCandidates(local.getReadableDatabase()));
            JSONObject latency=new JSONObject();long[] samples=new long[31];
            for(int i=0;i<samples.length;i++){started=System.nanoTime();try(Cursor rows=local.getReadableDatabase().rawQuery("SELECT body FROM activity_source WHERE id<? ORDER BY id DESC LIMIT 50",new String[]{Integer.toString(EVENTS-i*50+1)})){int count=0;while(rows.moveToNext()){new JSONObject(rows.getString(0));count++;}assertEquals(50,count);}samples[i]=(System.nanoTime()-started)/1000L;}
            Arrays.sort(samples);latency.put("page50MedianMicros",samples[15]).put("page50P95Micros",samples[29]);report.put("latency",latency);
            for(RemoteStore store:new RemoteStore[]{local,sub,dom})try(Cursor checkpoint=store.getWritableDatabase().rawQuery("PRAGMA wal_checkpoint(TRUNCATE)",null)){assertTrue(checkpoint.moveToFirst());assertEquals(0,checkpoint.getInt(0));}
            report.put("checkpointedFiles",databaseFiles(context));
        } finally {local.close();sub.close();dom.close();}
        report.put("afterClose",databaseFiles(context));
        File output=new File(context.getFilesDir(),"storage-attribution-baseline.json");try(Writer writer=new OutputStreamWriter(new FileOutputStream(output),StandardCharsets.UTF_8)){writer.write(report.toString(2));}
        System.out.println("STORAGE_REPORT "+report.toString());
        for(String name:names)assertTrue(context.deleteDatabase(name));
    }
    private static void seed(SQLiteDatabase db,String peer,String generation,boolean source,boolean outbound) throws Exception {
        db.beginTransaction();try {
            Random identities=new Random(717163L);
            for(int i=1;i<=EVENTS;i++){
                long observed=ORIGIN+(i/200)*DAY+(i%200)*180000L;String id="app_"+new UUID(identities.nextLong(),identities.nextLong()),key="com.example.selected"+(i%8);
                String body=RemoteProtocol.canonical(RemoteActivityWire.encode(new ActivityEvent(id,ActivityEvent.Kind.APP_OPENED,observed,key,0,"","",0),"Europe/Berlin",""));
                if(source)db.execSQL("INSERT INTO activity_source(event_id,source_key,body,observed,peer,generation,published) VALUES(?,?,?,?,?,?,?)",new Object[]{id,id,body,observed,peer,generation,outbound?1:0});
                if(outbound){
                    db.execSQL("INSERT INTO local_objects VALUES(?,?,?,?,?,?,?,?)",new Object[]{peer,generation,"EVENTS",id,i,body,0,observed});
                    if(i>RECEIVED)db.execSQL("INSERT INTO outbox VALUES(?,?,?,?,?,?,?,?)",new Object[]{peer,generation,"EVENTS",id,i,body,0,observed});
                }
                if(!source && i<=RECEIVED){
                    db.execSQL("INSERT INTO remote_objects VALUES(?,?,?,?,?,?,?,?)",new Object[]{peer,generation,"EVENTS",id,i,body,0,observed});
                    String entry=RemoteProtocol.sha256((peer+":"+generation+":"+id).getBytes(StandardCharsets.UTF_8));
                    db.execSQL("INSERT INTO activity_receipts(entry_key,peer,generation,event_id,body,received) VALUES(?,?,?,?,?,?)",new Object[]{entry,peer,generation,id,body,observed+5000});
                }
            }
            if(outbound)db.execSQL("INSERT INTO outgoing(peer,generation,stream,next_seq,acked,offered) VALUES(?,?,?,?,?,?)",new Object[]{peer,generation,"EVENTS",EVENTS,RECEIVED,RECEIVED});
            if(!source)db.execSQL("INSERT INTO incoming VALUES(?,?,?,?)",new Object[]{peer,generation,"EVENTS",RECEIVED});
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }
    private static JSONObject compressionCandidates(SQLiteDatabase db)throws Exception {
        byte[] dictionary="schema id kind observedAt packageName amountCents currency reason limitMinutes zone day configuration APP_OPENED TRIBUTE_TRIGGERED APP_LIMIT_REACHED SHARED_LIMIT_REACHED Europe/Berlin schema protectedSeconds serviceSeconds sessions activityEvents censors limitMillis limitStops whispers popups tamperEvents tributes assessedEUR assessedUSD paidEUR paidUSD currentSeconds currentEvents currentCensors".getBytes(StandardCharsets.UTF_8);
        long raw=0,zlib=0,withDictionary=0,started=System.nanoTime();int rows=0;byte[] output=new byte[8192];
        try(Cursor data=db.rawQuery("SELECT body FROM activity_source",null)) {
            while(data.moveToNext()){
                byte[] bytes=data.getString(0).getBytes(StandardCharsets.UTF_8);raw+=bytes.length;rows++;
                for(boolean dict:new boolean[]{false,true}) {
                    java.util.zip.Deflater deflater=new java.util.zip.Deflater(java.util.zip.Deflater.BEST_SPEED);
                    try{if(dict)deflater.setDictionary(dictionary);deflater.setInput(bytes);deflater.finish();int size=8;
                        while(!deflater.finished()){int count=deflater.deflate(output);assertTrue(count>0);size+=count;}
                        if(dict)withDictionary+=Math.min(bytes.length,size);else zlib+=Math.min(bytes.length,size);
                    }finally{deflater.end();}
                }
            }
        }
        return new JSONObject().put("rows",rows).put("rawBytes",raw).put("zlibWith8ByteEnvelope",zlib).put("dictionaryZlibWith8ByteEnvelope",withDictionary).put("bothCandidatesMillis",(System.nanoTime()-started)/1000000L);
    }
    private static JSONObject inspect(RemoteStore store,Context context,String name)throws Exception {
        JSONObject result=new JSONObject().put("file",name);SQLiteDatabase db=store.getReadableDatabase();
        for(String table:new String[]{"activity_source","activity_receipts","local_objects","outbox","remote_objects"})
            try(Cursor rows=db.rawQuery("SELECT COUNT(*),COALESCE(SUM(length(CAST(body AS BLOB))),0) FROM "+table,null)){assertTrue(rows.moveToFirst());result.put(table,new JSONObject().put("rows",rows.getLong(0)).put("bodyBytes",rows.getLong(1)));}
        for(String pragma:new String[]{"page_count","page_size","freelist_count"})try(Cursor row=db.rawQuery("PRAGMA "+pragma,null)){assertTrue(row.moveToFirst());result.put(pragma,row.getLong(0));}
        return result;
    }
    private JSONArray databaseFiles(Context context)throws Exception {
        JSONArray files=new JSONArray();for(String name:names)for(String suffix:new String[]{"","-wal","-shm"}){File file=new File(context.getDatabasePath(name).getPath()+suffix);if(file.exists())files.put(entry(file,context.getDataDir()));}return files;
    }
    private static JSONObject tree(File file,File root)throws Exception {
        JSONObject result=entry(file,root);if(file.isDirectory()&&!java.nio.file.Files.isSymbolicLink(file.toPath())){JSONArray children=new JSONArray();File[] entries=file.listFiles();if(entries!=null)for(File child:entries)children.put(tree(child,root));result.put("children",children);}return result;
    }
    private static JSONObject entry(File file,File root)throws Exception {
        return new JSONObject().put("path",root.toPath().relativize(file.toPath()).toString()).put("bytes",file.isDirectory()?0:file.length()).put("allocated",Os.lstat(file.getPath()).st_blocks*512L).put("symlink",java.nio.file.Files.isSymbolicLink(file.toPath()));
    }
}
