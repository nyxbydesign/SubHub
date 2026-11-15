package com.subhub.app.penance;

import android.content.Intent;
import android.text.TextUtils;
import android.view.View;
import android.widget.TextView;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.snackbar.Snackbar;
import com.subhub.app.R;
import com.subhub.app.databinding.ViewWalletConnectionBinding;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.util.AsyncUiScope;

import java.net.URI;
import java.util.Locale;
import java.util.function.BiConsumer;

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
    private final BiConsumer<CharSequence, Integer> feedback;
    private boolean updatingPaypalEnvironment,
            updatingAutoPay,
            paypalConnecting,
            updatingWalletCurrency,
            currencyReading,
            paypalVaultBusy,
            paypalApprovalLaunched,
            paypalReadyForDisplay;

    public WalletConnectionController(
            AppCompatActivity activity, ViewWalletConnectionBinding binding, TextView summary,
            BiConsumer<CharSequence, Integer> feedback) {
        this.activity = activity;
        this.binding = binding;
        this.summary = summary;
        this.feedback = feedback;
        paypalCredentials = new PayPalCredentialStore(activity);
        paypalClient = new PayPalOrdersClient(activity);
        autoPay = new HardcoreAutoPayManager(activity);
        uiData = AsyncUiScope.forPage(activity);
        com.subhub.app.util.InputBounds.restore(binding.paypalLink, new PenanceManager(activity).getPayPalLink(), 2048);
        PayPalCredentialStore.Credentials credentials = paypalCredentials.load();
        com.subhub.app.util.InputBounds.restore(binding.paypalClientId, credentials.clientId(), com.subhub.app.util.InputBounds.CREDENTIAL);
        com.subhub.app.util.InputBounds.limit(binding.paypalClientSecret, com.subhub.app.util.InputBounds.CREDENTIAL);
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
                                            showMessage(
                                                    wallet.changeCurrency(currency)
                                                            ? R.string.wallet_currency_changed
                                                            : R.string.wallet_currency_blocked,
                                                    Snackbar.LENGTH_LONG);
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
        return binding != null && ControllerPinManager.canEditConfiguration();
    }

    private void showMessage(@StringRes int resource, int duration) {
        showMessage(activity.getText(resource), duration);
    }

    private void showMessage(CharSequence message, int duration) {
        feedback.accept(message, duration);
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
        if (!link.isEmpty() && (!validPayPalLink(link)
                || link.length() > 2048 && !link.equals(new PenanceManager(activity).getPayPalLink()))) {
            showMessage(R.string.paypal_settings_invalid, Snackbar.LENGTH_SHORT);
            return;
        }
        new PenanceManager(activity).savePayPalLink(link);
        showMessage(R.string.paypal_settings_saved, Snackbar.LENGTH_SHORT);
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
        if (clientId.isEmpty() || secret.isEmpty()
                || clientId.length() > com.subhub.app.util.InputBounds.CREDENTIAL
                    && (selected != existing.environment() || !clientId.equals(existing.clientId()))
                || secret.length() > com.subhub.app.util.InputBounds.CREDENTIAL
                    && (selected != existing.environment() || !secret.equals(existing.secret()))) {
            showMessage(R.string.paypal_sandbox_invalid, Snackbar.LENGTH_SHORT);
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
                        showMessage(
                                getString(R.string.paypal_connection_failed, result.error()),
                                Snackbar.LENGTH_LONG);
                        refreshPayPalSandboxState();
                        return;
                    }
                    if (!paypalCredentials.save(selected, clientId, verifiedSecret)
                            || !paypalCredentials.markCredentialsVerified()) {
                        showMessage(R.string.paypal_sandbox_store_failed, Snackbar.LENGTH_LONG);
                        refreshPayPalSandboxState();
                        return;
                    }
                    binding.paypalClientSecret.setText("");
                    if (!oldBoundary.equals(paypalCredentials.load().boundaryId())) {
                        cancelActivePayPalCheckout();
                    }
                    showMessage(R.string.paypal_sandbox_saved, Snackbar.LENGTH_LONG);
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
        showMessage(R.string.paypal_sandbox_cleared, Snackbar.LENGTH_SHORT);
        refreshPayPalSandboxState();
    }

    private void refreshPayPalSandboxState() {
        if (binding == null || paypalCredentials == null) return;
        paypalReadyForDisplay = paypalCredentials.hasVerifiedCredentials();
        setLabelText(summary, activity.getText(paypalReadyForDisplay
                ? R.string.settings_services_ready : R.string.settings_services_setup));
        refreshWalletCurrency();
        PayPalEnvironment environment = paypalCredentials.selectedEnvironment();
        setLabelText(binding.paypalSandboxStatus,
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
        setLabelText(binding.paypalVaultStatus,
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
        setLabelText(binding.buttonLinkPaypalWallet, activity.getText(linkLabel));
        binding.buttonLinkPaypalWallet.setEnabled(
                editingAllowed() && paypalReadyForDisplay && !paypalVaultBusy);
        updatingAutoPay = true;
        binding.paypalAutoPayEnabled.setChecked(autoPay.isEnabled());
        updatingAutoPay = false;
        String autoPayError = autoPay.lastError();
        boolean autoPayPaused = "PAUSED".equals(autoPay.status()) && !autoPayError.isEmpty();
        binding.paypalAutoPayStatus.setVisibility(autoPayPaused ? View.VISIBLE : View.GONE);
        if (autoPayPaused) {
            setLabelText(binding.paypalAutoPayStatus,
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
        setLabelText(binding.walletCurrencyStatus,
                primary.isEmpty()
                        ? getString(R.string.wallet_currency_help)
                        : getString(
                                R.string.wallet_currency_primary, primary, wallet.getCurrency()));
    }

    private static void setLabelText(TextView label, CharSequence text) {
        if (!TextUtils.equals(label.getText(), text)) label.setText(text);
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
                            showMessage(
                                    wallet.changeCurrency(primary)
                                            ? R.string.wallet_currency_changed
                                            : R.string.wallet_currency_blocked,
                                    Snackbar.LENGTH_LONG);
                        }
                    } else if (primary.isEmpty()) {
                        showMessage(R.string.wallet_currency_unavailable, Snackbar.LENGTH_SHORT);
                    }
                    refreshPayPalSandboxState();
                });
    }

    private void linkPayPalWallet() {
        if (!editingAllowed() || paypalVaultBusy) return;
        if (!paypalCredentials.hasVerifiedCredentials()) {
            showMessage(R.string.paypal_wallet_connect_first, Snackbar.LENGTH_SHORT);
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
                            showMessage(R.string.paypal_vault_not_enabled, Snackbar.LENGTH_LONG);
                        } else {
                            showMessage(
                                    getString(R.string.paypal_vault_link_failed, result.error()),
                                    Snackbar.LENGTH_LONG);
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
                        showMessage(R.string.paypal_sandbox_store_failed, Snackbar.LENGTH_LONG);
                        refreshPayPalSandboxState();
                        return;
                    }
                    refreshPayPalSandboxState();
                    if (!openPayPalApproval(setup.approvalUrl())) {
                        showMessage(R.string.paypal_wallet_open_failed, Snackbar.LENGTH_LONG);
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
                            showMessage(
                                    getString(R.string.paypal_vault_link_failed, result.error()),
                                    Snackbar.LENGTH_LONG);
                        return;
                    }
                    if (!result.value().isConfirmable()) {
                        paypalVaultBusy = false;
                        refreshPayPalSandboxState();
                        if (reopenIfWaiting) {
                            if (!openPayPalApproval(pending.approvalUrl())) {
                                showMessage(
                                        R.string.paypal_wallet_open_failed,
                                        Snackbar.LENGTH_LONG);
                            }
                        } else if (showErrors) {
                            showMessage(R.string.paypal_vault_still_pending, Snackbar.LENGTH_LONG);
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
                            showMessage(
                                    getString(R.string.paypal_vault_link_failed, result.error()),
                                    Snackbar.LENGTH_LONG);
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
                        showMessage(R.string.paypal_vault_link_success, Snackbar.LENGTH_LONG);
                    } else {
                        showMessage(R.string.paypal_sandbox_store_failed, Snackbar.LENGTH_LONG);
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
        showMessage(R.string.paypal_environment_changed, Snackbar.LENGTH_SHORT);
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
            showMessage(R.string.paypal_auto_pay_link_first, Snackbar.LENGTH_SHORT);
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
                                showMessage(
                                        R.string.paypal_auto_pay_link_first,
                                        Snackbar.LENGTH_SHORT);
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
