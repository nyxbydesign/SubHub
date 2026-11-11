package com.subhub.app.detection;

import com.subhub.app.runtime.RuntimeLease;
import com.subhub.app.runtime.RuntimeTarget;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * INTERNAL optimization metadata bound to one immutable runtime identity, held only in RAM.
 * Share across that binding's model replacements and close after its native workers drain.
 * Neither a cache miss nor a retired cache can select any physical or other owner's storage.
 * Identity matching is isolation evidence, not authority to start a runtime or capture pixels.
 */
public final class OwnerDetectorProviderCache implements AutoCloseable {
    private final RuntimeLease.Identity owner;
    private final Map<String, String> providers = new HashMap<>();
    private boolean closed;
    private final DetectorProviderCache bound = new DetectorProviderCache() {
        @Override public String get(String key) {
            synchronized (OwnerDetectorProviderCache.this) {
                requireOpen();
                return providers.get(Objects.requireNonNull(key));
            }
        }
        @Override public void put(String key, String provider) {
            synchronized (OwnerDetectorProviderCache.this) {
                requireOpen();
                providers.put(Objects.requireNonNull(key), Objects.requireNonNull(provider));
            }
        }
        @Override public void remove(String key) {
            synchronized (OwnerDetectorProviderCache.this) {
                requireOpen();
                providers.remove(Objects.requireNonNull(key));
            }
        }
    };

    public OwnerDetectorProviderCache(RuntimeLease.Identity owner) {
        this.owner = Objects.requireNonNull(owner, "owner");
        if (owner.target.kind != RuntimeTarget.Kind.INTERNAL)
            throw new IllegalArgumentException("Provider cache requires an internal owner");
    }

    public synchronized DetectorProviderCache forOwner(RuntimeLease.Identity expected) {
        if (!owner.equals(expected)) throw new SecurityException("Different provider cache owner");
        requireOpen();
        return bound;
    }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("Provider cache is retired");
    }

    @Override public synchronized void close() {
        closed = true;
        providers.clear();
    }
}
