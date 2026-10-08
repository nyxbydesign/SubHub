package com.subhub.app.settings;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.R;
import com.subhub.app.security.ControllerPinManager;
import java.io.File;
import java.io.FileOutputStream;
import org.junit.Test;
import org.junit.runner.RunWith;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.*;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static androidx.test.espresso.assertion.ViewAssertions.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class SettingsGroupsAndroidTest {
    @Test public void categoriesOpenFocusedControlsAndKeepPrivacyDiscoverable() {
        ControllerPinManager.enterDomMode();
        try (ActivityScenario<GlobalSettingsActivity> scenario = ActivityScenario.launch(GlobalSettingsActivity.class)) {
            onView(withText(R.string.settings_apps)).perform(scrollTo(), click());
            onView(withId(R.id.button_toggle_apps)).perform(scrollTo()).check(matches(isDisplayed()));
            onView(withText(R.string.settings_all)).perform(scrollTo(), click());
            onView(org.hamcrest.Matchers.allOf(withText(R.string.privacy_title), withEffectiveVisibility(Visibility.VISIBLE))).perform(scrollTo(), click());
            onView(withId(R.id.privacy_discreet_toggle)).check(matches(isDisplayed()));
            androidx.test.espresso.Espresso.pressBack();
            onView(org.hamcrest.Matchers.allOf(withText(R.string.settings_features), withEffectiveVisibility(Visibility.VISIBLE))).perform(scrollTo());
            scenario.onActivity(activity -> {
                assertFalse(activity.findViewById(R.id.paypal_client_secret).isShown());
                View root = activity.getWindow().getDecorView();
                root.post(() -> {
                    Bitmap image = Bitmap.createBitmap(root.getWidth(), root.getHeight(), Bitmap.Config.ARGB_8888);
                    root.draw(new Canvas(image));
                    try (FileOutputStream out = new FileOutputStream(new File(activity.getFilesDir(), "settings-groups.png"))) {
                        image.compress(Bitmap.CompressFormat.PNG, 100, out);
                    } catch (Exception failure) { throw new AssertionError(failure); }
                    finally { image.recycle(); }
                });
            });
        } finally { ControllerPinManager.enterSubMode(); }
    }
}
