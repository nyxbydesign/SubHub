package com.subhub.app.storage;

import static org.junit.Assert.*;
import java.io.*;
import java.nio.channels.FileLock;
import java.util.*;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class DisposableCacheTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();
    private static final long NOW=1800000000000L, OLD=NOW-DisposableCache.MAX_IDLE_AGE-1000;
    @Test public void releasedImportsDisappearWhileUnrelatedFilesAreNeverCandidates()throws Exception {
        File root=temporary.newFolder();File proof=write(root,"proof.mp4",200,OLD);
        File imported=DisposableCache.temporary(root,"task-import-");assertTrue(imported.exists());
        DisposableCache.releaseTemporary(imported);DisposableCache.releaseTemporary(imported);
        assertFalse(imported.exists());DisposableCache.prune(root,NOW);assertTrue(proof.exists());
    }
    @Test public void liveImportsAndMultipleReaderPinsSurvivePressure()throws Exception {
        File root=temporary.newFolder();File file=DisposableCache.temporary(root,"artwork-");length(file,DisposableCache.IMPORT_BUDGET+1);assertTrue(file.setLastModified(OLD));
        try(DisposableCache.Pin first=DisposableCache.pin(file);DisposableCache.Pin second=DisposableCache.pin(file)) {
            DisposableCache.prune(root,NOW);assertTrue(file.exists());
            DisposableCache.releaseTemporary(file);assertTrue(file.exists());
        }
        assertFalse(file.exists());
    }
    @Test public void idleImportBudgetEvictsOldestKnownFilesAndHonorsAge()throws Exception {
        File root=temporary.newFolder();File first=write(root,"task-import-1.part",20L*1024*1024,NOW-3000);
        File second=write(root,"peer-import-2.part",20L*1024*1024,NOW-2000);
        File newest=write(root,"peer-artwork-3.part",20L*1024*1024,NOW-1000);
        File unrelated=write(root,"manual.part",20L*1024*1024,OLD);
        DisposableCache.prune(root,NOW);assertFalse(first.exists());assertFalse(second.exists());assertTrue(newest.exists());assertTrue(unrelated.exists());
        assertTrue(newest.setLastModified(OLD));DisposableCache.prune(root,NOW);assertFalse(newest.exists());
    }
    @Test public void clockRollbackDoesNotExpireOtherwiseSmallIdleFiles()throws Exception {
        File root=temporary.newFolder();File future=write(root,"artwork-8.part",10,NOW+1000);
        DisposableCache.prune(root,NOW);assertTrue(future.exists());
    }
    @Test public void editorCloseDeletesOnlyItsDerivedTree()throws Exception {
        File root=temporary.newFolder();File history=write(root,"history.db",100,OLD);String token=UUID.randomUUID().toString();File directory;
        try(DisposableCache.EditorLease editor=DisposableCache.editor(root,token)) {
            directory=editor.directory;write(directory,"derived.img",500,NOW);
            DisposableCache.startup(root,NOW);assertTrue(directory.isDirectory());
        }
        assertFalse(directory.exists());assertTrue(history.exists());
    }
    @Test public void editorDiscardWaitsForAnInFlightImport()throws Exception {
        File root=temporary.newFolder();DisposableCache.EditorLease editor=DisposableCache.editor(root,UUID.randomUUID().toString());
        File cache=new File(editor.directory,"cache");File imported=DisposableCache.temporary(cache,"artwork-");writeBytes(imported,new byte[]{1,2,3});
        editor.close();assertTrue(imported.exists());assertTrue(editor.directory.exists());
        DisposableCache.releaseTemporary(imported);assertFalse(editor.directory.exists());
    }
    @Test public void processStartupReclaimsAbandonedEditorsButKeepsAnExistingLock()throws Exception {
        File root=temporary.newFolder();File editors=new File(root,"remote-editor");assertTrue(editors.mkdir());
        File abandoned=new File(editors,UUID.randomUUID().toString());assertTrue(abandoned.mkdir());write(abandoned,"derived.img",1024,NOW);
        File busy=new File(editors,UUID.randomUUID().toString());assertTrue(busy.mkdir());
        try(RandomAccessFile owner=new RandomAccessFile(new File(busy,".storage-lease"),"rw");FileLock lock=owner.getChannel().lock()) {
            DisposableCache.startup(root,NOW);assertFalse(abandoned.exists());assertTrue(busy.exists());
        }
        DisposableCache.startup(root,NOW);assertFalse(busy.exists());
    }
    @Test public void invalidOwnersNeverCreateCacheDirectories()throws Exception {
        File root=temporary.newFolder();assertThrows(IOException.class,()->DisposableCache.temporary(root,"history-"));
        assertThrows(IOException.class,()->DisposableCache.editor(root,"../history"));assertArrayEquals(new String[0],root.list());
    }
    private static File write(File root,String name,long bytes,long time)throws IOException {File file=new File(root,name);length(file,bytes);assertTrue(file.setLastModified(time));return file;}
    private static void length(File file,long bytes)throws IOException {try(RandomAccessFile out=new RandomAccessFile(file,"rw")){out.setLength(bytes);}}
    private static void writeBytes(File file,byte[] bytes)throws IOException {try(OutputStream out=new FileOutputStream(file)){out.write(bytes);}}
}
