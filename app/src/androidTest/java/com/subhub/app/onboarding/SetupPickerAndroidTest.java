package com.subhub.app.onboarding;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.*;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.*;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import android.app.*;
import android.content.Context;
import android.graphics.*;
import android.view.*;
import android.widget.*;

import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;

import com.subhub.app.R;
import com.subhub.app.appmode.AppModeManager;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.settings.*;
import com.subhub.app.util.PrimaryHeader;

import org.junit.Test;

import java.io.*;
import java.lang.reflect.Field;

public class SetupPickerAndroidTest {
    @Test
    public void pickerReturnsToDraftAndPhonePreviewAndReviewExcludeWhispersAndNotices()
            throws Exception {
        assumeTrue(android.os.Build.MODEL.contains("sdk_gphone"));
        Instrumentation instrument = InstrumentationRegistry.getInstrumentation();
        Context context = instrument.getTargetContext();
        context.getSharedPreferences("subhub_onboarding", 0).edit().clear().commit();
        ControllerPinManager.useWithoutKeyholder(context);
        ControllerPinManager.enterDomMode();
        new FeatureModuleManager(context).save(true, true, true, true);
        Instrumentation.ActivityMonitor monitor =
                instrument.addMonitor(SetupAppsActivity.class.getName(), null, false);
        Activity picker = null;
        try (ActivityScenario<OnboardingActivity> tour =
                ActivityScenario.launch(OnboardingActivity.class)) {
            instrument.waitForIdleSync();
            tour.onActivity(
                    activity -> {
                        CensorPreviewView sample =
                                findPreview(activity.findViewById(android.R.id.content));
                        assertNotNull(sample);
                        Bitmap frame =
                                Bitmap.createBitmap(
                                        sample.getWidth(),
                                        sample.getHeight(),
                                        Bitmap.Config.ARGB_8888);
                        sample.draw(new Canvas(frame));
                        frame.recycle();
                        try {
                            Field field = CensorPreviewView.class.getDeclaredField("phoneBitmap");
                            field.setAccessible(true);
                            Bitmap phone = (Bitmap) field.get(sample);
                            assertTrue(
                                    "Rendered sample is portrait",
                                    phone.getHeight() > phone.getWidth());
                        } catch (ReflectiveOperationException error) {
                            throw new AssertionError(error);
                        }
                        capture(activity, "setup-mobile.png");
                    });
            onView(withId(R.id.tour_next)).perform(click());
            onView(withText(R.string.global_feature_wallet)).perform(scrollTo(), click());
            tour.onActivity(
                    a ->
                            assertFalse(
                                    text(a.findViewById(android.R.id.content))
                                            .contains("Whispers")));
            onView(withText(R.string.tour_choose_apps)).perform(scrollTo(), click());
            picker = monitor.waitForActivityWithTimeout(4000);
            assertNotNull(picker);
            Activity opened = picker;
            long deadline = android.os.SystemClock.uptimeMillis() + 5000;
            java.util.concurrent.atomic.AtomicBoolean ready =
                    new java.util.concurrent.atomic.AtomicBoolean();
            while (!ready.get() && android.os.SystemClock.uptimeMillis() < deadline) {
                instrument.runOnMainSync(
                        () ->
                                ready.set(
                                        ((ListView) opened.findViewById(R.id.app_list))
                                                        .getChildCount()
                                                > 1));
                android.os.SystemClock.sleep(25);
            }
            assertTrue(ready.get());
            instrument.runOnMainSync(
                    () -> {
                        assertNull(opened.findViewById(R.id.bottom_navigation));
                        ListView list = opened.findViewById(R.id.app_list);
                        IncludedAppRow row = (IncludedAppRow) list.getChildAt(0);
                        row.choice().setChecked(!row.choice().isChecked());
                        capture(opened, "setup-apps.png");
                        PrimaryHeader.backButton(opened.findViewById(android.R.id.content))
                                .performClick();
                    });
            onView(withId(R.id.tour_progress))
                    .check(matches(withText(context.getString(R.string.tour_progress, 2, 6))));
            onView(withText(R.string.global_feature_wallet)).check(matches(isNotChecked()));
            // Draft values survive the child activity and recreation.
            tour.recreate();
            onView(withText(R.string.global_feature_wallet)).check(matches(isNotChecked()));
            for (int step = 1; step < 5; step++) onView(withId(R.id.tour_next)).perform(click());
            tour.onActivity(
                    a -> {
                        String content = text(a.findViewById(android.R.id.content));
                        assertFalse(content.contains("Whispers"));
                        assertFalse(content.contains("Start from Home"));
                        assertFalse(content.contains("Configure the details"));
                        capture(a, "setup-review.png");
                    });
            onView(withId(R.id.tour_skip)).perform(click());
            assertFalse(new FeatureModuleManager(context).isWalletEnabled());
            assertTrue(new FeatureModuleManager(context).isSubliminalEnabled());
            assertFalse(new AppModeManager(context).isArmed());
        } finally {
            if (picker != null && !picker.isFinishing()) {
                Activity closed = picker;
                instrument.runOnMainSync(closed::finish);
            }
            instrument.removeMonitor(monitor);
            OnboardingState.complete(context);
            ControllerPinManager.setPin(context, "2468");
            ControllerPinManager.enterSubMode();
        }
    }

    private static CensorPreviewView findPreview(View view) {
        if (view instanceof CensorPreviewView) return (CensorPreviewView) view;
        if (view instanceof ViewGroup)
            for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
                CensorPreviewView found = findPreview(((ViewGroup) view).getChildAt(i));
                if (found != null) return found;
            }
        return null;
    }

    private static String text(View view) {
        String result = view instanceof TextView ? ((TextView) view).getText().toString() : "";
        if (view instanceof ViewGroup)
            for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
                result += "\n" + text(((ViewGroup) view).getChildAt(i));
        return result;
    }

    private static void capture(Activity activity, String name) {
        View root = activity.getWindow().getDecorView();
        root.post(
                () -> {
                    Bitmap frame =
                            Bitmap.createBitmap(
                                    root.getWidth(), root.getHeight(), Bitmap.Config.ARGB_8888);
                    root.draw(new Canvas(frame));
                    try (FileOutputStream out =
                            new FileOutputStream(new File(activity.getFilesDir(), name))) {
                        frame.compress(Bitmap.CompressFormat.PNG, 100, out);
                    } catch (IOException error) {
                        throw new AssertionError(error);
                    } finally {
                        frame.recycle();
                    }
                });
    }
}
