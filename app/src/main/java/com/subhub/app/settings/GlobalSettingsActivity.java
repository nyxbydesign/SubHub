package com.subhub.app.settings;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.widget.CompoundButtonCompat;

import com.subhub.app.R;
import com.subhub.app.databinding.ActivityGlobalSettingsBinding;
import com.subhub.app.appmode.AppModeManager;
import com.subhub.app.appmode.AppModePolicy;
import com.subhub.app.appmode.ResumeNotificationManager;
import com.subhub.app.commitment.CommitmentActivity;
import com.subhub.app.diagnostics.DiagnosticsActivity;
import com.subhub.app.penance.PenanceManager;
import com.subhub.app.penance.HardcoreAutoPayManager;
import com.subhub.app.penance.PayPalCredentialStore;
import com.subhub.app.penance.PayPalEnvironment;
import com.subhub.app.penance.PayPalOrdersClient;
import com.subhub.app.studio.StudioActivity;
import com.subhub.app.security.ControllerEditMode;
import com.subhub.app.security.ControllerPinGate;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.pack.SubHubPackLocks;
import com.subhub.app.pack.SubHubPackSchema;
import com.subhub.app.security.HardcoreModeManager;
import com.subhub.app.security.HardcoreReadinessNotificationManager;
import com.subhub.app.service.ScreenCaptureService;
import com.subhub.app.service.ScreenshotAccessibilityService;
import com.subhub.app.util.SubHubNavigation;

import java.text.Collator;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Always-available home for app-wide feature, safety, backup, and support settings. */
public final class GlobalSettingsActivity extends AppCompatActivity {
    private ActivityGlobalSettingsBinding binding;
    private FeatureModuleManager modules;
    private HardcoreModeManager hardcore;
    private AppModeManager appMode;
    private PayPalCredentialStore paypalCredentials;
    private PayPalOrdersClient paypalClient;
    private HardcoreAutoPayManager autoPay;
    private ActivityResultLauncher<Intent> hardcoreActivation;
    private ActivityResultLauncher<Intent> hardcoreAccessibility;
    private boolean updatingHardcore;
    private boolean updatingRecognition;
    private boolean updatingPaypalEnvironment;
    private boolean updatingAutoPay;
    private boolean paypalConnecting;
    private boolean updatingWalletCurrency;
    private boolean currencyReading;
    private boolean paypalVaultBusy;
    private boolean paypalApprovalLaunched;
    private boolean editingUnlocked;
    private final Set<String> censorPackages = new LinkedHashSet<>();
    private final Set<String> timerPackages = new LinkedHashSet<>();
    private final Set<String> subliminalPackages = new LinkedHashSet<>();
    private final ExecutorService appLoader = Executors.newSingleThreadExecutor();

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityGlobalSettingsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        arrangeSettingsSections();
        modules = new FeatureModuleManager(this);
        hardcore = new HardcoreModeManager(this);
        appMode = new AppModeManager(this);
        paypalCredentials = new PayPalCredentialStore(this);
        paypalClient = new PayPalOrdersClient(this);
        autoPay = new HardcoreAutoPayManager(this);
        censorPackages.addAll(appMode.getSelectedPackages());
        timerPackages.addAll(appMode.getTimerPackages());
        subliminalPackages.addAll(appMode.getSubliminalPackages());
        hardcoreActivation = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), ignored -> {
                    boolean active = hardcore.finishActivation();
                    refreshHardcoreState();
                    if (active && !new AppModeManager(this).isAccessibilityEnabled()) {
                        Toast.makeText(this, R.string.hardcore_status_accessibility,
                                Toast.LENGTH_LONG).show();
                    }
                });
        hardcoreAccessibility = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), ignored -> {
                    if (appMode.isAccessibilityEnabled()) {
                        hardcoreActivation.launch(hardcore.activationIntent());
                    } else {
                        hardcore.cancelPendingActivation();
                        Toast.makeText(this, R.string.hardcore_accessibility_cancelled,
                                Toast.LENGTH_LONG).show();
                    }
                    refreshHardcoreState();
                });
        binding.switchModuleCensor.setChecked(modules.isCensorEnabled());
        binding.switchModuleLimits.setChecked(modules.isLimitsEnabled());
        binding.switchModuleWallet.setChecked(modules.isWalletEnabled());
        binding.armed.setChecked(appMode.isArmed());
        binding.modeGroup.check(appMode.getMode() == AppModePolicy.Mode.SELECTED_APPS
                ? R.id.mode_selected : R.id.mode_always);
        binding.paypalLink.setText(new PenanceManager(this).getPayPalLink());
        PayPalCredentialStore.Credentials credentials = paypalCredentials.load();
        binding.paypalClientId.setText(credentials.clientId());
        updatingPaypalEnvironment = true;
        binding.paypalEnvironment.check(credentials.environment() == PayPalEnvironment.LIVE
                ? R.id.paypal_environment_live : R.id.paypal_environment_sandbox);
        updatingPaypalEnvironment = false;
        binding.buttonEditLock.setOnClickListener(view -> toggleEditSession());
        binding.buttonProfiles.setOnClickListener(view ->
                startActivity(new Intent(this, StudioActivity.class)));
        binding.buttonDiagnostics.setOnClickListener(view ->
                startActivity(new Intent(this, DiagnosticsActivity.class)));
        binding.buttonCommitment.setVisibility(View.GONE);
        binding.switchModuleCensor.setOnCheckedChangeListener((button, checked) -> saveModules());
        binding.switchModuleLimits.setOnCheckedChangeListener((button, checked) -> saveModules());
        binding.switchModuleWallet.setOnCheckedChangeListener((button, checked) -> saveModules());
        binding.switchHardcoreMode.setOnCheckedChangeListener((button, checked) -> {
            if (!updatingHardcore) changeHardcoreMode(checked);
        });
        binding.buttonHardcoreSystem.setOnClickListener(view -> openHardcoreSystemPage());
        binding.buttonHardcoreRestricted.setOnClickListener(view -> startActivity(new Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.parse("package:" + getPackageName()))));
        binding.buttonAccessibilitySettings.setOnClickListener(view ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        binding.modeGroup.setOnCheckedChangeListener((group, checkedId) -> {
            if (!updatingRecognition) saveRecognition();
        });
        binding.buttonSavePaypal.setOnClickListener(view -> savePayPalLink());
        binding.buttonSavePaypalSandbox.setOnClickListener(view -> savePayPalSandbox());
        binding.buttonClearPaypalSandbox.setOnClickListener(view -> clearPayPalSandbox());
        binding.buttonLinkPaypalWallet.setOnClickListener(view -> linkPayPalWallet());
        binding.paypalEnvironment.setOnCheckedChangeListener((group, checkedId) -> {
            if (!updatingPaypalEnvironment) changePayPalEnvironment(checkedId);
        });
        binding.walletCurrency.setOnCheckedChangeListener((group, checkedId) -> {
            if (updatingWalletCurrency) return;
            if (!editingUnlocked || !paypalCredentials.primaryCurrency().isEmpty()) {
                refreshWalletCurrency();
                return;
            }
            String currency = checkedId == R.id.wallet_currency_usd ? "USD" : "EUR";
            PenanceManager wallet = new PenanceManager(this);
            if (currency.equals(wallet.getCurrency())) return;
            new AlertDialog.Builder(this).setTitle(R.string.wallet_currency_label)
                    .setMessage(R.string.wallet_currency_help)
                    .setNegativeButton(android.R.string.cancel, (dialog, which) -> refreshWalletCurrency())
                    .setOnCancelListener(dialog -> refreshWalletCurrency())
                    .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                        if (editingUnlocked) {
                            Toast.makeText(this, wallet.changeCurrency(currency)
                                    ? R.string.wallet_currency_changed : R.string.wallet_currency_blocked,
                                    Toast.LENGTH_LONG).show();
                        }
                        refreshPayPalSandboxState();
                    }).show();
        });
        binding.buttonRefreshWalletCurrency.setOnClickListener(view -> readWalletCurrency());
        binding.paypalAutoPayEnabled.setOnCheckedChangeListener((button, checked) -> {
            if (!updatingAutoPay) changeAutoPay(checked);
        });
        binding.buttonToggleApps.setOnClickListener(view -> {
            boolean show = binding.appListContent.getVisibility() != View.VISIBLE;
            binding.appListContent.setVisibility(show ? View.VISIBLE : View.GONE);
            binding.buttonToggleApps.setText(show
                    ? R.string.app_selection_collapse : R.string.app_selection_expand);
        });
        binding.appListContent.setVisibility(View.GONE);
        SubHubNavigation.bind(this, binding.getRoot(), SubHubNavigation.Screen.SETTINGS);
        loadApps();
        applyEditState();
    }

    private void arrangeSettingsSections() {
        LinearLayout container = binding.settingsSections;
        View[] order = {
                binding.settingsGroupProtection,
                binding.hardcoreCard,
                binding.featureAreasCard,
                binding.settingsGroupCoverage,
                binding.androidAccessCard,
                binding.recognitionCard,
                binding.appListCard,
                binding.settingsGroupServices,
                binding.paypalCard,
                binding.appSettingsCard
        };
        for (View card : order) container.removeView(card);
        for (int index = 0; index < order.length; index++) {
            View card = order[index];
            LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) card.getLayoutParams();
            boolean groupLabel = isSettingsGroupLabel(card);
            boolean followsGroupLabel = index > 0 && isSettingsGroupLabel(order[index - 1]);
            params.topMargin = groupLabel
                    ? getResources().getDimensionPixelSize(index == 0
                            ? R.dimen.settings_first_group_gap : R.dimen.settings_group_gap)
                    : (followsGroupLabel ? 0
                            : getResources().getDimensionPixelSize(R.dimen.settings_card_gap));
            params.bottomMargin = groupLabel
                    ? getResources().getDimensionPixelSize(R.dimen.settings_group_label_gap) : 0;
            card.setLayoutParams(params);
            container.addView(card);
        }
        binding.hardcoreCard.setPadding(dp(12), dp(12), dp(12), dp(12));
        LinearLayout.LayoutParams header =
                (LinearLayout.LayoutParams) binding.settingsHeader.getLayoutParams();
        header.bottomMargin = dp(8);
        binding.settingsHeader.setLayoutParams(header);
    }

    private boolean isSettingsGroupLabel(View view) {
        return view == binding.settingsGroupProtection
                || view == binding.settingsGroupCoverage
                || view == binding.settingsGroupServices;
    }

    @Override protected void onResume() {
        super.onResume();
        boolean returnedFromPayPal = paypalApprovalLaunched;
        paypalApprovalLaunched = false;
        applyEditState();
        refreshHardcoreState();
        refreshAccessState();
        HardcoreReadinessNotificationManager.refresh(this);
        reconcilePendingPayPalWallet(false, returnedFromPayPal);
    }

    private void toggleEditSession() {
        if (ControllerPinManager.isSessionUnlocked()) {
            ControllerPinManager.enterSubMode();
            applyEditState();
            SubHubNavigation.bind(this, binding.getRoot(), SubHubNavigation.Screen.SETTINGS);
        } else ControllerPinGate.require(this, this::applyEditState, false);
    }

    private void applyEditState() {
        if (binding == null) return;
        editingUnlocked = ControllerPinManager.isSessionUnlocked();
        applySpaceVisibility();
        ControllerEditMode.renderButton(this, binding.buttonEditLock);
        boolean modulesEditable = editingUnlocked
                && !SubHubPackLocks.isLocked(this, SubHubPackSchema.MODULES);
        binding.switchModuleCensor.setEnabled(modulesEditable);
        binding.switchModuleLimits.setEnabled(modulesEditable);
        binding.switchModuleWallet.setEnabled(modulesEditable);
        binding.switchHardcoreMode.setEnabled(editingUnlocked);
        binding.buttonHardcoreSystem.setEnabled(editingUnlocked);
        binding.buttonHardcoreRestricted.setEnabled(editingUnlocked);
        binding.buttonAccessibilitySettings.setEnabled(editingUnlocked);
        binding.armed.setEnabled(editingUnlocked);
        binding.modeAlways.setEnabled(editingUnlocked);
        binding.modeSelected.setEnabled(editingUnlocked);
        binding.paypalLink.setEnabled(editingUnlocked);
        binding.buttonSavePaypal.setEnabled(editingUnlocked);
        binding.paypalClientId.setEnabled(editingUnlocked);
        binding.paypalClientSecret.setEnabled(editingUnlocked);
        binding.buttonSavePaypalSandbox.setEnabled(editingUnlocked && !paypalConnecting);
        binding.buttonClearPaypalSandbox.setEnabled(editingUnlocked);
        binding.buttonLinkPaypalWallet.setEnabled(editingUnlocked
                && paypalCredentials.hasVerifiedCredentials() && !paypalVaultBusy);
        binding.paypalEnvironmentSandbox.setEnabled(editingUnlocked);
        binding.paypalEnvironmentLive.setEnabled(editingUnlocked);
        binding.paypalAutoPayEnabled.setEnabled(editingUnlocked);
        for (int index = 0; index < binding.appList.getChildCount(); index++) {
            setEnabledRecursive(binding.appList.getChildAt(index), editingUnlocked);
        }
        refreshHardcoreState();
        refreshAccessState();
        refreshPayPalSandboxState();
    }

    private void applySpaceVisibility() {
        boolean domSpace = ControllerPinManager.isDomModeActive();
        int domVisibility = domSpace ? View.VISIBLE : View.GONE;
        binding.settingsGroupProtection.setVisibility(domVisibility);
        binding.hardcoreCard.setVisibility(domVisibility);
        binding.featureAreasCard.setVisibility(domVisibility);
        binding.settingsGroupCoverage.setVisibility(domVisibility);
        binding.androidAccessCard.setVisibility(domVisibility);
        binding.recognitionCard.setVisibility(domVisibility);
        binding.appListCard.setVisibility(domVisibility);
        binding.paypalCard.setVisibility(domVisibility);
        binding.buttonDiagnostics.setVisibility(domVisibility);
        binding.buttonCommitment.setVisibility(View.GONE);
        binding.settingsGroupServices.setVisibility(View.VISIBLE);
        binding.appSettingsCard.setVisibility(View.VISIBLE);
        binding.buttonProfiles.setVisibility(View.VISIBLE);
        binding.settingsSubtitle.setText(domSpace
                ? R.string.global_settings_subtitle
                : R.string.global_settings_subtitle_sub);
    }

    private void saveRecognition() {
        if (!editingUnlocked) return;
        AppModePolicy.Mode mode = binding.modeSelected.isChecked()
                ? AppModePolicy.Mode.SELECTED_APPS : AppModePolicy.Mode.ALWAYS;
        // This card configures where recognition runs. Only Home starts or stops protection.
        boolean armed = appMode.isArmed();
        appMode.save(armed, mode, censorPackages);
        if (armed) ResumeNotificationManager.show(this);
        else ResumeNotificationManager.cancel(this);
        refreshAccessState();
    }

    private void saveAppAssignments() {
        if (!editingUnlocked) return;
        appMode.saveAppSelections(censorPackages, timerPackages, subliminalPackages);
        if (!censorPackages.isEmpty() && !binding.modeSelected.isChecked()) {
            updatingRecognition = true;
            binding.modeGroup.check(R.id.mode_selected);
            updatingRecognition = false;
            saveRecognition();
        }
        renderSelectedCount();
    }

    private void savePayPalLink() {
        if (!editingUnlocked) return;
        String link = binding.paypalLink.getText() == null
                ? "" : binding.paypalLink.getText().toString().trim();
        if (!link.isEmpty() && !validPayPalLink(link)) {
            Toast.makeText(this, R.string.paypal_settings_invalid, Toast.LENGTH_SHORT).show();
            return;
        }
        new PenanceManager(this).savePayPalLink(link);
        Toast.makeText(this, R.string.paypal_settings_saved, Toast.LENGTH_SHORT).show();
    }

    private void savePayPalSandbox() {
        if (!editingUnlocked || paypalConnecting) return;
        String clientId = binding.paypalClientId.getText() == null
                ? "" : binding.paypalClientId.getText().toString().trim();
        String secret = binding.paypalClientSecret.getText() == null
                ? "" : binding.paypalClientSecret.getText().toString().trim();
        PayPalCredentialStore.Credentials existing = paypalCredentials.load();
        PayPalEnvironment selected = selectedPayPalEnvironment();
        if (secret.isEmpty() && selected == existing.environment()
                && clientId.equals(existing.clientId())) secret = existing.secret();
        if (clientId.isEmpty() || secret.isEmpty()) {
            Toast.makeText(this, R.string.paypal_sandbox_invalid, Toast.LENGTH_SHORT).show();
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
        paypalClient.validateCredentials(candidate, result -> {
            if (binding == null) return;
            paypalConnecting = false;
            binding.buttonSavePaypalSandbox.setText(R.string.paypal_sandbox_save);
            binding.buttonSavePaypalSandbox.setEnabled(editingUnlocked);
            if (!result.isSuccess()) {
                Toast.makeText(this, getString(R.string.paypal_connection_failed,
                        result.error()), Toast.LENGTH_LONG).show();
                refreshPayPalSandboxState();
                return;
            }
            if (!paypalCredentials.save(selected, clientId, verifiedSecret)
                    || !paypalCredentials.markCredentialsVerified()) {
                Toast.makeText(this, R.string.paypal_sandbox_store_failed,
                        Toast.LENGTH_LONG).show();
                refreshPayPalSandboxState();
                return;
            }
            binding.paypalClientSecret.setText("");
            if (!oldBoundary.equals(paypalCredentials.load().boundaryId())) {
                cancelActivePayPalCheckout();
            }
            Toast.makeText(this, R.string.paypal_sandbox_saved, Toast.LENGTH_LONG).show();
            refreshPayPalSandboxState();
            readWalletCurrency();
        });
    }

    private void clearPayPalSandbox() {
        if (!editingUnlocked) return;
        paypalCredentials.clear();
        cancelActivePayPalCheckout();
        binding.paypalClientId.setText("");
        binding.paypalClientSecret.setText("");
        Toast.makeText(this, R.string.paypal_sandbox_cleared, Toast.LENGTH_SHORT).show();
        refreshPayPalSandboxState();
    }

    private void refreshPayPalSandboxState() {
        if (binding == null || paypalCredentials == null) return;
        refreshWalletCurrency();
        PayPalEnvironment environment = paypalCredentials.selectedEnvironment();
        binding.paypalSandboxStatus.setText(getString(
                paypalCredentials.hasVerifiedCredentials()
                        ? R.string.paypal_environment_status_ready
                        : R.string.paypal_environment_status_off,
                environment == PayPalEnvironment.LIVE ? "LIVE" : "SANDBOX"));
        PayPalCredentialStore.VaultState vaultState = paypalCredentials.vaultState();
        PayPalCredentialStore.VaultStatus vault = vaultState.status();
        int vaultStatus;
        switch (vault) {
            case READY: vaultStatus = R.string.paypal_vault_status_ready; break;
            case PENDING: vaultStatus = R.string.paypal_vault_status_pending; break;
            case UNAVAILABLE: vaultStatus = R.string.paypal_vault_status_unavailable; break;
            case REQUESTED: vaultStatus = R.string.paypal_vault_status_requested; break;
            default: vaultStatus = R.string.paypal_vault_status_off;
        }
        binding.paypalVaultStatus.setText(vaultState.isReady()
                && !vaultState.maskedPayer().isEmpty()
                ? getString(R.string.paypal_vault_status_linked, vaultState.maskedPayer())
                : getString(vaultStatus));
        PayPalCredentialStore.PendingVaultSetup pending =
                paypalCredentials.pendingVaultSetup();
        int linkLabel = paypalVaultBusy ? R.string.paypal_wallet_linking
                : pending.isPresent() && validPayPalLink(pending.approvalUrl())
                ? R.string.paypal_wallet_resume
                : vaultState.isReady() ? R.string.paypal_wallet_relink
                : R.string.paypal_wallet_link;
        binding.buttonLinkPaypalWallet.setText(linkLabel);
        binding.buttonLinkPaypalWallet.setEnabled(editingUnlocked
                && paypalCredentials.hasVerifiedCredentials() && !paypalVaultBusy);
        updatingAutoPay = true;
        binding.paypalAutoPayEnabled.setChecked(autoPay.isEnabled());
        updatingAutoPay = false;
        String autoPayError = autoPay.lastError();
        boolean autoPayPaused = "PAUSED".equals(autoPay.status()) && !autoPayError.isEmpty();
        binding.paypalAutoPayStatus.setVisibility(autoPayPaused ? View.VISIBLE : View.GONE);
        if (autoPayPaused) {
            binding.paypalAutoPayStatus.setText(getString(
                    R.string.paypal_auto_pay_paused_status, autoPayError));
        }
    }

    private void refreshWalletCurrency() {
        PenanceManager wallet = new PenanceManager(this);
        String primary = paypalCredentials.primaryCurrency();
        updatingWalletCurrency = true;
        binding.walletCurrency.check("USD".equals(wallet.getCurrency())
                ? R.id.wallet_currency_usd : R.id.wallet_currency_eur);
        updatingWalletCurrency = false;
        binding.walletCurrencyEur.setEnabled(editingUnlocked && primary.isEmpty());
        binding.walletCurrencyUsd.setEnabled(editingUnlocked && primary.isEmpty());
        binding.buttonRefreshWalletCurrency.setEnabled(editingUnlocked && !currencyReading
                && paypalCredentials.hasVerifiedCredentials());
        binding.walletCurrencyStatus.setText(primary.isEmpty()
                ? getString(R.string.wallet_currency_help)
                : getString(R.string.wallet_currency_primary, primary, wallet.getCurrency()));
    }

    private void readWalletCurrency() {
        if (!editingUnlocked || currencyReading || !paypalCredentials.hasVerifiedCredentials()) return;
        currencyReading = true;
        PayPalCredentialStore.Credentials credentials = paypalCredentials.load();
        refreshWalletCurrency();
        paypalClient.readPrimaryCurrency(credentials, result -> {
            currencyReading = false;
            if (binding == null) return;
            if (!credentials.boundaryId().equals(paypalCredentials.load().boundaryId())) {
                refreshPayPalSandboxState();
                return;
            }
            String primary = result.isSuccess() ? result.value() : "";
            paypalCredentials.recordPrimaryCurrency(credentials, primary);
            if (!primary.isEmpty() && editingUnlocked) {
                PenanceManager wallet = new PenanceManager(this);
                if (!primary.equals(wallet.getCurrency())) {
                    Toast.makeText(this, wallet.changeCurrency(primary)
                            ? R.string.wallet_currency_changed : R.string.wallet_currency_blocked,
                            Toast.LENGTH_LONG).show();
                }
            } else if (primary.isEmpty()) {
                Toast.makeText(this, R.string.wallet_currency_unavailable, Toast.LENGTH_SHORT).show();
            }
            refreshPayPalSandboxState();
        });
    }

    private void linkPayPalWallet() {
        if (!editingUnlocked || paypalVaultBusy) return;
        if (!paypalCredentials.hasVerifiedCredentials()) {
            Toast.makeText(this, R.string.paypal_wallet_connect_first,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        PayPalCredentialStore.PendingVaultSetup pending =
                paypalCredentials.pendingVaultSetup();
        if (pending.isPresent()) {
            reconcilePendingPayPalWallet(true, true);
            return;
        }
        PayPalCredentialStore.Credentials credentials = paypalCredentials.load();
        paypalVaultBusy = true;
        refreshPayPalSandboxState();
        paypalClient.createVaultSetupToken(credentials,
                paypalCredentials.vaultState().customerId(), result -> {
                    paypalVaultBusy = false;
                    if (binding == null) return;
                    if (!result.isSuccess()) {
                        if (result.errorKind()
                                == PayPalOrdersClient.ErrorKind.VAULT_UNAVAILABLE) {
                            paypalCredentials.markVaultUnavailable(credentials);
                            Toast.makeText(this, R.string.paypal_vault_not_enabled,
                                    Toast.LENGTH_LONG).show();
                        } else {
                            Toast.makeText(this, getString(
                                    R.string.paypal_vault_link_failed, result.error()),
                                    Toast.LENGTH_LONG).show();
                        }
                        refreshPayPalSandboxState();
                        return;
                    }
                    PayPalOrdersClient.VaultSetup setup = result.value();
                    if (!paypalCredentials.recordPendingVaultSetup(credentials,
                            setup.setupTokenId(), setup.customerId(),
                            setup.clientMetadataId(), setup.approvalUrl())) {
                        Toast.makeText(this, R.string.paypal_sandbox_store_failed,
                                Toast.LENGTH_LONG).show();
                        refreshPayPalSandboxState();
                        return;
                    }
                    refreshPayPalSandboxState();
                    if (!openPayPalApproval(setup.approvalUrl())) {
                        Toast.makeText(this, R.string.paypal_wallet_open_failed,
                                Toast.LENGTH_LONG).show();
                    }
                });
    }

    private boolean openPayPalApproval(String approvalUrl) {
        if (!validPayPalLink(approvalUrl)) return false;
        try {
            startActivity(new Intent(Intent.ACTION_VIEW,
                    android.net.Uri.parse(approvalUrl)));
            paypalApprovalLaunched = true;
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private void reconcilePendingPayPalWallet(boolean reopenIfWaiting,
            boolean showErrors) {
        if (binding == null || paypalCredentials == null || paypalClient == null
                || paypalVaultBusy) return;
        PayPalCredentialStore.PendingVaultSetup pending =
                paypalCredentials.pendingVaultSetup();
        if (!pending.isPresent()) return;
        PayPalCredentialStore.Credentials credentials = paypalCredentials.load();
        if (!credentials.isComplete()
                || !credentials.boundaryId().equals(pending.boundaryId())) return;
        paypalVaultBusy = true;
        refreshPayPalSandboxState();
        paypalClient.getVaultSetupToken(credentials, pending.setupTokenId(),
                pending.clientMetadataId(), result -> {
                    if (binding == null) return;
                    if (!result.isSuccess()) {
                        paypalVaultBusy = false;
                        if (result.errorKind()
                                == PayPalOrdersClient.ErrorKind.VAULT_UNAVAILABLE) {
                            paypalCredentials.markVaultUnavailable(credentials);
                        }
                        refreshPayPalSandboxState();
                        if (showErrors) Toast.makeText(this, getString(
                                R.string.paypal_vault_link_failed, result.error()),
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    if (!result.value().isConfirmable()) {
                        paypalVaultBusy = false;
                        refreshPayPalSandboxState();
                        if (reopenIfWaiting) {
                            if (!openPayPalApproval(pending.approvalUrl())) {
                                Toast.makeText(this, R.string.paypal_wallet_open_failed,
                                        Toast.LENGTH_LONG).show();
                            }
                        } else if (showErrors) {
                            Toast.makeText(this, R.string.paypal_vault_still_pending,
                                    Toast.LENGTH_LONG).show();
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
        paypalClient.confirmVaultSetupToken(credentials, pending.setupTokenId(),
                pending.clientMetadataId(), result -> {
                    paypalVaultBusy = false;
                    if (binding == null) return;
                    if (!result.isSuccess()) {
                        if (result.errorKind()
                                == PayPalOrdersClient.ErrorKind.VAULT_UNAVAILABLE) {
                            paypalCredentials.markVaultUnavailable(credentials);
                        }
                        refreshPayPalSandboxState();
                        if (showErrors) Toast.makeText(this, getString(
                                R.string.paypal_vault_link_failed, result.error()),
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    PayPalOrdersClient.PaymentToken token = result.value();
                    paypalCredentials.recordVaultResult(credentials, "VAULTED", token.id(),
                            token.customerId(), token.payerEmail(), token.payerAccountId());
                    refreshPayPalSandboxState();
                    if (paypalCredentials.vaultState().isReady()) {
                        Toast.makeText(this, R.string.paypal_vault_link_success,
                                Toast.LENGTH_LONG).show();
                    } else {
                        Toast.makeText(this, R.string.paypal_sandbox_store_failed,
                                Toast.LENGTH_LONG).show();
                    }
                });
    }

    private PayPalEnvironment selectedPayPalEnvironment() {
        return binding.paypalEnvironment.getCheckedRadioButtonId()
                == R.id.paypal_environment_live
                ? PayPalEnvironment.LIVE : PayPalEnvironment.SANDBOX;
    }

    private void changePayPalEnvironment(int checkedId) {
        if (!editingUnlocked) {
            refreshPayPalEnvironmentSelection();
            return;
        }
        PayPalEnvironment selected = checkedId == R.id.paypal_environment_live
                ? PayPalEnvironment.LIVE : PayPalEnvironment.SANDBOX;
        if (selected == paypalCredentials.selectedEnvironment()) return;
        paypalCredentials.selectEnvironment(selected);
        cancelActivePayPalCheckout();
        binding.paypalClientId.setText("");
        binding.paypalClientSecret.setText("");
        Toast.makeText(this, R.string.paypal_environment_changed, Toast.LENGTH_SHORT).show();
        refreshPayPalSandboxState();
    }

    private void refreshPayPalEnvironmentSelection() {
        updatingPaypalEnvironment = true;
        binding.paypalEnvironment.check(
                paypalCredentials.selectedEnvironment() == PayPalEnvironment.LIVE
                        ? R.id.paypal_environment_live : R.id.paypal_environment_sandbox);
        updatingPaypalEnvironment = false;
    }

    private void changeAutoPay(boolean enabled) {
        if (!editingUnlocked) {
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
            Toast.makeText(this, R.string.paypal_auto_pay_link_first,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.paypal_auto_pay_allow_title)
                .setMessage(R.string.paypal_auto_pay_allow_body)
                .setNegativeButton(android.R.string.cancel, (dialog, which) -> {
                    updatingAutoPay = true;
                    binding.paypalAutoPayEnabled.setChecked(false);
                    updatingAutoPay = false;
                })
                .setOnCancelListener(dialog -> {
                    updatingAutoPay = true;
                    binding.paypalAutoPayEnabled.setChecked(false);
                    updatingAutoPay = false;
                })
                .setPositiveButton(R.string.paypal_auto_pay_allow, (dialog, which) -> {
                    if (!autoPay.enable()) {
                        Toast.makeText(this, R.string.paypal_auto_pay_link_first,
                                Toast.LENGTH_SHORT).show();
                    }
                    refreshPayPalSandboxState();
                })
                .show();
    }

    private void cancelActivePayPalCheckout() {
        PenanceManager penance = new PenanceManager(this);
        String settlementId = penance.getActiveSettlementId();
        if (!settlementId.isEmpty()) penance.cancelSettlement(settlementId);
    }

    private static boolean validPayPalLink(String value) {
        try {
            URI uri = URI.create(value);
            String host = uri.getHost();
            return host != null && "https".equalsIgnoreCase(uri.getScheme())
                    && ("paypal.me".equalsIgnoreCase(host)
                    || "paypal.com".equalsIgnoreCase(host)
                    || host.toLowerCase(Locale.ROOT).endsWith(".paypal.com"));
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private void refreshAccessState() {
        if (binding == null || appMode == null) return;
        int status;
        if (!appMode.isAccessibilityEnabled()) status = R.string.app_mode_status_permission_off;
        else if (ScreenshotAccessibilityService.isRecognitionActive()) {
            status = R.string.app_mode_status_recognizing;
        } else if (ScreenshotAccessibilityService.isRunning()) {
            status = R.string.app_mode_status_waiting;
        } else status = R.string.app_mode_status_reconnecting;
        binding.serviceStatus.setText(status);
    }

    private void changeHardcoreMode(boolean enabled) {
        if (!ControllerPinManager.isDomModeActive()) {
            refreshHardcoreState();
            return;
        }
        if (enabled) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.hardcore_consent_title)
                    .setMessage(R.string.hardcore_consent_body)
                    .setNegativeButton(android.R.string.cancel,
                            (dialog, which) -> refreshHardcoreState())
                    .setPositiveButton(R.string.hardcore_consent_enable, (dialog, which) -> {
                        hardcore.beginActivation();
                        refreshHardcoreState();
                        if (appMode.isAccessibilityEnabled()) {
                            hardcoreActivation.launch(hardcore.activationIntent());
                        } else {
                            hardcoreAccessibility.launch(
                                    new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                        }
                    })
                    .show();
        } else {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.hardcore_release_title)
                    .setMessage(R.string.hardcore_release_body)
                    .setNegativeButton(android.R.string.cancel,
                            (dialog, which) -> refreshHardcoreState())
                    .setPositiveButton(R.string.hardcore_release, (dialog, which) -> {
                        hardcore.disable();
                        refreshHardcoreState();
                    })
                    .show();
        }
    }

    private void refreshHardcoreState() {
        if (binding == null || hardcore == null) return;
        updatingHardcore = true;
        boolean active = hardcore.isEnabled();
        boolean accessibilityMissing = hardcore.isRequested()
                && !new AppModeManager(this).isAccessibilityEnabled();
        binding.switchHardcoreMode.setChecked(active || hardcore.isRequested());
        binding.buttonHardcoreRestricted.setVisibility(
                accessibilityMissing ? View.VISIBLE : View.GONE);
        if (accessibilityMissing) {
            binding.hardcoreStatus.setText(R.string.hardcore_status_accessibility);
            binding.buttonHardcoreSystem.setText(R.string.hardcore_open_accessibility);
        } else if (hardcore.isGuardReady()) {
            binding.hardcoreStatus.setText(R.string.hardcore_status_active);
            binding.buttonHardcoreSystem.setText(R.string.hardcore_open_admin);
        } else if (hardcore.isRequested()) {
            binding.hardcoreStatus.setText(R.string.hardcore_status_pending);
            binding.buttonHardcoreSystem.setText(R.string.hardcore_open_admin);
        } else {
            binding.hardcoreStatus.setText(R.string.hardcore_status_off);
            binding.buttonHardcoreSystem.setText(R.string.hardcore_open_admin);
        }
        updatingHardcore = false;
    }

    private void openHardcoreSystemPage() {
        if (hardcore.isRequested() && !new AppModeManager(this).isAccessibilityEnabled()) {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        } else {
            startActivity(hardcore.adminSettingsIntent());
        }
    }

    private void saveModules() {
        if (!ControllerPinManager.isSessionUnlocked()) return;
        boolean censor = binding.switchModuleCensor.isChecked();
        modules.save(censor, binding.switchModuleLimits.isChecked(),
                binding.switchModuleWallet.isChecked());
        if (!modules.hasRuntimeFeature()) {
            startService(ScreenCaptureService.stopIntent(this));
            new AppModeManager(this).setArmed(false);
        }
        SubHubNavigation.bind(this, binding.getRoot(), SubHubNavigation.Screen.SETTINGS);
    }

    private void loadApps() {
        appLoader.execute(() -> {
            PackageManager packages = getPackageManager();
            Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            @SuppressWarnings("deprecation")
            List<ResolveInfo> resolved = packages.queryIntentActivities(
                    launcher, PackageManager.MATCH_ALL);
            Map<String, AppEntry> unique = new LinkedHashMap<>();
            for (ResolveInfo info : resolved) {
                if (info.activityInfo == null
                        || getPackageName().equals(info.activityInfo.packageName)) continue;
                String packageName = info.activityInfo.packageName;
                if (unique.containsKey(packageName)) continue;
                CharSequence label = info.loadLabel(packages);
                Drawable icon;
                try { icon = info.loadIcon(packages); }
                catch (RuntimeException ignored) { icon = packages.getDefaultActivityIcon(); }
                unique.put(packageName, new AppEntry(
                        label == null ? packageName : label.toString(), packageName, icon));
            }
            List<AppEntry> entries = new ArrayList<>(unique.values());
            Collator collator = Collator.getInstance(Locale.getDefault());
            entries.sort((left, right) -> collator.compare(left.label, right.label));
            runOnUiThread(() -> renderApps(entries));
        });
    }

    private void renderApps(List<AppEntry> entries) {
        if (binding == null) return;
        binding.loadingApps.setVisibility(View.GONE);
        binding.appList.removeAllViews();
        Set<String> installed = new LinkedHashSet<>();
        int columns = Math.max(2, getResources().getInteger(R.integer.app_picker_columns));
        for (int index = 0; index < entries.size(); index++) {
            AppEntry entry = entries.get(index);
            installed.add(entry.packageName);
            LinearLayout tile = new LinearLayout(this);
            tile.setOrientation(LinearLayout.VERTICAL);
            tile.setGravity(Gravity.CENTER);
            tile.setPadding(dp(6), dp(7), dp(6), dp(6));
            tile.setContentDescription(entry.label + ", " + entry.packageName);

            ImageView icon = new ImageView(this);
            icon.setImageDrawable(entry.icon);
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            tile.addView(icon, new LinearLayout.LayoutParams(dp(36), dp(36)));

            TextView label = new TextView(this);
            label.setText(entry.label);
            label.setTextColor(getColor(R.color.text_primary));
            label.setTextSize(11f);
            label.setGravity(Gravity.CENTER);
            label.setMaxLines(2);
            label.setEllipsize(android.text.TextUtils.TruncateAt.END);
            tile.addView(label, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(34)));

            LinearLayout choices = new LinearLayout(this);
            choices.setOrientation(LinearLayout.VERTICAL);
            CheckBox censor = assignmentCheck(R.string.app_selection_censor,
                    censorPackages.contains(entry.packageName));
            CheckBox limit = assignmentCheck(R.string.app_selection_limit,
                    timerPackages.contains(entry.packageName));
            CheckBox subliminal = assignmentCheck(R.string.app_selection_subliminal,
                    subliminalPackages.contains(entry.packageName));
            choices.addView(censor);
            choices.addView(limit);
            choices.addView(subliminal);
            tile.addView(choices, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

            Runnable updateTile = () -> tile.setBackgroundResource(
                    censor.isChecked() || limit.isChecked() || subliminal.isChecked()
                            ? R.drawable.bg_app_picker_tile_selected
                            : R.drawable.bg_app_picker_tile);
            censor.setOnCheckedChangeListener((button, checked) -> {
                if (checked) censorPackages.add(entry.packageName);
                else censorPackages.remove(entry.packageName);
                updateTile.run();
                saveAppAssignments();
            });
            limit.setOnCheckedChangeListener((button, checked) -> {
                if (checked) timerPackages.add(entry.packageName);
                else timerPackages.remove(entry.packageName);
                updateTile.run();
                saveAppAssignments();
            });
            subliminal.setOnCheckedChangeListener((button, checked) -> {
                if (checked) subliminalPackages.add(entry.packageName);
                else subliminalPackages.remove(entry.packageName);
                updateTile.run();
                saveAppAssignments();
            });
            updateTile.run();
            GridLayout.LayoutParams tileParams = new GridLayout.LayoutParams(
                    GridLayout.spec(index / columns), GridLayout.spec(index % columns, 1f));
            tileParams.width = 0;
            tileParams.height = GridLayout.LayoutParams.WRAP_CONTENT;
            tileParams.setMargins(dp(3), dp(3), dp(3), dp(3));
            binding.appList.addView(tile, tileParams);
        }
        censorPackages.retainAll(installed);
        timerPackages.retainAll(installed);
        subliminalPackages.retainAll(installed);
        renderSelectedCount();
        applyEditState();
    }

    private CheckBox assignmentCheck(int label, boolean checked) {
        CheckBox check = new CheckBox(this);
        check.setText(label);
        check.setTextColor(getColor(R.color.text_secondary));
        check.setTextSize(10f);
        check.setChecked(checked);
        check.setEnabled(editingUnlocked);
        check.setMinHeight(dp(32));
        check.setPadding(0, 0, 0, 0);
        CompoundButtonCompat.setButtonTintList(check,
                ColorStateList.valueOf(getColor(R.color.accent)));
        return check;
    }

    private void renderSelectedCount() {
        if (binding != null) binding.selectedCount.setText(getString(
                R.string.app_selection_count_three, censorPackages.size(), timerPackages.size(),
                subliminalPackages.size()));
    }

    private static void setEnabledRecursive(View view, boolean enabled) {
        view.setEnabled(enabled);
        if (!(view instanceof android.view.ViewGroup)) return;
        android.view.ViewGroup group = (android.view.ViewGroup) view;
        for (int index = 0; index < group.getChildCount(); index++) {
            setEnabledRecursive(group.getChildAt(index), enabled);
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override protected void onDestroy() {
        appLoader.shutdownNow();
        if (paypalClient != null) paypalClient.close();
        binding = null;
        super.onDestroy();
    }

    private static final class AppEntry {
        private final String label;
        private final String packageName;
        private final Drawable icon;

        private AppEntry(String label, String packageName, Drawable icon) {
            this.label = label;
            this.packageName = packageName;
            this.icon = icon;
        }
    }
}
