package com.subhub.app.detection;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.os.Build;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.subhub.app.censor.android.VisualDetectorWorker;
import com.subhub.app.censor.engine.SceneEngine;
import com.subhub.app.censor.engine.VisualInferencePolicy;
import com.subhub.app.configuration.DraftPreferences;
import com.subhub.app.runtime.RuntimeLease;
import com.subhub.app.runtime.RuntimeOrigin;
import com.subhub.app.runtime.RuntimeTarget;

import org.junit.Test;
import org.junit.runner.RunWith;

/** Synthetic model initialization only. Never captures a screen or opens physical preferences. */
@RunWith(AndroidJUnit4.class)
public final class DetectorProviderCacheAndroidTest {
    @Test public void deviceConstructorsRetainStoreNameKeysAndBothInitializationPaths() throws Exception {
        Host host = new Host(false);
        DetectorConfig config = DetectorConfig.builder().inferenceThreads(1).build();
        try (DetectionEngine first = new DetectionEngine(host, config)) {
            assertEquals(1, host.applicationRequests);
            assertEquals(0, host.preferenceRequests);
            first.initialize();
            assertEquals(1, host.preferenceRequests);
            assertEquals(first.getActiveProvider(), host.preferences.getString(key(config, true), null));
        }
        String cached = host.preferences.getString(key(config, true), null);
        try (DetectionEngine replacement = new DetectionEngine(host, config, true)) {
            replacement.initialize();
            assertEquals(2, host.applicationRequests);
            assertEquals(2, host.preferenceRequests);
            assertEquals(cached, replacement.getActiveProvider());
        }
        try (DetectionEngine quality = new DetectionEngine(host, config, false)) {
            quality.initializeWithoutBenchmark("CPU");
            assertEquals(3, host.applicationRequests);
            assertEquals(3, host.preferenceRequests);
            assertEquals(quality.getActiveProvider(), host.preferences.getString(key(config, false), null));
        }
        assertEquals(2, host.preferences.getAll().size());
        assertTrue(host.preferenceRequests > 0);
    }

    @Test public void deviceInitializationReusesItsOnlyHandleAndForcedProviderDoesNotAcquireOne() throws Exception {
        DetectorConfig config = DetectorConfig.builder().inferenceThreads(1).build();
        for (boolean fast : new boolean[]{true, false}) {
            Host host = new Host(false, 1);
            try (DetectionEngine detector = new DetectionEngine(host, config, fast)) {
                assertEquals(1, host.applicationRequests);
                assertEquals(0, host.preferenceRequests);
                if (fast) detector.initialize(); else detector.initializeWithoutBenchmark("CPU");
                assertEquals(1, host.preferenceRequests);
                assertNotEquals("uninitialized", detector.getActiveProvider());
            }
        }
        Host host = new Host(false, 0);
        try (DetectionEngine forced = new DetectionEngine(host, config, true)) {
            forced.initializeForProvider("CPU");
            assertEquals(1, host.applicationRequests);
            assertEquals(0, host.preferenceRequests);
        }
    }

    @Test public void explicitCacheRoutesBothPathsAndForcedProviderDoesNotTouchCache() throws Exception {
        Host host = new Host(true);
        DetectorConfig config = DetectorConfig.builder().inferenceThreads(1).build();
        RuntimeLease.Identity owner = owner("one");
        try (OwnerDetectorProviderCache cache = new OwnerDetectorProviderCache(owner)) {
            DetectorProviderCache bound = cache.forOwner(owner);
            try (DetectionEngine fast = new DetectionEngine(host, config, true, bound)) {
                fast.initialize();
                assertEquals(fast.getActiveProvider(), bound.get(key(config, true)));
            }
            try (DetectionEngine quality = new DetectionEngine(host, config, false, bound)) {
                quality.initializeWithoutBenchmark("CPU");
                assertEquals(quality.getActiveProvider(), bound.get(key(config, false)));
            }
            String before = bound.get(key(config, true));
            try (DetectionEngine forced = new DetectionEngine(host, config, true, bound)) {
                forced.initializeForProvider("CPU");
            }
            assertEquals(before, bound.get(key(config, true)));
            try (OwnerDetectorProviderCache fresh = new OwnerDetectorProviderCache(owner)) {
                assertNull(fresh.forOwner(owner).get(key(config, true)));
            }
        }
        assertEquals(0, host.preferenceRequests);
    }

    @Test public void ownedWorkerFactoryUsesSameOwnerCacheInBothLanes() throws Exception {
        Host host = new Host(true);
        DetectorConfig config = DetectorConfig.builder().inferenceThreads(1).build();
        RuntimeLease.Identity owner = owner("one");
        try (OwnerDetectorProviderCache cache = new OwnerDetectorProviderCache(owner)) {
            try (VisualDetectorWorker fast = VisualDetectorWorker.initializeOwned(host, SceneEngine.Lane.FAST,
                    config, 1, "CPU", cache, owner)) {
                assertEquals(fast.provider(), cache.forOwner(owner).get(key(VisualInferencePolicy.fast(config), true)));
            }
            try (VisualDetectorWorker quality = VisualDetectorWorker.initializeOwned(host, SceneEngine.Lane.QUALITY,
                    config, 1, "CPU", cache, owner)) {
                assertEquals(quality.provider(), cache.forOwner(owner).get(key(VisualInferencePolicy.quality(config), false)));
            }
            assertThrows(SecurityException.class, () -> VisualDetectorWorker.initializeOwned(host,
                    SceneEngine.Lane.FAST, config, 1, "CPU", cache, owner("two")));
        }
        assertEquals(0, host.preferenceRequests);
    }

    @Test public void absentOrRetiredExplicitCacheFailsWithoutDeviceFallback() throws Exception {
        Host host = new Host(true);
        DetectorConfig config = DetectorConfig.builder().inferenceThreads(1).build();
        assertThrows(NullPointerException.class, () -> new DetectionEngine(host, config, true, null));
        RuntimeLease.Identity owner = owner("one");
        OwnerDetectorProviderCache cache = new OwnerDetectorProviderCache(owner);
        DetectorProviderCache bound = cache.forOwner(owner);
        try (DetectionEngine fast = new DetectionEngine(host, config, true, bound);
             DetectionEngine quality = new DetectionEngine(host, config, false, bound)) {
            cache.close();
            assertThrows(IllegalStateException.class, fast::initialize);
            assertThrows(IllegalStateException.class, () -> quality.initializeWithoutBenchmark("CPU"));
            assertThrows(IllegalStateException.class, () -> VisualDetectorWorker.initializeOwned(host,
                    SceneEngine.Lane.FAST, config, 1, "CPU", cache, owner));
        } finally { cache.close(); }
        assertEquals(0, host.preferenceRequests);
    }

    private static String key(DetectorConfig config, boolean fast) {
        String identity = Build.FINGERPRINT + '|' + config.getModelFilename() + '|'
                + config.getInferenceResolution() + '|' + config.getInferenceThreads()
                + '|' + (fast ? "realtime" : "quality") + "|ep-v3";
        return "provider_" + Integer.toHexString(identity.hashCode());
    }

    private static RuntimeLease.Identity owner(String id) {
        return new RuntimeLease.Identity(RuntimeTarget.internal(id), RuntimeOrigin.LOCAL_UI, "generation",
                "00000000-0000-0000-0000-000000000001", "00000000-0000-0000-0000-000000000002",
                "00000000-0000-0000-0000-000000000003", 1);
    }

    private static final class Host extends ContextWrapper {
        final DraftPreferences preferences = new DraftPreferences();
        final boolean refusePreferences;
        final int allowedPreferenceRequests;
        int applicationRequests;
        int preferenceRequests;
        Host(boolean refusePreferences) { this(refusePreferences, Integer.MAX_VALUE); }
        Host(boolean refusePreferences, int allowedPreferenceRequests) {
            super(ApplicationProvider.getApplicationContext());
            this.refusePreferences = refusePreferences;
            this.allowedPreferenceRequests = allowedPreferenceRequests;
        }
        @Override public Context getApplicationContext() { applicationRequests++; return this; }
        @Override public SharedPreferences getSharedPreferences(String name, int mode) {
            preferenceRequests++;
            if (refusePreferences) throw new AssertionError("Internal detector touched physical preferences");
            if (preferenceRequests > allowedPreferenceRequests)
                throw new IllegalStateException("Synthetic preference reopening failure");
            assertEquals("detector_provider_cache", name);
            assertEquals(Context.MODE_PRIVATE, mode);
            return preferences;
        }
    }
}
