package com.subhub.app.security;

import static org.junit.Assert.*;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.*;
import static androidx.test.espresso.assertion.ViewAssertions.doesNotExist;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import android.content.*;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.MainActivity;
import com.subhub.app.R;
import com.subhub.app.settings.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.*;

/** Protected actions explain the role boundary; only the explicit lock control authenticates. */
public final class RoleActionAndroidTest {
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final Map<SharedPreferences, Map<String, ?>> original = new LinkedHashMap<>();
    @Before public void fixture() {
        for (String name : new String[] {SettingsRepository.PREFERENCES_NAME, "subhub_controller_auth"}) {
            SharedPreferences prefs = context.getSharedPreferences(name, 0);
            original.put(prefs, prefs.getAll());
        }
        context.getSharedPreferences("subhub_controller_auth", 0).edit().clear().commit();
        ControllerPinManager.setPin(context, "2468");
        ControllerPinManager.enterSubMode();
    }
    @After public void restore() {
        for (Map.Entry<SharedPreferences, Map<String, ?>> item : original.entrySet())
            SharedPreferenceTestRestore.restore(item.getKey(), item.getValue());
        ControllerPinManager.enterSubMode();
    }
    @Test public void blockedActionDoesNotAuthenticateAndManualUnlockStillWorks() {
        AtomicBoolean called = new AtomicBoolean();
        try (ActivityScenario<MainActivity> page = ActivityScenario.launch(new Intent(context, MainActivity.class)
                .setAction(Intent.ACTION_MAIN).putExtra(MainActivity.EXTRA_SUPPRESS_PERMISSION_READINESS, true))) {
            page.onActivity(a -> ControllerPinGate.require(a, () -> called.set(true), false));
            assertFalse(called.get()); assertFalse(ControllerPinManager.isDomModeActive());
            onView(withHint(R.string.controller_pin_label)).check(doesNotExist());
            page.onActivity(a -> a.findViewById(R.id.button_edit_lock).performClick());
            onView(withHint(R.string.controller_pin_label)).perform(replaceText("2468"), closeSoftKeyboard());
            onView(withText(R.string.controller_pin_unlock)).inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).perform(click());
            assertTrue(ControllerPinManager.isDomModeActive());
            page.onActivity(a -> ControllerPinGate.require(a, () -> called.set(true), false));
            assertTrue(called.get());
        }
    }
    @Test public void unkeyedSubStillChoosesDomExplicitly() {
        ControllerPinManager.enterDomMode();
        assertTrue(ControllerPinManager.removePin(context));
        ControllerPinManager.enterSubMode();
        AtomicBoolean called = new AtomicBoolean();
        try (ActivityScenario<MainActivity> page = ActivityScenario.launch(new Intent(context, MainActivity.class)
                .setAction(Intent.ACTION_MAIN).putExtra(MainActivity.EXTRA_SUPPRESS_PERMISSION_READINESS, true))) {
            page.onActivity(a -> ControllerPinGate.require(a, () -> called.set(true), false));
            assertFalse(called.get()); assertFalse(ControllerPinManager.isDomModeActive());
            page.onActivity(a -> a.findViewById(R.id.button_edit_lock).performClick());
            assertTrue(ControllerPinManager.isDomModeActive());
        }
    }
}
