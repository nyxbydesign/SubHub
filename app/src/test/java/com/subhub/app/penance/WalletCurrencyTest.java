package com.subhub.app.penance;

import org.junit.Test;
import static org.junit.Assert.*;

public final class WalletCurrencyTest {
    @Test public void supportedAmountsAreExactAndNeverConverted() {
        assertEquals("€15.00", WalletCurrency.format("EUR", 1500));
        assertEquals("$15.00", WalletCurrency.format("USD", 1500));
        assertEquals("$0.01", WalletCurrency.format("USD", 1));
        assertEquals("€0.00", WalletCurrency.format("EUR", -1));
        assertEquals("$92233720368547758.07", WalletCurrency.format("USD", Long.MAX_VALUE));
    }

    @Test public void legacyEntriesRemainEurAndStatusTransitionsKeepUsd() {
        PenanceEvent legacy = new PenanceEvent("old", 0, 0, 100, 1,
                PenanceEvent.Status.PAID, "old-settlement");
        assertEquals("EUR", legacy.getCurrency());
        PenanceEvent usd = new PenanceEvent("new", 0, 0, 100, 1,
                PenanceInfraction.NEW_DETECTION, PenanceEvent.Status.OPEN, "", "USD");
        assertEquals("USD", usd.withStatus(PenanceEvent.Status.CHECKOUT, "payment")
                .withStatus(PenanceEvent.Status.PAID, "payment").getCurrency());
    }

    @Test public void unsupportedCurrenciesAreRejectedRatherThanDefaulting() {
        for (String code : new String[] {null, "", "GBP", "eur", "USD,EUR"}) {
            assertFalse(WalletCurrency.isSupported(code));
            try {
                WalletCurrency.format(code, 100);
                fail("Unsupported code accepted");
            } catch (IllegalArgumentException expected) { }
        }
    }
}
