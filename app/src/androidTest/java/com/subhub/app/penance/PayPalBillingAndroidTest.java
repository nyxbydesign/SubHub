package com.subhub.app.penance;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.subhub.app.R;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Exact serialized request metadata; never instantiates a network client or submits a charge. */
@RunWith(AndroidJUnit4.class)
public final class PayPalBillingAndroidTest {
    private final Context context = ApplicationProvider.getApplicationContext();

    @Test public void manualWritesBillForEveryCauseInBothCurrencies() throws Exception {
        for (String currency : new String[] {"EUR", "USD"}) {
            List<PayPalOrdersClient.OrderItem> items = allCauses(currency);
            JSONObject unit = PayPalOrdersClient.purchaseUnit("fixture", 2250, currency, items, false);
            JSONObject body = wire(PayPalOrdersClient.createOrderBody(unit, "synthetic-risk", false));
            JSONObject encoded = body.getJSONArray("purchase_units").getJSONObject(0);
            assertEquals("fixture", encoded.getString("reference_id"));
            assertEquals("fixture", encoded.getString("custom_id"));
            assertFalse(encoded.getString("description").isBlank());
            assertTrue(encoded.getString("description").length() <= 127);
            assertEquals(6, encoded.getJSONArray("items").length());
            int total = 0;
            for (int index = 0; index < items.size(); index++) {
                JSONObject row = encoded.getJSONArray("items").getJSONObject(index);
                assertEquals(items.get(index).description(), row.getString("description"));
                assertEquals("1", row.getString("quantity"));
                assertEquals(currency, row.getJSONObject("unit_amount").getString("currency_code"));
                total += items.get(index).amountCents();
            }
            assertEquals(2250, total);
            assertEquals("22.50", encoded.getJSONObject("amount").getString("value"));
            assertEquals("22.50", encoded.getJSONObject("amount").getJSONObject("breakdown")
                    .getJSONObject("item_total").getString("value"));
            assertTrue(items.get(4).name().startsWith(context.getString(R.string.penance_history_tamper)));
            assertTrue(items.get(5).name().startsWith(context.getString(R.string.paid_pause_history_label)));
        }
    }

    @Test public void automaticBillIsOneRowWithoutInteractiveApproval() throws Exception {
        for (String currency : new String[] {"EUR", "USD"}) {
            List<PayPalOrdersClient.OrderItem> items = allCauses(currency);
            JSONObject body = wire(PayPalOrdersClient.createStoredWalletBody(
                    PayPalOrdersClient.purchaseUnit("fixture", 2250, currency, items, true),
                    PayPalRequestPolicy.storedWalletRequest("synthetic-vault")));
            assertEquals(1, body.getJSONArray("purchase_units").length());
            JSONObject unit = body.getJSONArray("purchase_units").getJSONObject(0);
            JSONArray rows = unit.getJSONArray("items");
            assertEquals(1, rows.length());
            JSONObject row = rows.getJSONObject(0);
            assertEquals("1", row.getString("quantity"));
            assertEquals("22.50", row.getJSONObject("unit_amount").getString("value"));
            assertEquals(currency, row.getJSONObject("unit_amount").getString("currency_code"));
            for (PayPalOrdersClient.OrderItem item : items) {
                assertTrue("Every cause must reach PayPal", row.getString("description").contains(item.description()));
            }
            JSONObject paypal = body.getJSONObject("payment_source").getJSONObject("paypal");
            assertEquals("synthetic-vault", paypal.getString("vault_id"));
            assertFalse(paypal.has("experience_context"));
            JSONObject stored = paypal.getJSONObject("stored_credential");
            assertEquals("MERCHANT", stored.getString("payment_initiator"));
            assertEquals("SUBSEQUENT", stored.getString("usage"));
            assertEquals("UNSCHEDULED_POSTPAID", stored.getString("usage_pattern"));
        }
    }

    @Test public void groupedActualChargesSurvivePartialCapsAnd200EventLedger() throws Exception {
        List<PayPalOrdersClient.OrderItem> capped = PayPalOrderDetails.from(context,
                List.of(event("cap", 175, 7, PenanceInfraction.NEW_DETECTION, "USD")), "USD");
        JSONObject cap = PayPalOrdersClient.purchaseUnit("cap", 175, "USD", capped, true)
                .getJSONArray("items").getJSONObject(0);
        assertTrue(cap.getString("description").contains("× 7: USD 1.75"));
        assertEquals("1.75", cap.getJSONObject("unit_amount").getString("value"));
        assertEquals("1", cap.getString("quantity"));
        List<PenanceEvent> events = new ArrayList<>();
        for (int index = 0; index < 200; index++) events.add(event("row" + index, 1, 1,
                PenanceInfraction.NEW_DETECTION, "EUR"));
        List<PayPalOrdersClient.OrderItem> grouped = PayPalOrderDetails.from(context, events, "EUR");
        assertEquals(1, grouped.size());
        assertEquals(200, grouped.get(0).amountCents());
        assertTrue(grouped.get(0).name().endsWith("× 200"));
    }

    @Test public void compactSingleCauseDescriptionIsNotBlank() throws Exception {
        List<PayPalOrdersClient.OrderItem> items = PayPalOrderDetails.from(context,
                List.of(event("one", 1550, 1, PenanceInfraction.NEW_DETECTION, "EUR")), "EUR");
        for (boolean stored : new boolean[] {false, true}) {
            JSONObject unit = wire(PayPalOrdersClient.purchaseUnit("one", 1550, "EUR", items, stored));
            assertEquals(items.get(0).description(), unit.getString("description"));
            assertTrue(unit.getString("description").contains("EUR 15.50"));
            assertEquals(items.get(0).description(), unit.getJSONArray("items")
                    .getJSONObject(0).getString("description"));
        }
    }

    @Test public void longBillRemainsCompleteInItemDescription() throws Exception {
        String detail = "Long localized cause ".repeat(20) + "USD 1.00 END";
        JSONObject unit = wire(PayPalOrdersClient.purchaseUnit("long", 100, "USD",
                List.of(new PayPalOrdersClient.OrderItem("cause", detail, 100)), true));
        assertTrue(unit.getString("description").length() <= 127);
        assertTrue(unit.getString("description").contains("see bill details"));
        assertEquals(detail, unit.getJSONArray("items").getJSONObject(0).getString("description"));
    }

    @Test public void invalidAmountsAndMixedDenominationsFailClosed() throws Exception {
        try {
            PayPalOrdersClient.createStoredWalletBody(
                    PayPalOrdersClient.purchaseUnit("bad", 2250, "EUR", allCauses("EUR"), false),
                    PayPalRequestPolicy.storedWalletRequest("synthetic-vault"));
            fail("Multiple automatic bill rows accepted");
        } catch (IllegalArgumentException expected) { }
        for (boolean stored : new boolean[] {false, true}) {
            try {
                PayPalOrdersClient.purchaseUnit("bad", 2251, "EUR", allCauses("EUR"), stored);
                fail("Mismatched bill total accepted");
            } catch (IllegalArgumentException expected) { }
            try {
                PayPalOrdersClient.purchaseUnit("bad", 0, "EUR", List.of(), stored);
                fail("Non-positive payment accepted");
            } catch (IllegalArgumentException expected) { }
        }
        try {
            PayPalOrderDetails.from(context, List.of(event("mixed", 100, 1,
                    PenanceInfraction.NEW_DETECTION, "USD")), "EUR");
            fail("Mixed denomination accepted");
        } catch (IllegalArgumentException expected) { }
        try {
            PayPalOrdersClient.purchaseUnit("long", 100, "USD",
                    List.of(new PayPalOrdersClient.OrderItem("cause", "x".repeat(2049), 100)), true);
            fail("Bill silently truncated");
        } catch (IllegalArgumentException expected) { }
    }

    @Test public void amountOnlyCompatibilityHasTruthfulDescription() throws Exception {
        for (boolean stored : new boolean[] {false, true}) {
            JSONObject unit = wire(PayPalOrdersClient.purchaseUnit("old", 125, "USD", List.of(), stored));
            assertEquals("SubHub tribute: USD 1.25", unit.getString("description"));
            assertFalse(unit.has("items"));
            assertFalse(unit.getJSONObject("amount").has("breakdown"));
        }
    }

    @Test public void settlementBillDoesNotIncludeLaterLedgerEntries() {
        SharedPreferences preferences = context.getSharedPreferences(PenanceManager.PREFS_NAME, Context.MODE_PRIVATE);
        Map<String, ?> before = preferences.getAll();
        try {
            assertTrue(preferences.edit().clear().putString("wallet_currency", "USD")
                    .putString("events_v1", "a,1,1,175,7,OPEN,,NEW_DETECTION,USD").commit());
            PenanceManager.Settlement settlement = new PenanceManager(context).beginSettlement(3);
            assertNotNull(settlement);
            assertTrue(preferences.edit().putString("events_v1", "b,2,2,500,1,OPEN,,TAMPER_ATTEMPT,USD").commit());
            List<PayPalOrdersClient.OrderItem> bill = PayPalOrderDetails.from(context, settlement);
            assertEquals(1, bill.size());
            assertEquals(175, bill.get(0).amountCents());
            assertTrue(bill.get(0).name().endsWith("× 7"));
        } finally {
            SharedPreferences.Editor restore = preferences.edit().clear();
            for (var entry : before.entrySet()) {
                Object value = entry.getValue();
                if (value instanceof String) restore.putString(entry.getKey(), (String) value);
                else if (value instanceof Integer) restore.putInt(entry.getKey(), (Integer) value);
                else if (value instanceof Long) restore.putLong(entry.getKey(), (Long) value);
                else if (value instanceof Boolean) restore.putBoolean(entry.getKey(), (Boolean) value);
                else if (value instanceof Float) restore.putFloat(entry.getKey(), (Float) value);
                else if (value instanceof Set) {
                    @SuppressWarnings("unchecked") Set<String> values = (Set<String>) value;
                    restore.putStringSet(entry.getKey(), values);
                }
            }
            assertTrue(restore.commit());
        }
    }

    @Test public void unicodeNameBoundsKeepPairedSurrogates() throws Exception {
        JSONObject unit = wire(PayPalOrdersClient.purchaseUnit("unicode", 100, "USD",
                List.of(new PayPalOrdersClient.OrderItem("a".repeat(126) + "😀", "USD 1.00", 100)), false));
        String name = unit.getJSONArray("items").getJSONObject(0).getString("name");
        assertEquals(126, name.length());
        assertFalse(Character.isHighSurrogate(name.charAt(name.length() - 1)));
    }

    private List<PayPalOrdersClient.OrderItem> allCauses(String currency) {
        List<PenanceEvent> events = new ArrayList<>();
        events.add(event("det1", 175, 3, PenanceInfraction.NEW_DETECTION, currency));
        events.add(event("det2", 75, 2, PenanceInfraction.NEW_DETECTION, currency));
        for (PenanceInfraction infraction : PenanceInfraction.values()) {
            if (infraction == PenanceInfraction.NEW_DETECTION) continue;
            events.add(event(infraction.name(), (infraction.ordinal() + 1) * 100, 1, infraction, currency));
        }
        return PayPalOrderDetails.from(context, events, currency);
    }

    private static PenanceEvent event(String id, int cents, int count,
            PenanceInfraction infraction, String currency) {
        return new PenanceEvent(id, 1, 1, cents, count, infraction, PenanceEvent.Status.OPEN, "", currency);
    }

    private static JSONObject wire(JSONObject body) throws Exception { return new JSONObject(body.toString()); }
}
