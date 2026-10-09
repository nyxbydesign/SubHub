package com.subhub.app.penance;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import android.content.*;
import android.graphics.*;
import android.view.*;
import android.widget.*;

import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;

import com.subhub.app.R;
import com.subhub.app.appmode.AppModeManager;
import com.subhub.app.onboarding.OnboardingState;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.settings.FeatureModuleManager;

import org.junit.*;

import java.io.*;
import java.util.*;

public class WalletUiRefinementAndroidTest {
    private Context context;
    private PenanceManager wallet;

    @Before
    public void fixture() {
        assumeTrue(android.os.Build.MODEL.contains("sdk_gphone"));
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        OnboardingState.complete(context);
        ControllerPinManager.setPin(context, "2468");
        ControllerPinManager.enterDomMode();
        new FeatureModuleManager(context).save(true, true, true, false);
        new AppModeManager(context).save(true, Set.of("com.android.chrome"));
        context.getSharedPreferences(PenanceManager.PREFS_NAME, 0).edit().clear().commit();
        new PayPalCredentialStore(context).clear();
        new HardcoreAutoPayManager(context).disable();
        wallet = new PenanceManager(context);
        wallet.configure(
                true,
                Map.of(PenanceInfraction.NEW_DETECTION, 125, PenanceInfraction.CENSORED_TAP, 75),
                5000,
                20000,
                0,
                30,
                1);
        long paidAt = System.currentTimeMillis() - 2000;
        assertEquals(500, wallet.recordInfraction(PenanceInfraction.NEW_DETECTION, 4, paidAt));
        PenanceManager.Settlement paid = wallet.beginSettlement(paidAt);
        assertTrue(wallet.completeSettlement(paid.getId(), 500));
        assertEquals(
                250,
                wallet.recordInfraction(
                        PenanceInfraction.NEW_DETECTION, 2, System.currentTimeMillis()));
        assertEquals(
                75,
                wallet.recordInfraction(
                        PenanceInfraction.CENSORED_TAP, 1, System.currentTimeMillis()));
        wallet.configure(
                true,
                Map.of(PenanceInfraction.NEW_DETECTION, 125, PenanceInfraction.CENSORED_TAP, 75),
                5000,
                20000,
                10,
                30,
                1);
        assertEquals(
                75,
                wallet.recordInfraction(
                        PenanceInfraction.CENSORED_TAP, 1, System.currentTimeMillis()));
    }

    @After
    public void restore() {
        new AppModeManager(context).setArmed(false);
        ControllerPinManager.enterSubMode();
    }

    @Test
    public void compositionShowsPaymentFirstAndPreservesExpandedSectionAfterRecreation()
            throws Exception {
        try (ActivityScenario<PenanceActivity> scenario =
                ActivityScenario.launch(PenanceActivity.class)) {
            settle();
            scenario.onActivity(
                    a -> {
                        List<String> keys = new ArrayList<>();
                        collect(a.findViewById(android.R.id.content), keys);
                        assertEquals(
                                Arrays.asList(
                                        "wallet:rules",
                                        "wallet:pause",
                                        "wallet:paypal",
                                        "wallet:corrections"),
                                keys);
                        assertEquals(
                                wallet.money(325),
                                ((TextView) a.findViewById(R.id.due_amount)).getText().toString());
                        assertTrue(a.findViewById(R.id.button_settle).isEnabled());
                        assertEquals(
                                context.getString(R.string.wallet_setup_paypal),
                                ((TextView) a.findViewById(R.id.button_settle))
                                        .getText()
                                        .toString());
                        assertSame(
                                a.findViewById(R.id.balance_card),
                                a.findViewById(R.id.wallet_activity_card).getParent());
                        assertTrue(a.findViewById(R.id.wallet_history_list).isShown());
                        assertFalse(a.findViewById(R.id.rule_config_card).isShown());
                        capture(a, "wallet-overview.png");
                    });
            scenario.onActivity(
                    a ->
                            a.findViewById(android.R.id.content)
                                    .findViewWithTag("wallet:rules")
                                    .performClick());
            settle();
            scenario.onActivity(
                    a -> {
                        assertTrue(a.findViewById(R.id.rule_config_card).isShown());
                        capture(a, "wallet-rules.png");
                    });
            scenario.recreate();
            settle();
            scenario.onActivity(a -> assertTrue(a.findViewById(R.id.rule_config_card).isShown()));
            for (String key : Arrays.asList("pause", "paypal", "corrections")) {
                scenario.onActivity(
                        a ->
                                a.findViewById(android.R.id.content)
                                        .findViewWithTag("wallet:" + key)
                                        .performClick());
                settle();
                scenario.onActivity(a -> capture(a, "wallet-" + key + ".png"));
            }
            scenario.onActivity(a -> a.findViewById(R.id.button_back).performClick());
            settle();
            scenario.onActivity(
                    a -> {
                        assertTrue(a.findViewById(R.id.wallet_overview).isShown());
                        assertFalse(a.findViewById(R.id.button_back).isShown());
                    });
        }
    }

    @Test
    public void subModeKeepsConnectionAndConfigurationGuardedWhileShowingBalanceAndHistory()
            throws Exception {
        ControllerPinManager.enterSubMode();
        try (ActivityScenario<PenanceActivity> scenario =
                ActivityScenario.launch(PenanceActivity.class)) {
            settle();
            scenario.onActivity(
                    a -> {
                        View root = a.findViewById(android.R.id.content);
                        for (String key : Arrays.asList("rules", "pause", "paypal", "corrections"))
                            assertFalse(root.findViewWithTag("wallet:" + key).isShown());
                        assertTrue(a.findViewById(R.id.wallet_history_list).isShown());
                        assertFalse(a.findViewById(R.id.paypal_client_id).isEnabled());
                        assertFalse(a.findViewById(R.id.paypal_auto_pay_enabled).isEnabled());
                        ((CompoundButton) a.findViewById(R.id.paypal_auto_pay_enabled))
                                .setChecked(true);
                        assertFalse(new HardcoreAutoPayManager(context).isEnabled());
                        assertEquals(
                                325, wallet.snapshot(System.currentTimeMillis()).getDueCents());
                        capture(a, "wallet-sub.png");
                    });
        }
    }

    @Test
    public void pendingCheckoutShowsRecoveryActionsWithoutCreatingAnotherPayment()
            throws Exception {
        PenanceManager.Settlement settlement = wallet.beginSettlement(System.currentTimeMillis());
        assertNotNull(settlement);
        wallet.bindOrder(
                settlement.getId(), settlement.getId(), "https://paypal.me/synthetic-example");
        try (ActivityScenario<PenanceActivity> scenario =
                ActivityScenario.launch(PenanceActivity.class)) {
            settle();
            scenario.onActivity(
                    a -> {
                        assertTrue(a.findViewById(R.id.button_resume_checkout).isShown());
                        assertTrue(a.findViewById(R.id.button_cancel_checkout).isShown());
                        assertFalse(a.findViewById(R.id.button_settle).isEnabled());
                        assertEquals(
                                325,
                                wallet.snapshot(System.currentTimeMillis()).getCheckoutCents());
                        capture(a, "wallet-pending.png");
                    });
        }
    }

    private static void settle() {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        android.os.SystemClock.sleep(180);
    }

    private static void collect(View v, List<String> keys) {
        if (v.getTag() instanceof String && ((String) v.getTag()).startsWith("wallet:"))
            keys.add((String) v.getTag());
        if (v instanceof ViewGroup)
            for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++)
                collect(((ViewGroup) v).getChildAt(i), keys);
    }

    private static void capture(android.app.Activity a, String name) {
        View root = a.getWindow().getDecorView();
        Bitmap frame =
                Bitmap.createBitmap(root.getWidth(), root.getHeight(), Bitmap.Config.ARGB_8888);
        root.draw(new Canvas(frame));
        try (FileOutputStream out = new FileOutputStream(new File(a.getFilesDir(), name))) {
            assertTrue(frame.compress(Bitmap.CompressFormat.PNG, 100, out));
        } catch (IOException e) {
            throw new AssertionError(e);
        } finally {
            frame.recycle();
        }
    }
}
