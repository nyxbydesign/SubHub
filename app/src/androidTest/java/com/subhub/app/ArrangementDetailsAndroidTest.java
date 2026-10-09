package com.subhub.app;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.RootMatchers.isDialog;
import static org.junit.Assert.*;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.view.View;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.penance.PenanceInfraction;
import com.subhub.app.penance.PenanceManager;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.settings.FeatureModuleManager;
import com.subhub.app.settings.SettingsRepository;
import com.subhub.app.settings.SharedPreferenceTestRestore;
import java.io.File;
import java.io.FileOutputStream;
import java.util.EnumMap;
import java.util.Map;
import org.junit.*;

/** Every arrangement stays visible; summaries describe configuration without an activity claim. */
public class ArrangementDetailsAndroidTest {
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private SharedPreferences settings, wallet;
    private Map<String, ?> settingsBefore, walletBefore;

    @Before public void fixture() {
        settings = context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME, 0);
        wallet = context.getSharedPreferences(PenanceManager.PREFS_NAME, 0);
        settingsBefore = settings.getAll(); walletBefore = wallet.getAll();
        new FeatureModuleManager(context).save(false, false, false, false);
        Map<PenanceInfraction, Integer> rules = new EnumMap<>(PenanceInfraction.class);
        for (PenanceInfraction rule : PenanceInfraction.values())
            if (rule != PenanceInfraction.PAID_PAUSE) rules.put(rule, 125);
        new PenanceManager(context).configure(true, rules, 1000, 5000, 10, 15);
        ControllerPinManager.enterSubMode();
    }

    @After public void restore() {
        SharedPreferenceTestRestore.restore(settings, settingsBefore);
        SharedPreferenceTestRestore.restore(wallet, walletBefore);
        ControllerPinManager.enterSubMode();
    }

    @Test public void allFourRemainVisibleWithFiveOrZeroTributesAndReadableDetails() {
        try (ActivityScenario<MainActivity> page = ActivityScenario.launch(
                new Intent(context, MainActivity.class).setAction(Intent.ACTION_MAIN))) {
            page.onActivity(a -> {
                assertEquals("5 Tributes", ((TextView) a.findViewById(R.id.sub_wallet_voice)).getText().toString());
                assertCards(a);
            });
            int[] cards = {R.id.sub_censor_card, R.id.sub_limits_card, R.id.sub_wallet_card, R.id.sub_atmosphere_card};
            String[] names = {"censor", "limits", "wallet", "rituals"};
            for (int i = 0; i < cards.length; i++) {
                final int card = cards[i];
                final String name = names[i];
                page.onActivity(a -> a.findViewById(card).performClick());
                onView(withId(R.id.arrangement_detail_close)).inRoot(isDialog()).check((view, error) -> {
                    if (error != null) throw error;
                    View root = view.getRootView();
                    Rect close = new Rect(), body = new Rect();
                    assertTrue(view.getGlobalVisibleRect(close));
                    assertTrue(root.findViewById(R.id.arrangement_detail_body).getGlobalVisibleRect(body));
                    assertTrue(body.bottom <= close.top);
                    assertTrue(view.getHeight() >= Math.round(48 * view.getResources().getDisplayMetrics().density));
                    Bitmap image = Bitmap.createBitmap(root.getWidth(), root.getHeight(), Bitmap.Config.ARGB_8888);
                    root.draw(new Canvas(image));
                    try (FileOutputStream out = new FileOutputStream(new File(context.getFilesDir(), "followup-arrangement-" + name + ".png"))) {
                        assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, out));
                    } catch (Exception failure) { throw new AssertionError(failure); }
                    finally { image.recycle(); }
                });
                onView(withId(R.id.arrangement_detail_close)).inRoot(isDialog()).perform(click());
            }
            new PenanceManager(context).configure(false, java.util.Collections.emptyMap(), 1000, 5000, 10, 15);
            page.recreate();
            page.onActivity(a -> {
                assertCards(a);
                assertEquals("0 Tributes", ((TextView) a.findViewById(R.id.sub_wallet_voice)).getText().toString());
            });
        }
    }

    private static void assertCards(MainActivity a) {
        for (int id : new int[] {R.id.sub_censor_card, R.id.sub_limits_card, R.id.sub_wallet_card, R.id.sub_atmosphere_card})
            assertEquals(View.VISIBLE, a.findViewById(id).getVisibility());
        for (int id : new int[] {R.id.sub_censor_voice, R.id.sub_limits_summary, R.id.sub_wallet_voice, R.id.sub_atmosphere_status})
            assertFalse(((TextView) a.findViewById(id)).getText().toString().toLowerCase(java.util.Locale.ROOT).contains("active"));
    }
}
