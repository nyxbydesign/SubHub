package com.subhub.app.penance;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;

import androidx.core.app.NotificationCompat;

import com.subhub.app.R;

import java.util.concurrent.atomic.AtomicBoolean;

/** Fail-closed saved-wallet settlement engine with stable settlement idempotency. */
final class HardcoreAutoPayEngine {
    private static final String CHANNEL = "hardcore_wallet";
    private static final AtomicBoolean RUNNING = new AtomicBoolean();

    private HardcoreAutoPayEngine() {}

    static void run(Context context, Runnable finished) {
        run(context, false, finished);
    }

    static void run(Context context, boolean manualCashout, Runnable finished) {
        Context app = context.getApplicationContext();
        if (!RUNNING.compareAndSet(false, true)) {
            finished.run();
            return;
        }
        HardcoreAutoPayManager policy = new HardcoreAutoPayManager(app);
        if (!policy.isEligibleNow()) {
            done(finished);
            return;
        }
        PenanceManager penance = new PenanceManager(app);
        PenanceManager.CheckoutMode existingMode = penance.getActiveCheckoutMode();
        if (existingMode != PenanceManager.CheckoutMode.NONE
                && existingMode != PenanceManager.CheckoutMode.HARDCORE_AUTO) {
            String settlementId = penance.getActiveSettlementId();
            if (!settlementId.isEmpty()) penance.cancelSettlement(settlementId);
            policy.pause("An interactive checkout blocked automatic Wallet payment");
            notify(app, false, "Automatic Wallet payment paused");
            done(finished);
            return;
        }
        long now = System.currentTimeMillis();
        PenanceManager.Settlement settlement = manualCashout ? penance.beginSettlement(now)
                : penance.beginAutomaticSettlement(now);
        if (settlement == null) {
            HardcoreAutoPayManager.schedule(app);
            done(finished);
            return;
        }
        PayPalCredentialStore store = new PayPalCredentialStore(app);
        PayPalCredentialStore.Credentials credentials = store.load();
        PayPalCredentialStore.VaultState vault = store.vaultState();
        if (!credentials.isComplete() || !vault.isReady()) {
            penance.cancelSettlement(settlement.getId());
            policy.pause("Saved wallet authorization is no longer ready");
            notify(app, false, "Automatic Wallet payment paused");
            done(finished);
            return;
        }
        java.util.List<PayPalOrdersClient.OrderItem> bill;
        try {
            bill = PayPalOrderDetails.from(app, settlement);
        } catch (RuntimeException invalidBill) {
            penance.cancelSettlement(settlement.getId());
            policy.pause("The local settlement bill was invalid");
            notify(app, false, "Automatic Wallet payment paused");
            done(finished);
            return;
        }
        penance.markAutomaticSettlement(settlement.getId(), credentials.boundaryId());
        PayPalOrdersClient client = new PayPalOrdersClient(app);
        client.createStoredWalletPayment(credentials, settlement.getId(),
                settlement.getAmountCents(), settlement.getCurrency(), vault.vaultId(), bill, result -> {
                    try {
                        if (result.isSuccess()) {
                            if (penance.completeSettlement(
                                    settlement.getId(), settlement.getAmountCents())) {
                                policy.markPaid();
                                notify(app, true, "Wallet payment completed · "
                                        + WalletCurrency.format(settlement.getCurrency(),
                                                settlement.getAmountCents()));
                            } else {
                                policy.pause("The local settlement no longer matched");
                                notify(app, false, "Automatic Wallet payment paused");
                            }
                            HardcoreAutoPayManager.schedule(app);
                        } else if (result.errorKind() == PayPalOrdersClient.ErrorKind.NETWORK
                                || result.errorKind() == PayPalOrdersClient.ErrorKind.TRANSIENT) {
                            // The result may be ambiguous. Keep the same settlement and request ID.
                            HardcoreAutoPayManager.scheduleRetry(app);
                        } else {
                            penance.cancelSettlement(settlement.getId());
                            if (result.errorKind() == PayPalOrdersClient.ErrorKind.VAULT_UNAVAILABLE
                                    || result.errorKind()
                                    == PayPalOrdersClient.ErrorKind.REAUTHORIZATION_REQUIRED) {
                                store.markVaultUnavailable(credentials);
                            }
                            policy.pause(result.error());
                            notify(app, false, result.errorKind()
                                    == PayPalOrdersClient.ErrorKind.REAUTHORIZATION_REQUIRED
                                    ? "PayPal wallet approval is needed again"
                                    : "Automatic Wallet payment paused");
                        }
                    } finally {
                        client.close();
                        done(finished);
                    }
                });
    }

    private static void done(Runnable finished) {
        RUNNING.set(false);
        finished.run();
    }

    private static void notify(Context context, boolean success, String text) {
        NotificationManager notifications = context.getSystemService(NotificationManager.class);
        if (notifications == null) return;
        notifications.createNotificationChannel(new NotificationChannel(
                CHANNEL, "Hardcore Wallet", NotificationManager.IMPORTANCE_DEFAULT));
        notifications.notify(9062, com.subhub.app.privacy.PrivacyNotifications.present(context, 9062, new NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(success ? "SubHub Wallet" : "SubHub Wallet needs attention")
                .setContentText(text)
                .setAutoCancel(true)
                .build()));
    }
}
