package com.subhub.app;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withTagValue;
import static androidx.test.espresso.matcher.RootMatchers.isDialog;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.*;

import android.graphics.Color;
import android.view.MotionEvent;
import android.view.View;
import androidx.test.core.app.ActivityScenario;
import androidx.test.espresso.UiController;
import androidx.test.espresso.ViewAction;
import androidx.test.espresso.matcher.ViewMatchers;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.subhub.app.settings.SettingsRepository;
import com.subhub.app.util.ColorPickerDialog;
import java.util.concurrent.atomic.AtomicInteger;
import org.hamcrest.Matcher;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class ColorPickerDialogTest {
    @Test public void rgbKeyboardInputSelectsAnExactChannelValue() {
        AtomicInteger accepted = new AtomicInteger(Color.BLACK);
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> ColorPickerDialog.show(activity, "Test RGB", Color.BLACK, accepted::set));
            onView(withTagValue(is((Object) "color_channel:1"))).inRoot(isDialog()).perform(
                    androidx.test.espresso.action.ViewActions.scrollTo(), new ViewAction() {
                        @Override public Matcher<View> getConstraints() { return ViewMatchers.isDisplayed(); }
                        @Override public String getDescription() { return "choose RGB red=1 with native keyboard input"; }
                        @Override public void perform(UiController ui, View view) {
                            assertTrue(view.requestFocus());
                            android.view.KeyEvent down = new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN,
                                    android.view.KeyEvent.KEYCODE_DPAD_RIGHT);
                            android.view.KeyEvent up = new android.view.KeyEvent(android.view.KeyEvent.ACTION_UP,
                                    android.view.KeyEvent.KEYCODE_DPAD_RIGHT);
                            try { assertTrue(ui.injectKeyEvent(down)); assertTrue(ui.injectKeyEvent(up)); }
                            catch (androidx.test.espresso.InjectEventSecurityException denied) {
                                throw new AssertionError("Native RGB input was denied", denied);
                            }
                            ui.loopMainThreadUntilIdle();
                            assertEquals(1, ((android.widget.SeekBar) view).getProgress());
                        }
                    });
            onView(withId(android.R.id.button1)).inRoot(isDialog()).perform(click());
            assertEquals(Color.rgb(1, 0, 0), accepted.get());
        }
    }

    @Test public void wheelTouchConfirmsExactOpaqueRedAndCancelDoesNotWrite() {
        AtomicInteger accepted = new AtomicInteger(Color.BLUE);
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> ColorPickerDialog.show(activity, "Test color", Color.BLUE, accepted::set));
            onView(withTagValue(is((Object) "color_wheel"))).inRoot(isDialog()).perform(redEdge());
            onView(withId(android.R.id.button2)).inRoot(isDialog()).perform(click());
            assertEquals(Color.BLUE, accepted.get());
            scenario.onActivity(activity -> ColorPickerDialog.show(activity, "Test color", Color.BLUE, accepted::set));
            onView(withTagValue(is((Object) "color_wheel"))).inRoot(isDialog()).perform(redEdge());
            onView(withId(android.R.id.button1)).inRoot(isDialog()).perform(click());
            assertEquals(Color.RED, accepted.get());
        }
    }

    @Test public void repositoryLoadsIndependentEndpointsAndLegacyFallback() {
        android.content.Context context = androidx.test.core.app.ApplicationProvider.getApplicationContext();
        SettingsRepository repository = new SettingsRepository(context);
        android.content.SharedPreferences prefs = repository.preferences();
        String oldStart = prefs.getString(SettingsRepository.KEY_GRADIENT_START, null);
        String oldEnd = prefs.getString(SettingsRepository.KEY_GRADIENT_END, null);
        try {
            prefs.edit().remove(SettingsRepository.KEY_GRADIENT_START).remove(SettingsRepository.KEY_GRADIENT_END).commit();
            assertEquals(repository.loadAppearance().getBorderColor(), repository.loadAppearance().getGradientStart());
            prefs.edit().putString(SettingsRepository.KEY_GRADIENT_START, "#FF0000")
                    .putString(SettingsRepository.KEY_GRADIENT_END, "#0000FF").commit();
            assertEquals(Color.RED, repository.loadAppearance().getGradientStart());
            assertEquals(Color.BLUE, repository.loadAppearance().getGradientEnd());
        } finally {
            prefs.edit().putString(SettingsRepository.KEY_GRADIENT_START, oldStart)
                    .putString(SettingsRepository.KEY_GRADIENT_END, oldEnd).commit();
        }
    }

    public static ViewAction redEdge() {
        return new ViewAction() {
            @Override public Matcher<View> getConstraints() { return ViewMatchers.isDisplayed(); }
            @Override public String getDescription() { return "touch the native wheel's saturated red edge"; }
            @Override public void perform(UiController ui, View view) {
                int[] location = new int[2]; view.getLocationOnScreen(location);
                float x = location[0] + view.getWidth() / 2f + Math.min(view.getWidth(), view.getHeight()) / 2f
                        - 12 * view.getResources().getDisplayMetrics().density;
                float y = location[1] + view.getHeight() / 2f;
                long now = android.os.SystemClock.uptimeMillis();
                MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0);
                MotionEvent up = MotionEvent.obtain(now, now + 16, MotionEvent.ACTION_UP, x, y, 0);
                try { assertTrue(ui.injectMotionEvent(down)); assertTrue(ui.injectMotionEvent(up)); }
                catch (androidx.test.espresso.InjectEventSecurityException denied) {
                    throw new AssertionError("Native wheel input injection was denied", denied);
                }
                finally { down.recycle(); up.recycle(); }
                ui.loopMainThreadUntilIdle();
            }
        };
    }
}
