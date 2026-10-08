package com.subhub.app.penance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class PayPalRequestPolicyTest {
    @Test public void manualAndAutomaticFlowsUseSeparateStableIdempotencyKeys() {
        String settlement = "0f503239-84c9-45ee-94d4-900bed63ba7f";
        assertEquals(settlement + "-create",
                PayPalRequestPolicy.createRequestId(settlement));
        assertEquals(settlement + "-create-standard",
                PayPalRequestPolicy.standardCreateRequestId(settlement));
        assertEquals(settlement + "-capture",
                PayPalRequestPolicy.captureRequestId(settlement));
        assertEquals(settlement + "-auto",
                PayPalRequestPolicy.autoRequestId(settlement));
    }

    @Test public void onlyTransientHttpFailuresAreRetried() {
        assertTrue(PayPalRequestPolicy.isTransientStatus(408));
        assertTrue(PayPalRequestPolicy.isTransientStatus(429));
        assertTrue(PayPalRequestPolicy.isTransientStatus(500));
        assertTrue(PayPalRequestPolicy.isTransientStatus(503));
        assertFalse(PayPalRequestPolicy.isTransientStatus(400));
        assertFalse(PayPalRequestPolicy.isTransientStatus(401));
        assertFalse(PayPalRequestPolicy.isTransientStatus(422));
    }

    @Test public void readOnlyRequestsNeverOpenAnOutputStream() {
        assertFalse(PayPalRequestPolicy.hasRequestBody("GET", "{}"));
        assertFalse(PayPalRequestPolicy.hasRequestBody("HEAD", "{}"));
        assertFalse(PayPalRequestPolicy.hasRequestBody("POST", ""));
        assertTrue(PayPalRequestPolicy.hasRequestBody("POST", "{}"));
    }

    @Test public void automaticWalletRequestIsMerchantInitiatedAndHeadless() {
        PayPalRequestPolicy.StoredWalletRequest request =
                PayPalRequestPolicy.storedWalletRequest("vault-token");
        assertEquals("vault-token", request.vaultId());
        assertEquals("MERCHANT", request.paymentInitiator());
        assertEquals("SUBSEQUENT", request.usage());
        assertEquals("UNSCHEDULED_POSTPAID", request.usagePattern());
        assertFalse(request.permitsInteractiveCheckout());
        assertFalse(request.permitsLineItems());
        assertEquals(1, request.maximumBillRows());
    }

    @Test(expected = IllegalArgumentException.class)
    public void automaticWalletRequestRejectsMissingVaultToken() {
        PayPalRequestPolicy.storedWalletRequest("  ");
    }

    @Test public void automaticWalletResponseNeverRoutesToCheckout() {
        assertEquals(PayPalRequestPolicy.StoredWalletOutcome.COMPLETED,
                PayPalRequestPolicy.storedWalletOutcome("COMPLETED", false, false));
        assertEquals(PayPalRequestPolicy.StoredWalletOutcome.REAUTHORIZATION_REQUIRED,
                PayPalRequestPolicy.storedWalletOutcome("PAYER_ACTION_REQUIRED", false, false));
        assertEquals(PayPalRequestPolicy.StoredWalletOutcome.REAUTHORIZATION_REQUIRED,
                PayPalRequestPolicy.storedWalletOutcome("CREATED", true, false));
        assertEquals(PayPalRequestPolicy.StoredWalletOutcome.REAUTHORIZATION_REQUIRED,
                PayPalRequestPolicy.storedWalletOutcome("CREATED", false, true));
        assertEquals(PayPalRequestPolicy.StoredWalletOutcome.INVALID_RESPONSE,
                PayPalRequestPolicy.storedWalletOutcome("CREATED", false, false));
    }

    @Test public void eligibleAutomaticSettlementCannotUsePayerPresentCheckout() {
        assertEquals(PayPalRequestPolicy.CheckoutRoute.STORED_WALLET,
                PayPalRequestPolicy.checkoutRoute(false, true, true, true));
        assertEquals(PayPalRequestPolicy.CheckoutRoute.BLOCKED,
                PayPalRequestPolicy.checkoutRoute(false, true, true, false));
        assertEquals(PayPalRequestPolicy.CheckoutRoute.PAYER_PRESENT,
                PayPalRequestPolicy.checkoutRoute(false, true, false, false));
        assertEquals(PayPalRequestPolicy.CheckoutRoute.PAYER_PRESENT,
                PayPalRequestPolicy.checkoutRoute(false, false, true, false));
        assertEquals(PayPalRequestPolicy.CheckoutRoute.PAYER_PRESENT,
                PayPalRequestPolicy.checkoutRoute(true, true, true, true));
    }
}
