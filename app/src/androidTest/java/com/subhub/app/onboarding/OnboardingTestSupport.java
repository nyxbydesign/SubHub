package com.subhub.app.onboarding;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withTagValue;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.*;
import androidx.test.core.app.ActivityScenario;
import com.subhub.app.R;
import com.subhub.app.security.DeviceRolePreferences.Mode;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** Native navigation follows durable page identities rather than historical page positions. */
public final class OnboardingTestSupport {
    private OnboardingTestSupport() { }
    public static String pageId(ActivityScenario<OnboardingActivity> page) {
        AtomicReference<String> id = new AtomicReference<>();
        page.onActivity(activity -> {
            Object tag = activity.findViewById(R.id.tour_content).getTag();
            assertTrue(tag instanceof String && ((String) tag).startsWith("onboarding_page:"));
            id.set(((String) tag).substring("onboarding_page:".length()));
        });
        return id.get();
    }
    public static void choose(ActivityScenario<OnboardingActivity> page, Mode role) {
        assertEquals(OnboardingFlow.ROLE, pageId(page));
        onView(withTagValue(is("device_role:" + role.name()))).perform(scrollTo(), click());
        onView(withId(R.id.tour_next)).perform(click());
    }
    public static void goTo(ActivityScenario<OnboardingActivity> page, String id) {
        goTo(page, id, Mode.BOTH);
    }
    public static void goTo(ActivityScenario<OnboardingActivity> page, String id, Mode role) {
        List<OnboardingPage> flow = OnboardingFlow.pages(role);
        int target = OnboardingFlow.index(flow, id);
        assertTrue("Requested page is in the selected role", target > 0);
        for (int attempts = 0; attempts < 64; attempts++) {
            String current = pageId(page);
            if (id.equals(current)) return;
            if (OnboardingFlow.ROLE.equals(current)) { choose(page, role); continue; }
            int index = OnboardingFlow.index(flow, current);
            if (index == 0 || index > target) androidx.test.espresso.Espresso.pressBack();
            else onView(withId(R.id.tour_next)).perform(click());
        }
        fail("Setup did not reach " + id);
    }
}
