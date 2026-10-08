package com.subhub.app.commitment;

import android.content.Context;
import android.os.SystemClock;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.subhub.app.R;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.settings.SettingsRepository;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class PactOptionsAndroidTest {
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    @Before public void setup() { CommitmentManager.emergencyRelease(context); ControllerPinManager.enterSubMode(); }
    @After public void cleanup() { CommitmentManager.emergencyRelease(context); ControllerPinManager.enterSubMode(); }
    @Test public void randomDeadlineIsChosenOnceAndHiddenOnlyFromSub() {
        assertTrue(CommitmentManager.start(context, PactDuration.MIN, PactDuration.MIN * 2, true));
        long duration = CommitmentManager.originalDurationMillis(context);
        long deadline = new SettingsRepository(context).preferences().getLong("commitment_ends_at", 0);
        assertTrue(duration >= PactDuration.MIN && duration <= PactDuration.MIN * 2);
        assertTrue(CommitmentManager.isCountdownHidden(context));
        assertEquals(context.getString(R.string.pact_time_hidden), CommitmentManager.countdownLabel(context));
        assertFalse(CommitmentManager.start(context, PactDuration.MIN, PactDuration.MAX, false));
        CommitmentManager.applyBootPolicy(context);
        assertEquals(duration, CommitmentManager.originalDurationMillis(context));
        assertEquals(deadline, new SettingsRepository(context).preferences().getLong("commitment_ends_at", 0));
        ControllerPinManager.enterDomMode(); assertFalse(CommitmentManager.isCountdownHidden(context));
        assertNotEquals(context.getString(R.string.pact_time_hidden), CommitmentManager.countdownLabel(context));
    }
    @Test public void monotonicExpiryClearsHiddenStateAndDoesNotExtendDeadline() {
        assertTrue(CommitmentManager.start(context, PactDuration.MIN, PactDuration.MIN, true));
        new SettingsRepository(context).preferences().edit()
                .putLong("commitment_ends_elapsed", SystemClock.elapsedRealtime() - 1).commit();
        assertFalse(CommitmentManager.isActive(context));
        assertFalse(CommitmentManager.isCountdownHidden(context));
        assertEquals(0, CommitmentManager.remainingMillis(context));
    }
}
