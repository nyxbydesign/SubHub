package com.subhub.app.remote;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.system.Os;
import android.system.OsConstants;
import com.subhub.app.appmode.RuntimeSessionIdentity;
import com.subhub.app.security.DeviceRolePreferences;
import com.subhub.app.security.SubFeaturePolicy;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import org.json.JSONObject;

/** Prospective retained-state adoption and a current independent persistence receipt, never user authentication. */
final class LinkingWitness {
    private static final Map<String,LinkingWitness> DOMAINS=new LinkedHashMap<>();
    private final Context context;
    private final String identity;
    private final File directory,marker;
    private final LinkingReceiptStore receipts;
    private final ReentrantLock gate=new ReentrantLock();
    private final ThreadLocal<Integer> entered=ThreadLocal.withInitial(()->0);
    private FileOutputStream lockStream;
    private OwnedDescriptor lockDescriptor;
    private FileLock fileLock;
    private boolean failed;
    private static final class AcknowledgedReservation {
        final State before;final long revision;final String command;
        AcknowledgedReservation(State before,long revision,String command){this.before=before;this.revision=revision;this.command=command;}
    }
    enum AuthorityUse { OBSERVATION, ACTION }
    private final ThreadLocal<AcknowledgedReservation> observingBefore=new ThreadLocal<>();
    private final ThreadLocal<State> authorityAnchor=new ThreadLocal<>();
    interface SqlWork<T>{T run(SQLiteDatabase db);}
    interface CheckedWork<T>{T run()throws Exception;}
    interface Observation<T>{SqlWork<T> observe();}
    private static final class AcknowledgedRejection extends Exception { }
    static boolean acknowledgedRejection(Throwable failure){for(Throwable note:failure.getSuppressed())if(note instanceof AcknowledgedRejection)return true;return false;}
    static void markAcknowledgedRejection(Throwable failure){failure.addSuppressed(new AcknowledgedRejection());}
    private enum Disposition { PRE_SQL, ROLLED_BACK, COMMITTED, UNCERTAIN }

    private LinkingWitness(Context context,RemoteStore store)throws Exception{
        this.context=context.getApplicationContext();identity=store.resolvedDatabaseFile().getPath();
        String name=LocalRelationshipAuthority.digest(identity);
        directory=new File(this.context.getNoBackupFilesDir(),"linking-"+name).getCanonicalFile();
        marker=new File(directory,"current.json");receipts=new LinkingReceiptStore(identity);
    }
    static LinkingWitness attach(Context context,RemoteStore store){
        outside(store);
        try{
            String identity=store.resolvedDatabaseFile().getPath();
            synchronized(DOMAINS){
                LinkingWitness witness=DOMAINS.get(identity);
                if(witness==null){witness=new LinkingWitness(context,store);DOMAINS.put(identity,witness);}
                store.attachedWitness=witness;return witness;
            }
        }catch(Exception failure){throw unavailable(failure);}
    }
    static LinkingWitness registered(RemoteStore store){
        outside(store);
        try{
            synchronized(DOMAINS){LinkingWitness existing=DOMAINS.get(store.resolvedDatabaseFile().getPath());if(existing!=null){store.attachedWitness=existing;return existing;}}
            // Every application-database writer participates even before the presentation adapter is installed.
            return store.isApplicationDatabase()?attach(store.applicationContext(),store):null;
        }catch(Exception failure){throw unavailable(failure);}
    }
    static LinkingWitness attachedWithoutIo(RemoteStore store){return store.attachedWitness;}
    static void outside(RemoteStore store){
        if(Thread.holdsLock(store)||Thread.holdsLock(SubFeaturePolicy.LOCK)||Thread.holdsLock(RuntimeSessionIdentity.LOCK)
                ||store.hasCurrentFormalTransaction())throw new IllegalStateException("Linking entry requires no outer SQL or authority lock");
    }
    static IllegalStateException unavailable(Throwable cause){return new IllegalStateException("Linking recovery unavailable",cause);}
    <T>T exclusive(RemoteStore store,CheckedWork<T> work){
        outside(store);gate.lock();
        try{
            if(entered.get()==0){
                if(!directory.isDirectory()&&!directory.mkdir())throw new java.io.IOException("Linking directory unavailable");
                if(lockStream==null){
                    syncDirectory(directory.getParentFile());
                    OwnedDescriptor opened=OwnedDescriptor.open(new File(directory,"operation.lock").getPath(),OsConstants.O_CREAT|OsConstants.O_RDWR|OsConstants.O_NOFOLLOW,0600);
                    boolean published=false;
                    try{FileOutputStream stream=new FileOutputStream(opened.fd());syncDirectory();lockDescriptor=opened;lockStream=stream;published=true;}
                    finally{if(!published)opened.close();}
                }
                FileChannel channel=lockStream.getChannel();fileLock=channel.lock();
            }
            entered.set(entered.get()+1);
            try{return work.run();}finally{entered.set(entered.get()-1);}
        }catch(RuntimeException failure){throw failure;}
        catch(Exception failure){throw unavailable(failure);}
        finally{
            try{if(entered.get()==0&&fileLock!=null){fileLock.release();fileLock=null;}}
            catch(Exception failure){failed=true;}
            gate.unlock();
        }
    }
    /** The already-shipped JNA runtime calls stable libc ABI functions; these flag constants are
     * public since API21. Ownership stays explicit because Android streams borrow supplied FDs. */
    public interface DescriptorOps extends com.sun.jna.Library {
        int open(String path,int flags,int mode);
        int fcntl(int fd,int command,int argument);
        int close(int fd);
    }
    private static final class DescriptorApi {
        static final DescriptorOps LIBC=com.sun.jna.Native.load("c",DescriptorOps.class);
    }
    private static final class OwnedDescriptor implements AutoCloseable {
        private android.os.ParcelFileDescriptor owner;
        private OwnedDescriptor(android.os.ParcelFileDescriptor owner){this.owner=owner;}
        FileDescriptor fd(){if(owner==null)throw new IllegalStateException("Descriptor closed");return owner.getFileDescriptor();}
        static OwnedDescriptor open(String path,int flags,int mode)throws Exception{
            DescriptorOps libc=DescriptorApi.LIBC;int raw=libc.open(path,flags,mode);
            if(raw<0)throw new android.system.ErrnoException("open",com.sun.jna.Native.getLastError());
            try{
                int before=libc.fcntl(raw,OsConstants.F_GETFD,0);
                if(before<0)throw new android.system.ErrnoException("fcntl(F_GETFD)",com.sun.jna.Native.getLastError());
                if(libc.fcntl(raw,OsConstants.F_SETFD,before|OsConstants.FD_CLOEXEC)<0)
                    throw new android.system.ErrnoException("fcntl(F_SETFD)",com.sun.jna.Native.getLastError());
                int actual=libc.fcntl(raw,OsConstants.F_GETFD,0);
                if(actual<0)throw new android.system.ErrnoException("fcntl(F_GETFD)",com.sun.jna.Native.getLastError());
                if((actual&OsConstants.FD_CLOEXEC)==0)throw new java.io.IOException("Close-on-exec was not acknowledged");
                return new OwnedDescriptor(android.os.ParcelFileDescriptor.adoptFd(raw));
            }catch(Exception failure){
                if(libc.close(raw)<0)failure.addSuppressed(new android.system.ErrnoException("close",com.sun.jna.Native.getLastError()));
                throw failure;
            }
        }
        @Override public void close()throws Exception{
            if(owner==null)return;android.os.ParcelFileDescriptor closing=owner;owner=null;
            int raw=closing.detachFd();
            if(DescriptorApi.LIBC.close(raw)<0)throw new android.system.ErrnoException("close",com.sun.jna.Native.getLastError());
        }
    }
    private static byte[] readBounded(FileDescriptor fd,int maximum)throws Exception{
        java.io.ByteArrayOutputStream output=new java.io.ByteArrayOutputStream();byte[] buffer=new byte[1024];int count;
        while((count=Os.read(fd,buffer,0,Math.min(buffer.length,maximum+1-output.size())))>0){
            output.write(buffer,0,count);if(output.size()>maximum)throw new java.io.IOException("Marker too large");
        }
        return output.toByteArray();
    }
    private void syncDirectory()throws Exception{syncDirectory(directory);}
    private static void syncDirectory(File directory)throws Exception{
        try(OwnedDescriptor opened=OwnedDescriptor.open(directory.getPath(),OsConstants.O_RDONLY|OsConstants.O_NOFOLLOW,0)){
            if(!OsConstants.S_ISDIR(Os.fstat(opened.fd()).st_mode))throw new java.io.IOException("Directory descriptor required");
            Os.fsync(opened.fd());
        }
    }
    private void writeMarker(JSONObject value)throws Exception{
        byte[] bytes=value.toString().getBytes(StandardCharsets.UTF_8);
        if(bytes.length>4096)throw new java.io.IOException("Linking marker too large");
        File temporary=new File(directory,"pending-"+UUID.randomUUID());
        boolean acknowledged=false;
        try{
            try(OwnedDescriptor opened=OwnedDescriptor.open(temporary.getPath(),OsConstants.O_CREAT|OsConstants.O_EXCL|OsConstants.O_WRONLY|OsConstants.O_NOFOLLOW,0600)){
                int offset=0;while(offset<bytes.length){int count=Os.write(opened.fd(),bytes,offset,bytes.length-offset);if(count<=0)throw new java.io.IOException("Incomplete marker write");offset+=count;}
                Os.fsync(opened.fd());
            }
            Os.rename(temporary.getPath(),marker.getPath());syncDirectory();
            try(OwnedDescriptor opened=OwnedDescriptor.open(marker.getPath(),OsConstants.O_RDONLY|OsConstants.O_NOFOLLOW,0)){
                if(!java.util.Arrays.equals(bytes,readBounded(opened.fd(),4096)))throw new java.io.IOException("Marker readback differs");
            }
            acknowledged=true;
        }finally{if(!acknowledged)failed=true;}
    }
    private JSONObject readMarker()throws Exception{
        if(!marker.exists())return null;
        byte[] bytes;
        try(OwnedDescriptor opened=OwnedDescriptor.open(marker.getPath(),OsConstants.O_RDONLY|OsConstants.O_NOFOLLOW,0)){bytes=readBounded(opened.fd(),4096);}
        JSONObject value=new JSONObject(new String(bytes,StandardCharsets.UTF_8));
        if(value.length()!=5||value.getInt("version")!=1||!value.getString("epoch").matches("[0-9a-f]{32}")
                ||value.getLong("revision")<1||!value.getString("command").matches("[0-9a-f]{64}")
                ||!"RESERVED".equals(value.getString("phase"))&&!"SEALED".equals(value.getString("phase")))throw new java.io.IOException("Invalid marker");
        return value;
    }
    private static JSONObject marker(String epoch,long revision,String command,String phase)throws Exception{
        return new JSONObject().put("version",1).put("epoch",epoch).put("revision",revision).put("command",command).put("phase",phase);
    }
    static void create(SQLiteDatabase db){
        db.execSQL("CREATE TABLE linking_state(id INTEGER PRIMARY KEY CHECK(id=1),epoch TEXT,revision INTEGER NOT NULL DEFAULT 0,"
                +"command TEXT,state_digest TEXT,init_digest TEXT,initialized INTEGER NOT NULL DEFAULT 0 CHECK(initialized IN(0,1)),ready_revision INTEGER NOT NULL DEFAULT 0,snapshot_scope TEXT)");
        db.execSQL("INSERT INTO linking_state(id) VALUES(1)");
        db.execSQL("CREATE TABLE linking_unlinks(relationship_id TEXT NOT NULL,generation TEXT NOT NULL,epoch TEXT NOT NULL,"
                +"revision INTEGER NOT NULL CHECK(revision>0),request_digest TEXT NOT NULL,outcome TEXT NOT NULL CHECK(outcome='UNLINKED'),"
                +"PRIMARY KEY(relationship_id,generation),FOREIGN KEY(relationship_id,generation) REFERENCES formal_relationships(id,generation))");
    }
    private static final class State{
        String epoch,command,digest,initial;long revision,readyRevision;boolean initialized;
    }
    private static State state(SQLiteDatabase db){
        try(Cursor row=db.rawQuery("SELECT epoch,revision,command,state_digest,init_digest,initialized,ready_revision FROM linking_state WHERE id=1",null)){
            if(!row.moveToFirst())throw new IllegalStateException("Linking state missing");
            State s=new State();s.epoch=row.getString(0);s.revision=row.getLong(1);s.command=row.getString(2);s.digest=row.getString(3);s.initial=row.getString(4);s.initialized=row.getInt(5)!=0;s.readyRevision=row.getLong(6);return s;
        }
    }
    private static String digest(SQLiteDatabase db){
        try{
            MessageDigest hash=MessageDigest.getInstance("SHA-256");
            String[][] tables={
                {"formal_authority","*","id"},{"formal_participants","*","ref"},{"formal_relationships","*","id,generation"},
                {"formal_governing_slots","*","sub_ref"},{"formal_operations","*","id"},
                {"linking_unlinks","*","relationship_id,generation"},
                {"peers","peer,generation,local_role,state","peer,generation"},
                {"invites","id,token_hash,expires,candidate,approved,consumed","id"},{"linking_state","snapshot_scope","id"}};
            for(String[] table:tables){
                hash.update(table[0].getBytes(StandardCharsets.US_ASCII));
                try(Cursor rows=db.rawQuery("SELECT "+table[1]+" FROM "+table[0]+" ORDER BY "+table[2],null)){
                    while(rows.moveToNext())for(int i=0;i<rows.getColumnCount();i++){
                        byte[] data=rows.isNull(i)?new byte[0]:rows.getType(i)==Cursor.FIELD_TYPE_BLOB?rows.getBlob(i):rows.getString(i).getBytes(StandardCharsets.UTF_8);
                        hash.update((byte)rows.getType(i));hash.update(java.nio.ByteBuffer.allocate(4).putInt(data.length).array());hash.update(data);
                    }
                }
            }
            StringBuilder value=new StringBuilder(64);for(byte b:hash.digest())value.append(Character.forDigit((b>>>4)&15,16)).append(Character.forDigit(b&15,16));return value.toString();
        }catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    private State read(RemoteStore store){synchronized(store){return state(store.getWritableDatabase());}}
    private void seal(State state)throws Exception{writeMarker(marker(state.epoch,state.revision,state.command,"SEALED"));}
    private State verify(RemoteStore store)throws Exception{
        if(failed)throw new java.io.IOException("Unacknowledged persistence failure");
        State s=read(store);LinkingReceiptStore.Receipt init=receipts.read(LinkingReceiptStore.INIT),ever=receipts.read(LinkingReceiptStore.EVER),current=receipts.read(LinkingReceiptStore.CURRENT);
        if(init==null||current==null||s.epoch==null||!init.epoch.equals(s.epoch)||!init.digest.equals(s.initial)
                ||!current.epoch.equals(s.epoch)||current.revision!=s.revision||!current.digest.equals(s.command))throw new java.io.IOException("Linking receipts and database differ");
        synchronized(store){if(!s.digest.equals(digest(store.getWritableDatabase())))throw new java.io.IOException("Retained authority differs from current receipt");}
        JSONObject record=readMarker();
        if(record==null){
            if(ever!=null)throw new java.io.IOException("Initialized marker missing");
            seal(s); // Exact committed first-initialization phase, protected by the CURRENT receipt.
        }else{
            if(!s.epoch.equals(record.getString("epoch"))||s.revision!=record.getLong("revision")||!s.command.equals(record.getString("command")))
                throw new java.io.IOException("Linking marker revision differs");
            if("RESERVED".equals(record.getString("phase")))seal(s); // Same committed command, never re-execute SQL.
        }
        if(ever!=null&&(!ever.epoch.equals(s.epoch)||!ever.digest.equals(s.initial)))throw new java.io.IOException("Initialization receipt differs");
        if(s.initialized&&ever==null){
            if(s.revision!=s.readyRevision)throw new java.io.IOException("Ever-usable receipt lost after initialization");
            receipts.write(LinkingReceiptStore.EVER,new LinkingReceiptStore.Receipt(s.epoch,0,s.initial));
        }
        return s;
    }
    private <T>T commit(RemoteStore store,State before,String command,SqlWork<T> work,boolean initialized)throws Exception{
        return commitObserved(store,before,command,()->work,initialized);
    }
    private <T>T commitObserved(RemoteStore store,State before,String command,Observation<T> observation,boolean initialized)throws Exception{
        long revision=Math.addExact(before.revision,1);
        writeMarker(marker(before.epoch,revision,command,"RESERVED"));
        receipts.write(LinkingReceiptStore.CURRENT,new LinkingReceiptStore.Receipt(before.epoch,revision,command));
        T result;State after;Disposition disposition=Disposition.PRE_SQL;
        try {
            SqlWork<T> work;
            synchronized(store){requireBefore(store.getReadableDatabase(),before);}
            observingBefore.set(new AcknowledgedReservation(before,revision,command));
            try{work=observation.observe();}finally{observingBefore.remove();}
            synchronized(SubFeaturePolicy.LOCK){synchronized(store){synchronized(RuntimeSessionIdentity.LOCK){
                SQLiteDatabase db=store.getWritableDatabase();if(db.inTransaction())throw new IllegalStateException("Independent linking transaction required");
                disposition=Disposition.UNCERTAIN;db.beginTransaction();boolean successful=false;Exception workFailure=null;
                try{
                    requireBefore(db,before);
                    result=work.run(db);
                    writeState(db,before,revision,command,initialized);
                    after=state(db);db.setTransactionSuccessful();successful=true;
                }catch(Exception failure){workFailure=failure;throw failure;}finally{
                    // Only a normally acknowledged end establishes rollback/commit. A before-image after
                    // an exception cannot prove an attempted grant was never durably observed elsewhere.
                    try{db.endTransaction();}catch(RuntimeException completionFailure){if(workFailure!=null)completionFailure.addSuppressed(workFailure);throw completionFailure;}
                    disposition=successful?Disposition.COMMITTED:Disposition.ROLLED_BACK;
                }
            }}}
        } catch(Exception rejected) {
            // A rejected command consumes its reservation without changing authority. If rollback or this
            // acknowledgement is uncertain, the independent receipt keeps every new authority path closed.
            if(disposition==Disposition.PRE_SQL||disposition==Disposition.ROLLED_BACK){
                try { acknowledgeRejected(store,before,revision,command);markAcknowledgedRejection(rejected); }
                catch(Exception uncertain) { failed=true;rejected.addSuppressed(uncertain); }
            }else failed=true;
            throw rejected;
        }
        seal(after);
        if(authorityAnchor.get()!=null)authorityAnchor.set(after);
        return result;
    }
    private static void requireBefore(SQLiteDatabase db,State before)throws java.io.IOException{
        State actual=state(db);
        if(actual.revision!=before.revision||actual.epoch!=null&&!actual.epoch.equals(before.epoch)
                ||before.digest!=null&&!before.digest.equals(digest(db)))throw new java.io.IOException("Authority changed before commit");
    }
    private static void writeState(SQLiteDatabase db,State before,long revision,String command,boolean initialized){
        String stateDigest=digest(db);
        db.execSQL("UPDATE linking_state SET epoch=?,revision=?,command=?,state_digest=?,init_digest=?,initialized=?,ready_revision=? WHERE id=1",
                new Object[]{before.epoch,revision,command,stateDigest,before.initial,initialized?1:0,before.readyRevision==0&&initialized?revision:before.readyRevision});
    }
    private void acknowledgeRejected(RemoteStore store,State before,long revision,String command)throws Exception{
        State after;
        synchronized(SubFeaturePolicy.LOCK){synchronized(store){synchronized(RuntimeSessionIdentity.LOCK){
            SQLiteDatabase db=store.getWritableDatabase();db.beginTransaction();
            try{
                requireBefore(db,before);writeState(db,before,revision,command,before.initialized);after=state(db);
                db.setTransactionSuccessful();
            }finally{db.endTransaction();}
        }}}
        seal(after);
    }
    private void ensure(RemoteStore store)throws Exception{
        if(failed)throw new java.io.IOException("Unacknowledged persistence failure");
        State s=read(store);LinkingReceiptStore.Receipt init=receipts.read(LinkingReceiptStore.INIT);
        if(s.epoch==null){
            LinkingReceiptStore.Receipt ever=receipts.read(LinkingReceiptStore.EVER),current=receipts.read(LinkingReceiptStore.CURRENT);
            if(ever!=null||current!=null)throw new java.io.IOException("Retained initialization history is incomplete");
            String baseline;synchronized(store){baseline=digest(store.getWritableDatabase());}
            if(init==null){
                if(marker.exists())throw new java.io.IOException("Marker without initialization receipt");
                String epoch=UUID.randomUUID().toString().replace("-","");
                init=receipts.write(LinkingReceiptStore.INIT,new LinkingReceiptStore.Receipt(epoch,0,baseline));
            }else if(!init.digest.equals(baseline))throw new java.io.IOException("Initialization baseline differs");
            s.epoch=init.epoch;s.initial=init.digest;s.digest=baseline;
            String command=LocalRelationshipAuthority.digest("initialize:"+s.epoch+":"+baseline);
            commit(store,s,command,db->{FormalRelationshipStore.migrate(db);return null;},false);
        }
        s=verify(store);
        while(!s.initialized){
            boolean complete;
            synchronized(store){complete=FormalRelationshipStore.number(store.getWritableDatabase(),"migration_complete")==1;}
            if(!complete){
                String command=LocalRelationshipAuthority.digest("adopt:"+s.epoch+":"+s.revision);
                commit(store,s,command,db->{FormalRelationshipStore.migrate(db);return null;},false);
            }else{
                DeviceRolePreferences.Mode mode=DeviceRolePreferences.get(context);
                String command=LocalRelationshipAuthority.digest("participants:"+s.epoch+":"+mode.name());
                commit(store,s,command,db->{
                    if(mode!=DeviceRolePreferences.Mode.SUB_ONLY)FormalRelationshipStore.local(db,"DOM");
                    if(mode!=DeviceRolePreferences.Mode.DOM_ONLY)FormalRelationshipStore.local(db,"SUB");
                    FormalRelationshipStore.changed(db);return null;
                },true);
            }
            s=verify(store);
        }
    }
    <T>T readCurrent(RemoteStore store,CheckedWork<T> work){
        return exclusive(store,()->{ensure(store);return work.run();});
    }
    <T>T mutate(RemoteStore store,String kind,String original,SqlWork<T> work){
        return mutateObserved(store,kind,original,()->work);
    }
    <T>T mutateObserved(RemoteStore store,String kind,String original,Observation<T> observation){
        return exclusive(store,()->{
            if(observingBefore.get()!=null)throw new IllegalStateException("No nested authority command during authentication observation");
            ensure(store);State before=verify(store);
            String command=LocalRelationshipAuthority.digest(kind+":"+before.epoch+":"+(before.revision+1)+":"+original);
            return commitObserved(store,before,command,observation,true);
        });
    }
    /** Existing authority entry points may run beneath legacy monitors only inside this thread's
     * already verified gate. A pending reservation admits its unchanged before-image solely for
     * fresh authentication observation, never a snapshot or absence proof. */
    <T>T readAuthority(RemoteStore store,AuthorityUse use,CheckedWork<T> work){
        if(use==AuthorityUse.ACTION&&observingBefore.get()!=null)throw new IllegalStateException("Reserved continuity permits authentication observation only");
        boolean held=Thread.holdsLock(store)||Thread.holdsLock(SubFeaturePolicy.LOCK)||Thread.holdsLock(RuntimeSessionIdentity.LOCK)||store.hasCurrentFormalTransaction();
        if(held){
            State expected=authorityAnchor.get();
            if(failed||!gate.isHeldByCurrentThread()||expected==null||store.hasCurrentFormalTransaction()
                    ||Thread.holdsLock(RuntimeSessionIdentity.LOCK)&&!Thread.holdsLock(store))throw new IllegalStateException("Current continuity gate required");
            synchronized(store){requireAnchor(store.openedFormalDatabase(),expected);}
            try{return work.run();}catch(RuntimeException failure){throw failure;}catch(Exception failure){throw unavailable(failure);}
        }
        return exclusive(store,()->{
            if(failed)throw new java.io.IOException("Unacknowledged persistence failure");
            AcknowledgedReservation reservation=observingBefore.get();State expected;
            if(reservation!=null){
                expected=reservation.before;
                if(reservation.revision!=Math.addExact(expected.revision,1)||reservation.command==null)throw new IllegalStateException("Invalid acknowledged reservation");
                synchronized(store){requireBefore(store.getReadableDatabase(),expected);}
            }else if(authorityAnchor.get()!=null){
                expected=authorityAnchor.get();synchronized(store){requireAnchor(store.getReadableDatabase(),expected);}
            }else{ensure(store);expected=verify(store);}
            State previous=authorityAnchor.get();authorityAnchor.set(expected);
            try{return work.run();}finally{if(previous==null)authorityAnchor.remove();}
        });
    }
    private static void requireAnchor(SQLiteDatabase db,State expected){
        State actual=state(db);
        if(actual.revision!=expected.revision||!java.util.Objects.equals(actual.epoch,expected.epoch)
                ||!java.util.Objects.equals(actual.command,expected.command)||!java.util.Objects.equals(actual.digest,expected.digest))
            throw new IllegalStateException("Continuity anchor changed");
    }
    <T>T readRetained(RemoteStore store,CheckedWork<T> work){return exclusive(store,work);}
    boolean consistent(RemoteStore store){
        try{return exclusive(store,()->{verify(store);return true;});}catch(RuntimeException unavailable){return false;}
    }
    static String transactionRevision(SQLiteDatabase db){State state=state(db);return "fr1:"+state.epoch+":"+state.revision;}
    static String snapshotScope(SQLiteDatabase db){try(Cursor row=db.rawQuery("SELECT snapshot_scope FROM linking_state WHERE id=1",null)){if(!row.moveToFirst())throw new IllegalStateException("Linking state missing");return row.getString(0);}}
    String revision(RemoteStore store){
        return readCurrent(store,()->{State state=read(store);return "fr1:"+state.epoch+":"+state.revision;});
    }
    void cleanupOwnedFixture(RemoteStore store){
        FormalRelationshipCoordinator.requireOwnedFixture(store);
        exclusive(store,()->{
            receipts.deleteOwnedFixtureReceipts();
            if(marker.exists()&&!marker.delete())throw new java.io.IOException("Fixture marker cleanup failed");
            File[] files=directory.listFiles();if(files!=null)for(File file:files){if(file.getName().startsWith("pending-")&&!file.delete())throw new java.io.IOException("Fixture temporary cleanup failed");}
            return null;
        });
        gate.lock();try{
            if(lockStream!=null){lockStream.close();lockStream=null;}
            if(lockDescriptor!=null){lockDescriptor.close();lockDescriptor=null;}
            File lock=new File(directory,"operation.lock");if(lock.exists()&&!lock.delete())throw new java.io.IOException("Fixture lock cleanup failed");
            if(directory.exists()&&!directory.delete())throw new java.io.IOException("Fixture directory cleanup failed");
            synchronized(DOMAINS){DOMAINS.remove(identity);}
        }catch(Exception failure){throw unavailable(failure);}finally{gate.unlock();}
    }
}
