package com.subhub.app.remote;

import com.subhub.app.people.PersonId;
import com.subhub.app.people.PersonSnapshot;
import com.subhub.app.people.ProfileData;
import java.util.Collections;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class RemotePersonPresenceTest {
    @Test public void foregroundBeforeFirstCheckpointKeepsOfflineHistoryAfterFalseExpiryAndRestart() throws Exception {
        JSONObject saved=new JSONObject().put("foreground",true).put("receivedElapsed",100)
                .put("process","first").put("lastOnlineAt",1000);
        PeerPresence.Observation live=PeerPresence.decode(saved.toString(),2000,200,"first");
        assertEquals(PersonSnapshot.Connection.CONNECTED,connection(live,0));
        PeerPresence.Observation expired=PeerPresence.decode(saved.toString(),2000,90_100,"first");
        assertEquals(1000,expired.lastOnlineAt);
        assertEquals(PersonSnapshot.Connection.OFFLINE,connection(expired,0));
        saved.put("foreground",false).put("receivedElapsed",300);
        PeerPresence.Observation background=PeerPresence.decode(saved.toString(),2000,400,"first");
        assertEquals(1000,background.lastOnlineAt);
        assertEquals(PersonSnapshot.Connection.OFFLINE,connection(background,0));
        PeerPresence.Observation restarted=PeerPresence.decode(saved.toString(),2000,10,"second");
        assertEquals(1000,restarted.lastOnlineAt);
        assertEquals(PersonSnapshot.Connection.OFFLINE,connection(restarted,0));
    }

    @Test public void unknownAndTransportOnlyObservationsNeverInventForegroundHistory() {
        PeerPresence.Observation unknown=PeerPresence.decode("",2000,400,"first");
        assertEquals(0,unknown.lastOnlineAt);
        assertEquals(PersonSnapshot.Connection.PAIRED,connection(unknown,0));
        assertEquals(PersonSnapshot.Connection.OFFLINE,connection(unknown,1500));
        assertEquals(0,unknown.lastOnlineAt);
    }

    @Test public void immutableProfileAndCounterpartCopiesRetainTheirOwnForegroundHistory() {
        PersonId sub=new PersonId("sub_fixture"),dom=new PersonId("dom_fixture");
        PersonSnapshot saved=new PersonSnapshot(sub,"Sub","","","",PersonSnapshot.Connection.OFFLINE,
                PersonSnapshot.Freshness.UNKNOWN,0,Collections.emptySet(),Collections.emptyList(),
                Collections.emptyList(),"EUR",0,0,0,0,false).withLastOnlineAt(1000);
        PersonSnapshot.PeerContact counterpart=new PersonSnapshot.PeerContact(dom,PersonSnapshot.Connection.OFFLINE,800);
        PersonSnapshot copied=saved.withProfile(ProfileData.empty("Changed"),Collections.emptyMap())
                .withContractDom(ProfileData.empty("Dom"),dom,counterpart).withLastContactAt(1500)
                .withContractDom(ProfileData.empty("Updated Dom"),dom);
        assertEquals(sub,copied.id);assertEquals(1000,copied.lastOnlineAt);assertEquals(1500,copied.lastContactAt);
        assertEquals(counterpart,copied.contractDomContact);assertEquals(800,copied.contractDomContact.lastOnlineAt);
        PersonSnapshot replaced=copied.withContractDom(ProfileData.empty("Other Dom"),new PersonId("other_dom"));
        assertEquals(1000,replaced.lastOnlineAt);assertNull(replaced.contractDomContact);
    }

    private static PersonSnapshot.Connection connection(PeerPresence.Observation presence,long transportContact) {
        return RemotePersonRepository.connection(RemoteStore.State.ACTIVE,presence,transportContact,"");
    }
}
