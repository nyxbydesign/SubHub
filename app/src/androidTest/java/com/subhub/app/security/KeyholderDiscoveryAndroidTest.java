package com.subhub.app.security;

import android.content.Context;
import android.content.Intent;
import android.view.View;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.MainActivity;
import com.subhub.app.R;
import com.subhub.app.atmosphere.AtmosphereActivity;
import org.junit.Test;
import org.junit.runner.RunWith;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.*;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static androidx.test.espresso.assertion.ViewAssertions.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class KeyholderDiscoveryAndroidTest {
    @Test public void methodCardsAreExclusiveAndRestoreTheirExpandedSelection() {
        ControllerPinManager.enterDomMode();
        try (ActivityScenario<AuthenticatorActivity> page = ActivityScenario.launch(AuthenticatorActivity.class)) {
            onView(withId(R.id.keyholder_pin_change_button)).check(matches(withEffectiveVisibility(Visibility.VISIBLE)));
            onView(withId(R.id.keyholder_pair_button)).check(matches(withEffectiveVisibility(Visibility.GONE)));
            onView(withId(R.id.keyholder_remote_header)).perform(scrollTo(), click());
            onView(withId(R.id.keyholder_pin_change_button)).check(matches(withEffectiveVisibility(Visibility.GONE)));
            onView(withId(R.id.keyholder_pair_button)).check(matches(withEffectiveVisibility(Visibility.VISIBLE)));
            page.recreate();
            onView(withId(R.id.keyholder_pair_button)).check(matches(withEffectiveVisibility(Visibility.VISIBLE)));
            onView(withId(R.id.keyholder_pin_header)).perform(scrollTo(), click());
            onView(withId(R.id.keyholder_pair_button)).check(matches(withEffectiveVisibility(Visibility.GONE)));
            onView(withId(R.id.keyholder_pin_change_button)).check(matches(withEffectiveVisibility(Visibility.VISIBLE)));
        } finally { ControllerPinManager.enterSubMode(); }
    }
    @Test public void dismissedIntroductionStaysDismissedAndRitualsStaysDomOnly() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        context.getSharedPreferences("subhub_home", 0).edit().remove("keyholder_intro_dismissed").commit();
        ControllerPinManager.enterSubMode();
        try (ActivityScenario<MainActivity> home = ActivityScenario.launch(new Intent(context, MainActivity.class).setAction(Intent.ACTION_MAIN))) {
            home.onActivity(a->{
                if("PERMISSIONS".equals(a.findViewById(R.id.global_notice_host).getTag()))a.findViewById(R.id.global_notice_dismiss).performClick();
                assertEquals("KEYHOLDER",a.findViewById(R.id.global_notice_host).getTag());
            });
            onView(withId(R.id.global_notice_dismiss)).perform(scrollTo(), click());
            home.recreate();
            home.onActivity(activity -> {
                assertEquals(View.GONE, activity.findViewById(R.id.global_notice_host).getVisibility());
                assertEquals(View.GONE, activity.findViewById(R.id.nav_atmosphere).getVisibility());
            });
        } finally { context.getSharedPreferences("subhub_home", 0).edit().remove("keyholder_intro_dismissed").commit(); }
    }
    @Test public void subCanDiscoverBothMethodsButCannotChangePinWithoutAuthorization() {
        ControllerPinManager.enterSubMode();
        try (ActivityScenario<AuthenticatorActivity> rituals = ActivityScenario.launch(AuthenticatorActivity.class)) {
            onView(withId(R.id.keyholder_pin_change_button)).check(matches(isDisplayed())).perform(click());
            onView(withText(R.string.controller_pin_unlock)).check(matches(isDisplayed()));
            onView(withText(android.R.string.cancel)).perform(click());
            assertFalse(ControllerPinManager.isDomModeActive());
            onView(withId(R.id.keyholder_remote_header)).perform(scrollTo(), click());
            onView(withId(R.id.keyholder_pair_button)).perform(scrollTo()).check(matches(isDisplayed()));
        }
    }
}
