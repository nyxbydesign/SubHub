package com.subhub.app.diagnostics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public final class CensorLabVideoProfileTest {
    @Test public void highRefreshPixelPrefersBoundedSixtyFpsThenThirtyFpsFallbacks() {
        List<CensorLabVideoProfile> profiles = CensorLabVideoProfile.candidates(
                1344, 2992, 120f);

        assertFalse(profiles.isEmpty());
        assertEquals(60, profiles.get(0).frameRate);
        assertTrue(Math.max(profiles.get(0).width, profiles.get(0).height) <= 2400);
        assertEquals(0, profiles.get(0).width % 16);
        assertEquals(0, profiles.get(0).height % 16);
        float sourceAspect = 1344f / 2992f;
        float outputAspect = profiles.get(0).width / (float) profiles.get(0).height;
        assertTrue(Math.abs(sourceAspect - outputAspect) / sourceAspect < 0.01f);
        assertTrue(profiles.stream().anyMatch(profile -> profile.frameRate == 30));
    }

    @Test public void lowRefreshDisplayNeverRequestsUnsupportedSixtyFpsCapture() {
        List<CensorLabVideoProfile> profiles = CensorLabVideoProfile.candidates(
                1080, 2400, 30f);

        assertFalse(profiles.isEmpty());
        assertTrue(profiles.stream().allMatch(profile -> profile.frameRate == 30));
    }
}
