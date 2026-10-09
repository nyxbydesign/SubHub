package com.subhub.app.atmosphere;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.espresso.intent.Intents;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;

import com.subhub.app.R;
import com.subhub.app.onboarding.OnboardingState;
import com.subhub.app.pack.SubHubPack;
import com.subhub.app.pack.SubHubPackManager;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.studio.StudioActivity;

import org.junit.*;

import java.io.File;
import java.io.FileOutputStream;

/** Disposable emulator fixtures; no live media, credentials or pack catalog. */
public final class RitualsToolsAndroidTest {
    private Context app;
    private SubHubPackManager packs;
    private String fixture;

    @Before
    public void setup() throws Exception {
        assumeTrue(android.os.Build.MODEL.contains("sdk_gphone"));
        app = InstrumentationRegistry.getInstrumentation().getTargetContext();
        OnboardingState.complete(app);
        ControllerPinManager.setPin(app, "2468");
        ControllerPinManager.enterDomMode();
        packs = new SubHubPackManager(app);
        SubHubPack pack = packs.createBlank();
        pack.setMetadata("Focus", "Sample", "", "1.0.0");
        packs.addToLibrary(pack);
        fixture = pack.getId();
    }

    @After
    public void cleanup() {
        if (fixture != null) {
            packs.deleteLibrary(fixture);
            packs.deleteDraft(fixture);
        }
        ControllerPinManager.enterSubMode();
    }

    @Test
    public void packsLeadTheToolsAndStudioReturnsToRituals() throws Exception {
        try (ActivityScenario<AtmosphereActivity> scenario =
                ActivityScenario.launch(AtmosphereActivity.class)) {
            awaitOverview(scenario);
            scenario.onActivity(
                    a -> {
                        View pack = a.findViewById(R.id.rituals_packs_card);
                        ViewGroup parent = (ViewGroup) pack.getParent();
                        assertEquals(1, parent.indexOfChild(pack));
                        assertEquals(
                                2, parent.indexOfChild(a.findViewById(R.id.rituals_gallery_card)));
                        assertNotNull(a.findViewById(R.id.achievements_home_card));
                        assertNull(a.findViewById(R.id.daily_statistics_panel));
                        capture(a, "rituals-overview.png");
                        a.findViewById(R.id.rituals_pack_library).performClick();
                    });
            idle();
            InstrumentationRegistry.getInstrumentation()
                    .runOnMainSync(
                            () -> {
                                StudioActivity studio = null;
                                for (Activity a :
                                        ActivityLifecycleMonitorRegistry.getInstance()
                                                .getActivitiesInStage(Stage.RESUMED))
                                    if (a instanceof StudioActivity) studio = (StudioActivity) a;
                                assertNotNull(studio);
                                assertTrue(
                                        studio.getIntent()
                                                .getBooleanExtra(
                                                        StudioActivity.EXTRA_FROM_RITUALS, false));
                                studio.findViewById(R.id.button_back).performClick();
                            });
            idle();
            scenario.onActivity(
                    a -> {
                        ScrollView scroll =
                                (ScrollView)
                                        ((View) a.findViewById(R.id.rituals_packs_card).getParent())
                                                .getParent();
                        scroll.fullScroll(View.FOCUS_DOWN);
                    });
            idle();
            scenario.onActivity(a -> capture(a, "rituals-achievements.png"));
        }
    }

    @Test
    public void importShortcutUsesTheExistingPickerAndSubCanReadAchievements() throws Exception {
        Intents.init();
        try {
            Intents.intending(
                            androidx.test.espresso.intent.matcher.IntentMatchers.hasAction(
                                    Intent.ACTION_OPEN_DOCUMENT))
                    .respondWith(
                            new android.app.Instrumentation.ActivityResult(
                                    Activity.RESULT_CANCELED, null));
            try (ActivityScenario<AtmosphereActivity> scenario =
                    ActivityScenario.launch(AtmosphereActivity.class)) {
                awaitOverview(scenario);
                scenario.onActivity(a -> a.findViewById(R.id.rituals_import_pack).performClick());
                idle();
                Intents.intended(
                        androidx.test.espresso.intent.matcher.IntentMatchers.hasAction(
                                Intent.ACTION_OPEN_DOCUMENT));
            }
        } finally {
            Intents.release();
        }
        ControllerPinManager.enterSubMode();
        try (ActivityScenario<AtmosphereActivity> scenario =
                ActivityScenario.launch(AtmosphereActivity.class)) {
            awaitOverview(scenario);
            scenario.onActivity(
                    a -> {
                        assertTrue(a.findViewById(R.id.nav_atmosphere).isShown());
                        assertNotNull(a.findViewById(R.id.achievements_home_card));
                        assertFalse(a.findViewById(R.id.switch_whispers).isEnabled());
                        assertFalse(a.findViewById(R.id.switch_popup_storm).isEnabled());
                        capture(a, "rituals-sub.png");
                    });
        }
    }

    private void awaitOverview(ActivityScenario<AtmosphereActivity> scenario) {
        long deadline = android.os.SystemClock.elapsedRealtime() + 5000;
        java.util.concurrent.atomic.AtomicBoolean loaded =
                new java.util.concurrent.atomic.AtomicBoolean();
        do {
            idle();
            scenario.onActivity(
                    a ->
                            loaded.set(
                                    ((TextView) a.findViewById(R.id.rituals_pack_count)).length()
                                                    > 0
                                            && ((TextView)
                                                                    a.findViewById(
                                                                            R.id
                                                                                    .achievements_home_count))
                                                            .length()
                                                    > 0));
        } while (!loaded.get() && android.os.SystemClock.elapsedRealtime() < deadline);
        assertTrue("Overview metadata must load", loaded.get());
    }

    private static void idle() {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        android.os.SystemClock.sleep(120);
    }

    private static void capture(Activity activity, String name) {
        View root = activity.getWindow().getDecorView();
        Bitmap frame =
                Bitmap.createBitmap(root.getWidth(), root.getHeight(), Bitmap.Config.ARGB_8888);
        root.draw(new Canvas(frame));
        try (FileOutputStream out = new FileOutputStream(new File(activity.getFilesDir(), name))) {
            assertTrue(frame.compress(Bitmap.CompressFormat.PNG, 100, out));
        } catch (java.io.IOException failure) {
            throw new AssertionError(failure);
        } finally {
            frame.recycle();
        }
    }
}
