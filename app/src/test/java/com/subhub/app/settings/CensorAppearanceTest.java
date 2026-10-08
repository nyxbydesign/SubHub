package com.subhub.app.settings;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.graphics.Color;

import org.junit.Test;

public final class CensorAppearanceTest {
    @Test public void unknownPreferenceFallsBackToBox() {
        assertEquals(CensorAppearance.Type.BOX,
                CensorAppearance.Type.fromPreference("future-style"));
    }

    @Test public void knownPreferencesAreCaseInsensitive() {
        assertEquals(CensorAppearance.Type.PIXELATE,
                CensorAppearance.Type.fromPreference("PIXELATE"));
    }

    @Test public void retiredPreferencesUseTheDefaultWithoutMigration() {
        assertEquals(CensorAppearance.Type.BOX,
                CensorAppearance.Type.fromPreference("bar"));
        assertEquals(CensorAppearance.Type.BOX,
                CensorAppearance.Type.fromPreference(" BAR "));
    }

    @Test public void retiredStyleAliasesAreNotAccepted() {
        assertEquals(CensorAppearance.Type.BOX,
                CensorAppearance.Type.fromPreference("mosaic"));
        assertEquals(CensorAppearance.Type.BOX,
                CensorAppearance.Type.fromPreference("custom_image"));
        assertEquals(CensorAppearance.Type.BOX,
                CensorAppearance.Type.fromPreference("windows_error"));
    }

    @Test public void stablePhraseSelectionWrapsNegativeIds() {
        CensorAppearance appearance = new CensorAppearance(
                CensorAppearance.Type.BOX, 50, true, true, Color.MAGENTA);
        assertEquals("BLOCKED", appearance.phraseFor(-1));
    }

    @Test public void opaqueBoxDoesNotRetainAFullScreenshot() {
        assertFalse(appearance(CensorAppearance.Type.BOX, false).requiresSourceFrame());
    }

    @Test public void sampledEffectsRetainAFrameOnlyWhenTheyNeedPixels() {
        assertTrue(appearance(CensorAppearance.Type.PIXELATE, false).requiresSourceFrame());
        assertTrue(appearance(CensorAppearance.Type.BLUR, false).requiresSourceFrame());
        assertTrue(appearance(CensorAppearance.Type.GLITCH, false).requiresSourceFrame());
        assertFalse(appearance(CensorAppearance.Type.STATIC, false).requiresSourceFrame());
    }

    @Test public void reverseBoxUsesPixelsToHideTheBackground() {
        assertTrue(appearance(CensorAppearance.Type.BOX, true).requiresSourceFrame());
    }

    private static CensorAppearance appearance(CensorAppearance.Type type, boolean reverse) {
        return new CensorAppearance(type, 50, .2f, false, false,
                CensorAppearance.BorderEffect.CLASSIC, false, 0, java.util.List.of(),
                reverse, 100, "rectangle", "SubHub", "Blocked");
    }
}
