package com.subhub.app.remote;

import static org.junit.Assert.*;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

@RunWith(AndroidJUnit4.class)
public class RemoteStoreAndroidTest {
    private static final String DOM = repeat('a'), SUB = repeat('b'), SUB2 = repeat('c');
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final List<String> databases = new ArrayList<>();
    private final List<RemoteStore> stores = new ArrayList<>();
    private RemoteStore dom, sub;
    private String generation;

    @Before public void setup() {
        dom = store(); sub = store(); generation = pair(dom,sub,SUB);
    }
    @After public void cleanup() {
        for (RemoteStore store : stores) store.close();
        for (String name : databases) context.deleteDatabase(name);
    }

    @Test public void acknowledgementAdvancesOnlyAfterDurableReceipt() throws Exception {
        sub.publish(DOM,generation,RemoteProtocol.Stream.STATS,"summary",new JSONObject().put("protectedSeconds",12),false,1000);
        RemoteProtocol.Batch sent = sub.nextBatch(DOM,generation,RemoteProtocol.Stream.STATS);
        assertEquals(0,sent.after);
        RemoteProtocol.Ack ack = dom.receive(SUB,sent);
        assertEquals(0,sub.nextBatch(DOM,generation,RemoteProtocol.Stream.STATS).after);
        sub.acknowledge(DOM,ack);
        RemoteProtocol.Batch next = sub.nextBatch(DOM,generation,RemoteProtocol.Stream.STATS);
        assertEquals(1,next.after); assertTrue(next.changes.isEmpty());
        assertEquals(12,dom.mirrored(SUB,generation,RemoteProtocol.Stream.STATS,"summary").getLong("protectedSeconds"));
    }

    @Test public void lostAckAndNewerCoalescedUpdatesAreSafe() throws Exception {
        sub.publish(DOM,generation,RemoteProtocol.Stream.STATS,"summary",new JSONObject().put("count",1),false,1000);
        RemoteProtocol.Batch first = sub.nextBatch(DOM,generation,RemoteProtocol.Stream.STATS);
        dom.receive(SUB,first);
        sub.publish(DOM,generation,RemoteProtocol.Stream.STATS,"summary",new JSONObject().put("count",2),false,1001);
        RemoteProtocol.Batch retry = sub.nextBatch(DOM,generation,RemoteProtocol.Stream.STATS);
        sub.acknowledge(DOM,dom.receive(SUB,retry));
        dom.receive(SUB,first);
        assertEquals(2,dom.mirrored(SUB,generation,RemoteProtocol.Stream.STATS,"summary").getLong("count"));
    }

    @Test public void updateDuringOutstandingAckIsNotLost() throws Exception {
        sub.publish(DOM,generation,RemoteProtocol.Stream.STATS,"summary",new JSONObject().put("count",1),false,1000);
        RemoteProtocol.Batch first = sub.nextBatch(DOM,generation,RemoteProtocol.Stream.STATS);
        RemoteProtocol.Ack ack = dom.receive(SUB,first);
        sub.publish(DOM,generation,RemoteProtocol.Stream.STATS,"summary",new JSONObject().put("count",2),false,1001);
        sub.acknowledge(DOM,ack);
        RemoteProtocol.Batch next = sub.nextBatch(DOM,generation,RemoteProtocol.Stream.STATS);
        assertEquals(1,next.after); assertEquals(2,next.through);
        assertEquals(2,new JSONObject(next.changes.get(0).body).getInt("count"));
    }

    @Test public void duplicateAndDeletionSurviveDatabaseReopen() throws Exception {
        sub.publish(DOM,generation,RemoteProtocol.Stream.STATS,"summary",new JSONObject().put("count",1),false,1000);
        RemoteProtocol.Batch batch = sub.nextBatch(DOM,generation,RemoteProtocol.Stream.STATS);
        dom.receive(SUB,batch); dom.receive(SUB,batch);
        assertEquals(1,dom.mirror(SUB,generation,RemoteProtocol.Stream.STATS).size());
        String name = databases.get(0); dom.close();
        dom = new RemoteStore(context,name); stores.add(dom);
        dom.receive(SUB,batch);
        sub.acknowledge(DOM,new RemoteProtocol.Ack(batch));
        sub.publish(DOM,generation,RemoteProtocol.Stream.STATS,"summary",new JSONObject(),true,1001);
        RemoteProtocol.Batch deletion = sub.nextBatch(DOM,generation,RemoteProtocol.Stream.STATS);
        sub.acknowledge(DOM,dom.receive(SUB,deletion));
        assertNull(dom.mirrored(SUB,generation,RemoteProtocol.Stream.STATS,"summary"));
    }

    @Test public void authorityAndGenerationCannotBeSelectedByPayload() {
        assertThrows(SecurityException.class, () -> sub.publish(DOM,generation,RemoteProtocol.Stream.CONFIGURATION,"active",new JSONObject(),false,1000));
        RemoteProtocol.Batch wrongRole = new RemoteProtocol.Batch(generation,RemoteProtocol.Stream.STATS,0,1,
                Collections.singletonList(new RemoteProtocol.Change(1,"summary","{}",false,1000)));
        assertThrows(SecurityException.class, () -> sub.receive(DOM,wrongRole));
        RemoteProtocol.Batch wrongGeneration = new RemoteProtocol.Batch("other",RemoteProtocol.Stream.STATS,0,1,
                Collections.singletonList(new RemoteProtocol.Change(1,"summary","{}",false,1000)));
        assertThrows(SecurityException.class, () -> dom.receive(SUB,wrongGeneration));
    }

    @Test public void multipleSubsHaveIndependentMirrorsAndAcknowledgements() throws Exception {
        RemoteStore secondSub = store(); String secondGeneration = pair(dom,secondSub,SUB2);
        sub.publish(DOM,generation,RemoteProtocol.Stream.STATS,"summary",new JSONObject().put("count",1),false,1000);
        secondSub.publish(DOM,secondGeneration,RemoteProtocol.Stream.STATS,"summary",new JSONObject().put("count",99),false,1000);
        RemoteProtocol.Batch first = sub.nextBatch(DOM,generation,RemoteProtocol.Stream.STATS);
        sub.acknowledge(DOM,dom.receive(SUB,first));
        dom.receive(SUB2,secondSub.nextBatch(DOM,secondGeneration,RemoteProtocol.Stream.STATS));
        assertEquals(1,dom.mirrored(SUB,generation,RemoteProtocol.Stream.STATS,"summary").getInt("count"));
        assertEquals(99,dom.mirrored(SUB2,secondGeneration,RemoteProtocol.Stream.STATS,"summary").getInt("count"));
        assertEquals(0,secondSub.nextBatch(DOM,secondGeneration,RemoteProtocol.Stream.STATS).after);
    }

    @Test public void subCannotGainAnotherDomWithoutRevocation() {
        PairingInvite other = PairingInvite.create(SUB2,"ticket",1000);
        assertThrows(android.database.sqlite.SQLiteConstraintException.class, () -> sub.acceptInvite(other,"Other Dom"));
        sub.revoke(DOM,generation);
        sub.acceptInvite(other,"Other Dom"); sub.activate(SUB2,other.id);
        assertTrue(sub.isRemotelyControlled());
        assertEquals(2,sub.peers(true).size());
    }

    @Test public void revokedPairCannotSendAndArchivedHistoryRemains() throws Exception {
        sub.publish(DOM,generation,RemoteProtocol.Stream.STATS,"summary",new JSONObject().put("count",5),false,1000);
        RemoteProtocol.Batch batch = sub.nextBatch(DOM,generation,RemoteProtocol.Stream.STATS);
        dom.receive(SUB,batch); dom.revoke(SUB,generation);
        assertThrows(SecurityException.class, () -> dom.receive(SUB,batch));
        assertEquals(5,dom.mirrored(SUB,generation,RemoteProtocol.Stream.STATS,"summary").getInt("count"));
        assertEquals(RemoteStore.State.REVOKED,dom.peer(SUB,generation).state);
    }

    @Test public void invitationRequiresMatchingSecretAndCandidateAndCannotBeReused() {
        PairingInvite invite = PairingInvite.create(DOM,"ticket",1000); dom.saveInvite(invite);
        assertThrows(SecurityException.class, () -> dom.bindInvite(invite.id,SUB2,"wrong",1001));
        dom.bindInvite(invite.id,SUB2,invite.tokenHash(),1001);
        assertThrows(SecurityException.class, () -> dom.bindInvite(invite.id,SUB,invite.tokenHash(),1001));
        dom.approveInvite(invite.id,SUB2,"Second Sub",1001);
        assertThrows(SecurityException.class, () -> dom.bindInvite(invite.id,SUB2,invite.tokenHash(),1001));
    }

    @Test public void failedOutboxWriteRollsBackSourceRecordAndRevision() throws Exception {
        sub.getWritableDatabase().execSQL("CREATE TRIGGER refuse_outbox BEFORE INSERT ON outbox BEGIN SELECT RAISE(ABORT,'fixture storage failure'); END");
        assertThrows(android.database.sqlite.SQLiteException.class, () -> sub.publish(DOM,generation,
                RemoteProtocol.Stream.STATS,"summary",new JSONObject().put("count",1),false,1000));
        sub.getWritableDatabase().execSQL("DROP TRIGGER refuse_outbox");
        assertEquals(1,sub.publish(DOM,generation,RemoteProtocol.Stream.STATS,"summary",new JSONObject().put("count",1),false,1000));
        assertEquals(1,sub.nextBatch(DOM,generation,RemoteProtocol.Stream.STATS).changes.size());
    }

    private String pair(RemoteStore dom,RemoteStore sub,String subIdentity) {
        PairingInvite invite = PairingInvite.create(DOM,"ticket",1000);
        dom.saveInvite(invite); dom.bindInvite(invite.id,subIdentity,invite.tokenHash(),1001);
        sub.acceptInvite(invite,"Dom"); dom.approveInvite(invite.id,subIdentity,"Sub",1001);
        dom.activate(subIdentity,invite.id); sub.activate(DOM,invite.id);
        return invite.id;
    }
    private RemoteStore store() {
        String name = "remote-test-" + UUID.randomUUID() + ".db"; databases.add(name);
        RemoteStore store = new RemoteStore(context,name); stores.add(store); return store;
    }
    private static String repeat(char value) { return String.join("",Collections.nCopies(64,Character.toString(value))); }
}
