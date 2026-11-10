package com.subhub.app.tasks;

import android.content.*;
import android.os.SystemClock;
import android.view.View;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.*;
import com.subhub.app.penance.*;
import com.subhub.app.security.*;
import com.subhub.app.settings.*;
import org.junit.*;
import org.junit.runner.RunWith;
import java.util.concurrent.atomic.AtomicBoolean;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class TaskTributeSettingsAndroidTest {
    @Rule public final KeyholderPresentationAndroidTest.IntegrationFixture integration = new KeyholderPresentationAndroidTest.IntegrationFixture();
    @Rule public final UiAutomationStateRule automation = new UiAutomationStateRule();
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    @Test public void taskFailureRuleUsesAuthorizedNativeEditorAndBecomesReadOnlyInSubMode() throws Exception {
        context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME, 0).edit()
                .putString(DeviceRolePreferences.KEY_MODE, DeviceRolePreferences.Mode.BOTH.name()).commit();
        ControllerPinManager.initialize(context); assertTrue(ControllerPinManager.setPin(context, "2468")); ControllerPinManager.enterDomMode();
        new PenanceManager(context).disableInfraction(PenanceInfraction.TASK_FAILURE);
        try (ActivityScenario<PenanceActivity> page = ActivityScenario.launch(new Intent(context, PenanceActivity.class).setAction(Intent.ACTION_MAIN))) {
            onView(withTagValue(is("wallet:rules"))).perform(NativeUiActions.revealAboveNavigation(), click());
            AtomicBoolean disabled = new AtomicBoolean();
            page.onActivity(activity -> disabled.set(!activity.findViewById(R.id.rule_task_failure_amount).isEnabled()));
            assertTrue(disabled.get());
            onView(withId(R.id.rule_task_failure_enabled)).perform(NativeUiActions.revealAboveNavigation(), click());
            long deadline = SystemClock.elapsedRealtime() + 5000;
            while (!new PenanceManager(context).isInfractionEnabled(PenanceInfraction.TASK_FAILURE) && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50);
            assertTrue(new PenanceManager(context).isInfractionEnabled(PenanceInfraction.TASK_FAILURE));
            page.onActivity(activity -> assertTrue(activity.findViewById(R.id.rule_task_failure_amount).isEnabled()));
            ControllerPinManager.enterSubMode(); page.recreate();
            page.onActivity(activity -> {
                assertFalse(activity.findViewById(R.id.rule_task_failure_enabled).isEnabled());
                assertFalse(activity.findViewById(R.id.rule_task_failure_amount).isEnabled());
            });
            assertTrue(new PenanceManager(context).isInfractionEnabled(PenanceInfraction.TASK_FAILURE));
        }
    }
}
