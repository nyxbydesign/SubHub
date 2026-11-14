package com.subhub.app.commitment;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import android.content.Context;
import android.content.Intent;
import android.graphics.*;
import android.view.View;
import android.widget.*;

import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;

import com.subhub.app.MainActivity;
import com.subhub.app.R;
import com.subhub.app.appmode.AppModeManager;
import com.subhub.app.onboarding.OnboardingState;
import com.subhub.app.people.PersonId;
import com.subhub.app.people.PersonRoutes;
import com.subhub.app.people.ServiceDurationActivity;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.security.ProtectionStopPolicy;
import com.subhub.app.settings.FeatureModuleManager;

import org.junit.*;

import java.io.*;

public class ServiceDurationUiAndroidTest {
    private Context context;

    @Before
    public void fixture() {
        assumeTrue(android.os.Build.MODEL.contains("sdk_gphone"));
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        OnboardingState.complete(context);
        CommitmentManager.emergencyRelease(context);
        new AppModeManager(context).setArmed(false);
        new FeatureModuleManager(context).save(true, true, true, false);
        context.getSharedPreferences("subhub_service_duration_ui", 0).edit().clear().commit();
        ControllerPinManager.setPin(context, "2468");
        ControllerPinManager.enterDomMode();
    }

    @After
    public void restore() {
        if (context != null) {
            CommitmentManager.emergencyRelease(context);
            ControllerPinManager.enterSubMode();
        }
    }

    @Test
    public void contractDurationPersistsRandomFieldsAndHomeReadsTheSelection() {
        Intent duration = new Intent(context, ServiceDurationActivity.class)
                .putExtra(PersonRoutes.EXTRA_PERSON_ID, PersonId.LOCAL.value());
        try (ActivityScenario<ServiceDurationActivity> settings = ActivityScenario.launch(duration)) {
            settings.onActivity(
                    activity -> {
                        ServiceDurationView picker = activity.findViewById(R.id.person_duration_control);
                        assertTrue(picker.isShown());
                        activity.findViewById(R.id.commitment_timer_1h).performClick();
                        assertTrue(activity.findViewById(R.id.commitment_timer_1h).isSelected());
                        assertFalse(CommitmentManager.isActive(context));
                        assertFalse(new AppModeManager(context).isArmed());
                        activity.findViewById(R.id.service_duration_random).performClick();
                        ((EditText) activity.findViewById(R.id.service_duration_min_hours))
                                .setText("2");
                        ((EditText) activity.findViewById(R.id.service_duration_max_hours))
                                .setText("5");
                        activity.findViewById(R.id.service_duration_hide).performClick();
                        assertTrue(picker.validateSelection());
                        assertEquals(2 * 3_600_000L, picker.selection().minimum);
                        assertEquals(5 * 3_600_000L, picker.selection().maximum);
                        assertTrue(picker.selection().hidden);
                        capture(activity, "duration-settings-random.png");
                    });
            settings.recreate();
            settings.onActivity(
                    activity -> {
                        assertTrue(
                                activity.findViewById(R.id.service_duration_random).isSelected());
                        assertTrue(
                                ((CompoundButton) activity.findViewById(R.id.service_duration_hide))
                                        .isChecked());
                        assertEquals(
                                "2",
                                ((EditText) activity.findViewById(R.id.service_duration_min_hours))
                                        .getText()
                                        .toString());
                    });
            settings.onActivity(
                    activity -> {
                        ((EditText) activity.findViewById(R.id.service_duration_min_hours))
                                .setText("6");
                        ((EditText) activity.findViewById(R.id.service_duration_max_hours))
                                .setText("5");
                        ServiceDurationView picker = activity.findViewById(R.id.person_duration_control);
                        assertFalse(picker.validateSelection());
                        assertNotNull(
                                ((EditText) activity.findViewById(R.id.service_duration_max_hours))
                                        .getError());
                        assertFalse(new AppModeManager(context).isArmed());
                        assertFalse(CommitmentManager.isActive(context));
                        activity.findViewById(R.id.commitment_timer_permanent).performClick();
                        assertTrue(
                                activity.findViewById(R.id.commitment_timer_permanent)
                                        .isSelected());
                        assertFalse(activity.findViewById(R.id.service_duration_range).isShown());
                        assertTrue(activity.findViewById(R.id.service_duration_hide).isShown());
                        capture(activity, "duration-contract-fixed.png");
                    });
        }
        try (ActivityScenario<MainActivity> home = ActivityScenario.launch(homeIntent())) {
            home.onActivity(activity -> {
                ServiceDurationView selection = activity.findViewById(R.id.commitment_start_panel);
                assertEquals(0L, selection.selection().maximum);
                assertTrue(selection.selection().hidden);
                assertTrue(activity.findViewById(R.id.service_configured_duration).isShown());
                assertFalse(new AppModeManager(context).isArmed());
                assertFalse(CommitmentManager.isActive(context));
            });
        }
    }

    @Test
    public void activeHiddenCountdownKeepsSubjectReleaseBlocked() {
        assertTrue(CommitmentManager.start(context, PactDuration.MIN, PactDuration.MIN, true));
        ControllerPinManager.enterSubMode();
        try (ActivityScenario<MainActivity> active = ActivityScenario.launch(homeIntent())) {
            active.onActivity(
                    activity -> {
                        assertTrue(activity.findViewById(R.id.commitment_active_panel).isShown());
                        assertFalse(activity.findViewById(R.id.commitment_start_panel).isShown());
                        assertEquals(
                                context.getString(R.string.pact_time_hidden),
                                activity.findViewById(R.id.service_countdown).getContentDescription());
                        assertFalse(activity.findViewById(R.id.button_protection).isShown());
                        assertEquals(ProtectionStopPolicy.Decision.TIMER_LOCKED,
                                ProtectionStopPolicy.decision(context));
                        assertFalse(ControllerPinManager.isDomModeActive());
                        assertTrue(CommitmentManager.isActive(context));
                        capture(activity, "duration-active-sub.png");
                    });
        }
    }

    private Intent homeIntent() {
        return new Intent(context, MainActivity.class).setAction(Intent.ACTION_MAIN)
                .putExtra(MainActivity.EXTRA_SUPPRESS_PERMISSION_READINESS, true);
    }

    private static void capture(android.app.Activity activity, String name) {
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
