package com.subhub.app.settings;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.matcher.ViewMatchers.withTagValue;

import static com.subhub.app.NativeUiActions.revealAboveNavigation;

import static org.junit.Assert.*;
import android.view.*;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.R;
import com.subhub.app.security.ControllerPinManager;
import org.junit.*;
import java.util.*;

public class SettingsGroupsAndroidTest {
    @Before
    public void fixture() {
        ControllerPinManager.setPin(
                InstrumentationRegistry.getInstrumentation().getTargetContext(), "2468");
        ControllerPinManager.enterDomMode();
    }

    @After
    public void restore() {
        ControllerPinManager.enterSubMode();
    }

    @Test
    public void directSectionsKeepHardcoreWithPrivacyAndPermissions() {
        try(ActivityScenario<GlobalSettingsActivity> scenario=ActivityScenario.launch(GlobalSettingsActivity.class)) {
            scenario.onActivity(a->{
                        List<String> keys=new ArrayList<>();
                        collect(a.findViewById(android.R.id.content),keys);
                assertEquals(
                                Arrays.asList("settings:apps",
                                        "settings:privacy","settings:help"),keys);
                        assertNull(a.findViewById(R.id.paypal_card));
                    });
            scenario.onActivity(
                    a -> {
                        View hardcore = a.findViewById(R.id.hardcore_card);
                        assertTrue(hardcore.isShown());
                        assertNull(a.findViewById(R.id.feature_areas_card));
                        assertNull(a.findViewById(R.id.button_packs));
                    });
            scenario.onActivity(
                    a -> {
                        View privacy = a.findViewById(R.id.privacy_discreet_toggle),
                                permission = a.findViewById(R.id.android_access_card);
                        assertTrue(privacy.isShown());
                        assertTrue(permission.isShown());
                        int[] p = new int[2], q = new int[2];
                        privacy.getLocationOnScreen(p);
                        permission.getLocationOnScreen(q);
                        assertTrue(q[1] > p[1]);
                    });
            scenario.recreate();
            scenario.onActivity(
                    a -> assertTrue(a.findViewById(R.id.privacy_discreet_toggle).isShown())); }
                }

    @Test public void appsOpenWithOneSectionAndRestoreWithoutAnInnerToggle() {
        try (ActivityScenario<GlobalSettingsActivity> scenario = ActivityScenario.launch(GlobalSettingsActivity.class)) {
            onView(withTagValue(org.hamcrest.Matchers.is("settings:apps"))).perform(revealAboveNavigation(), click());
            scenario.onActivity(a -> {
                assertTrue(a.findViewById(R.id.app_list_content).isShown());
                View header = a.findViewById(android.R.id.content).findViewWithTag("settings:apps");
                assertTrue(((com.subhub.app.util.ExpandableSectionView) header.getParent()).summary().isShown());
                assertNull(a.findViewById(R.id.button_toggle_apps));
            });
            scenario.recreate();
            scenario.onActivity(a -> assertTrue(a.findViewById(R.id.app_list_content).isShown()));
            onView(withTagValue(org.hamcrest.Matchers.is("settings:apps"))).perform(revealAboveNavigation(), click());
            scenario.onActivity(a -> assertFalse(a.findViewById(R.id.app_list_content).isShown()));
        }
    }

    private static void collect(View v, List<String> keys) {
        if (v.getTag() instanceof String && ((String) v.getTag()).startsWith("settings:"))
            keys.add((String) v.getTag());
        if (v instanceof ViewGroup)
            for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++)
                collect(((ViewGroup) v).getChildAt(i), keys); }
    }
