package com.subhub.app.configuration;

import static org.junit.Assert.*;
import org.junit.Test;

public final class ConfigurationArtworkTest {
    @Test public void acceptsTheExistingRelativeArtworkNamespaces() {
        for (String path : new String[] {"assets/censor/image.png", "assets/popup/nested/image.webp",
                "assets/cover/image.jpg", "assets\\censor\\image.png"}) {
            assertTrue(path, ConfigurationArtwork.isSafeAssetPath(path));
        }
    }

    @Test public void rejectsTraversalAbsolutePathsAndUnrecognizedNamespaces() {
        assertFalse(ConfigurationArtwork.isSafeAssetPath(null));
        for (String path : new String[] {"", "/assets/censor/image.png", "assets/censor/../secret",
                "assets/censor/./image.png", "assets//censor/image.png", "assets/censor/",
                "assets/censor/a\u0000b", "assets/censor/C:file.png", "assets/other/image.png",
                "assets/censor", "file:///assets/censor/image.png", "assets\\censor\\..\\secret"}) {
            assertFalse(path, ConfigurationArtwork.isSafeAssetPath(path));
        }
    }
}
