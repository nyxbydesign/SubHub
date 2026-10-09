package com.subhub.app.penance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.View;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.subhub.app.MainActivity;
import com.subhub.app.R;
import com.subhub.app.appmode.AppModeManager;
import com.subhub.app.commitment.CommitmentManager;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.security.HardcoreModeManager;
import com.subhub.app.settings.FeatureModuleManager;
import com.subhub.app.settings.SettingsRepository;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.EnumMap;
import java.util.Map;

@RunWith(AndroidJUnit4.class)
public final class PenanceContractTest {
    private Context context;
    private PenanceManager manager;

    @Before public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        context.getSharedPreferences(PenanceManager.PREFS_NAME, Context.MODE_PRIVATE)
                .edit().clear().commit();
        context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME, Context.MODE_PRIVATE)
                .edit().remove(HardcoreModeManager.KEY_REQUESTED).commit();
        manager = new PenanceManager(context);
        new FeatureModuleManager(context).save(true, true, true);
        new AppModeManager(context).setArmed(true);
        new HardcoreAutoPayManager(context).disable();
    }

    @After public void tearDown() {
        CommitmentManager.emergencyRelease(context);
        new PaidPauseManager(context).finish();
        context.getSharedPreferences(PenanceManager.PREFS_NAME, Context.MODE_PRIVATE)
                .edit().clear().commit();
        context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME, Context.MODE_PRIVATE)
                .edit().remove(HardcoreModeManager.KEY_REQUESTED).commit();
        new PayPalCredentialStore(context).clear();
        new HardcoreAutoPayManager(context).disable();
        new AppModeManager(context).setArmed(false);
        new FeatureModuleManager(context).save(true, true, true);
    }

    @Test public void disabledProtectionNeverCreatesTribute() {
        long now = 1_725_552_000_000L;
        manager.configure(true, 100, 500, 2_000, 0);
        new AppModeManager(context).setArmed(false);

        assertEquals(0, manager.recordStrikes(1, now));
        assertTrue(manager.snapshot(now).getEvents().isEmpty());
    }

    @Test public void automaticMinimumLeavesSmallBalanceOpenButManualCashoutStillWorks() {
        long now = System.currentTimeMillis();
        manager.configure(true, 1499, 10000, 20000, 0);
        assertEquals(1499, manager.recordStrikes(1, now));
        org.junit.Assert.assertNull(manager.beginAutomaticSettlement(now));
        assertEquals(0, manager.nextAutomaticDueAtMillis(now));
        assertEquals(PenanceEvent.Status.OPEN, manager.snapshot(now).getEvents().get(0).getStatus());
        assertEquals(1499, manager.beginSettlement(now).getAmountCents());
    }

    @Test public void exactAutomaticMinimumSettlesAndExistingSmallerAutomaticRetryKeepsItsId() {
        long now = System.currentTimeMillis();
        manager.configure(true, 1500, 10000, 20000, 0);
        manager.recordStrikes(1, now);
        assertEquals(1500, manager.beginAutomaticSettlement(now).getAmountCents());
        manager.forgiveAllUnpaid();
        manager.configure(true, 100, 10000, 20000, 0);
        manager.recordStrikes(1, now + 1);
        PenanceManager.Settlement existing = manager.beginSettlement(now + 1);
        manager.markAutomaticSettlement(existing.getId(), "test-boundary");
        assertEquals(existing.getId(), manager.beginAutomaticSettlement(now + 2).getId());
        assertEquals(100, manager.beginAutomaticSettlement(now + 2).getAmountCents());
    }

    @Test public void paidPauseUsesItsExactConfiguredCheckoutAndStartsAfterPayment() {
        long now = System.currentTimeMillis();
        manager.configure(true, 100, 500, 2_000, 0);
        PaidPauseManager pause = new PaidPauseManager(context);
        pause.configure(true, 375, 12);
        CommitmentManager.start(context, 60L * 60L * 1000L);
        new AppModeManager(context).setArmed(true);

        assertTrue(manager.requestPaidPause(now));
        PenanceManager.Settlement settlement = manager.beginPaidPauseSettlement(now);
        assertNotNull(settlement);
        assertEquals(375, settlement.getAmountCents());
        assertTrue(manager.completeSettlement(settlement.getId(), 375));
        assertTrue(pause.isActive());
        assertFalse(new AppModeManager(context).isArmed());
    }

    @Test public void disabledMoneyRulesBlockPaidPauseAtEveryActionBoundary() {
        long now = System.currentTimeMillis();
        PaidPauseManager pause = new PaidPauseManager(context);
        pause.configure(true, 375, 12);
        CommitmentManager.start(context, 60L * 60L * 1000L);
        new AppModeManager(context).setArmed(true);

        assertFalse(pause.canPurchase());
        assertFalse(manager.requestPaidPause(now));
        assertTrue(manager.snapshot(now).getEvents().isEmpty());

        manager.configure(true, 100, 500, 2_000, 0);
        assertTrue(manager.requestPaidPause(now));
        PenanceManager.Settlement settlement = manager.beginPaidPauseSettlement(now);
        assertNotNull(settlement);
        manager.configure(false, 100, 500, 2_000, 0);

        assertFalse(manager.completeSettlement(settlement.getId(), 375));
        assertFalse(pause.isActive());
    }

    @Test public void disabledWalletModuleBlocksPaidPauseAndTamperTribute() {
        long now = System.currentTimeMillis();
        Map<PenanceInfraction, Integer> rules = new EnumMap<>(PenanceInfraction.class);
        rules.put(PenanceInfraction.TAMPER_ATTEMPT, 500);
        manager.configure(true, rules, 2_000, 10_000, 0, 10, 1, 5);
        new PaidPauseManager(context).configure(true, 375, 12);
        CommitmentManager.start(context, 60L * 60L * 1000L);
        context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(HardcoreModeManager.KEY_REQUESTED, true).commit();
        context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME, 0).edit()
                .putBoolean(FeatureModuleManager.KEY_WALLET_ENABLED, false).commit();
        context.getSharedPreferences(PenanceManager.PREFS_NAME, 0).edit()
                .putBoolean("enabled", true).remove(PenanceManager.KEY_RULE_PARTICIPATION_MIGRATED).commit();
        manager = new PenanceManager(context);

        assertFalse(new PaidPauseManager(context).canPurchase());
        assertFalse(manager.requestPaidPause(now));
        assertEquals(0, manager.recordInfraction(
                PenanceInfraction.TAMPER_ATTEMPT, 1, now));
        assertEquals(0, TamperTributeReporter.record(context));
        assertTrue(manager.snapshot(now).getEvents().isEmpty());
    }

    @Test public void paidPauseOfferStaysConfigurableWhenMoneyRulesAreDisabled() {
        ControllerPinManager.enterDomMode();
        try (ActivityScenario<PenanceActivity> scenario =
                     ActivityScenario.launch(PenanceActivity.class)) {
            scenario.onActivity(activity -> {
                activity.findViewById(android.R.id.content).findViewWithTag("wallet:pause").performClick();
                assertTrue(activity.findViewById(R.id.paid_pause_config_card).isShown());
                assertTrue(activity.findViewById(R.id.paid_pause_enabled).isEnabled());
                assertFalse(manager.isInfractionEnabled(PenanceInfraction.NEW_DETECTION));
            });
        } finally {
            ControllerPinManager.enterSubMode();
        }
    }

    @Test public void sandboxCredentialsAreEncryptedPerInstallAndCanBeCleared() {
        PayPalCredentialStore store = new PayPalCredentialStore(context);
        store.clear();
        assertTrue(store.save(PayPalEnvironment.SANDBOX,
                "sandbox-client-id", "sandbox-client-secret"));
        PayPalCredentialStore.Credentials loaded = store.load();
        assertEquals("sandbox-client-id", loaded.clientId());
        assertEquals("sandbox-client-secret", loaded.secret());
        assertFalse(store.hasVerifiedCredentials());
        assertTrue(store.markCredentialsVerified());
        assertTrue(store.hasVerifiedCredentials());
        assertEquals(PayPalCredentialStore.VaultStatus.REQUESTED,
                store.vaultState().status());
        for (Object raw : context.getSharedPreferences(
                PayPalCredentialStore.PREFS_NAME, Context.MODE_PRIVATE)
                .getAll().values()) {
            assertNotNull(raw);
            assertFalse("sandbox-client-id".equals(raw));
            assertFalse("sandbox-client-secret".equals(raw));
        }
        store.selectEnvironment(PayPalEnvironment.LIVE);
        assertEquals(PayPalEnvironment.LIVE, store.selectedEnvironment());
        assertFalse(store.hasCredentials());
        assertFalse(store.hasVerifiedCredentials());
        assertEquals(PayPalCredentialStore.VaultStatus.DISCONNECTED,
                store.vaultState().status());
        store.clear();
        assertFalse(store.hasCredentials());
    }

    @Test public void mercyCanForgiveAFalsePositiveWithoutCreatingPayment() {
        long now = 1_725_552_000_000L;
        manager.configure(true, 100, 500, 2_000, 10);
        assertEquals(200, manager.recordStrikes(2, now));
        PenanceSnapshot snapshot = manager.snapshot(now);
        assertEquals(0, snapshot.getDueCents());
        assertEquals(200, snapshot.getMercyCents());
        assertTrue(manager.forgiveLatestInMercy(now));
        assertEquals(0, manager.snapshot(now).getMercyCents());
        assertTrue(manager.getActiveOrderId().isEmpty());
    }

    @Test public void autoPayRequiresAReadyEnvironmentBoundSavedWallet() {
        PayPalCredentialStore store = new PayPalCredentialStore(context);
        assertTrue(store.save(PayPalEnvironment.SANDBOX, "client", "secret"));
        assertTrue(store.markCredentialsVerified());
        PayPalCredentialStore.Credentials credentials = store.load();
        store.recordVaultResult(credentials, "VAULTED", "vault-1", "customer-1",
                "payer@example.com", "PAYER-1234");
        assertEquals("p•••@e•••.com", store.vaultState().maskedPayer());
        HardcoreAutoPayManager auto = new HardcoreAutoPayManager(context);
        assertTrue(auto.enable());
        assertTrue(auto.isEnabled());
        store.selectEnvironment(PayPalEnvironment.LIVE);
        assertFalse(auto.isEnabled());
    }

    @Test public void automaticCheckoutIsNotMistakenForManualConfirmation() {
        long now = System.currentTimeMillis();
        manager.configure(true, 100, 500, 2_000, 0);
        manager.recordStrikes(1, now);
        PenanceManager.Settlement settlement = manager.beginSettlement(now);
        assertNotNull(settlement);
        manager.markAutomaticSettlement(settlement.getId(), "boundary");
        assertEquals(PenanceManager.CheckoutMode.HARDCORE_AUTO,
                manager.getActiveCheckoutMode());
    }

    @Test public void enablingAutoPayClearsAnInteractiveCheckoutRoute() {
        long now = System.currentTimeMillis();
        manager.configure(true, 100, 500, 2_000, 0);
        manager.recordStrikes(1, now);
        PenanceManager.Settlement settlement = manager.beginSettlement(now);
        assertNotNull(settlement);
        manager.bindOrder(settlement.getId(), "ORDER-123",
                "https://www.paypal.com/checkoutnow?token=ORDER-123", "boundary");
        assertEquals(PenanceManager.CheckoutMode.API_APPROVAL,
                manager.getActiveCheckoutMode());

        PayPalCredentialStore store = new PayPalCredentialStore(context);
        assertTrue(store.save(PayPalEnvironment.SANDBOX, "client", "secret"));
        assertTrue(store.markCredentialsVerified());
        PayPalCredentialStore.Credentials credentials = store.load();
        store.recordVaultResult(credentials, "VAULTED", "vault-1", "customer-1",
                "payer@example.com", "PAYER-1234");

        assertTrue(new HardcoreAutoPayManager(context).enable());
        assertEquals(PenanceManager.CheckoutMode.NONE, manager.getActiveCheckoutMode());
        assertTrue(manager.getActiveApprovalUrl().isEmpty());
        assertEquals(100, manager.snapshot(now).getDueCents());
    }

    @Test public void settlementRequiresAnExactConfirmedAmount() {
        long now = 1_725_552_000_000L;
        manager.configure(true, 125, 500, 2_000, 0);
        assertEquals(250, manager.recordStrikes(2, now));
        assertEquals(0L, manager.getTotalPaidCents());
        PenanceManager.Settlement settlement = manager.beginSettlement(now);
        assertNotNull(settlement);
        assertEquals(250, settlement.getAmountCents());
        assertEquals(0L, manager.getTotalPaidCents());
        assertFalse(manager.completeSettlement(settlement.getId(), 249));
        assertEquals(0L, manager.getTotalPaidCents());
        assertEquals(250, manager.snapshot(now).getCheckoutCents());
        assertTrue(manager.completeSettlement(settlement.getId(), 250));
        assertEquals(0, manager.snapshot(now).getCheckoutCents());
        assertEquals(250, manager.snapshot(now).getPaidCents());
        assertEquals(250L, manager.getTotalPaidCents());
        assertFalse(manager.completeSettlement(settlement.getId(), 250));
        assertEquals(250L, manager.getTotalPaidCents());
    }

    @Test public void safetyReleaseForgivesDueAndCheckoutEntries() {
        long now = 1_725_552_000_000L;
        manager.configure(true, 100, 500, 2_000, 0);
        manager.recordStrikes(2, now);
        PenanceManager.Settlement settlement = manager.beginSettlement(now);
        manager.bindOrder(settlement.getId(), "ORDER-123", "https://paypal.example.test/approve");
        manager.forgiveAllUnpaid();
        PenanceSnapshot snapshot = manager.snapshot(now);
        assertEquals(0, snapshot.getDueCents());
        assertEquals(0, snapshot.getCheckoutCents());
        assertTrue(manager.getActiveOrderId().isEmpty());
    }

    @Test public void eachRuleHasItsOwnCostAndDisabledRulesDoNothing() {
        long now = 1_725_552_000_000L;
        Map<PenanceInfraction, Integer> rules = new EnumMap<>(PenanceInfraction.class);
        rules.put(PenanceInfraction.CENSORED_DWELL, 175);
        rules.put(PenanceInfraction.WATCHED_APP_OPEN, 50);
        manager.configure(true, rules, 500, 2_000, 0, 10, 1);
        assertEquals(175, manager.recordInfraction(
                PenanceInfraction.CENSORED_DWELL, 1, now));
        assertEquals(0, manager.recordInfraction(
                PenanceInfraction.CENSORED_TAP, 1, now));
        assertEquals(50, manager.recordInfraction(
                PenanceInfraction.WATCHED_APP_OPEN, 1, now));
        assertTrue(manager.snapshot(now).getEvents().stream().anyMatch(event ->
                event.getInfraction() == PenanceInfraction.WATCHED_APP_OPEN));
    }

    @Test public void enabledCensorTapCreatesItsOwnLedgerEntry() {
        long now = 1_725_552_000_000L;
        Map<PenanceInfraction, Integer> rules = new EnumMap<>(PenanceInfraction.class);
        rules.put(PenanceInfraction.CENSORED_TAP, 250);
        manager.configure(true, rules, 1_000, 5_000, 0, 10, 1);

        assertEquals(250, manager.recordInfraction(
                PenanceInfraction.CENSORED_TAP, 1, now));
        assertTrue(manager.snapshot(now).getEvents().stream().anyMatch(event ->
                event.getInfraction() == PenanceInfraction.CENSORED_TAP
                        && event.getAmountCents() == 250));
    }

    @Test public void tamperTributeRequiresHardcoreAndIsRateLimited() {
        long now = 1_725_552_000_000L;
        Map<PenanceInfraction, Integer> rules = new EnumMap<>(PenanceInfraction.class);
        rules.put(PenanceInfraction.TAMPER_ATTEMPT, 500);
        manager.configure(true, rules, 2_000, 10_000, 0, 10, 1, 5);

        assertEquals(0, manager.recordInfraction(
                PenanceInfraction.TAMPER_ATTEMPT, 1, now));
        context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(HardcoreModeManager.KEY_REQUESTED, true).commit();
        assertEquals(500, manager.recordInfraction(
                PenanceInfraction.TAMPER_ATTEMPT, 1, now));
        assertEquals(0, manager.recordInfraction(
                PenanceInfraction.TAMPER_ATTEMPT, 1, now + 4 * 60_000L));
        assertEquals(500, manager.recordInfraction(
                PenanceInfraction.TAMPER_ATTEMPT, 1, now + 5 * 60_000L));
    }

    @Test public void newDetectionRuleBillsOnlyAtConfiguredBatchBoundary() {
        long now = 1_725_552_000_000L;
        Map<PenanceInfraction, Integer> rules = new EnumMap<>(PenanceInfraction.class);
        rules.put(PenanceInfraction.NEW_DETECTION, 100);
        manager.configure(true, rules, 500, 2_000, 0, 10, 5);
        assertEquals(0, manager.recordInfraction(
                PenanceInfraction.NEW_DETECTION, 2, now));
        assertEquals(100, manager.recordInfraction(
                PenanceInfraction.NEW_DETECTION, 3, now + 1));
        assertEquals(200, manager.recordInfraction(
                PenanceInfraction.NEW_DETECTION, 12, now + 2));
        assertEquals(300, manager.snapshot(now + 2).getDueCents());
    }

    @Test public void ordinaryRuleSaveKeepsBatchProgressButChangingBatchResetsIt() {
        long now = 1_725_552_000_000L;
        Map<PenanceInfraction, Integer> rules = new EnumMap<>(PenanceInfraction.class);
        rules.put(PenanceInfraction.NEW_DETECTION, 100);
        manager.configure(true, rules, 500, 2_000, 0, 10, 5);
        assertEquals(0, manager.recordInfraction(PenanceInfraction.NEW_DETECTION, 2, now));
        assertEquals(2, manager.getDetectionRemainder());

        rules.put(PenanceInfraction.NEW_DETECTION, 125);
        manager.configure(true, rules, 500, 2_000, 0, 10, 5);
        assertEquals(2, manager.getDetectionRemainder());

        manager.configure(true, rules, 500, 2_000, 0, 10, 3);
        assertEquals(0, manager.getDetectionRemainder());
    }

    @Test public void validRulesHaveNoExtraFormulaCaption() {
        manager.configure(true, 100, 500, 2_000, 0);
        try (ActivityScenario<PenanceActivity> scenario = ActivityScenario.launch(PenanceActivity.class)) {
            scenario.onActivity(activity -> assertEquals(android.view.View.GONE,
                    activity.findViewById(R.id.rule_math_preview).getVisibility()));
        }
    }

    @Test public void reachedDailyCapIsVisibleAndExplainsWhyNoMoneyWasAdded() {
        long now = System.currentTimeMillis();
        manager.configure(true, 100, 500, 2_000, 0);
        assertEquals(500, manager.recordStrikes(5, now));
        assertEquals(0, manager.getDailyRemainingCents(now));
        assertEquals(1_500, manager.getWeeklyRemainingCents(now));

        try (ActivityScenario<PenanceActivity> scenario =
                     ActivityScenario.launch(PenanceActivity.class)) {
            scenario.onActivity(activity -> assertEquals(
                    activity.getString(R.string.penance_daily_cap_reached),
                    ((TextView) activity.findViewById(R.id.rule_math_preview))
                            .getText().toString()));
        }
    }

    @Test public void fullHistoryDropsOldForgivenEntriesBeforeRejectingNewOnes() {
        long now = System.currentTimeMillis();
        manager.configure(true, 1, 50_000, 200_000, 0);
        for (int index = 0; index < 200; index++) {
            assertEquals(1, manager.recordStrikes(1, now + index));
        }
        manager.forgiveAllUnpaid();
        assertEquals(1, manager.recordStrikes(1, now + 201));
    }

    @Test public void walletUsesFocusedRuleEditorAndSubHomeEntryRemainsAvailable() {
        ControllerPinManager.enterDomMode();
        try (ActivityScenario<PenanceActivity> scenario =
                     ActivityScenario.launch(PenanceActivity.class)) {
            scenario.onActivity(activity ->
                            activity.findViewById(android.R.id.content)
                                    .findViewWithTag("wallet:rules")
                                    .performClick());
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
                    .waitForIdleSync();
            scenario.onActivity(
                    activity -> {
                        assertTrue(activity.findViewById(R.id.rule_config_card).isShown());
                        assertTrue(activity.findViewById(R.id.safety_config_card).isShown());
                android.view.ViewGroup rules = activity.findViewById(R.id.rule_grid);
                assertEquals(4, rules.getChildCount());
                        for (int i = 1; i < rules.getChildCount(); i++) {
                            assertTrue(rules.getChildAt(i).getTop() > rules.getChildAt(i - 1).getTop());
                assertEquals(rules.getChildAt(0).getWidth(), rules.getChildAt(i).getWidth());
                        }
                        assertEquals(
                                View.VISIBLE,
                                activity.findViewById(R.id.detection_batch).getVisibility());
                activity.findViewById(R.id.button_back).performClick();
                        assertTrue(activity.findViewById(R.id.wallet_overview).isShown());
            });
        }
        ControllerPinManager.enterSubMode();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> assertEquals(View.VISIBLE,
                    activity.findViewById(R.id.sub_wallet_card).getVisibility()));
        }
    }

    @Test public void detectionBatchInputFollowsNewDetectionRuleToggle() {
        ControllerPinManager.enterDomMode();
        try (ActivityScenario<PenanceActivity> scenario =
                     ActivityScenario.launch(PenanceActivity.class)) {
            scenario.onActivity(activity -> {
                android.widget.CompoundButton toggle = activity.findViewById(
                        R.id.rule_detection_enabled);
                View batch = activity.findViewById(R.id.detection_batch);
                toggle.setChecked(false);
                assertFalse(batch.isEnabled());
                assertTrue(batch.getAlpha() < 0.5f);
                toggle.setChecked(true);
                assertTrue(batch.isEnabled());
                assertEquals(1f, batch.getAlpha(), 0.01f);
            });
        } finally {
            ControllerPinManager.enterSubMode();
        }
    }
}
