package com.subhub.app.penance;

import android.content.Intent;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.subhub.app.R;
import com.subhub.app.databinding.ViewWalletConnectionBinding;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.util.AsyncUiScope;

import java.net.URI;
import java.util.Locale;

/**
 * Wallet-owned connection UI; payment clients, storage and authorization boundaries stay central.
 */
public final class WalletConnectionController implements AutoCloseable {
    private final AppCompatActivity activity;
    private ViewWalletConnectionBinding binding;
    private final TextView summary;
    private final PayPalCredentialStore paypalCredentials;
    private final PayPalOrdersClient paypalClient;
    private final HardcoreAutoPayManager autoPay;
    private final AsyncUiScope uiData;
    private boolean updatingPaypalEnvironment,
            updatingAutoPay,
            paypalConnecting,
            updatingWalletCurrency,
            currencyReading,
            paypalVaultBusy,
            paypalApprovalLaunched,
            paypalReadyForDisplay;

    public WalletConnectionController(
            AppCompatActivity activity, ViewWalletConnectionBinding binding, TextView summary) {
        this.activity = activity;
        this.binding = binding;
        this.summary = summary;
        paypalCredentials = new PayPalCredentialStore(activity);
        paypalClient = new PayPalOrdersClient(activity);
        autoPay = new HardcoreAutoPayManager(activity);
        uiData = AsyncUiScope.forPage(activity);
        binding.paypalLink.setText(new PenanceManager(activity).getPayPalLink());
        PayPalCredentialStore.Credentials credentials = paypalCredentials.load();
        binding.paypalClientId.setText(credentials.clientId());
        updatingPaypalEnvironment = true;
        binding.paypalEnvironment.check(
                credentials.environment() == PayPalEnvironment.LIVE
                        ? R.id.paypal_environment_live
                        : R.id.paypal_environment_sandbox);
        updatingPaypalEnvironment = false;
        binding.buttonSavePaypal.setOnClickListener(view -> savePayPalLink());
        binding.buttonSavePaypalSandbox.setOnClickListener(view -> savePayPalSandbox());
        binding.buttonClearPaypalSandbox.setOnClickListener(view -> clearPayPalSandbox());
        binding.buttonLinkPaypalWallet.setOnClickListener(view -> linkPayPalWallet());
        binding.paypalEnvironment.setOnCheckedChangeListener(
                (group, checkedId) -> {
                    if (!updatingPaypalEnvironment) changePayPalEnvironment(checkedId);
                });
        binding.walletCurrency.setOnCheckedChangeListener(
                (group, checkedId) -> {
                    if (updatingWalletCurrency) return;
                    if (!editingAllowed() || !paypalCredentials.primaryCurrency().isEmpty()) {
                        refreshWalletCurrency();
                        return;
                    }
                    String currency = checkedId == R.id.wallet_currency_usd ? "USD" : "EUR";
                    PenanceManager wallet = new PenanceManager(activity);
                    if (currency.equals(wallet.getCurrency())) return;
                    com.subhub.app.util.ThemedDialogs.builder(activity)
                            .setTitle(R.string.wallet_currency_label)
                            .setMessage(R.string.wallet_currency_help)
                            .setNegativeButton(
                                    android.R.string.cancel,
                                    (dialog, which) -> refreshWalletCurrency())
                            .setOnCancelListener(dialog -> refreshWalletCurrency())
                            .setPositiveButton(
                                    android.R.string.ok,
                                    (dialog, which) -> {
                                        if (editingAllowed()) {
                                            Toast.makeText(
                                                            activity,
                                                            wallet.changeCurrency(currency)
                                                                    ? R.string
                                                                            .wallet_currency_changed
                                                                    : R.string
                                                                            .wallet_currency_blocked,
                                                            Toast.LENGTH_LONG)
                                                    .show();
                                        }
                                        refreshPayPalSandboxState();
                                    })
                            .show();
                });
        binding.buttonRefreshWalletCurrency.setOnClickListener(view -> readWalletCurrency());
        binding.paypalAutoPayEnabled.setOnCheckedChangeListener(
                (button, checked) -> {
                    if (!updatingAutoPay) changeAutoPay(checked);
                });
        refresh();
    }

    private boolean editingAllowed() {
        return binding != null && ControllerPinManager.isSessionUnlocked();
    }

    private String getString(int id, Object... arguments) {
        return activity.getString(id, arguments);
    }

    private void startActivity(Intent intent) {
        activity.startActivity(intent);
    }

    public void onResume() {
        boolean returned = paypalApprovalLaunched;
        paypalApprovalLaunched = false;
        refresh();
        reconcilePendingPayPalWallet(false, returned);
    }

    public void refresh() {
        if (binding == null) return;
        boolean editing = editingAllowed();
        View[] inputs = {
            binding.paypalLink,
            binding.buttonSavePaypal,
            binding.paypalClientId,
            binding.paypalClientSecret,
            binding.buttonClearPaypalSandbox,
            binding.paypalEnvironmentSandbox,
            binding.paypalEnvironmentLive,
            binding.paypalAutoPayEnabled
        };
        for (View input : inputs) input.setEnabled(editing);
        binding.buttonSavePaypalSandbox.setEnabled(editing && !paypalConnecting);
        refreshPayPalSandboxState();
    }

    private void savePayPalLink() {
        if (!editingAllowed()) return;
        String link =
                binding.paypalLink.getText() == null
                        ? ""
                        : binding.paypalLink.getText().toString().trim();
        if (!link.isEmpty() && !validPayPalLink(link)) {
            Toast.makeText(activity, R.string.paypal_settings_invalid, Toast.LENGTH_SHORT).show();
            return;
        }
        new PenanceManager(activity).savePayPalLink(link);
        Toast.makeText(activity, R.string.paypal_settings_saved, Toast.LENGTH_SHORT).show();
    }

    private void savePayPalSandbox() {
        if (!editingAllowed() || paypalConnecting) return;
        String clientId =
                binding.paypalClientId.getText() == null
                        ? ""
                        : binding.paypalClientId.getText().toString().trim();
        String secret =
                binding.paypalClientSecret.getText() == null
                        ? ""
                        : binding.paypalClientSecret.getText().toString().trim();
        PayPalCredentialStore.Credentials existing = paypalCredentials.load();
        PayPalEnvironment selected = selectedPayPalEnvironment();
        if (secret.isEmpty()
                && selected == existing.environment()
                && clientId.equals(existing.clientId())) secret = existing.secret();
        if (clientId.isEmpty() || secret.isEmpty()) {
            Toast.makeText(activity, R.string.paypal_sandbox_invalid, Toast.LENGTH_SHORT).show();
            return;
        }
        final String verifiedSecret = secret;
        PayPalCredentialStore.Credentials candidate =
                PayPalCredentialStore.Credentials.create(selected, clientId, verifiedSecret);
        String oldBoundary = existing.boundaryId();
        paypalConnecting = true;
        binding.buttonSavePaypalSandbox.setEnabled(false);
        binding.buttonSavePaypalSandbox.setText(R.string.paypal_connecting);
        binding.paypalSandboxStatus.setText(R.string.paypal_environment_status_connecting);
        paypalClient.validateCredentials(
                candidate,
                result -> {
                    if (binding == null) return;
                    paypalConnecting = false;
                    binding.buttonSavePaypalSandbox.setText(R.string.paypal_sandbox_save);
                    binding.buttonSavePaypalSandbox.setEnabled(editingAllowed());
                    if (!result.isSuccess()) {
                        Toast.makeText(
                                        activity,
                                        getString(
                                                R.string.paypal_connection_failed, result.error()),
                                        Toast.LENGTH_LONG)
                                .show();
                        refreshPayPalSandboxState();
                        return;
                    }
                    if (!paypalCredentials.save(selected, clientId, verifiedSecret)
                            || !paypalCredentials.markCredentialsVerified()) {
                        Toast.makeText(
                                        activity,
                                        R.string.paypal_sandbox_store_failed,
                                        Toast.LENGTH_LONG)
                                .show();
                        refreshPayPalSandboxState();
                        return;
                    }
                    binding.paypalClientSecret.setText("");
                    if (!oldBoundary.equals(paypalCredentials.load().boundaryId())) {
                        cancelActivePayPalCheckout();
                    }
                    Toast.makeText(activity, R.string.paypal_sandbox_saved, Toast.LENGTH_LONG)
                            .show();
                    refreshPayPalSandboxState();
                    readWalletCurrency();
                });
    }

    private void clearPayPalSandbox() {
        if (!editingAllowed()) return;
        paypalCredentials.clear();
        cancelActivePayPalCheckout();
        binding.paypalClientId.setText("");
        binding.paypalClientSecret.setText("");
        Toast.makeText(activity, R.string.paypal_sandbox_cleared, Toast.LENGTH_SHORT).show();
        refreshPayPalSandboxState();
    }

    private void refreshPayPalSandboxState() {
        if (binding == null || paypalCredentials == null) return;
        paypalReadyForDisplay = paypalCredentials.hasVerifiedCredentials();
        summary.setText(
                paypalReadyForDisplay
                        ? R.string.settings_services_ready
                        : R.string.settings_services_setup);
        refreshWalletCurrency();
        PayPalEnvironment environment = paypalCredentials.selectedEnvironment();
        binding.paypalSandboxStatus.setText(
                getString(
                        paypalReadyForDisplay
                                ? R.string.paypal_environment_status_ready
                                : R.string.paypal_environment_status_off,
                        environment == PayPalEnvironment.LIVE ? "LIVE" : "SANDBOX"));
        PayPalCredentialStore.VaultState vaultState = paypalCredentials.vaultState();
        PayPalCredentialStore.VaultStatus vault = vaultState.status();
        int vaultStatus;
        switch (vault) {
            case READY:
                vaultStatus = R.string.paypal_vault_status_ready;
                break;
            case PENDING:
                vaultStatus = R.string.paypal_vault_status_pending;
                break;
            case UNAVAILABLE:
                vaultStatus = R.string.paypal_vault_status_unavailable;
                break;
            case REQUESTED:
                vaultStatus = R.string.paypal_vault_status_requested;
                break;
            default:
                vaultStatus = R.string.paypal_vault_status_off;
        }
        binding.paypalVaultStatus.setText(
                vaultState.isReady() && !vaultState.maskedPayer().isEmpty()
                        ? getString(R.string.paypal_vault_status_linked, vaultState.maskedPayer())
                        : getString(vaultStatus));
        PayPalCredentialStore.PendingVaultSetup pending = paypalCredentials.pendingVaultSetup();
        int linkLabel =
                paypalVaultBusy
                        ? R.string.paypal_wallet_linking
                        : pending.isPresent() && validPayPalLink(pending.approvalUrl())
                                ? R.string.paypal_wallet_resume
                                : vaultState.isReady()
                                        ? R.string.paypal_wallet_relink
                                        : R.string.paypal_wallet_link;
        binding.buttonLinkPaypalWallet.setText(linkLabel);
        binding.buttonLinkPaypalWallet.setEnabled(
                editingAllowed() && paypalReadyForDisplay && !paypalVaultBusy);
        updatingAutoPay = true;
        binding.paypalAutoPayEnabled.setChecked(autoPay.isEnabled());
        updatingAutoPay = false;
        String autoPayError = autoPay.lastError();
        boolean autoPayPaused = "PAUSED".equals(autoPay.status()) && !autoPayError.isEmpty();
        binding.paypalAutoPayStatus.setVisibility(autoPayPaused ? View.VISIBLE : View.GONE);
        if (autoPayPaused) {
            binding.paypalAutoPayStatus.setText(
                    getString(R.string.paypal_auto_pay_paused_status, autoPayError));
        }
    }

    private void refreshWalletCurrency() {
        PenanceManager wallet = new PenanceManager(activity);
        String primary = paypalCredentials.primaryCurrency();
        updatingWalletCurrency = true;
        binding.walletCurrency.check(
                "USD".equals(wallet.getCurrency())
                        ? R.id.wallet_currency_usd
                        : R.id.wallet_currency_eur);
        updatingWalletCurrency = false;
        binding.walletCurrencyEur.setEnabled(editingAllowed() && primary.isEmpty());
        binding.walletCurrencyUsd.setEnabled(editingAllowed() && primary.isEmpty());
        binding.buttonRefreshWalletCurrency.setEnabled(
                editingAllowed() && !currencyReading && paypalReadyForDisplay);
        binding.walletCurrencyStatus.setText(
                primary.isEmpty()
                        ? getString(R.string.wallet_currency_help)
                        : getString(
                                R.string.wallet_currency_primary, primary, wallet.getCurrency()));
    }

    private void readWalletCurrency() {
        if (!editingAllowed() || currencyReading || !paypalCredentials.hasVerifiedCredentials())
            return;
        currencyReading = true;
        PayPalCredentialStore.Credentials credentials = paypalCredentials.load();
        refreshWalletCurrency();
        paypalClient.readPrimaryCurrency(
                credentials,
                result -> {
                    currencyReading = false;
                    if (binding == null) return;
                    if (!credentials.boundaryId().equals(paypalCredentials.load().boundaryId())) {
                        refreshPayPalSandboxState();
                        return;
                    }
                    String primary = result.isSuccess() ? result.value() : "";
                    paypalCredentials.recordPrimaryCurrency(credentials, primary);
                    if (!primary.isEmpty() && editingAllowed()) {
                        PenanceManager wallet = new PenanceManager(activity);
                        if (!primary.equals(wallet.getCurrency())) {
                            Toast.makeText(
                                            activity,
                                            wallet.changeCurrency(primary)
                                                    ? R.string.wallet_currency_changed
                                                    : R.string.wallet_currency_blocked,
                                            Toast.LENGTH_LONG)
                                    .show();
                        }
                    } else if (primary.isEmpty()) {
                        Toast.makeText(
                                        activity,
                                        R.string.wallet_currency_unavailable,
                                        Toast.LENGTH_SHORT)
                                .show();
                    }
                    refreshPayPalSandboxState();
                });
    }

    private void linkPayPalWallet() {
        if (!editingAllowed() || paypalVaultBusy) return;
        if (!paypalCredentials.hasVerifiedCredentials()) {
            Toast.makeText(activity, R.string.paypal_wallet_connect_first, Toast.LENGTH_SHORT)
                    .show();
            return;
        }
        PayPalCredentialStore.PendingVaultSetup pending = paypalCredentials.pendingVaultSetup();
        if (pending.isPresent()) {
            reconcilePendingPayPalWallet(true, true);
            return;
        }
        PayPalCredentialStore.Credentials credentials = paypalCredentials.load();
        paypalVaultBusy = true;
        refreshPayPalSandboxState();
        paypalClient.createVaultSetupToken(
                credentials,
                paypalCredentials.vaultState().customerId(),
                result -> {
                    paypalVaultBusy = false;
                    if (binding == null) return;
                    if (!result.isSuccess()) {
                        if (result.errorKind() == PayPalOrdersClient.ErrorKind.VAULT_UNAVAILABLE) {
                            paypalCredentials.markVaultUnavailable(credentials);
                            Toast.makeText(
                                            activity,
                                            R.string.paypal_vault_not_enabled,
                                            Toast.LENGTH_LONG)
                                    .show();
                        } else {
                            Toast.makeText(
                                            activity,
                                            getString(
                                                    R.string.paypal_vault_link_failed,
                                                    result.error()),
                                            Toast.LENGTH_LONG)
                                    .show();
                        }
                        refreshPayPalSandboxState();
                        return;
                    }
                    PayPalOrdersClient.VaultSetup setup = result.value();
                    if (!paypalCredentials.recordPendingVaultSetup(
                            credentials,
                            setup.setupTokenId(),
                            setup.customerId(),
                            setup.clientMetadataId(),
                            setup.approvalUrl())) {
                        Toast.makeText(
                                        activity,
                                        R.string.paypal_sandbox_store_failed,
                                        Toast.LENGTH_LONG)
                                .show();
                        refreshPayPalSandboxState();
                        return;
                    }
                    refreshPayPalSandboxState();
                    if (!openPayPalApproval(setup.approvalUrl())) {
                        Toast.makeText(
                                        activity,
                                        R.string.paypal_wallet_open_failed,
                                        Toast.LENGTH_LONG)
                                .show();
                    }
                });
    }

    private boolean openPayPalApproval(String approvalUrl) {
        if (!validPayPalLink(approvalUrl)) return false;
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(approvalUrl)));
            paypalApprovalLaunched = true;
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private void reconcilePendingPayPalWallet(boolean reopenIfWaiting, boolean showErrors) {
        if (binding == null || paypalCredentials == null || paypalClient == null || paypalVaultBusy)
            return;
        PayPalCredentialStore.PendingVaultSetup pending = paypalCredentials.pendingVaultSetup();
        if (!pending.isPresent()) return;
        PayPalCredentialStore.Credentials credentials = paypalCredentials.load();
        if (!credentials.isComplete() || !credentials.boundaryId().equals(pending.boundaryId()))
            return;
        paypalVaultBusy = true;
        refreshPayPalSandboxState();
        paypalClient.getVaultSetupToken(
                credentials,
                pending.setupTokenId(),
                pending.clientMetadataId(),
                result -> {
                    if (binding == null) return;
                    if (!result.isSuccess()) {
                        paypalVaultBusy = false;
                        if (result.errorKind() == PayPalOrdersClient.ErrorKind.VAULT_UNAVAILABLE) {
                            paypalCredentials.markVaultUnavailable(credentials);
                        }
                        refreshPayPalSandboxState();
                        if (showErrors)
                            Toast.makeText(
                                            activity,
                                            getString(
                                                    R.string.paypal_vault_link_failed,
                                                    result.error()),
                                            Toast.LENGTH_LONG)
                                    .show();
                        return;
                    }
                    if (!result.value().isConfirmable()) {
                        paypalVaultBusy = false;
                        refreshPayPalSandboxState();
                        if (reopenIfWaiting) {
                            if (!openPayPalApproval(pending.approvalUrl())) {
                                Toast.makeText(
                                                activity,
                                                R.string.paypal_wallet_open_failed,
                                                Toast.LENGTH_LONG)
                                        .show();
                            }
                        } else if (showErrors) {
                            Toast.makeText(
                                            activity,
                                            R.string.paypal_vault_still_pending,
                                            Toast.LENGTH_LONG)
                                    .show();
                        }
                        return;
                    }
                    confirmPendingPayPalWallet(credentials, pending, showErrors);
                });
    }

    private void confirmPendingPayPalWallet(
            PayPalCredentialStore.Credentials credentials,
            PayPalCredentialStore.PendingVaultSetup pending,
            boolean showErrors) {
        paypalClient.confirmVaultSetupToken(
                credentials,
                pending.setupTokenId(),
                pending.clientMetadataId(),
                result -> {
                    paypalVaultBusy = false;
                    if (binding == null) return;
                    if (!result.isSuccess()) {
                        if (result.errorKind() == PayPalOrdersClient.ErrorKind.VAULT_UNAVAILABLE) {
                            paypalCredentials.markVaultUnavailable(credentials);
                        }
                        refreshPayPalSandboxState();
                        if (showErrors)
                            Toast.makeText(
                                            activity,
                                            getString(
                                                    R.string.paypal_vault_link_failed,
                                                    result.error()),
                                            Toast.LENGTH_LONG)
                                    .show();
                        return;
                    }
                    PayPalOrdersClient.PaymentToken token = result.value();
                    paypalCredentials.recordVaultResult(
                            credentials,
                            "VAULTED",
                            token.id(),
                            token.customerId(),
                            token.payerEmail(),
                            token.payerAccountId());
                    refreshPayPalSandboxState();
                    if (paypalCredentials.vaultState().isReady()) {
                        Toast.makeText(
                                        activity,
                                        R.string.paypal_vault_link_success,
                                        Toast.LENGTH_LONG)
                                .show();
                    } else {
                        Toast.makeText(
                                        activity,
                                        R.string.paypal_sandbox_store_failed,
                                        Toast.LENGTH_LONG)
                                .show();
                    }
                });
    }

    private PayPalEnvironment selectedPayPalEnvironment() {
        return binding.paypalEnvironment.getCheckedRadioButtonId() == R.id.paypal_environment_live
                ? PayPalEnvironment.LIVE
                : PayPalEnvironment.SANDBOX;
    }

    private void changePayPalEnvironment(int checkedId) {
        if (!editingAllowed()) {
            refreshPayPalEnvironmentSelection();
            return;
        }
        PayPalEnvironment selected =
                checkedId == R.id.paypal_environment_live
                        ? PayPalEnvironment.LIVE
                        : PayPalEnvironment.SANDBOX;
        if (selected == paypalCredentials.selectedEnvironment()) return;
        paypalCredentials.selectEnvironment(selected);
        cancelActivePayPalCheckout();
        binding.paypalClientId.setText("");
        binding.paypalClientSecret.setText("");
        Toast.makeText(activity, R.string.paypal_environment_changed, Toast.LENGTH_SHORT).show();
        refreshPayPalSandboxState();
    }

    private void refreshPayPalEnvironmentSelection() {
        updatingPaypalEnvironment = true;
        binding.paypalEnvironment.check(
                paypalCredentials.selectedEnvironment() == PayPalEnvironment.LIVE
                        ? R.id.paypal_environment_live
                        : R.id.paypal_environment_sandbox);
        updatingPaypalEnvironment = false;
    }

    private void changeAutoPay(boolean enabled) {
        if (!editingAllowed()) {
            refreshPayPalSandboxState();
            return;
        }
        if (!enabled) {
            autoPay.disable();
            refreshPayPalSandboxState();
            return;
        }
        if (!paypalCredentials.vaultState().isReady()) {
            updatingAutoPay = true;
            binding.paypalAutoPayEnabled.setChecked(false);
            updatingAutoPay = false;
            Toast.makeText(activity, R.string.paypal_auto_pay_link_first, Toast.LENGTH_SHORT)
                    .show();
            return;
        }
        com.subhub.app.util.ThemedDialogs.builder(activity)
                .setTitle(R.string.paypal_auto_pay_allow_title)
                .setMessage(R.string.paypal_auto_pay_allow_body)
                .setNegativeButton(
                        android.R.string.cancel,
                        (dialog, which) -> {
                            updatingAutoPay = true;
                            binding.paypalAutoPayEnabled.setChecked(false);
                            updatingAutoPay = false;
                        })
                .setOnCancelListener(
                        dialog -> {
                            updatingAutoPay = true;
                            binding.paypalAutoPayEnabled.setChecked(false);
                            updatingAutoPay = false;
                        })
                .setPositiveButton(
                        R.string.paypal_auto_pay_allow,
                        (dialog, which) -> {
                            if (!autoPay.enable()) {
                                Toast.makeText(
                                                activity,
                                                R.string.paypal_auto_pay_link_first,
                                                Toast.LENGTH_SHORT)
                                        .show();
                            }
                            refreshPayPalSandboxState();
                        })
                .show();
    }

    private void cancelActivePayPalCheckout() {
        PenanceManager penance = new PenanceManager(activity);
        String settlementId = penance.getActiveSettlementId();
        if (!settlementId.isEmpty()) penance.cancelSettlement(settlementId);
    }

    private static boolean validPayPalLink(String value) {
        try {
            URI uri = URI.create(value);
            String host = uri.getHost();
            return host != null
                    && "https".equalsIgnoreCase(uri.getScheme())
                    && ("paypal.me".equalsIgnoreCase(host)
                            || "paypal.com".equalsIgnoreCase(host)
                            || host.toLowerCase(Locale.ROOT).endsWith(".paypal.com"));
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    @Override
    public void close() {
        binding = null;
        paypalClient.close();
    }
}
