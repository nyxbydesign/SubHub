package com.subhub.app.service;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.List;

public final class ForegroundWindowResolverTest {
    @Test public void focusedCodexWindowWinsOverStaleActiveXWindow() {
        ForegroundWindowResolver.Candidate selected = ForegroundWindowResolver.select(List.of(
                candidate("com.twitter.android", 10, true, false, 2),
                candidate("com.openai.chatgpt", 11, true, true, 3)), "com.example.ime");

        assertEquals("com.openai.chatgpt", selected.packageName);
        assertEquals(11, selected.windowId);
    }

    @Test public void transientSystemWindowDoesNotReplaceTheUnderlyingApp() {
        ForegroundWindowResolver.Candidate selected = ForegroundWindowResolver.select(List.of(
                candidate("com.twitter.android", 10, true, false, 2),
                candidate("com.android.systemui", 20, true, true, 9)), "com.example.ime");

        assertEquals("com.twitter.android", selected.packageName);
    }

    @Test public void focusedApplicationOverlayDoesNotReplaceProtectedWindowStillInStack() {
        ForegroundWindowResolver.Candidate selected = ForegroundWindowResolver.select(List.of(
                candidate("org.chromium.chrome.stable", 10, false, false, 2),
                candidate("com.pryshedko.mtisland", 20, true, true, 9)),
                "com.example.ime", "org.chromium.chrome.stable");

        assertEquals("org.chromium.chrome.stable", selected.packageName);
        assertEquals(10, selected.windowId);
    }

    @Test public void focusedApplicationWinsOnceProtectedWindowLeavesStack() {
        ForegroundWindowResolver.Candidate selected = ForegroundWindowResolver.select(List.of(
                candidate("com.pryshedko.mtisland", 20, true, true, 9)),
                "com.example.ime", "org.chromium.chrome.stable");

        assertEquals("com.pryshedko.mtisland", selected.packageName);
        assertEquals(20, selected.windowId);
    }

    private static ForegroundWindowResolver.Candidate candidate(
            String packageName, int id, boolean active, boolean focused, int layer) {
        return new ForegroundWindowResolver.Candidate(
                packageName, id, active, focused, layer);
    }
}
