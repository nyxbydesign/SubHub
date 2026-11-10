package com.subhub.app.storage;

import static org.junit.Assert.*;
import android.content.Context;
import android.os.Build;
import android.system.Os;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class DisposableCacheAndroidTest {
    private Context context;private File root,protectedFile;
    @Before public void setup()throws Exception {
        Assume.assumeTrue(Build.HARDWARE.contains("ranchu")||Build.HARDWARE.contains("goldfish"));
        context=InstrumentationRegistry.getInstrumentation().getTargetContext();root=new File(context.getCacheDir(),"storage-cache-fixture-"+UUID.randomUUID());assertTrue(root.mkdir());
        protectedFile=new File(context.getFilesDir(),"storage-protected-"+UUID.randomUUID()+".blob");try(OutputStream out=new FileOutputStream(protectedFile)){out.write(new byte[]{8,6,7,5,3,0,9});}
    }
    @After public void cleanup()throws Exception {if(root!=null)remove(root);if(protectedFile!=null)assertTrue(protectedFile.delete());}
    @Test public void physicalIdleCacheBytesFallWhileInUseImportsAndHistoryStayIntact()throws Exception {
        long now=System.currentTimeMillis();
        File active=DisposableCache.temporary(root,"artwork-");write(active,10L*1024*1024);assertTrue(active.setLastModified(now-DisposableCache.MAX_IDLE_AGE-1000));
        for(int i=1;i<=3;i++){File file=new File(root,"task-import-"+i+".part");write(file,20L*1024*1024);assertTrue(file.setLastModified(now-4000+i*1000));}
        File unknown=new File(root,"retained-proof.blob");write(unknown,16384);long before=allocated(root);
        long expected=allocated(new File(root,"task-import-1.part"))+allocated(new File(root,"task-import-2.part"));
        DisposableCache.prune(root,now);long after=allocated(root);
        assertTrue(active.isFile());assertTrue(unknown.isFile());assertTrue(protectedFile.isFile());
        assertArrayEquals(new byte[]{8,6,7,5,3,0,9},Files.readAllBytes(protectedFile.toPath()));
        JSONObject report=new JSONObject().put("fixtureOnly",true).put("beforeAllocated",before).put("afterAllocated",after).put("reclaimedAllocated",before-after).put("expectedDeletedAllocated",expected).put("activeImportPreserved",true).put("unknownCacheAndProtectedHistoryPreserved",true);
        try(Writer writer=new OutputStreamWriter(new FileOutputStream(new File(context.getFilesDir(),"storage-cache-result.json")),StandardCharsets.UTF_8)){writer.write(report.toString(2));}
        assertEquals(expected,before-after);DisposableCache.releaseTemporary(active);assertFalse(active.exists());System.out.println("STORAGE_CACHE "+report);
    }
    @Test public void cleanupRefusesEditorSymlinkTraversal()throws Exception {
        File outside=new File(context.getCacheDir(),"storage-outside-"+UUID.randomUUID());assertTrue(outside.mkdir());File keep=new File(outside,"keep");write(keep,32);
        File editors=new File(root,"remote-editor");assertTrue(editors.mkdir());File linked=new File(editors,UUID.randomUUID().toString());Os.symlink(outside.getPath(),linked.getPath());
        try {DisposableCache.startup(root,System.currentTimeMillis());assertTrue(keep.isFile());assertTrue(Files.isSymbolicLink(linked.toPath()));}
        finally {Files.delete(linked.toPath());remove(outside);}
    }
    private static long allocated(File file)throws Exception {long sum=file.isFile()?Os.lstat(file.getPath()).st_blocks*512L:0;File[] children=file.listFiles();if(children!=null)for(File child:children)sum+=allocated(child);return sum;}
    private static void write(File file,long bytes)throws Exception {try(OutputStream out=new BufferedOutputStream(new FileOutputStream(file))){byte[] buffer=new byte[65536];java.util.Arrays.fill(buffer,(byte)91);while(bytes>0){int n=(int)Math.min(buffer.length,bytes);out.write(buffer,0,n);bytes-=n;}}}
    private static void remove(File file)throws Exception {if(Files.isSymbolicLink(file.toPath())){Files.delete(file.toPath());return;}File[] children=file.listFiles();if(children!=null)for(File child:children)remove(child);assertTrue(file.delete());}
}
