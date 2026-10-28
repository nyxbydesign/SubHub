package com.subhub.app.remote;

import static org.junit.Assert.*;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.os.Build;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.storage.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class StorageCrashAndroidTest {
    private static final String DATABASE="storage-crash.db",MARKER="storage-crash-ready.json",PROOF="storage-crash-proof.blob";
    @Test public void prepareUncommittedRewriteForProcessDeath()throws Exception {
        Assume.assumeTrue("prepare".equals(InstrumentationRegistry.getArguments().getString("storage_crash_phase")));
        Assume.assumeTrue(Build.HARDWARE.contains("ranchu")||Build.HARDWARE.contains("goldfish"));
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();assertFalse(context.getDatabasePath(DATABASE).exists());
        StorageCompressionAndroidTest.legacy(context,DATABASE,true,16);
        File proof=new File(context.getFilesDir(),PROOF);try(OutputStream out=new FileOutputStream(proof)){out.write(new byte[]{2,7,1,8,2,8});}
        DisposableCache.EditorLease editor=DisposableCache.editor(context.getCacheDir(),UUID.randomUUID().toString());
        File derived=new File(editor.directory,"derived.bin");try(OutputStream out=new FileOutputStream(derived)){out.write(new byte[4096]);}
        SQLiteDatabase db=SQLiteDatabase.openDatabase(context.getDatabasePath(DATABASE).getPath(),null,SQLiteDatabase.OPEN_READWRITE);db.enableWriteAheadLogging();
        String before=StorageCompressionAndroidTest.digest(db);db.beginTransaction();
        try {
            new RemoteStore(context,DATABASE).onUpgrade(db,3,4);db.setVersion(4);
            assertEquals(48,StoredPayloads.compressBatch(db,64));assertEquals(16,StorageCompressionAndroidTest.count(db,"activity_source","typeof(body)='blob'"));
            JSONObject ready=new JSONObject().put("database",DATABASE).put("logicalBefore",before).put("editor",editor.directory.getName()).put("proof",PROOF).put("transactionCommitted",false);
            try(FileOutputStream out=new FileOutputStream(new File(context.getFilesDir(),MARKER))){out.write(ready.toString().getBytes(StandardCharsets.UTF_8));out.getFD().sync();}
            System.out.println("STORAGE_CRASH_READY");long until=android.os.SystemClock.uptimeMillis()+20000;
            while(android.os.SystemClock.uptimeMillis()<until)Thread.sleep(100);
            fail("Host did not stop the owned fixture process before its deadline");
        }finally{db.endTransaction();db.close();editor.close();}
    }
    @Test public void verifyInterruptedRewriteAndDeadEditorRecovery()throws Exception {
        Assume.assumeTrue("verify".equals(InstrumentationRegistry.getArguments().getString("storage_crash_phase")));
        Assume.assumeTrue(Build.HARDWARE.contains("ranchu")||Build.HARDWARE.contains("goldfish"));
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();File marker=new File(context.getFilesDir(),MARKER);
        JSONObject ready=new JSONObject(new String(Files.readAllBytes(marker.toPath()),StandardCharsets.UTF_8));assertEquals(DATABASE,ready.getString("database"));
        try(SQLiteDatabase raw=SQLiteDatabase.openDatabase(context.getDatabasePath(DATABASE).getPath(),null,SQLiteDatabase.OPEN_READWRITE)) {
            assertEquals(3,raw.getVersion());assertEquals(ready.getString("logicalBefore"),StorageCompressionAndroidTest.digest(raw));
            assertEquals(0,StorageCompressionAndroidTest.count(raw,"activity_source","typeof(body)='blob'"));
        }
        try(RemoteStore reopened=new RemoteStore(context,DATABASE)) {
            assertEquals(4,reopened.getReadableDatabase().getVersion());assertEquals(ready.getString("logicalBefore"),StorageCompressionAndroidTest.digest(reopened.getReadableDatabase()));
            int n;do{n=reopened.compactStoredPayloads(7);}while(n>0);
            assertEquals(ready.getString("logicalBefore"),StorageCompressionAndroidTest.digest(reopened.getReadableDatabase()));
        }
        File dead=new File(new File(context.getCacheDir(),"remote-editor"),ready.getString("editor"));long until=android.os.SystemClock.uptimeMillis()+8000;
        while(dead.exists()&&android.os.SystemClock.uptimeMillis()<until)Thread.sleep(20);
        assertFalse("Startup must reclaim the abandoned derived editor cache",dead.exists());
        File proof=new File(context.getFilesDir(),PROOF);assertArrayEquals(new byte[]{2,7,1,8,2,8},Files.readAllBytes(proof.toPath()));
        assertTrue(context.deleteDatabase(DATABASE));assertTrue(marker.delete());assertTrue(proof.delete());
    }
}
