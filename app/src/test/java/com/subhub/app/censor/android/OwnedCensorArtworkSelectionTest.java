package com.subhub.app.censor.android;

import static org.junit.Assert.*;
import com.subhub.app.runtime.RuntimeLease;
import com.subhub.app.runtime.RuntimeOrigin;
import com.subhub.app.runtime.RuntimeTarget;
import java.util.Set;
import java.util.UUID;
import org.junit.Test;

public final class OwnedCensorArtworkSelectionTest {
    private static RuntimeLease.Identity owner() {
        return new RuntimeLease.Identity(RuntimeTarget.internal("dummy_sub_preview"), RuntimeOrigin.LOCAL_UI,
                "profile-generation", UUID.randomUUID().toString(), UUID.randomUUID().toString(), UUID.randomUUID().toString(), 1);
    }
    @Test public void unrelatedAdoptionRetainsExactAcceptedDecodeToken() {
        var owner = owner(); var selection = new OwnedCensorArtworkSelection(owner, 1, 0, Set.of("one"), Set.of());
        var accepted = selection.snapshot();
        selection.update(owner, 2, 0, Set.of("one"), Set.of());
        assertSame(accepted, selection.snapshot()); assertTrue(selection.current(accepted));
        assertThrows(SecurityException.class, () -> selection.update(owner, 1, 0, Set.of("one"), Set.of()));
    }
    @Test public void changedManifestInvalidatesHeldDecodeBeforePublication() {
        var owner = owner(); var selection = new OwnedCensorArtworkSelection(owner, 1, 0, Set.of("one"), Set.of());
        var accepted = selection.snapshot();
        selection.update(owner, 2, 1, Set.of("two"), Set.of());
        assertFalse(selection.current(accepted)); assertEquals(Set.of("one"), accepted.enabled);
        assertEquals(Set.of("two"), selection.snapshot().enabled);
    }
    @Test public void disabledSetAndRevisionEachFenceOlderDecode() {
        var owner = owner(); var selection = new OwnedCensorArtworkSelection(owner, 1, 0, Set.of("one"), Set.of());
        var first = selection.snapshot(); selection.update(owner, 2, 1, Set.of("one"), Set.of("one"));
        assertFalse(selection.current(first)); var second = selection.snapshot();
        selection.update(owner, 3, 2, Set.of("one"), Set.of("one")); assertFalse(selection.current(second));
    }
    @Test public void sameExternalRevisionCannotSwapManifest() {
        var owner = owner(); var selection = new OwnedCensorArtworkSelection(owner, 1, 0, Set.of("one"), Set.of());
        var accepted = selection.snapshot();
        assertThrows(SecurityException.class, () -> selection.update(owner, 1, 0, Set.of("two"), Set.of()));
        assertSame(accepted, selection.snapshot());
    }
    @Test public void replacementOwnerCannotReuseOldArtworkToken() {
        var owner = owner(); var selection = new OwnedCensorArtworkSelection(owner, 1, 0, Set.of("one"), Set.of());
        assertThrows(SecurityException.class, () -> selection.update(owner(), 2, 0, Set.of("one"), Set.of()));
    }
    @Test public void equalDataFromAnotherSelectionDoesNotProveCurrentDecode() {
        var owner = owner();
        var first = new OwnedCensorArtworkSelection(owner, 1, 0, Set.of("one"), Set.of());
        var second = new OwnedCensorArtworkSelection(owner, 1, 0, Set.of("one"), Set.of());
        assertFalse(second.current(first.snapshot()));
    }
}
