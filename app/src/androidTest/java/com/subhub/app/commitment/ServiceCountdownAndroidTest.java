package com.subhub.app.commitment;

import static org.junit.Assert.*;
import android.content.*;
import android.graphics.*;
import android.os.SystemClock;
import android.view.*;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.MainActivity;
import com.subhub.app.R;
import com.subhub.app.appmode.AppModeManager;
import com.subhub.app.penance.*;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.settings.*;
import java.io.*;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.*;

public final class ServiceCountdownAndroidTest {
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final Map<SharedPreferences, Map<String, ?>> original = new LinkedHashMap<>();
    @Before public void fixture() {
        for (String name : new String[] {SettingsRepository.PREFERENCES_NAME, PenanceManager.PREFS_NAME,
                PayPalCredentialStore.PREFS_NAME, "subhub_service_duration_ui"}) {
            SharedPreferences prefs = context.getSharedPreferences(name, 0);
            original.put(prefs, prefs.getAll());
        }
        new HardcoreAutoPayManager(context).disable();
        new PayPalCredentialStore(context).clear();
        new PaidPauseManager(context).configure(false, 500, 15);
        CommitmentManager.emergencyRelease(context);
        ControllerPinManager.setPin(context, "2468");
        ControllerPinManager.enterDomMode();
    }
    @After public void restore() {
        CommitmentManager.emergencyRelease(context);
        for (Map.Entry<SharedPreferences, Map<String, ?>> entry : original.entrySet())
            SharedPreferenceTestRestore.restore(entry.getKey(), entry.getValue());
        ControllerPinManager.enterSubMode();
    }
    private ActivityScenario<MainActivity> launch() {
        return ActivityScenario.launch(new Intent(context, MainActivity.class).setAction(Intent.ACTION_MAIN)
                .putExtra(MainActivity.EXTRA_SUPPRESS_PERMISSION_READINESS, true));
    }
    @Test public void timedServiceReplacesChoicesAtTheirHeightAndAnimatesTheClock() throws Exception {
        try (ActivityScenario<MainActivity> page = launch()) {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            SystemClock.sleep(150);
            page.onActivity(a -> {
                assertTrue(a.findViewById(R.id.commitment_start_panel).isShown());
                assertTrue(CommitmentManager.start(context, 3600000, 3600000, false));
                try {
                    java.lang.reflect.Method update = MainActivity.class.getDeclaredMethod("renderCommitmentState");
                    update.setAccessible(true); update.invoke(a);
                    ServiceCountdownView clock = a.findViewById(R.id.service_countdown);
                    Field animation = ServiceCountdownView.class.getDeclaredField("reveal");
                    animation.setAccessible(true);
                    if (android.animation.ValueAnimator.areAnimatorsEnabled())
                        assertTrue(((android.animation.ValueAnimator) animation.get(clock)).isRunning());
                } catch (Exception error) { throw new AssertionError(error); }
            });
            SystemClock.sleep(900);
            page.onActivity(a -> {
                assertFalse(a.findViewById(R.id.commitment_start_panel).isShown());
                ServiceCountdownView clock = a.findViewById(R.id.service_countdown);
                assertTrue(clock.isShown());
                assertEquals(((ServiceDurationView) a.findViewById(R.id.commitment_start_panel)).choiceAreaHeight(), clock.getHeight());
                assertTrue(clock.getContentDescription().toString().contains(":"));
                capture(a, "clock-timed");
            });
            page.recreate();
            page.onActivity(a -> assertTrue(a.findViewById(R.id.service_countdown).isShown()));
        }
    }
    @Test public void hiddenTimeUsesBlurWhenDetectionIsOffAndConfiguredStyleWhenOn() throws Exception {
        SettingsRepository settings = new SettingsRepository(context);
        settings.saveAppearance(CensorAppearance.Type.BOX, 50, false, false);
        settings.preferences().edit().putString(SettingsRepository.paletteKey(CensorAppearance.Type.BOX, 1), "#D578B0").commit();
        new FeatureModuleManager(context).setCensorEnabled(false);
        assertTrue(CommitmentManager.start(context, 3600000, 3600000, true));
        try (ActivityScenario<MainActivity> page = launch()) {
            awaitMask(page, CensorAppearance.Type.BLUR);
            page.onActivity(a -> {
                ServiceCountdownView clock = a.findViewById(R.id.service_countdown);
                assertEquals(a.getString(R.string.pact_time_hidden), clock.getContentDescription());
                clock.setCountdown(3300000, 3600000, true, false);
                Bitmap first = center(clock);
                clock.setCountdown(1400000, 3600000, true, false);
                Bitmap second = center(clock);
                try { assertTrue("Hidden center must contain no actual time", first.sameAs(second)); }
                finally { first.recycle(); second.recycle(); }
                capture(a, "clock-hidden-blur");
            });
            new FeatureModuleManager(context).setCensorEnabled(true);
            page.onActivity(a -> ((ServiceCountdownView) a.findViewById(R.id.service_countdown))
                    .setCountdown(1400000, 3600000, true, false));
            awaitMask(page, CensorAppearance.Type.BOX);
            page.onActivity(a -> {
                ServiceCountdownView clock = a.findViewById(R.id.service_countdown);
                Bitmap image = center(clock);
                try { assertEquals(Color.parseColor("#D578B0"), image.getPixel(image.getWidth()/2, image.getHeight()/2)); }
                finally { image.recycle(); }
                assertEquals(a.getString(R.string.pact_time_hidden), clock.getContentDescription());
                capture(a, "clock-hidden-configured");
            });
            page.recreate();
            awaitMask(page, CensorAppearance.Type.BOX);
            page.onActivity(a -> assertEquals(a.getString(R.string.pact_time_hidden),
                    a.findViewById(R.id.service_countdown).getContentDescription()));
        }
    }
    @Test public void permanentServiceHidesChoicesWithoutDrawingACountdown() {
        new AppModeManager(context).setArmed(true);
        try (ActivityScenario<MainActivity> page = launch()) {
            page.onActivity(a -> {
                assertFalse(a.findViewById(R.id.commitment_start_panel).isShown());
                assertFalse(a.findViewById(R.id.service_countdown).isShown());
                assertTrue(a.findViewById(R.id.service_duration_permanent_status).isShown());
                capture(a, "clock-permanent");
            });
        }
    }
    @Test public void subCannotLeavePermanentServiceAndDomCanStopItNormally() {
        new AppModeManager(context).setArmed(true);
        ControllerPinManager.enterSubMode();
        try (ActivityScenario<MainActivity> page = launch()) {
            page.onActivity(a -> {
                View stop = a.findViewById(R.id.button_protection);
                assertEquals(.45f, stop.getAlpha(), .01f);
                stop.performClick();
                assertTrue(new AppModeManager(context).isArmed());
                assertFalse(ControllerPinManager.isDomModeActive());
                assertFalse(com.subhub.app.security.ProtectionStopPolicy.showNotificationStop(context));
                assertTrue(a.findViewById(R.id.commitment_active_panel).isShown());
            });
            androidx.test.espresso.Espresso.onView(
                    androidx.test.espresso.matcher.ViewMatchers.withHint(R.string.controller_pin_label))
                    .check(androidx.test.espresso.assertion.ViewAssertions.doesNotExist());
            ControllerPinManager.enterDomMode();
            page.recreate();
            page.onActivity(a -> {
                View stop = a.findViewById(R.id.button_protection);
                assertEquals(1f, stop.getAlpha(), .01f);
                stop.performClick();
                assertFalse(new AppModeManager(context).isArmed());
                assertFalse(CommitmentManager.isActive(context));
            });
        }
    }

    @Test public void authorizedLeaveStopsTimedServiceWithoutStartingAnotherSession() {
        new FeatureModuleManager(context).setCensorEnabled(false);
        assertTrue(CommitmentManager.start(context, 3600000, 3600000, false));
        androidx.test.espresso.intent.Intents.init();
        try {
            androidx.test.espresso.intent.Intents.intending(
                    androidx.test.espresso.intent.matcher.IntentMatchers.hasAction(
                            android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    .respondWith(new android.app.Instrumentation.ActivityResult(android.app.Activity.RESULT_CANCELED, null));
            try (ActivityScenario<MainActivity> page = launch()) {
                page.onActivity(a -> a.findViewById(R.id.button_protection).performClick());
                page.onActivity(a -> {
                    assertFalse(CommitmentManager.isActive(context));
                    assertFalse(new AppModeManager(context).isArmed());
                });
                AtomicBoolean inactive = new AtomicBoolean();
                long until = SystemClock.uptimeMillis() + 3000;
                do {
                    page.onActivity(a -> inactive.set(
                            a.findViewById(R.id.commitment_active_panel).getVisibility() == View.GONE));
                    if (inactive.get()) break;
                    SystemClock.sleep(30);
                } while (SystemClock.uptimeMillis() < until);
                assertTrue("The stop transition must finish on the duration choices", inactive.get());
                page.onActivity(a -> {
                    assertTrue(a.findViewById(R.id.commitment_start_panel).isShown());
                    assertFalse(a.findViewById(R.id.commitment_active_panel).isShown());
                    assertEquals(a.getString(R.string.start_protection),
                            ((android.widget.TextView) a.findViewById(R.id.button_protection)).getText().toString());
                });
                page.recreate();
                page.onActivity(a -> assertTrue(a.findViewById(R.id.commitment_start_panel).isShown()));
                androidx.test.espresso.intent.Intents.intended(
                        androidx.test.espresso.intent.matcher.IntentMatchers.hasAction(
                                android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS),
                        androidx.test.espresso.intent.VerificationModes.times(0));
            }
        } finally { androidx.test.espresso.intent.Intents.release(); }
    }
    private static Bitmap center(View view) {
        Bitmap whole = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
        view.draw(new Canvas(whole));
        int size = Math.min(view.getWidth(), view.getHeight());
        Bitmap crop = Bitmap.createBitmap(whole, view.getWidth()/2-size/4, view.getHeight()/2-size/10, size/2, size/5);
        whole.recycle(); return crop;
    }
    private static void awaitMask(ActivityScenario<MainActivity> page, CensorAppearance.Type expected) {
        long until = SystemClock.uptimeMillis()+8000;
        AtomicBoolean ready = new AtomicBoolean();
        do {
            page.onActivity(a -> {
                try {
                    Field field = ServiceCountdownView.class.getDeclaredField("mask"); field.setAccessible(true);
                    Object mask = field.get(a.findViewById(R.id.service_countdown));
                    if (mask != null && a.findViewById(R.id.service_countdown).getWidth() > 0) {
                        Field type = mask.getClass().getDeclaredField("type"); type.setAccessible(true);
                        ready.set(type.get(mask) == expected);
                    }
                } catch (Exception error) { throw new AssertionError(error); }
            });
            if (ready.get()) return;
            SystemClock.sleep(50);
        } while (SystemClock.uptimeMillis()<until);
        fail("Countdown mask did not render");
    }
    private static void capture(android.app.Activity a, String name) {
        View root=a.getWindow().getDecorView();
        root.post(() -> {
            Bitmap image=Bitmap.createBitmap(root.getWidth(),root.getHeight(),Bitmap.Config.ARGB_8888);
            root.draw(new Canvas(image));
            try (OutputStream out=new FileOutputStream(new File(a.getFilesDir(), "review-"+name+".png"))) {
                assertTrue(image.compress(Bitmap.CompressFormat.PNG,100,out));
            } catch(IOException error){throw new AssertionError(error);} finally{image.recycle();}
        });
    }
}
