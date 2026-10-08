package com.subhub.app.penance;

import java.util.Locale;

/** Supported two-decimal wallet denominations. No exchange-rate conversion. */
public final class WalletCurrency {
    private WalletCurrency() { }

    public static boolean isSupported(String code) {
        return "EUR".equals(code) || "USD".equals(code);
    }

    public static String requireSupported(String code) {
        if (!isSupported(code)) throw new IllegalArgumentException("Unsupported wallet currency");
        return code;
    }

    public static String format(String code, long cents) {
        requireSupported(code);
        long amount = Math.max(0L, cents);
        return String.format(Locale.ROOT, "%s%d.%02d", "EUR".equals(code) ? "€" : "$",
                amount / 100L, amount % 100L);
    }
}
