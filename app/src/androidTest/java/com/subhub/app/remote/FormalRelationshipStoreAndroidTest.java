package com.subhub.app.remote;

import static org.junit.Assert.*;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.people.People;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.security.DeviceRolePreferences;
import com.subhub.app.security.KeyholderProvider;
import com.subhub.app.settings.SettingsRepository;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public final class FormalRelationshipStoreAndroidTest extends FormalRelationshipFixture {
    @Test public void parsedPendingClaimHasNoAuthenticatedDomAndReservesSlot() {
        PairingInvite invite=PairingInvite.create(DOM,"fixture",1000); store.acceptInvite(invite,"Claimed Dom");
        try(Cursor row=db().rawQuery("SELECT claimed_endpoint,dom_ref,sub_ref FROM formal_relationships",null)) {
            assertTrue(row.moveToFirst()); assertEquals(DOM,row.getString(0)); assertTrue(row.isNull(1)); assertNotNull(row.getString(2));
        }
        assertEquals(1,count("formal_governing_slots"));
        assertThrows(SecurityException.class,()->coordinator().internalSnapshot());
    }
    @Test public void callbacksAreCommittedUnlockedAndFailureCannotUndoOutcome() {
        PairingInvite invite=PairingInvite.create(DOM,"fixture",1000); store.acceptInvite(invite,"Dom");
        RemoteStore observer=another();
        store.onActivated(peer->{assertFalse(Thread.holdsLock(store));assertEquals(RemoteStore.State.ACTIVE,observer.peer(DOM,invite.id).state);throw new IllegalStateException("observer failure");});
        assertThrows(IllegalStateException.class,()->store.activate(DOM,invite.id));
        assertEquals(RemoteStore.State.ACTIVE,store.peer(DOM,invite.id).state);
        store.onRevoked((p,g)->{assertFalse(Thread.holdsLock(store));assertEquals(RemoteStore.State.REVOKED,observer.peer(p,g).state);throw new IllegalStateException("observer failure");});
        assertThrows(IllegalStateException.class,()->store.revoke(DOM,invite.id));
        assertEquals(0,count("formal_governing_slots"));
    }
    @Test public void rollbackAndOuterLocksNeverEmitPrematureCallbacks() {
        PairingInvite invite=PairingInvite.create(DOM,"fixture",1000);store.acceptInvite(invite,"Dom");
        int[] callbacks={0};store.onActivated(peer->callbacks[0]++);store.onRevoked((p,g)->callbacks[0]++);
        synchronized(store){assertThrows(IllegalStateException.class,()->store.activate(DOM,invite.id));assertThrows(IllegalStateException.class,()->store.cancelInvite(invite.id));assertThrows(IllegalStateException.class,()->store.revoke(DOM,invite.id));}
        db().beginTransaction();try{assertThrows(IllegalStateException.class,()->store.activate(DOM,invite.id));assertThrows(IllegalStateException.class,()->store.revoke(DOM,invite.id));assertThrows(IllegalStateException.class,()->store.cancelInvite(invite.id));}finally{db().endTransaction();}
        db().execSQL("CREATE TRIGGER fail_activation BEFORE UPDATE ON formal_relationships BEGIN SELECT RAISE(ABORT,'owned failure'); END");
        assertThrows(android.database.sqlite.SQLiteException.class,()->store.activate(DOM,invite.id));
        assertEquals(RemoteStore.State.PENDING,store.peer(DOM,invite.id).state);assertEquals(0,callbacks[0]);
        db().execSQL("DROP TRIGGER fail_activation");
        store.cancelInvite(invite.id);assertEquals(1,callbacks[0]);assertEquals(0,count("formal_governing_slots"));
    }
    @Test public void twoHandlesCannotCommitTwoPendingGoverningDoms() throws Exception {
        RemoteStore second=another();PairingInvite a=PairingInvite.create(DOM,"fixture",1000),b=PairingInvite.create(SUB,"fixture",1000);
        ExecutorService workers=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);
        try {
            Future<Boolean> one=workers.submit(()->accept(store,a,start)),two=workers.submit(()->accept(second,b,start));start.countDown();
            assertNotEquals(one.get(),two.get());assertEquals(1,count("peers"));assertEquals(1,count("formal_governing_slots"));
        } finally {workers.shutdownNow();}
    }
    private static boolean accept(RemoteStore target,PairingInvite invite,CountDownLatch start)throws Exception {
        start.await();try{target.acceptInvite(invite,"Claim");return true;}catch(SecurityException|android.database.sqlite.SQLiteConstraintException rejected){return false;}
    }
}

/** Private preference namespaces, real local PIN and exact global restoration; no user credentials or grants. */
abstract class FormalRelationshipFixture {
    static final String DOM=repeat('a'),SUB=repeat('b');
    Context target,context;String prefix,database;RemoteStore store;KeyholderProvider originalProvider;
    private boolean originalMode;private Field applicationField;private Object originalApplication;
    final List<RemoteStore> handles=new ArrayList<>();final List<String> names=new ArrayList<>();
    @Before public void setupFormalFixture() throws Exception {
        target=InstrumentationRegistry.getInstrumentation().getTargetContext();prefix="formal-fixture-"+UUID.randomUUID()+"-";
        originalProvider=People.keyholder();originalMode=ControllerPinManager.isDomModeActive();
        applicationField=ControllerPinManager.class.getDeclaredField("application");applicationField.setAccessible(true);originalApplication=applicationField.get(null);
        context=new ContextWrapper(target){@Override public Context getApplicationContext(){return this;}@Override public SharedPreferences getSharedPreferences(String name,int mode){return target.getSharedPreferences(prefix+name,mode);}};
        context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME,0).edit().putString(DeviceRolePreferences.KEY_MODE,DeviceRolePreferences.Mode.BOTH.name()).commit();
        RemoteStore owned=FormalRelationshipCoordinator.createOwnedFixture(context);database=owned.getDatabaseName();handles.add(owned);names.add(database);store=owned;
        beforeOpen();store=open(database);store.migrateFormalRelationships();
        People.installKeyholder(new RemoteKeyholder(context,store,new RemoteSecrets(context,"owned-secrets","subhub.test.formal."+prefix)));
        assertTrue(ControllerPinManager.setPin(context,"624871"));assertTrue(ControllerPinManager.verify(context,"624871"));
    }
    void beforeOpen() throws Exception { }
    RemoteStore open(String name){if(!names.contains(name))names.add(name);RemoteStore result=new RemoteStore(context,name);handles.add(result);return result;}
    RemoteStore another(){return open(database);}
    SQLiteDatabase db(){return store.getWritableDatabase();}
    long count(String table){try(Cursor row=db().rawQuery("SELECT COUNT(*) FROM "+table,null)){assertTrue(row.moveToFirst());return row.getLong(0);}}
    FormalRelationshipCoordinator coordinator(){return new FormalRelationshipCoordinator(context,store);}
    static String repeat(char c){return new String(new char[64]).replace('\0',c);}
    @After public void restoreFormalFixture() throws Exception {
        try {
            if(originalProvider!=null)People.installKeyholder(originalProvider);
            if(originalMode)ControllerPinManager.enterDomMode();else ControllerPinManager.enterSubMode();
            if(applicationField!=null)applicationField.set(null,originalApplication);
        } finally {
            for(RemoteStore handle:handles)handle.close();
            if(store!=null)FormalRelationshipCoordinator.releaseOwnedFixture(store);
            for(String name:names){assertTrue(name.equals(database)||name.startsWith(prefix));target.deleteDatabase(name);}
            if(prefix!=null)for(String name:new String[]{SettingsRepository.PREFERENCES_NAME,"subhub_controller_auth","owned-secrets","subhub_local_feature_consent"})
                assertTrue(target.getSharedPreferences(prefix+name,0).edit().clear().commit());
        }
    }
}
