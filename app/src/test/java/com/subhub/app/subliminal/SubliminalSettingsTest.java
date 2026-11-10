package com.subhub.app.subliminal;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Collections;

public final class SubliminalSettingsTest {
    @Test public void rendererClampsMalformedOpacityAtBothBounds() {
        assertEquals(0.35f, alphaFor(36), 0f);
        assertEquals(0.35f, alphaFor(Integer.MAX_VALUE), 0f);
        assertEquals(0.01f, alphaFor(0), 0f);
        assertEquals(0.01f, alphaFor(Integer.MIN_VALUE), 0f);
    }

    @Test public void rendererPreservesEveryValidSelectedOpacity() {
        for (int opacity = 1; opacity <= 35; opacity++) {
            assertEquals(opacity / 100f, alphaFor(opacity), 0f);
        }
    }

    private static float alphaFor(int opacity) {
        SubliminalSettings settings = new SubliminalSettings(SubliminalSettings.Preset.NORMAL,
                true, opacity, 2_000L, 25_000L, 60_000L, 19,
                Collections.emptySet(), "");
        return SubliminalOverlayController.opacityAlpha(settings);
    }
}
