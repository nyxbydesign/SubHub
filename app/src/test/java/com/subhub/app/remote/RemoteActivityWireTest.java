package com.subhub.app.remote;

import static org.junit.Assert.*;
import com.subhub.app.activity.ActivityEvent;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.UUID;
import org.json.JSONObject;
import org.junit.Test;

public class RemoteActivityWireTest {
    private static final long TIME=1_700_000_000_000L;
    private static JSONObject app()throws Exception {
        return RemoteActivityWire.encode(new ActivityEvent(UUID.randomUUID().toString(),ActivityEvent.Kind.APP_OPENED,
                TIME,"com.example.app",0,"","",0),"Europe/Berlin","");
    }
    @Test public void realFrameRoundTripPreservesSourceClockAndFactIdentity()throws Exception {
        JSONObject event=app();ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        RemoteProtocol.Batch sent=new RemoteProtocol.Batch("activity_fixture",RemoteProtocol.Stream.EVENTS,0,1,
                java.util.Collections.singletonList(new RemoteProtocol.Change(1,event.getString("id"),event.toString(),false,TIME)));
        RemoteProtocol.write(bytes,sent.json());
        RemoteProtocol.Batch received=RemoteProtocol.Batch.parse(RemoteProtocol.read(new ByteArrayInputStream(bytes.toByteArray())));
        assertEquals(sent.digest(),received.digest());
        ActivityEvent decoded=RemoteActivityWire.decode(new JSONObject(received.changes.get(0).body));
        assertEquals(event.getString("id"),decoded.id);assertEquals(TIME,decoded.observedAt);assertEquals("com.example.app",decoded.packageName);
    }
    @Test public void unknownSchemaFieldsAndKindsFailInsteadOfBecomingEmptyActivity()throws Exception {
        assertThrows(Exception.class,()->RemoteActivityWire.decode(app().put("schema",2)));
        assertThrows(Exception.class,()->RemoteActivityWire.decode(app().put("kind","PAYMENT_COMPLETED")));
        assertThrows(Exception.class,()->RemoteActivityWire.decode(app().put("secret","unexpected")));
        JSONObject missing=app();missing.remove("packageName");assertThrows(Exception.class,()->RemoteActivityWire.decode(missing));
    }
    @Test public void stringNumbersAndInconsistentCivilClockAreRejected()throws Exception {
        assertThrows(Exception.class,()->RemoteActivityWire.decode(app().put("observedAt",Long.toString(TIME))));
        assertThrows(Exception.class,()->RemoteActivityWire.decode(app().put("day","2000-01-01")));
        assertThrows(Exception.class,()->RemoteActivityWire.decode(app().put("zone","unknown/zone")));
        assertThrows(Exception.class,()->RemoteActivityWire.decode(app().put("amountCents",1)));
    }
    @Test public void limitsRequireActualMinutesAndAConfigurationIdentity()throws Exception {
        ActivityEvent limit=new ActivityEvent("limit_fixture",ActivityEvent.Kind.APP_LIMIT_REACHED,TIME,"com.example.app",0,"","",37);
        String fingerprint=RemoteProtocol.sha256("actual configuration".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(37,RemoteActivityWire.decode(RemoteActivityWire.encode(limit,"UTC",fingerprint)).limitMinutes);
        assertThrows(Exception.class,()->RemoteActivityWire.decode(RemoteActivityWire.encode(limit,"UTC",fingerprint).put("configuration","")));
        assertThrows(Exception.class,()->RemoteActivityWire.decode(RemoteActivityWire.encode(limit,"UTC",fingerprint).put("limitMinutes",1441)));
    }
    @Test public void assessedTributesKeepTheirCurrencyWithoutPaymentOrCredentialFields()throws Exception {
        ActivityEvent tribute=new ActivityEvent("tribute_fixture",ActivityEvent.Kind.TRIBUTE_TRIGGERED,TIME,"",123,"EUR","watched_app_open",0);
        JSONObject value=RemoteActivityWire.encode(tribute,"UTC","");ActivityEvent parsed=RemoteActivityWire.decode(value);
        assertEquals(123,parsed.amountCents);assertEquals("EUR",parsed.currency);assertEquals("watched_app_open",parsed.reason);
        assertFalse(value.has("paid"));assertFalse(value.has("settlementId"));
        assertThrows(Exception.class,()->RemoteActivityWire.decode(value.put("reason","invalid\ntext")));
    }
}
