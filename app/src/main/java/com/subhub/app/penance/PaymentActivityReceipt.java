package com.subhub.app.penance;

import com.subhub.app.remote.RemoteProtocol;
import org.json.JSONException;
import org.json.JSONObject;

/** A bounded local outbox fact from a verified capture, never a source of Wallet credit. */
public final class PaymentActivityReceipt {
    public final String id, settlement, currency, peer, generation;
    public final long observedAt;
    public final int amountCents;
    PaymentActivityReceipt(String id, String settlement, long time, int amount, String currency, String peer, String generation) {
        if (!id.matches("paid_[0-9a-f]{64}") || time <= 0 || amount <= 0) throw new IllegalArgumentException("Invalid payment activity");
        RemoteProtocol.identifier(settlement); WalletCurrency.requireSupported(currency);
        new com.subhub.app.remote.RemoteActivityRecorder.PaymentAudience(peer, generation);
        this.id = id; this.settlement = settlement; this.observedAt = time; this.amountCents = amount;
        this.currency = currency; this.peer = peer; this.generation = generation;
    }
    JSONObject json() throws JSONException {
        return new JSONObject().put("id", id).put("settlement", settlement).put("time", observedAt).put("amount", amountCents)
                .put("currency", currency).put("peer", peer).put("generation", generation);
    }
    static PaymentActivityReceipt parse(JSONObject value) throws JSONException {
        if (value.length() != 7) throw new JSONException("Invalid payment activity receipt");
        long amount = RemoteProtocol.integer(value, "amount");
        if (amount <= 0 || amount > Integer.MAX_VALUE) throw new JSONException("Invalid payment activity amount");
        return new PaymentActivityReceipt(text(value, "id"), text(value, "settlement"), RemoteProtocol.integer(value, "time"),
                (int) amount, text(value, "currency"), text(value, "peer"), text(value, "generation"));
    }
    private static String text(JSONObject value, String key) throws JSONException {
        Object raw = value.get(key); if (!(raw instanceof String)) throw new JSONException("Invalid payment activity text");
        return (String) raw;
    }

}
