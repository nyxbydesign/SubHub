package com.subhub.app.people;

import android.content.Context;
import com.subhub.app.internal.*;
import com.subhub.app.settings.SharedPreferenceTestRestore;
import java.lang.reflect.*;
import java.util.*;

/** Owns one injected internal namespace and restores the exact application registry entry. */
final class InternalSubUiFixture implements AutoCloseable {
    private final Context app;
    private final Map<String,?> registry, tasksRegistry, ownerRegistry;
    private final Map<Context,InternalSubData> open;
    private final Set<Context> failed;
    private final InternalSubData original;
    private final boolean hadOriginal, wasFailed, originalPersistenceFailure;
    private final String originalToken;
    private final Field tokenField, persistenceField;
    private InternalVisibilityIdleFixture visibility;
    private boolean closed, brokerDrained, writerDrained;
    private android.database.sqlite.SQLiteDatabase closedDatabase;
    final InternalSubData data;
    private final java.io.File parent;
    @SuppressWarnings("unchecked") InternalSubUiFixture(Context context)throws Exception {
        app=context.getApplicationContext();
        registry=new LinkedHashMap<>(app.getSharedPreferences("subhub_internal_registry",0).getAll());
        tasksRegistry=new LinkedHashMap<>(app.getSharedPreferences("subhub_internal_tasks_registry",0).getAll());
        ownerRegistry=new LinkedHashMap<>(app.getSharedPreferences("subhub_internal_owner_data_registry",0).getAll());
        Field field=InternalSubData.class.getDeclaredField("OPEN");field.setAccessible(true);open=(Map<Context,InternalSubData>)field.get(null);
        Field failures=InternalSubData.class.getDeclaredField("FAILED");failures.setAccessible(true);failed=(Set<Context>)failures.get(null);
        tokenField=DummySubPreview.class.getDeclaredField("token");tokenField.setAccessible(true);originalToken=(String)tokenField.get(null);
        persistenceField=DummySubPreview.class.getDeclaredField("persistenceFailed");persistenceField.setAccessible(true);originalPersistenceFailure=persistenceField.getBoolean(null);
        parent=java.nio.file.Files.createTempDirectory(app.getCacheDir().toPath(),"internal-sub-ui-").toFile();
        FileInternalSubBackend backend=FileInternalSubBackend.createNew(parent,new InternalProfile("Dummy Sub","Owned internal UI fixture","","",0),InternalConfigurationStore.defaults());
        Constructor<InternalSubData> constructor=InternalSubData.class.getDeclaredConstructor(FileInternalSubBackend.class);constructor.setAccessible(true);data=constructor.newInstance(backend);
        synchronized(InternalSubData.class){hadOriginal=open.containsKey(app);original=open.get(app);wasFailed=failed.remove(app);open.put(app,data);}
        try {
            visibility = new InternalVisibilityIdleFixture(app, candidate -> candidate == data);
            persistenceField.setBoolean(null,false);
            if(!app.getSharedPreferences("subhub_internal_owner_data_registry",0).edit().clear().commit())
                throw new AssertionError("Fixture owner-data registry acknowledgement failed");
            data.initializeOwnerData(app,OwnerDataProvisioning.Decision.FIRST_INTERNAL_CREATE);
            if(!app.getSharedPreferences("subhub_internal_registry",0).edit().putString("provisioning","ready").commit())
                throw new AssertionError("Fixture registry acknowledgement failed");
        }catch(Throwable failure){
            try{close();}catch(Throwable cleanup){failure.addSuppressed(cleanup);}
            rethrow(failure);
        }
    }
    @FunctionalInterface interface RegistryRestore {
        void restore(android.content.SharedPreferences preferences, Map<String,?> original) throws Exception;
    }
    @Override public void close()throws Exception { closeWithRegistryRestore(SharedPreferenceTestRestore::restore); }
    void closeWithRegistryRestore(RegistryRestore restoration)throws Exception {
        if(closed)return;
        closed=true;
        OwnedFixtureCleanup cleanup=new OwnedFixtureCleanup();
        cleanup.attempt(()->{
            InternalOwnerDataBroker broker;
            try{broker=InternalOwnerDataBroker.attached(data);}
            catch(IllegalStateException neverAttached){brokerDrained=true;return;}
            broker.closeAfterDrain().get(10,java.util.concurrent.TimeUnit.SECONDS);brokerDrained=true;
        });
        cleanup.attempt(()->{
            Field writes=InternalSubData.class.getDeclaredField("writes");writes.setAccessible(true);
            java.util.concurrent.ExecutorService executor=(java.util.concurrent.ExecutorService)writes.get(data);
            executor.shutdown();
            writerDrained=executor.awaitTermination(10,java.util.concurrent.TimeUnit.SECONDS);
            if(!writerDrained)throw new AssertionError("Internal writes did not drain");
        });
        cleanup.attempt(()->{synchronized(InternalSubData.class){if(hadOriginal)open.put(app,original);else open.remove(app);}});
        cleanup.attempt(()->{synchronized(InternalSubData.class){if(wasFailed)failed.add(app);else failed.remove(app);}});
        cleanup.attempt(()->{if(visibility!=null)visibility.close();});
        cleanup.attempt(()->tokenField.set(null,originalToken));
        cleanup.attempt(()->persistenceField.setBoolean(null,originalPersistenceFailure));
        restoreRegistry(cleanup,restoration,"subhub_internal_registry",registry);
        restoreRegistry(cleanup,restoration,"subhub_internal_tasks_registry",tasksRegistry);
        restoreRegistry(cleanup,restoration,"subhub_internal_owner_data_registry",ownerRegistry);
        cleanup.attempt(()->{
            // Remove only this fixture's backend under the monitor; close its database outside it.
            Class<?> tasks=Class.forName("com.subhub.app.tasks.InternalTaskBackend");Field mapField=tasks.getDeclaredField("OPEN");mapField.setAccessible(true);
            Object backend;synchronized(tasks){backend=((Map<?,?>)mapField.get(null)).remove(data);}
            if(backend!=null){Field store=tasks.getDeclaredField("store");store.setAccessible(true);
                android.database.sqlite.SQLiteOpenHelper owned=(android.database.sqlite.SQLiteOpenHelper)store.get(backend);
                if(owned!=null){cleanup.attempt(()->closedDatabase=owned.getReadableDatabase());cleanup.attempt(owned::close);}}
        });
        cleanup.attempt(()->{
            if(!brokerDrained)throw new AssertionError("Owned broker did not acknowledge closure");
            Field brokers=InternalOwnerDataBroker.class.getDeclaredField("OPEN");brokers.setAccessible(true);
            Map<?,?> owners=(Map<?,?>)brokers.get(null);synchronized(owners){owners.remove(data);}
        });
        cleanup.attempt(()->{
            if(!writerDrained)throw new AssertionError("Retain owned files while a writer may still be running");
            try(java.util.stream.Stream<java.nio.file.Path> files=java.nio.file.Files.walk(parent.toPath())){
                for(java.nio.file.Path file:(Iterable<java.nio.file.Path>)files.sorted(Comparator.reverseOrder())::iterator)java.nio.file.Files.delete(file);
            }
        });
        cleanup.finish();
    }
    private void restoreRegistry(OwnedFixtureCleanup cleanup,RegistryRestore restoration,String name,Map<String,?> values) {
        final boolean[] restored={false};
        cleanup.attempt(()->{restoration.restore(app.getSharedPreferences(name,0),values);restored[0]=true;});
        // One bounded repair of this exact captured fixture snapshot. Preserve the first failure
        // even when the retry succeeds, and never let it skip another independent registry.
        if(!restored[0])cleanup.attempt(()->SharedPreferenceTestRestore.restore(app.getSharedPreferences(name,0),values));
    }
    void assertOriginalRestored()throws Exception {
        synchronized(InternalSubData.class){
            require(open.containsKey(app)==hadOriginal&&open.get(app)==original,"Exact original data singleton not restored");
            require(failed.contains(app)==wasFailed,"Original failure marker not restored");
        }
        require(Objects.equals(tokenField.get(null),originalToken),"Original route token not restored");
        require(persistenceField.getBoolean(null)==originalPersistenceFailure,"Original persistence flag not restored");
        if(visibility!=null)visibility.assertOriginalRestored();
        require(registry.equals(app.getSharedPreferences("subhub_internal_registry",0).getAll()),"Original profile registry not restored");
        require(tasksRegistry.equals(app.getSharedPreferences("subhub_internal_tasks_registry",0).getAll()),"Original Tasks registry not restored");
        require(ownerRegistry.equals(app.getSharedPreferences("subhub_internal_owner_data_registry",0).getAll()),"Original owner registry not restored");
    }
    void assertOwnedDisposed()throws Exception {
        require(brokerDrained&&writerDrained,"Owned worker and broker did not drain");
        require(closedDatabase==null||!closedDatabase.isOpen(),"Owned SQL handle remains open");
        require(!parent.exists(),"Owned namespace remains after cleanup");
        Class<?> tasks=Class.forName("com.subhub.app.tasks.InternalTaskBackend");Field backends=tasks.getDeclaredField("OPEN");backends.setAccessible(true);
        synchronized(tasks){require(!((Map<?,?>)backends.get(null)).containsKey(data),"Owned Tasks backend retained");}
        Field brokers=InternalOwnerDataBroker.class.getDeclaredField("OPEN");brokers.setAccessible(true);
        Map<?,?> owners=(Map<?,?>)brokers.get(null);synchronized(owners){require(!owners.containsKey(data),"Owned broker retained");}
    }
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static void rethrow(Throwable failure)throws Exception {
        if(failure instanceof Exception)throw (Exception)failure;
        if(failure instanceof Error)throw (Error)failure;
        throw new AssertionError(failure);
    }
}
