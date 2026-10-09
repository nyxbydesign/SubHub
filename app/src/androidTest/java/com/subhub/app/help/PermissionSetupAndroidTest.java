package com.subhub.app.help;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static androidx.test.espresso.matcher.ViewMatchers.withId;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.Until;

import com.subhub.app.R;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.settings.SharedPreferenceTestRestore;

import org.junit.*;

import java.io.File;
import java.io.FileOutputStream;
import java.util.LinkedHashMap;
import java.util.Map;

public final class PermissionSetupAndroidTest {
    private Context context;
    private SharedPreferences onboarding;
    private Map<String, ?> original;

    @Before
    public void fixture() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        onboarding = context.getSharedPreferences("subhub_onboarding", 0);
        original = new LinkedHashMap<>(onboarding.getAll());
        onboarding.edit().putBoolean("completed", true).remove("in_progress").commit();
        ControllerPinManager.enterDomMode();
    }

    @After
    public void restore() {
        SharedPreferenceTestRestore.restore(onboarding, original);
        ControllerPinManager.enterSubMode();
    }

    @Test
    public void guideOpensFromHelpAndKeepsAllThreeStepsReadable() throws Exception {
        try (ActivityScenario<HelpActivity> scenario =
                ActivityScenario.launch(HelpActivity.class)) {
            onView(withId(R.id.button_accessibility)).perform(scrollTo(), click());
            UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
            assertTrue(
                    device.wait(
                            Until.hasObject(
                                    By.text(context.getString(R.string.permission_setup_title))),
                            5000));
            android.app.Activity active = current();
            assertTrue(active instanceof PermissionSetupActivity);
            InstrumentationRegistry.getInstrumentation()
                    .runOnMainSync(
                            () -> {
                                for (int id :
                                        new int[] {
                                            R.id.permission_step_one_open,
                                            R.id.permission_step_two_open,
                                            R.id.permission_step_three_open
                                        }) {
                                    View button = active.findViewById(id);
                                    assertEquals(
                                            id == R.id.permission_step_one_open,
                                            button.isEnabled());
                                    assertTrue(
                                            button.getHeight()
                                                    >= Math.round(
                                                            48
                                                                    * active.getResources()
                                                                            .getDisplayMetrics()
                                                                            .density));
                                }
                                readable(active.findViewById(android.R.id.content));
                                capture(active, "permission-guide-top.png");
                            });
            onView(withId(R.id.permission_step_three_open)).perform(scrollTo());
            InstrumentationRegistry.getInstrumentation()
                    .runOnMainSync(() -> capture(active, "permission-guide-bottom.png"));
            device.pressBack();
            assertTrue(
                    device.wait(
                            Until.hasObject(By.text(context.getString(R.string.help_title))),
                            5000));
        }
    }

    @Test
    public void laterStepsUnlockInOrderAndSurviveRecreation() throws Exception {
        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        try (ActivityScenario<PermissionSetupActivity> scenario =
                ActivityScenario.launch(PermissionSetupActivity.class)) {
            scenario.onActivity(
                    a -> {
                        assertFalse(a.findViewById(R.id.permission_step_two_open).isEnabled());
                        assertFalse(a.findViewById(R.id.permission_step_three_open).isEnabled());
                        assertEquals(
                                .5f, a.findViewById(R.id.permission_step_two).getAlpha(), .01f);
                    });
            onView(withId(R.id.permission_step_one_open)).perform(scrollTo(), click());
            assertTrue(device.wait(Until.hasObject(By.pkg("com.android.settings")), 5000));
            device.pressBack();
            assertTrue(
                    device.wait(
                            Until.hasObject(
                                    By.text(context.getString(R.string.permission_setup_title))),
                            5000));
            scenario.recreate();
            scenario.onActivity(
                    a -> {
                        assertTrue(a.findViewById(R.id.permission_step_two_open).isEnabled());
                        assertFalse(a.findViewById(R.id.permission_step_three_open).isEnabled());
                        assertEquals(1f, a.findViewById(R.id.permission_step_two).getAlpha(), .01f);
                    });
            onView(withId(R.id.permission_step_two_open)).perform(scrollTo(), click());
            assertTrue(device.wait(Until.hasObject(By.pkg("com.android.settings")), 5000));
            device.pressBack();
            assertTrue(
                    device.wait(
                            Until.hasObject(
                                    By.text(context.getString(R.string.permission_setup_title))),
                            5000));
            scenario.recreate();
            scenario.onActivity(
                    a -> {
                        assertTrue(a.findViewById(R.id.permission_step_three_open).isEnabled());
                        assertEquals(
                                1f, a.findViewById(R.id.permission_step_three).getAlpha(), .01f);
                    });
        }
    }

    @Test
    public void existingSubModeCanReadGuideButCannotOpenPermissionActions() {
        ControllerPinManager.enterSubMode();
        try (ActivityScenario<PermissionSetupActivity> scenario =
                ActivityScenario.launch(PermissionSetupActivity.class)) {
            scenario.onActivity(
                    a -> {
                        assertFalse(a.findViewById(R.id.permission_step_one_open).isEnabled());
                        assertFalse(a.findViewById(R.id.permission_step_two_open).isEnabled());
                        assertFalse(a.findViewById(R.id.permission_step_three_open).isEnabled());
                        assertTrue(a.findViewById(R.id.permission_step_one_body).isShown());
                    });
        }
    }

    @Test
    public void firstRunSetupKeepsPermissionActionsAvailableBeforeDomHandoff() {
        onboarding.edit().putBoolean("completed", false).putBoolean("in_progress", true).commit();
        ControllerPinManager.enterSubMode();
        try (ActivityScenario<PermissionSetupActivity> scenario =
                ActivityScenario.launch(PermissionSetupActivity.class)) {
            scenario.onActivity(
                    a -> assertTrue(a.findViewById(R.id.permission_step_one_open).isEnabled()));
        }
    }

    private static android.app.Activity current() {
        final android.app.Activity[] active = {null};
        InstrumentationRegistry.getInstrumentation()
                .runOnMainSync(
                        () ->
                                active[0] =
                                        androidx.test.runner.lifecycle
                                                .ActivityLifecycleMonitorRegistry.getInstance()
                                                .getActivitiesInStage(
                                                        androidx.test.runner.lifecycle.Stage
                                                                .RESUMED)
                                                .iterator()
                                                .next());
        return active[0];
    }

    private static void readable(View view) {
        if (view instanceof TextView) {
            TextView text = (TextView) view;
            if (text.isShown() && text.getLayout() != null) {
                for (int line = 0; line < text.getLineCount(); line++)
                    assertEquals(
                            "Ellipsized: " + text.getText(),
                            0,
                            text.getLayout().getEllipsisCount(line));
                assertTrue(
                        "Clipped: " + text.getText(),
                        text.getLayout().getHeight()
                                <= text.getHeight()
                                        - text.getCompoundPaddingTop()
                                        - text.getCompoundPaddingBottom());
            }
        }
        if (view instanceof ViewGroup)
            for (int index = 0; index < ((ViewGroup) view).getChildCount(); index++)
                readable(((ViewGroup) view).getChildAt(index));
    }

    private static void capture(android.app.Activity activity, String name) {
        View root = activity.getWindow().getDecorView();
        Bitmap bitmap =
                Bitmap.createBitmap(root.getWidth(), root.getHeight(), Bitmap.Config.ARGB_8888);
        root.draw(new Canvas(bitmap));
        try (FileOutputStream output =
                new FileOutputStream(new File(activity.getFilesDir(), name))) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
        } catch (Exception e) {
            throw new AssertionError(e);
        } finally {
            bitmap.recycle();
        }
    }
}
