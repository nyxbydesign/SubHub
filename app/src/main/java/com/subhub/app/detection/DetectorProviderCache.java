package com.subhub.app.detection;

/** Provider-selection optimization metadata, never detector configuration or retained counters. */
public interface DetectorProviderCache {
    String get(String key);
    void put(String key, String provider);
    void remove(String key);
}
