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
import java.io.ByteArrayOutputStream;
import java.util.Collections;
import java.util.UUID;

@RunWith(AndroidJUnit4.class)
public class RemoteSyncContentAndroidTest {
    private static final String DOM=String.join("",Collections.nCopies(64,"a"));
    private static final String SUB=String.join("",Collections.nCopies(64,"b"));
    private final Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
    private String domName,subName,generation;
    private RemoteStore dom,sub;

    @Before public void setup() {
        domName="sync-content-dom-"+UUID.randomUUID()+".db";
        subName="sync-content-sub-"+UUID.randomUUID()+".db";
        dom=new RemoteStore(context,domName);sub=new RemoteStore(context,subName);
        PairingInvite invite=PairingInvite.create(DOM,"ticket",1000);generation=invite.id;
        dom.saveInvite(invite);dom.bindInvite(invite.id,SUB,invite.tokenHash(),1001);
        sub.acceptInvite(invite,"Dom");dom.approveInvite(invite.id,SUB,"Sub",1001);
        dom.activate(SUB,invite.id);sub.activate(DOM,invite.id);
    }
    @After public void cleanup() {
        dom.close();sub.close();context.deleteDatabase(domName);context.deleteDatabase(subName);
    }

    @Test public void unchangedRuntimeSurvivesReopenAndNeedsNoContentRetransfer() throws Exception {
        JSONObject initial=runtime(1000);
        assertEquals(1,sub.publish(DOM,generation,RemoteProtocol.Stream.RUNTIME,"current",initial,false,1000));
        RemoteProtocol.Batch sent=sub.nextBatch(DOM,generation,RemoteProtocol.Stream.RUNTIME);
        RemoteProtocol.Ack ack=dom.receive(SUB,sent);
        // A lost receipt still retries exactly the same content and digest.
        assertEquals(1,sub.publish(DOM,generation,RemoteProtocol.Stream.RUNTIME,"current",runtime(2000),false,2000));
        assertEquals(sent.digest(),sub.nextBatch(DOM,generation,RemoteProtocol.Stream.RUNTIME).digest());
        sub.acknowledge(DOM,ack);
        sub.close();sub=new RemoteStore(context,subName);
        assertEquals(1,sub.publish(DOM,generation,RemoteProtocol.Stream.RUNTIME,"current",runtime(3000),false,3000));
        RemoteProtocol.Batch unchanged=sub.nextBatch(DOM,generation,RemoteProtocol.Stream.RUNTIME);
        assertTrue(unchanged.changes.isEmpty());assertEquals(1,unchanged.through);
        assertEquals(1000,dom.mirrored(SUB,generation,RemoteProtocol.Stream.RUNTIME,"current").getLong("observedAt"));
        dom.putMetadata(SUB,generation,"confirmed_RUNTIME","1");
        dom.confirmHeads(SUB,generation,sub.outgoingHeads(DOM,generation,false));
        assertTrue(Long.parseLong(dom.metadata(SUB,generation,"confirmed_RUNTIME","0"))>1);
        // Freshness comes from the authenticated unchanged stream head, not a new sample timestamp.
        assertEquals(1000,dom.mirrored(SUB,generation,RemoteProtocol.Stream.RUNTIME,"current").getLong("observedAt"));
        ByteArrayOutputStream frame=new ByteArrayOutputStream();RemoteProtocol.write(frame,sent.json());
        android.util.Log.i("RemoteSyncContentTest","unchanged runtime: firstRecords=1 firstWireBytes="+frame.size()+" repeatRecords=0 repeatContentBytes=0");
    }

    @Test public void changedSessionExpiryAndConsentRemainQueuedUntilAccepted() throws Exception {
        sub.publish(DOM,generation,RemoteProtocol.Stream.RUNTIME,"current",runtime(1000),false,1000);
        RemoteProtocol.Batch first=sub.nextBatch(DOM,generation,RemoteProtocol.Stream.RUNTIME);
        sub.acknowledge(DOM,dom.receive(SUB,first));
        dom.putMetadata(SUB,generation,"confirmed_RUNTIME","1");
        JSONObject changed=runtime(2000).put("serviceActive",false).put("sessionEnd",1500)
                .put("featureConsent",new JSONObject().put("censor",false));
        assertEquals(2,sub.publish(DOM,generation,RemoteProtocol.Stream.RUNTIME,"current",changed,false,2000));
        dom.confirmHeads(SUB,generation,sub.outgoingHeads(DOM,generation,false));
        assertEquals("1",dom.metadata(SUB,generation,"confirmed_RUNTIME","0"));
        RemoteProtocol.Batch pending=sub.nextBatch(DOM,generation,RemoteProtocol.Stream.RUNTIME);
        assertEquals(1,pending.changes.size());
        sub.acknowledge(DOM,dom.receive(SUB,pending));
        assertFalse(dom.mirrored(SUB,generation,RemoteProtocol.Stream.RUNTIME,"current").getBoolean("serviceActive"));
    }

    @Test public void lifecycleEventObservationIsNotTreatedAsSnapshotSamplingTime() throws Exception {
        JSONObject receipt=new JSONObject().put("sequence",1).put("sessionIdentity","expired-session");
        assertEquals(1,sub.publish(DOM,generation,RemoteProtocol.Stream.RUNTIME,"lifecycle:pact-expiry",receipt,false,1000));
        assertEquals(2,sub.publish(DOM,generation,RemoteProtocol.Stream.RUNTIME,"lifecycle:pact-expiry",receipt,false,2000));
        assertEquals(2000,sub.nextBatch(DOM,generation,RemoteProtocol.Stream.RUNTIME).changes.get(0).observedAt);
    }

    private JSONObject runtime(long time) throws Exception {
        return new JSONObject().put("schema",1).put("observedAt",time).put("serviceActive",true)
                .put("sessionIdentity",String.join("",Collections.nCopies(64,"c")))
                .put("sessionStart",1000).put("sessionEnd",9000).put("appliedRevision",1)
                .put("featureConsent",new JSONObject().put("censor",true));
    }
}
