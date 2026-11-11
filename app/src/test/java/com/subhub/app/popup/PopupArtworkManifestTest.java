package com.subhub.app.popup;

import static org.junit.Assert.*;
import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.Test;

public final class PopupArtworkManifestTest {
    private static final String A = "a".repeat(64), B = "b".repeat(64);
    @Test public void onlyExplicitEnabledOwnedHashesAreEligible() {
        PopupArtworkManifest manifest = new PopupArtworkManifest(Set.of(A, B), Set.of(B));
        assertEquals(Set.of(A), manifest.enabled);
        assertFalse(manifest.builtInFallback);
        PopupArtworkManifest empty = new PopupArtworkManifest(Set.of(), Set.of());
        assertTrue(empty.enabled.isEmpty());
        assertTrue(empty.builtInFallback);
        PopupArtworkManifest disabled = new PopupArtworkManifest(Set.of(A, B), Set.of(A, B));
        assertTrue(disabled.enabled.isEmpty());
        assertTrue(disabled.builtInFallback);
    }
    @Test public void sourceMutationCannotChangeAnAdmittedSnapshot() {
        Set<String> source = new LinkedHashSet<>(Set.of(A));
        PopupArtworkManifest manifest = new PopupArtworkManifest(source, Set.of());
        source.clear(); source.add(B);
        assertEquals(Set.of(A), manifest.enabled);
        assertThrows(UnsupportedOperationException.class, () -> manifest.enabled.add(B));
    }
    @Test public void pathsUrisBuiltinsAndMalformedHashesCannotEnterOwnedDecoder() {
        for (String value : new String[]{"/data/local/device.png", "../device.png", "content://images/1",
                "asset://popup_storm/guardian_shield.webp", "A".repeat(64), "a".repeat(63)}) {
            assertThrows(IllegalArgumentException.class,
                    () -> new PopupArtworkManifest(Set.of(value), Set.of()));
            assertThrows(IllegalArgumentException.class,
                    () -> new PopupArtworkManifest(Set.of(A), Set.of(value)));
        }
    }
    @Test public void decoderBudgetKeepsEveryEnabledImageEligibleAtBoundedResolution() {
        Set<String> hashes = new LinkedHashSet<>();
        for (int i = 0; i < PopupArtworkManifest.MAX_IMAGES; i++) hashes.add(String.format("%064x", i));
        PopupArtworkManifest manifest = new PopupArtworkManifest(hashes, Set.of());
        assertEquals(PopupArtworkManifest.MAX_IMAGES, manifest.enabled.size());
        long worstBytes = (long) (manifest.decodeEdge() + 1) * (manifest.decodeEdge() + 1)
                * 4 * manifest.enabled.size();
        assertTrue(worstBytes <= PopupArtworkManifest.MAX_DECODE_BYTES);
        assertTrue(manifest.decodeEdge() > 0);
        assertEquals(PopupArtworkManifest.MAX_DECODE_EDGE,
                new PopupArtworkManifest(Set.of(A), Set.of()).decodeEdge());
    }
    @Test public void oversizedManifestIsRejectedInsteadOfSilentlyFallingBack() {
        Set<String> hashes = new LinkedHashSet<>();
        for (int i = 0; i <= PopupArtworkManifest.MAX_IMAGES; i++) hashes.add(String.format("%064x", i));
        assertThrows(IllegalArgumentException.class, () -> new PopupArtworkManifest(hashes, Set.of()));
    }
}
