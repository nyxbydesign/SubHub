package com.subhub.app.remote;

import java.time.Instant;
import java.time.ZoneId;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class ExtraTimeRequestPolicyTest {
    private static final long NOW = Instant.parse("2026-11-05T15:00:00Z").toEpochMilli();
    private static ExtraTimeStore.Row request(long at) {
        String zone = "Europe/Berlin";
        return new ExtraTimeStore.Row("request_1","org.example.reader","Reader",
                Instant.ofEpochMilli(at).atZone(ZoneId.of(zone)).toLocalDate().toString(),zone,"a".repeat(64),
                at,30*60_000,30,"PENDING",0,0,100,"EUR");
    }
    @Test public void nearbySenderClockAheadDoesNotExpireTheReceiversSameDayRequest() {
        assertTrue(request(NOW+5_000).today(NOW));
        assertTrue(request(NOW+30_000).today(NOW));
        assertFalse(request(NOW+30_001).today(NOW));
        assertFalse(request(NOW+3_600_000).today(NOW));
    }
    @Test public void sourceLocalDayAndZoneEndTheOneTimeAllowance() {
        ExtraTimeStore.Row row = request(NOW);
        assertTrue(row.today(Instant.parse("2026-11-05T22:59:59Z").toEpochMilli()));
        assertFalse(row.today(Instant.parse("2026-11-05T23:00:00Z").toEpochMilli()));
        assertFalse(row.today(Instant.parse("2026-11-04T23:00:00Z").toEpochMilli()));
    }
    @Test public void wireCopyBindsOriginalFactsAndCannotCarryLocalCreditState() throws Exception {
        ExtraTimeStore.Row original = request(NOW);
        ExtraTimeStore.Row copy = ExtraTimeStore.Row.parse(original.json(false),false);
        assertTrue(original.immutableEquals(copy));
        JSONObject mutated = original.json(false).put("tribute",101);
        assertFalse(original.immutableEquals(ExtraTimeStore.Row.parse(mutated,false)));
        assertThrows(Exception.class,()->ExtraTimeStore.Row.parse(original.json(true),false));
        assertThrows(Exception.class,()->ExtraTimeStore.Row.parse(original.json(false).put("minutes",1),false));
    }
    @Test public void decisionsRequireBoundedPositiveMinutesAndTheSameImmutableRequest() {
        ExtraTimeStore.Row row = request(NOW);
        assertThrows(IllegalArgumentException.class,()->row.state("APPROVED",0,0));
        assertThrows(IllegalArgumentException.class,()->row.state("APPROVED",1441,0));
        ExtraTimeStore.Row grant = row.state("APPROVED",25,55*60_000);
        assertTrue(row.immutableEquals(grant)); assertEquals(25,grant.minutes);
        assertEquals(0,row.state("DECLINED",0,0).ceiling);
    }
    @Test public void legacyContractDefaultsAreExplicitAndPartialExtensionsRemainInvalid() throws Exception {
        JSONObject sections = new JSONObject().put("wallet",new JSONObject());
        ExtraTimeContractFields.expandLegacy(sections);
        JSONObject wallet = sections.getJSONObject("wallet");
        assertFalse(wallet.getBoolean(ExtraTimeContractFields.ENABLED));
        assertEquals(100,wallet.getInt(ExtraTimeContractFields.CENTS));
        JSONObject doc = new JSONObject().put("contract",new JSONObject().put("sections",sections));
        JSONObject old = ExtraTimeContractFields.forPeer(doc,false,false);
        assertFalse(old.getJSONObject("contract").getJSONObject("sections").getJSONObject("wallet").has(ExtraTimeContractFields.ENABLED));
        wallet.put(ExtraTimeContractFields.ENABLED,true);
        assertThrows(SecurityException.class,()->ExtraTimeContractFields.forPeer(doc,false,true));
        JSONObject partial = new JSONObject().put("wallet",new JSONObject().put(ExtraTimeContractFields.ENABLED,false));
        ExtraTimeContractFields.expandLegacy(partial);
        assertFalse(partial.getJSONObject("wallet").has(ExtraTimeContractFields.CENTS));
    }
}
