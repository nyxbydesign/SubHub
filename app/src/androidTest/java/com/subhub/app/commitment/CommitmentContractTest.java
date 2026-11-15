package com.subhub.app.commitment;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.View;

import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.subhub.app.MainActivity;
import com.subhub.app.R;
import com.subhub.app.appmode.AppModeManager;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.security.ProtectionStopPolicy;
import com.subhub.app.settings.FeatureModuleManager;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class CommitmentContractTest {
    private Context context;

    @Before public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        CommitmentManager.emergencyRelease(context);
        new AppModeManager(context).setArmed(false);
        new FeatureModuleManager(context).save(true, true, true);
        ControllerPinManager.enterSubMode();
    }

    @After public void tearDown() {
        CommitmentManager.emergencyRelease(context);
        new AppModeManager(context).setArmed(false);
        ControllerPinManager.enterSubMode();
    }

    @Test public void pactNeedsOnlyADurationAndEndsThroughTheDomRecoveryBoundary() {
        assertTrue(CommitmentManager.start(context, 2L * 60L * 60L * 1000L));
        assertTrue(CommitmentManager.isActive(context));
        CommitmentManager.emergencyRelease(context);
        assertFalse(CommitmentManager.isActive(context));
    }

    @Test public void durationIsBoundedAndEmergencyReleaseIsUnconditional() {
        assertTrue(CommitmentManager.start(context, 1L));
        assertEquals(CommitmentManager.MIN_DURATION_MS,
                CommitmentManager.originalDurationMillis(context));
        CommitmentManager.emergencyRelease(context);
        assertFalse(CommitmentManager.isActive(context));
        assertTrue(CommitmentManager.start(context, Long.MAX_VALUE));
        assertEquals(CommitmentManager.MAX_DURATION_MS,
                CommitmentManager.originalDurationMillis(context));
    }

    @Test public void sharedHomeOffersEveryServiceDurationAndProtectionControl() {
        ControllerPinManager.enterSubMode();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                if (activity.findViewById(R.id.commitment_card).getVisibility() != View.VISIBLE) {
                    activity.findViewById(R.id.button_edit_lock).performClick();
                }
                assertEquals(View.VISIBLE,
                        activity.findViewById(R.id.commitment_start_panel).getVisibility());
                assertEquals(View.VISIBLE,
                        activity.findViewById(R.id.button_protection).getVisibility());
                assertEquals(View.VISIBLE, activity.findViewById(R.id.commitment_timer_1h).getVisibility());
                assertEquals(View.VISIBLE, activity.findViewById(R.id.commitment_timer_24h).getVisibility());
                assertEquals(View.VISIBLE, activity.findViewById(R.id.commitment_timer_7d).getVisibility());
                assertEquals(View.VISIBLE, activity.findViewById(R.id.commitment_timer_30d).getVisibility());
                assertEquals(View.VISIBLE,
                        activity.findViewById(R.id.commitment_timer_permanent).getVisibility());
            });
        }
    }

    @Test public void activePactShowsCountdownAndSubCannotStopProtection() {
        assertTrue(CommitmentManager.start(context, CommitmentManager.MIN_DURATION_MS));
        assertFalse(ControllerPinManager.isDomModeActive());
        android.content.Intent home = new android.content.Intent(context, MainActivity.class)
                .setAction(android.content.Intent.ACTION_MAIN)
                .putExtra(MainActivity.EXTRA_SUPPRESS_PERMISSION_READINESS, true);
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(home)) {
            scenario.onActivity(activity -> {
                assertEquals(View.VISIBLE, activity.findViewById(R.id.commitment_active_panel).getVisibility());
                CharSequence description = activity.findViewById(R.id.service_countdown).getContentDescription();
                assertFalse(android.text.TextUtils.isEmpty(description));
                assertEquals(View.GONE, activity.findViewById(R.id.button_protection).getVisibility());
                assertEquals(ProtectionStopPolicy.Decision.TIMER_LOCKED,
                        ProtectionStopPolicy.decision(context));
                assertFalse(ControllerPinManager.isDomModeActive());
                assertTrue(CommitmentManager.isActive(context));
            });
        }
    }

    @Test public void sealingArmsProtectionAndRequiresDomModeToStop() {
        assertTrue(CommitmentManager.start(context, CommitmentManager.MIN_DURATION_MS));
        assertTrue(new AppModeManager(context).isArmed());
        assertFalse(CommitmentManager.mayStopProtection(context));
        ControllerPinManager.enterDomMode();
        assertFalse(CommitmentManager.mayStopProtection(context));
    }

    @Test public void pactNeverDisablesMainProtectionControl() {
        assertTrue(CommitmentManager.start(context, CommitmentManager.MIN_DURATION_MS));
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                if (activity.findViewById(R.id.commitment_card).getVisibility() != View.VISIBLE) {
                    activity.findViewById(R.id.button_edit_lock).performClick();
                }
                assertTrue(activity.findViewById(R.id.button_protection).isEnabled());
                assertEquals(View.VISIBLE,
                        activity.findViewById(R.id.commitment_card).getVisibility());
            });
        }
    }

    @Test public void reopeningHomeRearmsInsteadOfDeletingATimedService() {
        assertTrue(CommitmentManager.start(context, CommitmentManager.MIN_DURATION_MS));
        new AppModeManager(context).setArmed(false);
        assertTrue(CommitmentManager.isActive(context));

        try (ActivityScenario<MainActivity> ignored = ActivityScenario.launch(MainActivity.class)) {
            assertTrue(CommitmentManager.isActive(context));
            assertTrue(new AppModeManager(context).isArmed());
        }
    }

    @Test public void serviceDurationAndHardcoreCopyUseTheCurrentModel() {
        String hardcore = context.getString(R.string.hardcore_title).toLowerCase();
        int[] serviceCopy = {
                R.string.person_duration_title,
                R.string.commitment_active_remaining,
                R.string.role_end_session,
                R.string.commitment_stop_requires_dom,
                R.string.achievement_desc_pact_sealed,
                R.string.achievement_desc_pact_long_haul,
                R.string.achievement_desc_paid_pause
        };
        assertFalse(hardcore.contains("consensual"));
        for (int resource : serviceCopy) {
            String copy = context.getString(resource).toLowerCase();
            assertFalse(copy.contains("timed protection"));
            assertFalse(copy.contains("timed mode"));
            assertFalse(copy.contains("pact"));
            assertFalse(copy.contains("hash"));
            assertFalse(copy.contains("salt"));
        }
    }
}
