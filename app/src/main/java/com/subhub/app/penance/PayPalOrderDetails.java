package com.subhub.app.penance;

import android.content.Context;

import com.subhub.app.R;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;

/** Converts one immutable local settlement into matching PayPal order rows. */
final class PayPalOrderDetails {
    private PayPalOrderDetails() {}

    static List<PayPalOrdersClient.OrderItem> from(
            Context context, PenanceManager.Settlement settlement) {
        List<PayPalOrdersClient.OrderItem> items = new ArrayList<>();
        if (context == null || settlement == null) return items;
        return from(context, settlement.getEvents(), settlement.getCurrency());
    }

    static List<PayPalOrdersClient.OrderItem> from(
            Context context, List<PenanceEvent> events, String currency) {
        WalletCurrency.requireSupported(currency);
        EnumMap<PenanceInfraction, long[]> totals = new EnumMap<>(PenanceInfraction.class);
        for (PenanceEvent event : events) {
            if (!currency.equals(event.getCurrency()) || event.getAmountCents() <= 0
                    || event.getStrikeCount() <= 0) {
                throw new IllegalArgumentException("Invalid settlement bill entry");
            }
            long[] group = totals.computeIfAbsent(event.getInfraction(), ignored -> new long[2]);
            group[0] = Math.addExact(group[0], event.getStrikeCount());
            group[1] = Math.addExact(group[1], event.getAmountCents());
        }
        List<PayPalOrdersClient.OrderItem> items = new ArrayList<>();
        for (var entry : totals.entrySet()) {
            String label = label(context, entry.getKey());
            long count = entry.getValue()[0];
            int amount = Math.toIntExact(entry.getValue()[1]);
            String name = context.getString(R.string.paypal_order_item_name,
                    label, count);
            String description = context.getString(R.string.paypal_bill_line, name,
                    String.format(Locale.ROOT, "%s %d.%02d", currency, amount / 100, amount % 100));
            items.add(new PayPalOrdersClient.OrderItem(name, description, amount));
        }
        return List.copyOf(items);
    }

    private static String label(Context context, PenanceInfraction infraction) {
        if (infraction == PenanceInfraction.TAMPER_ATTEMPT) {
            return context.getString(R.string.penance_history_tamper);
        }
        if (infraction == PenanceInfraction.PAID_PAUSE) {
            return context.getString(R.string.paid_pause_history_label);
        }
        if (infraction == PenanceInfraction.CENSORED_DWELL) {
            return context.getString(R.string.penance_history_dwell);
        }
        if (infraction == PenanceInfraction.CENSORED_TAP) {
            return context.getString(R.string.penance_history_tap);
        }
        if (infraction == PenanceInfraction.WATCHED_APP_OPEN) {
            return context.getString(R.string.penance_history_app_open);
        }
        return context.getString(R.string.penance_history_detection);
    }
}
