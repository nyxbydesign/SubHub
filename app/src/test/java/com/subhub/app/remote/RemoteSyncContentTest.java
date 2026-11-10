package com.subhub.app.remote;

import static org.junit.Assert.*;
import org.json.JSONObject;
import org.junit.Test;

public class RemoteSyncContentTest {
    @Test public void samplingTimeDoesNotChangeRuntimeContent() throws Exception {
        JSONObject initial = runtime();
        JSONObject repeated = new JSONObject(initial.toString()).put("observedAt", 9000);
        assertTrue(RemoteSyncContent.same(RemoteProtocol.Stream.RUNTIME,"current",initial.toString(),repeated.toString()));
        assertEquals(1000, initial.getLong("observedAt"));
    }

    @Test public void everyStateFieldStillChangesRuntimeContent() throws Exception {
        JSONObject initial = runtime();
        String[] fields = {"sessionIdentity","sessionEnd","sessionStart","appliedRevision","serviceActive","paused","featureConsent","expirySequence"};
        Object[] changes = {"different",9000,9000,2,false,true,new JSONObject().put("censor",false),2};
        for (int i=0;i<fields.length;i++) {
            JSONObject changed = new JSONObject(initial.toString()).put(fields[i],changes[i]).put("observedAt",9000);
            assertFalse(fields[i],RemoteSyncContent.same(RemoteProtocol.Stream.RUNTIME,"current",initial.toString(),changed.toString()));
        }
    }

    @Test public void eventAndCommandTimesRemainPartOfContentIdentity() throws Exception {
        String first = runtime().toString(), later = runtime().put("observedAt",9000).toString();
        assertFalse(RemoteSyncContent.same(RemoteProtocol.Stream.RUNTIME,"lifecycle:pact-expiry",first,later));
        assertFalse(RemoteSyncContent.same(RemoteProtocol.Stream.CONFIGURATION,"desired",first,later));
    }

    private JSONObject runtime() throws Exception {
        return new JSONObject().put("observedAt",1000).put("serviceActive",true)
                .put("sessionIdentity","session").put("sessionStart",1000).put("sessionEnd",5000)
                .put("appliedRevision",1).put("paused",false).put("expirySequence",1)
                .put("featureConsent",new JSONObject().put("censor",true));
    }
}
