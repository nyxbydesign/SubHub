package com.subhub.app.censor.android;

import android.content.Intent;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

/** Injected platform samples verify fences; these cases do not claim an actual display transition. */
@RunWith(AndroidJUnit4.class)
public final class CensorScreenStateAndroidTest {
    @Test public void startupOffAndWakeLockedCannotAdmitUntilFreshUnlockedSample() {
        AtomicBoolean usable = new AtomicBoolean();
        CensorScreenState screen = new CensorScreenState(usable::get);
        assertFalse(screen.eligible()); long initial = screen.generation();
        screen.refresh(Intent.ACTION_SCREEN_OFF);
        screen.refresh(Intent.ACTION_SCREEN_ON);
        assertFalse(screen.eligible()); assertFalse(screen.isCurrent(initial));
        screen.refresh(Intent.ACTION_USER_PRESENT);
        assertFalse(screen.eligible());
        usable.set(true); screen.refresh(Intent.ACTION_USER_PRESENT);
        assertTrue(screen.eligible()); assertFalse(screen.isCurrent(initial));
    }

    @Test public void offImmediatelyFencesEvenAnOldInteractivePlatformSampleAndRapidWake() {
        CensorScreenState screen = new CensorScreenState(() -> true);
        long before = screen.generation();
        screen.refresh(Intent.ACTION_SCREEN_OFF);
        assertFalse(screen.eligible()); screen.refresh(null); assertFalse(screen.eligible());
        screen.refresh(Intent.ACTION_SCREEN_ON);
        assertTrue(screen.eligible()); assertFalse(screen.isCurrent(before));
    }

    @Test public void sampledLockIsLatchedAndOldStampCannotReviveWithoutRefresh() {
        AtomicBoolean usable = new AtomicBoolean(true);
        CensorScreenState screen = new CensorScreenState(usable::get);
        long before = screen.generation(); usable.set(false);
        assertFalse(screen.isCurrent(before));
        usable.set(true); assertFalse(screen.eligible()); assertFalse(screen.isCurrent(before));
        screen.refresh(null); assertTrue(screen.eligible()); assertFalse(screen.isCurrent(before));
    }

    @Test public void unavailablePlatformFailsClosed() {
        CensorScreenState screen = new CensorScreenState(() -> { throw new IllegalStateException("Unavailable platform"); });
        assertFalse(screen.eligible()); screen.refresh(Intent.ACTION_USER_PRESENT); assertFalse(screen.eligible());
    }
}
