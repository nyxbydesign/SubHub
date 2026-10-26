package com.subhub.app.remote;

import static org.junit.Assert.*;

import org.json.JSONObject;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.zip.GZIPOutputStream;

public class RemoteProtocolTest {
    private static final String DOM = String.join("", Collections.nCopies(64, "a"));
    private static final String SUB = String.join("", Collections.nCopies(64, "b"));

    @Test public void canonicalDigestSurvivesObjectKeyOrdering() throws Exception {
        RemoteProtocol.Batch a = batch("{\"b\":2,\"a\":{\"z\":3,\"c\":4}}");
        RemoteProtocol.Batch b = batch("{\"a\":{\"c\":4,\"z\":3},\"b\":2}");
        assertEquals(a.digest(), b.digest());
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        RemoteProtocol.write(output, a.json());
        RemoteProtocol.Batch parsed = RemoteProtocol.Batch.parse(RemoteProtocol.read(new ByteArrayInputStream(output.toByteArray())));
        assertEquals(a.digest(), parsed.digest());
        assertEquals(a.digest(), RemoteProtocol.Ack.parse(new RemoteProtocol.Ack(parsed).json()).digest);
    }

    @Test public void compressedAndUncompressedFramesCanShareAStream() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        RemoteProtocol.write(output, new JSONObject().put("version", 1).put("text", "short"));
        String text = String.join("", Collections.nCopies(9000, "x"));
        RemoteProtocol.write(output, new JSONObject().put("version", 1).put("text", text));
        assertTrue(output.size() < 1000);
        ByteArrayInputStream input = new ByteArrayInputStream(output.toByteArray());
        assertEquals("short", RemoteProtocol.read(input).getString("text"));
        assertEquals(text, RemoteProtocol.read(input).getString("text"));
        assertEquals(0, input.available());
    }

    @Test public void refusesCompressionBombBeforeJsonParsing() throws Exception {
        ByteArrayOutputStream zipped = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(zipped)) { gzip.write(new byte[RemoteProtocol.MAX_FRAME_BYTES + 1]); }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        DataOutputStream frame = new DataOutputStream(output);
        frame.writeInt(0x53554831); frame.writeByte(1); frame.writeInt(zipped.size()); frame.write(zipped.toByteArray());
        assertThrows(IOException.class, () -> RemoteProtocol.read(new ByteArrayInputStream(output.toByteArray())));
    }

    @Test public void refusesOversizedHeaderBeforePayloadAllocation() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        DataOutputStream frame = new DataOutputStream(output);
        frame.writeInt(0x53554831); frame.writeByte(0); frame.writeInt(Integer.MAX_VALUE);
        assertThrows(IOException.class, () -> RemoteProtocol.read(new ByteArrayInputStream(output.toByteArray())));
    }

    @Test public void rejectsFractionalAndStringSequenceNumbers() throws Exception {
        JSONObject json = batch("{}").json();
        json.put("through", 1.5);
        assertThrows(org.json.JSONException.class, () -> RemoteProtocol.Batch.parse(json));
        json.put("through", "1");
        assertThrows(org.json.JSONException.class, () -> RemoteProtocol.Batch.parse(json));
    }

    @Test public void allowsCoalescedSequenceHolesButRejectsMissingBoundary() {
        new RemoteProtocol.Batch("generation", RemoteProtocol.Stream.STATS, 0, 8,
                Arrays.asList(new RemoteProtocol.Change(3, "a", "{}", false, 1),
                        new RemoteProtocol.Change(8, "b", "{}", false, 1)));
        assertThrows(IllegalArgumentException.class, () -> new RemoteProtocol.Batch("generation",
                RemoteProtocol.Stream.STATS, 0, 8, Collections.singletonList(new RemoteProtocol.Change(3,"a","{}",false,1))));
    }

    @Test public void rejectsOutOfOrderChangesAndPayloadBearingTombstone() {
        assertThrows(IllegalArgumentException.class, () -> new RemoteProtocol.Batch("generation", RemoteProtocol.Stream.STATS, 0, 3,
                Arrays.asList(new RemoteProtocol.Change(3,"a","{}",false,1),new RemoteProtocol.Change(2,"b","{}",false,1))));
        assertThrows(IllegalArgumentException.class, () -> new RemoteProtocol.Change(1,"a","{\"secret\":1}",true,1));
    }

    @Test public void invitationRoundTripPinsIdentityAndRedactsSecret() {
        PairingInvite invite = PairingInvite.create(DOM,"opaque route+/=",1000);
        PairingInvite parsed = PairingInvite.parse(invite.link(),1001);
        assertEquals(invite.id, parsed.id); assertEquals(invite.route, parsed.route);
        assertEquals(invite.tokenHash(), parsed.tokenHash());
        assertEquals(DOM, parsed.domIdentity);
        assertFalse(invite.toString().contains(invite.secret()));
        assertNotEquals(PairingInvite.fingerprint(DOM,SUB,invite.id),PairingInvite.fingerprint(SUB,DOM,invite.id));
    }

    @Test public void invitationRejectsExpiryDuplicatesAndUntrustedUriForms() {
        PairingInvite invite = PairingInvite.create(DOM,"ticket",1000);
        assertThrows(IllegalArgumentException.class, () -> PairingInvite.parse(invite.link(),invite.expiresAt));
        assertThrows(IllegalArgumentException.class, () -> PairingInvite.parse(invite.link(),0));
        assertThrows(IllegalArgumentException.class, () -> PairingInvite.parse(invite.link().replace("v=1", "v=1&v=1"),1001));
        assertThrows(IllegalArgumentException.class, () -> PairingInvite.parse(invite.link().replace("subhubapp://pair", "https://pair"),1001));
        assertThrows(IllegalArgumentException.class, () -> PairingInvite.parse(invite.link().replace("subhubapp://pair", "subhubapp://pair:123"),1001));
    }

    @Test public void unknownProtocolVersionDoesNotLookLikeAnEmptySync() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        RemoteProtocol.write(output, new JSONObject().put("version",2).put("type","batch"));
        assertThrows(IOException.class, () -> RemoteProtocol.read(new ByteArrayInputStream(output.toByteArray())));
    }

    @Test public void untrustedDeviceNamesCannotInjectConfirmationLinesOrBidiControls() {
        assertEquals("Alex",RemoteProtocol.deviceName(" Alex "));
        assertEquals("هاتف",RemoteProtocol.deviceName("هاتف"));
        assertThrows(IllegalArgumentException.class, () -> RemoteProtocol.deviceName("Device\nApproved"));
        assertThrows(IllegalArgumentException.class, () -> RemoteProtocol.deviceName("Device\u202e1234"));
    }

    private static RemoteProtocol.Batch batch(String body) {
        return new RemoteProtocol.Batch("generation", RemoteProtocol.Stream.STATS,0,1,
                Collections.singletonList(new RemoteProtocol.Change(1,"summary",body,false,1000)));
    }
}
