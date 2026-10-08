package com.subhub.app.penance;

import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.subhub.app.appmode.AppModeManager;
import com.subhub.app.settings.FeatureModuleManager;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Disposable emulator only. No credentials or network requests. */
@RunWith(AndroidJUnit4.class)
public final class WalletCurrencyAndroidTest {
    private Context context;
    private PenanceManager manager;

    @Before public void setup() {
        context = ApplicationProvider.getApplicationContext();
        context.getSharedPreferences(PenanceManager.PREFS_NAME, Context.MODE_PRIVATE)
                .edit().clear().commit();
        manager = new PenanceManager(context);
        new PayPalCredentialStore(context).clear();
        new HardcoreAutoPayManager(context).disable();
        new FeatureModuleManager(context).save(true, true, true);
        new AppModeManager(context).setArmed(true);
        manager.configure(true, 100, 500, 2000, 0);
    }

    @After public void cleanup() {
        manager.forgiveAllUnpaid();
        new AppModeManager(context).setArmed(false);
        com.subhub.app.security.ControllerPinManager.enterSubMode();
        new PayPalCredentialStore(context).clear();
        context.getSharedPreferences(PenanceManager.PREFS_NAME, Context.MODE_PRIVATE)
                .edit().clear().commit();
    }

    @Test public void unpaidMercyCheckoutAndOrderBlockSwitching() {
        long now = System.currentTimeMillis();
        manager.configure(true, 100, 500, 2000, 10);
        assertEquals(100, manager.recordStrikes(1, now));
        assertFalse(manager.changeCurrency("USD"));
        assertFalse(manager.changeCurrency("USD"));
        PenanceManager.Settlement checkout = manager.beginSettlement(now + 600001);
        assertNotNull(checkout);
        assertEquals("EUR", checkout.getCurrency());
        manager.bindOrder(checkout.getId(), "fixture-order", "");
        assertFalse(manager.changeCurrency("USD"));
        assertEquals("EUR", manager.getCurrency());
        assertTrue(manager.completeSettlement(checkout.getId(), 100));
        assertTrue(manager.changeCurrency("USD"));
        assertEquals("USD", new PenanceManager(context).getCurrency());
        assertFalse(new HardcoreAutoPayManager(context).isConfigured());
    }

    @Test public void historyCapsAndPaidTotalsStayInTheirOwnCurrency() {
        long now = System.currentTimeMillis();
        assertEquals(500, manager.recordStrikes(5, now));
        PenanceManager.Settlement eur = manager.beginSettlement(now);
        assertTrue(manager.completeSettlement(eur.getId(), 500));
        assertEquals(500, manager.getTotalPaidCents());
        assertTrue(manager.changeCurrency("USD"));
        assertEquals(0, manager.getTotalPaidCents());
        assertEquals(500, manager.getDailyRemainingCents(now));
        assertEquals(100, manager.recordStrikes(1, now + 1));
        PenanceManager.Settlement usd = manager.beginSettlement(now + 1);
        assertEquals("USD", usd.getCurrency());
        assertTrue(manager.completeSettlement(usd.getId(), 100));
        assertEquals(100, manager.getTotalPaidCents());
        assertEquals(500, manager.getTotalPaidCents("EUR"));
        assertEquals(100, manager.getTotalPaidCents("USD"));
        assertEquals(100, manager.snapshot(now + 2).getPaidCents());
        assertEquals(2, manager.snapshot(now + 2).getEvents().size());
        assertTrue(manager.changeCurrency("EUR"));
        assertEquals(500, manager.getTotalPaidCents());
        assertEquals(0, manager.getDailyRemainingCents(now + 2));
        assertEquals("USD", manager.snapshot(now + 2).getEvents().get(0).getCurrency());
    }

    @Test public void legacySevenAndEightFieldRowsMigrateAsEur() {
        context.getSharedPreferences(PenanceManager.PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putString("events_v1", "a,1,1,100,1,PAID,old;b,2,2,250,1,PAID,new,NEW_DETECTION")
                .commit();
        assertEquals(350, manager.getTotalPaidCents());
        assertTrue(manager.changeCurrency("USD"));
        assertEquals(0, manager.getTotalPaidCents());
        for (PenanceEvent event : manager.snapshot(3).getEvents()) {
            assertEquals("EUR", event.getCurrency());
        }
    }

    @Test public void primarySelectionRequiresOneSupportedBooleanPrimary() throws Exception {
        assertEquals("EUR", PayPalOrdersClient.primaryCurrency(new JSONObject(
                "{\"balances\":[{\"currency\":\"EUR\",\"primary\":true}]}")));
        assertEquals("USD", PayPalOrdersClient.primaryCurrency(new JSONObject(
                "{\"balances\":[{\"currency\":\"EUR\",\"primary\":false},{\"currency\":\"USD\",\"primary\":true}]}")));
        for (String json : new String[] {"{}", "{\"balances\":[]}",
                "{\"balances\":[{\"currency\":\"GBP\",\"primary\":true}]}",
                "{\"balances\":[{\"currency\":\"EUR\",\"primary\":\"true\"}]}",
                "{\"balances\":[{\"currency\":\"EUR\",\"primary\":true},{\"currency\":\"USD\",\"primary\":true}]}"}) {
            assertEquals("", PayPalOrdersClient.primaryCurrency(new JSONObject(json)));
        }
    }

    @Test public void credentialCurrencyMetadataIsEncryptedBoundaryBoundAndNotImported() {
        PayPalCredentialStore store = new PayPalCredentialStore(context);
        assertTrue(store.save(PayPalEnvironment.SANDBOX, "currency-fixture", "not-a-real-secret"));
        assertTrue(store.markCredentialsVerified());
        assertTrue(store.recordPrimaryCurrency(store.load(), "USD"));
        assertEquals("USD", new PayPalCredentialStore(context).primaryCurrency());
        assertNotEquals("USD", context.getSharedPreferences(PayPalCredentialStore.PREFS_NAME,
                Context.MODE_PRIVATE).getString("primary_currency", ""));
        assertTrue(store.saveImported(PayPalEnvironment.SANDBOX, "currency-fixture", "not-a-real-secret"));
        assertEquals("", store.primaryCurrency());
        assertTrue(store.markCredentialsVerified());
        assertTrue(store.recordPrimaryCurrency(store.load(), "EUR"));
        store.selectEnvironment(PayPalEnvironment.LIVE);
        assertEquals("", store.primaryCurrency());
    }

    @Test public void itemAmountsAndCaptureVerificationUseTheSettlementCurrency() throws Exception {
        JSONObject unit = new JSONObject();
        JSONObject amount = new JSONObject().put("currency_code", "USD").put("value", "1.25");
        PayPalOrdersClient.addOrderItems(unit, amount, 125, "USD", java.util.Collections.singletonList(
                new PayPalOrdersClient.OrderItem("fixture", "fixture", 125)));
        assertEquals("USD", unit.getJSONArray("items").getJSONObject(0)
                .getJSONObject("unit_amount").getString("currency_code"));
        assertEquals("USD", amount.getJSONObject("breakdown").getJSONObject("item_total")
                .getString("currency_code"));
        JSONObject response = new JSONObject("{\"status\":\"COMPLETED\",\"purchase_units\":["
                + "{\"custom_id\":\"fixture\",\"payments\":{\"captures\":[{\"id\":\"capture\","
                + "\"status\":\"COMPLETED\",\"amount\":{\"currency_code\":\"USD\",\"value\":\"1.25\"}}]}}]}");
        assertNotNull(PayPalOrdersClient.parseCapture(response, "fixture", 125, "USD"));
        try {
            PayPalOrdersClient.parseCapture(response, "fixture", 125, "EUR");
            fail("Currency mismatch accepted");
        } catch (IllegalStateException expected) { }
        try {
            PayPalOrdersClient.parseCapture(response, "fixture", 126, "USD");
            fail("Amount mismatch accepted");
        } catch (IllegalStateException expected) { }
    }

    @Test public void statsValuesDoNotRelabelLegacyEurOrMixCurrentUsd() {
        com.subhub.app.stats.StatsRepository stats = new com.subhub.app.stats.StatsRepository(context);
        stats.endSession();
        long previousEur = stats.tributeCents("EUR", false);
        long previousUsd = stats.tributeCents("USD", false);
        stats.startSession();
        stats.recordTributeEvent(200, false, "EUR");
        stats.recordTributeEvent(300, false, "USD");
        assertEquals(previousEur + 200, stats.tributeCents("EUR", false));
        assertEquals(previousUsd + 300, stats.tributeCents("USD", false));
        assertEquals(200, stats.tributeCents("EUR", true));
        assertEquals(300, stats.tributeCents("USD", true));
        stats.endSession();
        assertEquals(0, stats.tributeCents("USD", true));
        stats.startSession();
        assertEquals(0, stats.tributeCents("USD", true));
        stats.endSession();
    }

    @Test public void currencySelectorIsControllerGatedAndPersistsConfirmedChoice() {
        new PayPalCredentialStore(context).clear();
        com.subhub.app.security.ControllerPinManager.enterDomMode();
        try (androidx.test.core.app.ActivityScenario<com.subhub.app.settings.GlobalSettingsActivity> scenario =
                androidx.test.core.app.ActivityScenario.launch(com.subhub.app.settings.GlobalSettingsActivity.class)) {
            scenario.onActivity(activity -> {
                assertTrue(activity.findViewById(com.subhub.app.R.id.wallet_currency_usd).isEnabled());
                ((android.widget.RadioButton) activity.findViewById(com.subhub.app.R.id.wallet_currency_usd))
                        .performClick();
            });
            androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withId(android.R.id.button1))
                    .perform(androidx.test.espresso.action.ViewActions.click());
            assertEquals("USD", manager.getCurrency());
            androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers
                    .withId(com.subhub.app.R.id.wallet_currency))
                    .perform(androidx.test.espresso.action.ViewActions.scrollTo());
            androidx.test.uiautomator.UiDevice device = androidx.test.uiautomator.UiDevice.getInstance(
                    androidx.test.platform.app.InstrumentationRegistry.getInstrumentation());
            device.waitForIdle();
            assertTrue(device.takeScreenshot(new java.io.File(context.getExternalFilesDir(null),
                    "wallet-currency-preview.png")));
        }
        com.subhub.app.security.ControllerPinManager.enterSubMode();
        try (androidx.test.core.app.ActivityScenario<com.subhub.app.settings.GlobalSettingsActivity> scenario =
                androidx.test.core.app.ActivityScenario.launch(com.subhub.app.settings.GlobalSettingsActivity.class)) {
            scenario.onActivity(activity -> {
                assertFalse(activity.findViewById(com.subhub.app.R.id.wallet_currency_eur).isEnabled());
                assertFalse(activity.findViewById(com.subhub.app.R.id.button_refresh_wallet_currency).isEnabled());
            });
        }
    }

    @Test public void walletHeaderShowsActualCurrencyInBothModes() {
        assertTrue(manager.changeCurrency("USD"));
        for (boolean dom : new boolean[] {true, false}) {
            if (dom) com.subhub.app.security.ControllerPinManager.enterDomMode();
            else com.subhub.app.security.ControllerPinManager.enterSubMode();
            try (androidx.test.core.app.ActivityScenario<PenanceActivity> scenario =
                    androidx.test.core.app.ActivityScenario.launch(PenanceActivity.class)) {
                scenario.onActivity(activity -> assertEquals("Wallet currency: USD",
                        ((android.widget.TextView) activity.findViewById(com.subhub.app.R.id.penance_subtitle))
                                .getText().toString()));
            }
        }
    }
}
