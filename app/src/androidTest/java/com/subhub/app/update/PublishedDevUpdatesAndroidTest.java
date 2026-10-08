package com.subhub.app.update;

import static org.junit.Assert.*;

import android.content.Context;
import android.os.Build;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.BuildConfig;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Explicit post-publication gate; read-only GitHub metadata, no APK download or install. */
@RunWith(AndroidJUnit4.class)
public final class PublishedDevUpdatesAndroidTest {
    @Test public void publishedBuildIsOfferedByTheRealAppClientOnlyAfterOptIn() {
        String expected = InstrumentationRegistry.getArguments().getString("expectedDevTag", "");
        assertTrue("Supply the exact newly published dev tag", expected.matches("v[0-9]+\\.[0-9]+\\.[0-9]+-dev\\.[0-9]+"));
        Context context = ApplicationProvider.getApplicationContext();
        UpdateStateStore state = new UpdateStateStore(context);
        boolean previous = state.devUpdates();
        try {
            state.setDevUpdates(false);
            state.setEtag("");
            GitHubReleaseRepository.Result stable = new GitHubReleaseRepository(context).check();
            assertTrue("Stable-only public check must succeed: " + stable.detail, stable.succeeded());
            assertTrue(stable.candidate == null || !stable.candidate.prerelease);
            state.setDevUpdates(true);
            GitHubReleaseRepository.Result dev = new GitHubReleaseRepository(context).check();
            assertTrue("Public development check must succeed: " + dev.detail, dev.succeeded());
            assertNotNull("Expected real compatible development update", dev.candidate);
            assertEquals(expected, dev.candidate.manifest.tag);
            assertTrue(dev.candidate.prerelease);
            assertTrue(dev.candidate.manifest.versionCode > BuildConfig.VERSION_CODE);
            assertEquals(5, dev.candidate.manifest.assets.size());
            UpdateManifest.Asset asset = dev.candidate.manifest.selectAsset(Build.SUPPORTED_ABIS);
            assertNotNull(asset);
            assertTrue(asset.size > 1024 * 1024);
            assertEquals("https://github.com/confiteor48/SubHub/releases/download/" + expected + "/" + asset.name,
                    asset.url);
            assertFalse(dev.candidate.notes.isEmpty());
            state.setDevUpdates(false);
            assertNull("Switching off must immediately withdraw the dev candidate", state.candidate());
            GitHubReleaseRepository.Result disabled = new GitHubReleaseRepository(context).check();
            assertTrue(disabled.succeeded());
            assertTrue(disabled.candidate == null || !disabled.candidate.prerelease);
        } finally {
            state.setDevUpdates(previous);
        }
    }
}
