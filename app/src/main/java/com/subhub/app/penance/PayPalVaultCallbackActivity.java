package com.subhub.app.penance;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import com.subhub.app.R;

/** Narrow approval-return boundary for payer-present PayPal Wallet authorization. */
public final class PayPalVaultCallbackActivity extends Activity {
    private PayPalOrdersClient paypalClient;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        PayPalCredentialStore store = new PayPalCredentialStore(this);
        Uri data = getIntent() == null ? null : getIntent().getData();
        if (!validCallback(data)) {
            finishToWallet(R.string.paypal_vault_link_missing);
            return;
        }
        if ("/vault/cancel".equalsIgnoreCase(data.getPath())) {
            store.clearPendingVaultSetup();
            finishToWallet(R.string.paypal_vault_link_cancelled);
            return;
        }
        PayPalCredentialStore.PendingVaultSetup pending = store.pendingVaultSetup();
        PayPalCredentialStore.Credentials credentials = store.load();
        String callbackMetadata = data.getQueryParameter("cmid");
        if (!pending.isPresent() || !credentials.isComplete()
                || !credentials.boundaryId().equals(pending.boundaryId())
                || (callbackMetadata != null && !callbackMetadata.isEmpty()
                && !callbackMetadata.equals(pending.clientMetadataId()))) {
            finishToWallet(R.string.paypal_vault_link_missing);
            return;
        }
        paypalClient = new PayPalOrdersClient(this);
        paypalClient.confirmVaultSetupToken(credentials, pending.setupTokenId(),
                pending.clientMetadataId(), result -> {
                    if (isFinishing()) return;
                    if (!result.isSuccess()) {
                        if (result.errorKind() == PayPalOrdersClient.ErrorKind.VAULT_UNAVAILABLE) {
                            store.markVaultUnavailable(credentials);
                        }
                        finishToWallet(getString(
                                R.string.paypal_vault_link_failed, result.error()));
                        return;
                    }
                    PayPalOrdersClient.PaymentToken token = result.value();
                    store.recordVaultResult(credentials, "VAULTED", token.id(),
                            token.customerId(), token.payerEmail(), token.payerAccountId());
                    finishToWallet(R.string.paypal_vault_link_success);
                });
    }

    private static boolean validCallback(Uri data) {
        if (data == null || !"subhubapp".equalsIgnoreCase(data.getScheme())
                || !"paypal".equalsIgnoreCase(data.getHost())) return false;
        return "/vault/return".equalsIgnoreCase(data.getPath())
                || "/vault/cancel".equalsIgnoreCase(data.getPath());
    }

    private void finishToWallet(int message) {
        finishToWallet(getString(message));
    }

    private void finishToWallet(String message) {
        if (paypalClient != null) paypalClient.close();
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        startActivity(new Intent(this, PenanceActivity.class)
                        .putExtra(PenanceActivity.EXTRA_SHOW_CONNECTION, true)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        finish();
    }

    @Override protected void onDestroy() {
        if (paypalClient != null) paypalClient.close();
        super.onDestroy();
    }
}
