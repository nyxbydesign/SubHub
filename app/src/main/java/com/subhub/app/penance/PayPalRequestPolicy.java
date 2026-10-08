package com.subhub.app.penance;

/** Pure policy for PayPal idempotency keys and bounded transient retry decisions. */
public final class PayPalRequestPolicy {
    private PayPalRequestPolicy() {}

    public static String createRequestId(String settlementId) {
        return settlementId + "-create";
    }

    public static String standardCreateRequestId(String settlementId) {
        return settlementId + "-create-standard";
    }

    public static String captureRequestId(String settlementId) {
        return settlementId + "-capture";
    }

    public static String autoRequestId(String settlementId) {
        return settlementId + "-auto";
    }

    public static String vaultSetupRequestId(String nonce) {
        return nonce + "-vault-setup";
    }

    public static String vaultConfirmRequestId(String setupTokenId) {
        return setupTokenId + "-vault-confirm";
    }

    public static boolean hasRequestBody(String method, String body) {
        if (body == null || body.isEmpty()) return false;
        return !"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method);
    }

    public static boolean isTransientStatus(int status) {
        return status == 408 || status == 429 || status >= 500;
    }

    /** Canonical payer-absent PayPal Wallet source. It intentionally has no checkout URL fields. */
    public static StoredWalletRequest storedWalletRequest(String vaultId) {
        String cleanVaultId = vaultId == null ? "" : vaultId.trim();
        if (cleanVaultId.isEmpty()) {
            throw new IllegalArgumentException("Saved PayPal wallet token is missing");
        }
        return new StoredWalletRequest(cleanVaultId, "MERCHANT", "SUBSEQUENT",
                "UNSCHEDULED_POSTPAID");
    }

    public static StoredWalletOutcome storedWalletOutcome(
            String status, boolean hasPayerAction, boolean hasApproval) {
        if (hasPayerAction || hasApproval
                || "PAYER_ACTION_REQUIRED".equalsIgnoreCase(status)) {
            return StoredWalletOutcome.REAUTHORIZATION_REQUIRED;
        }
        return "COMPLETED".equalsIgnoreCase(status)
                ? StoredWalletOutcome.COMPLETED : StoredWalletOutcome.INVALID_RESPONSE;
    }

    public static CheckoutRoute checkoutRoute(
            boolean paidPauseOnly, boolean automaticContext,
            boolean autoPayConfigured, boolean autoPayEligible) {
        if (paidPauseOnly || !automaticContext) return CheckoutRoute.PAYER_PRESENT;
        if (autoPayEligible) return CheckoutRoute.STORED_WALLET;
        return autoPayConfigured ? CheckoutRoute.BLOCKED : CheckoutRoute.PAYER_PRESENT;
    }

    public enum StoredWalletOutcome {
        COMPLETED, REAUTHORIZATION_REQUIRED, INVALID_RESPONSE
    }

    public enum CheckoutRoute { STORED_WALLET, PAYER_PRESENT, BLOCKED }

    public static final class StoredWalletRequest {
        private final String vaultId;
        private final String paymentInitiator;
        private final String usage;
        private final String usagePattern;

        private StoredWalletRequest(String vaultId, String paymentInitiator,
                String usage, String usagePattern) {
            this.vaultId = vaultId;
            this.paymentInitiator = paymentInitiator;
            this.usage = usage;
            this.usagePattern = usagePattern;
        }

        public String vaultId() { return vaultId; }
        public String paymentInitiator() { return paymentInitiator; }
        public String usage() { return usage; }
        public String usagePattern() { return usagePattern; }
        public boolean permitsInteractiveCheckout() { return false; }
        /** Separate per-infraction purchase rows stay disabled; one aggregate bill is metadata. */
        public boolean permitsLineItems() { return false; }
        public int maximumBillRows() { return 1; }
    }
}
