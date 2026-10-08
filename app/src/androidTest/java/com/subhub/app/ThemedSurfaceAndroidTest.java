package com.subhub.app;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.matcher.RootMatchers.isDialog;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static org.junit.Assert.*;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.espresso.assertion.ViewAssertions;

import com.google.android.material.snackbar.Snackbar;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.util.ColorPickerDialog;
import com.subhub.app.util.ThemedDialogs;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Native theme/semantics checks, without screenshots or real provider operations. */
@RunWith(AndroidJUnit4.class)
public final class ThemedSurfaceAndroidTest {
    @Test public void confirmationDialogHasOpaqueRoundedThemeAndFullSizeActions() {
        AtomicBoolean confirmed = new AtomicBoolean();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> ThemedDialogs.builder(activity)
                    .setTitle("Theme check").setMessage("A local confirmation.")
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(android.R.string.ok, (dialog, which) -> confirmed.set(true)).show());
            onView(withId(android.R.id.button1)).inRoot(isDialog()).check(ViewAssertions.matches(isDisplayed()));
            onView(withId(android.R.id.button1)).inRoot(isDialog()).check((view, error) -> {
                if (error != null) throw error;
                assertTrue(view.getHeight() >= view.getResources().getDimensionPixelSize(R.dimen.control_min_height));
            });
            onView(withId(android.R.id.button2)).inRoot(isDialog()).perform(click());
            assertFalse(confirmed.get());
            scenario.onActivity(activity -> {
                Drawable bubble = activity.getDrawable(R.drawable.bg_native_dialog);
                Bitmap sample = Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888);
                try {
                    bubble.setBounds(0, 0, 160, 160);
                    bubble.draw(new Canvas(sample));
                    assertEquals(255, Color.alpha(sample.getPixel(80, 80)));
                    assertTrue(Color.red(sample.getPixel(80, 80)) < 90);
                    assertEquals(0, Color.alpha(sample.getPixel(0, 0)));
                } finally { sample.recycle(); }
                TextView help = new TextView(activity, null, 0, R.style.Widget_SubHub_FieldHelp);
                assertEquals(activity.getColor(R.color.text_secondary), help.getCurrentTextColor());
            });
        }
    }

    @Test public void colorPreviewUsesRoundedSwatchAndCancelStillDoesNotApply() {
        AtomicBoolean applied = new AtomicBoolean();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> ColorPickerDialog.show(activity, "Color theme", Color.RED, color -> applied.set(true)));
            onView(androidx.test.espresso.matcher.ViewMatchers.withTagValue(org.hamcrest.Matchers.is((Object) "color_preview")))
                    .inRoot(isDialog()).check((view, error) -> {
                        if (error != null) throw error;
                        assertTrue(view.getBackground() instanceof android.graphics.drawable.GradientDrawable);
                        assertEquals("#FF0000", ((TextView) view).getText().toString());
                        assertEquals(Color.BLACK, ((TextView) view).getCurrentTextColor());
                    });
            onView(withId(android.R.id.button2)).inRoot(isDialog()).perform(click());
            assertFalse(applied.get());
        }
    }

    @Test public void inlineNoticesUseThemedTextAndActionWithoutChangingNavigation() {
        ControllerPinManager.enterDomMode();
        AtomicReference<Snackbar> notice = new AtomicReference<>();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                Snackbar value = Snackbar.make(activity.findViewById(android.R.id.content),
                        "Saved locally", Snackbar.LENGTH_INDEFINITE).setAction("Undo", view -> { });
                notice.set(value);
                value.show();
            });
            onView(withId(com.google.android.material.R.id.snackbar_text)).check(ViewAssertions.matches(isDisplayed()));
            scenario.onActivity(activity -> {
                TextView text = notice.get().getView().findViewById(com.google.android.material.R.id.snackbar_text);
                TextView action = notice.get().getView().findViewById(com.google.android.material.R.id.snackbar_action);
                assertEquals(activity.getColor(R.color.text_primary), text.getCurrentTextColor());
                assertEquals(activity.getColor(R.color.accent_text), action.getCurrentTextColor());
                assertNotNull(notice.get().getView().getBackground());
                notice.get().dismiss();
            });
        }
    }
}
