package com.subhub.app.detection;

import static org.junit.Assert.*;

import com.subhub.app.runtime.RuntimeLease;
import com.subhub.app.runtime.RuntimeOrigin;
import com.subhub.app.runtime.RuntimeTarget;

import org.junit.Test;

public final class OwnerDetectorProviderCacheTest {
    private static final String SESSION = "00000000-0000-0000-0000-000000000001";
    private static final String ACTIVATION = "00000000-0000-0000-0000-000000000002";
    private static final String PROCESS = "00000000-0000-0000-0000-000000000003";

    @Test public void exactOwnerSharesAcrossModelReplacementsButNotOwnersOrNewBindings() {
        RuntimeLease.Identity owner = owner("one", "generation", SESSION, ACTIVATION, PROCESS, 1);
        try (OwnerDetectorProviderCache first = new OwnerDetectorProviderCache(owner);
             OwnerDetectorProviderCache other = new OwnerDetectorProviderCache(owner("two", "generation", SESSION, ACTIVATION, PROCESS, 1));
             OwnerDetectorProviderCache fresh = new OwnerDetectorProviderCache(owner)) {
            DetectorProviderCache fast = first.forOwner(owner);
            fast.put("fast", "CPU");
            DetectorProviderCache replacement = first.forOwner(owner("one", "generation", SESSION, ACTIVATION, PROCESS, 1));
            assertEquals("CPU", replacement.get("fast"));
            replacement.put("quality", "NNAPI");
            assertEquals("NNAPI", fast.get("quality"));
            assertNull(other.forOwner(owner("two", "generation", SESSION, ACTIVATION, PROCESS, 1)).get("fast"));
            assertNull(fresh.forOwner(owner).get("fast"));
            replacement.remove("fast");
            assertNull(fast.get("fast"));
            assertEquals("NNAPI", fast.get("quality"));
        }
    }

    @Test public void eachImmutableOwnerFieldIsCheckedAndDeviceIsRejected() {
        RuntimeLease.Identity owner = owner("one", "generation", SESSION, ACTIVATION, PROCESS, 1);
        try (OwnerDetectorProviderCache cache = new OwnerDetectorProviderCache(owner)) {
            RuntimeLease.Identity[] wrong = {
                    owner("two", "generation", SESSION, ACTIVATION, PROCESS, 1),
                    owner("one", "replacement", SESSION, ACTIVATION, PROCESS, 1),
                    owner("one", "generation", PROCESS, ACTIVATION, PROCESS, 1),
                    owner("one", "generation", SESSION, PROCESS, PROCESS, 1),
                    owner("one", "generation", SESSION, ACTIVATION, SESSION, 1),
                    owner("one", "generation", SESSION, ACTIVATION, PROCESS, 2),
                    new RuntimeLease.Identity(RuntimeTarget.DEVICE, RuntimeOrigin.LOCAL_UI,
                            "generation", SESSION, ACTIVATION, PROCESS, 1)
            };
            for (RuntimeLease.Identity candidate : wrong)
                assertThrows(SecurityException.class, () -> cache.forOwner(candidate));
            assertThrows(SecurityException.class, () -> cache.forOwner(null));
            assertThrows(IllegalArgumentException.class, () -> new OwnerDetectorProviderCache(wrong[6]));
            assertThrows(NullPointerException.class, () -> new OwnerDetectorProviderCache(null));
        }
    }

    @Test public void closeIsIdempotentAndRetiresPreviouslyReturnedViews() {
        RuntimeLease.Identity owner = owner("one", "generation", SESSION, ACTIVATION, PROCESS, 1);
        OwnerDetectorProviderCache cache = new OwnerDetectorProviderCache(owner);
        DetectorProviderCache view = cache.forOwner(owner);
        view.put("fast", "CPU");
        cache.close(); cache.close();
        assertThrows(IllegalStateException.class, () -> cache.forOwner(owner));
        assertThrows(IllegalStateException.class, () -> view.get("fast"));
        assertThrows(IllegalStateException.class, () -> view.put("fast", "NNAPI"));
        assertThrows(IllegalStateException.class, () -> view.remove("fast"));
    }

    @Test public void missingKeysAreMissesAndNullWritesCannotEraseExistingValues() {
        RuntimeLease.Identity owner = owner("one", "generation", SESSION, ACTIVATION, PROCESS, 1);
        try (OwnerDetectorProviderCache cache = new OwnerDetectorProviderCache(owner)) {
            DetectorProviderCache view = cache.forOwner(owner);
            assertNull(view.get("missing")); view.remove("missing");
            view.put("fast", "CPU");
            assertThrows(NullPointerException.class, () -> view.get(null));
            assertThrows(NullPointerException.class, () -> view.put(null, "NNAPI"));
            assertThrows(NullPointerException.class, () -> view.put("fast", null));
            assertThrows(NullPointerException.class, () -> view.remove(null));
            assertEquals("CPU", view.get("fast"));
        }
    }

    @Test public void parallelModelLanesShareOnlyTheirBoundCache() throws Exception {
        RuntimeLease.Identity owner = owner("one", "generation", SESSION, ACTIVATION, PROCESS, 1);
        try (OwnerDetectorProviderCache cache = new OwnerDetectorProviderCache(owner)) {
            DetectorProviderCache view = cache.forOwner(owner);
            Thread fast = new Thread(() -> { for (int i = 0; i < 1000; i++) view.put("fast-" + i, "CPU"); });
            Thread quality = new Thread(() -> { for (int i = 0; i < 1000; i++) view.put("quality-" + i, "NNAPI"); });
            fast.start(); quality.start(); fast.join(); quality.join();
            for (int i = 0; i < 1000; i++) {
                assertEquals("CPU", view.get("fast-" + i));
                assertEquals("NNAPI", view.get("quality-" + i));
            }
        }
    }

    private static RuntimeLease.Identity owner(String id, String generation, String session,
            String activation, String process, long revision) {
        return new RuntimeLease.Identity(RuntimeTarget.internal(id), RuntimeOrigin.LOCAL_UI,
                generation, session, activation, process, revision);
    }
}
