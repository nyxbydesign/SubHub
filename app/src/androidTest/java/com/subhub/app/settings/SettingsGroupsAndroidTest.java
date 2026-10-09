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
    @Test public void settingsOrderMatchesPurposeAndStudioLivesWithArrangements() {
        ControllerPinManager.enterDomMode();
        try(ActivityScenario<GlobalSettingsActivity> scenario=ActivityScenario.launch(GlobalSettingsActivity.class)) {
            scenario.onActivity(a->{
                java.util.List<String> keys=new java.util.ArrayList<>();collectKeys(a.findViewById(android.R.id.content),keys);
                assertEquals(java.util.Arrays.asList("settings:features","settings:apps","settings:permissions","settings:privacy","settings:pacts","settings:services","settings:help"),keys);
            });
            onView(withTagValue(org.hamcrest.Matchers.is("settings:pacts"))).perform(revealAboveNavigation(),click());
            onView(withId(R.id.button_packs)).perform(scrollTo()).check(matches(isDisplayed()));
            onView(withId(R.id.button_commitment)).check(matches(isDisplayed()));
            onView(withTagValue(org.hamcrest.Matchers.is("settings:permissions"))).perform(revealAboveNavigation(),click());
            onView(withId(R.id.button_accessibility_settings)).perform(scrollTo()).check(matches(isDisplayed()));
        } finally { ControllerPinManager.enterSubMode(); }
    }
    @Test public void everyExpandedGroupHasVisibleControlsAndKeepsOneGroupOpen() {
        ControllerPinManager.enterDomMode();
        try(ActivityScenario<GlobalSettingsActivity> scenario=ActivityScenario.launch(GlobalSettingsActivity.class)) {
            for(String key:new String[]{"features","apps","permissions","privacy","pacts","services","help"}) {
                onView(withTagValue(org.hamcrest.Matchers.is("settings:"+key))).perform(revealAboveNavigation(),click());
                InstrumentationRegistry.getInstrumentation().waitForIdleSync();android.os.SystemClock.sleep(150);
                scenario.onActivity(a->{
                    View header=a.findViewById(android.R.id.content).findViewWithTag("settings:"+key);
                    android.widget.LinearLayout c=(android.widget.LinearLayout)header.getParent();
                    assertTrue("Expanded "+key+" Dom="+ControllerPinManager.isDomModeActive()+" visibility="+c.getChildAt(1).getVisibility(),c.getChildAt(1).isShown());
                    android.view.ViewGroup menu=(android.view.ViewGroup)c.getParent();int expanded=0;
                    for(int i=0;i<menu.getChildCount();i++){android.view.ViewGroup group=(android.view.ViewGroup)menu.getChildAt(i);if(group.getChildAt(1).isShown())expanded++;}
                    assertEquals(1,expanded);
                    View root=a.getWindow().getDecorView();Bitmap image=Bitmap.createBitmap(root.getWidth(),root.getHeight(),Bitmap.Config.ARGB_8888);root.draw(new Canvas(image));
                    try(FileOutputStream out=new FileOutputStream(new File(a.getFilesDir(),"settings-quality-"+key+".png"))){image.compress(Bitmap.CompressFormat.PNG,100,out);}catch(Exception error){throw new AssertionError(error);}finally{image.recycle();}
                });
            }
        } finally { ControllerPinManager.enterSubMode(); }
    }
    private androidx.test.espresso.ViewAction revealAboveNavigation() {
        return new androidx.test.espresso.ViewAction() {
            @Override public org.hamcrest.Matcher<View> getConstraints() {
                return androidx.test.espresso.matcher.ViewMatchers.isDescendantOfA(
                        org.hamcrest.Matchers.instanceOf(android.widget.ScrollView.class));
            }
            @Override public String getDescription() { return "reveal control above floating navigation"; }
            @Override public void perform(androidx.test.espresso.UiController ui, View view) {
                android.view.ViewParent parent = view.getParent();
                while (!(parent instanceof android.widget.ScrollView)) parent = parent.getParent();
                android.widget.ScrollView scroll = (android.widget.ScrollView) parent;
                android.graphics.Rect target = new android.graphics.Rect();
                view.getDrawingRect(target);
                scroll.offsetDescendantRectToMyCoords(view, target);
                scroll.scrollTo(0, Math.max(0, target.top - scroll.getHeight() / 3));
                ui.loopMainThreadUntilIdle();
                android.graphics.Rect visible = new android.graphics.Rect();
                assertTrue(view.getGlobalVisibleRect(visible));
                View navigation = view.getRootView().findViewById(R.id.bottom_navigation);
                android.graphics.Rect nav = new android.graphics.Rect();
                if (navigation != null && navigation.getGlobalVisibleRect(nav)) {
                    assertTrue("Tap target must be above navigation", visible.bottom <= nav.top);
                }
            }
        };
    }

    private static void collectKeys(View view,java.util.List<String> keys){if(view.getTag() instanceof String&&((String)view.getTag()).startsWith("settings:"))keys.add((String)view.getTag());if(view instanceof android.view.ViewGroup){android.view.ViewGroup g=(android.view.ViewGroup)view;for(int i=0;i<g.getChildCount();i++)collectKeys(g.getChildAt(i),keys);}}
    @Test public void categoriesOpenFocusedControlsAndKeepPrivacyDiscoverable() {
        ControllerPinManager.enterDomMode();
        try (ActivityScenario<GlobalSettingsActivity> scenario = ActivityScenario.launch(GlobalSettingsActivity.class)) {
            onView(withText(R.string.settings_apps)).perform(revealAboveNavigation(), click());
            onView(withId(R.id.button_toggle_apps)).perform(scrollTo()).check(matches(isDisplayed()));
            onView(org.hamcrest.Matchers.allOf(withText(R.string.privacy_title), withEffectiveVisibility(Visibility.VISIBLE))).perform(revealAboveNavigation(), click());
            onView(withId(R.id.privacy_discreet_toggle)).check(matches(isDisplayed()));
            onView(withTagValue(org.hamcrest.Matchers.is("settings:features"))).perform(revealAboveNavigation(),click());
            onView(withId(R.id.switch_module_censor)).check(matches(isDisplayed()));
            onView(withId(R.id.privacy_discreet_toggle)).check(matches(withEffectiveVisibility(Visibility.GONE)));
            onView(withTagValue(org.hamcrest.Matchers.is("settings:help"))).check(matches(withEffectiveVisibility(Visibility.VISIBLE)));
            onView(withTagValue(org.hamcrest.Matchers.is("settings:features"))).perform(scrollTo());
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
