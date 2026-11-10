package com.subhub.app.penance;

import android.content.SharedPreferences;
import com.subhub.app.remote.RemoteActivityRecorder.PaymentAudience;
import com.subhub.app.remote.RemoteProtocol;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Optional capture facts share the completed ledger commit; their capacity cannot hold a payment open. */
final class PaymentActivityStore {
    static final String AUDIENCE = "payment_activity_audience_v1", OUTBOX = "payment_activity_outbox_v1", GAP = "payment_activity_gap_v1";
    static final int MAX = 128;
    private PaymentActivityStore() { }
    static void bind(SharedPreferences.Editor edit, String settlement, PaymentAudience audience) {
        try {
            edit.remove(AUDIENCE);
            edit.putString(AUDIENCE, new JSONObject().put("settlement", settlement).put("peer", audience.peer)
                    .put("generation", audience.generation).toString());
        } catch (JSONException | RuntimeException unavailable) {
            try { edit.remove(AUDIENCE); } catch (RuntimeException ignored) { /* Do not retain a previous checkout's audience. */ }
        }
    }
    static void append(SharedPreferences saved, SharedPreferences.Editor edit, String settlement, PayPalOrdersClient.Capture capture,
            int tributeCents, long observedAt) {
        if (tributeCents <= 0) return;
        try {
            String id = "paid_" + RemoteProtocol.sha256((capture.id() + ":" + settlement).getBytes(StandardCharsets.UTF_8));
            List<PaymentActivityReceipt> rows = read(saved);
            for (PaymentActivityReceipt row : rows) if (row.id.equals(id)) {
                if (!row.settlement.equals(settlement) || row.amountCents != tributeCents || !row.currency.equals(capture.currency()))
                    throw new IllegalStateException("Payment activity identity changed");
                return;
            }
            if (rows.size() >= MAX) { edit.putBoolean(GAP, true); return; }
            PaymentAudience audience = PaymentAudience.NONE;
            JSONObject bound = new JSONObject(saved.getString(AUDIENCE, "{}"));
            if (settlement.equals(bound.optString("settlement")))
                audience = new PaymentAudience(bound.getString("peer"), bound.getString("generation"));
            rows.add(new PaymentActivityReceipt(id, settlement, observedAt, tributeCents, capture.currency(), audience.peer, audience.generation));
            edit.putString(OUTBOX, encode(rows));
        } catch (JSONException | RuntimeException unavailable) {
            edit.putBoolean(GAP, true);
        }
    }
    static List<PaymentActivityReceipt> read(SharedPreferences saved) throws JSONException {
        String encoded = saved.getString(OUTBOX, "[]");
        if (encoded.length() > 128 * 1024) throw new JSONException("Payment activity data exceeds its bound");
        JSONArray array = new JSONArray(encoded);
        if (array.length() > MAX) throw new JSONException("Payment activity capacity exceeded");
        List<PaymentActivityReceipt> result = new ArrayList<>(); java.util.Set<String> identities = new java.util.HashSet<>();
        for (int index = 0; index < array.length(); index++) {
            PaymentActivityReceipt row = PaymentActivityReceipt.parse(array.getJSONObject(index));
            if (!identities.add(row.id)) throw new JSONException("Duplicate payment activity receipt");
            result.add(row);
        }
        return result;
    }
    static String encode(List<PaymentActivityReceipt> rows) throws JSONException {
        JSONArray array = new JSONArray(); for (PaymentActivityReceipt row : rows) array.put(row.json()); return array.toString();
    }
    static boolean acknowledge(SharedPreferences saved, PaymentActivityReceipt receipt) throws JSONException {
        List<PaymentActivityReceipt> rows = read(saved); boolean removed = false;
        for (java.util.Iterator<PaymentActivityReceipt> iterator = rows.iterator(); iterator.hasNext();) {
            PaymentActivityReceipt row = iterator.next();
            if (!row.id.equals(receipt.id)) continue;
            if (!row.json().toString().equals(receipt.json().toString())) throw new IllegalStateException("Payment activity receipt changed");
            iterator.remove(); removed = true;
        }
        if (!removed) return true;
        SharedPreferences.Editor edit = saved.edit().putString(OUTBOX, encode(rows));
        JSONObject audience = new JSONObject(saved.getString(AUDIENCE, "{}"));
        if (receipt.settlement.equals(audience.optString("settlement"))) edit.remove(AUDIENCE);
        return edit.commit();
    }
}
