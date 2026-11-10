package com.subhub.app.internal;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** An immutable acknowledged storage snapshot, not runtime or editor authority. */
public final class InternalSubRecord {
    static final int MAX_SET_ENTRIES = 4096;
    static final int MAX_FIELDS = 8192;
    static final int MAX_TEXT_LENGTH = 512_000;
    public final String targetId, generation;
    public final long revision, configurationRevision;
    public final boolean visible;
    public final InternalProfile profile;
    private final Map<String, Map<String, Object>> configuration;

    InternalSubRecord(String targetId, String generation, long revision, long configurationRevision,
            boolean visible, InternalProfile profile, Map<String, ? extends Map<String, ?>> configuration) {
        if (!InternalStorageScope.TARGET_ID.equals(targetId))
            throw new IllegalArgumentException("Not the internal Sub target");
        if (generation == null || !UUID.fromString(generation).toString().equals(generation)
                || revision < 0 || configurationRevision < 0 || configurationRevision > revision)
            throw new IllegalArgumentException("Invalid internal revision");
        this.targetId = targetId; this.generation = generation; this.revision = revision;
        this.configurationRevision = configurationRevision; this.visible = visible;
        this.profile = Objects.requireNonNull(profile);
        Map<String, Map<String, Object>> sections = new LinkedHashMap<>();
        if (configuration == null || configuration.size() > 16)
            throw new IllegalArgumentException("Invalid internal configuration");
        for (Map.Entry<String, ? extends Map<String, ?>> entry : configuration.entrySet()) {
            key(entry.getKey());
            Map<String, Object> values = new LinkedHashMap<>();
            if (entry.getValue().size() > MAX_FIELDS) throw new IllegalArgumentException("Too many settings");
            for (Map.Entry<String, ?> setting : entry.getValue().entrySet()) {
                key(setting.getKey()); values.put(setting.getKey(), copyValue(setting.getValue()));
            }
            sections.put(entry.getKey(), Collections.unmodifiableMap(values));
        }
        this.configuration = Collections.unmodifiableMap(sections);
    }

    public Map<String, Map<String, Object>> configuration() { return configuration; }

    static void requireSuccessor(InternalSubRecord before, InternalSubRecord after) {
        boolean visibilityChanged = before.visible != after.visible;
        boolean profileChanged = !before.profile.equals(after.profile);
        boolean configurationChanged = !before.configuration.equals(after.configuration);
        int changes = (visibilityChanged ? 1 : 0) + (profileChanged ? 1 : 0) + (configurationChanged ? 1 : 0);
        if (!before.targetId.equals(after.targetId) || after.revision != Math.addExact(before.revision, 1L)
                || changes != 1 || visibilityChanged == before.generation.equals(after.generation)
                || after.configurationRevision != (configurationChanged
                        ? Math.addExact(before.configurationRevision, 1L) : before.configurationRevision))
            throw new IllegalArgumentException("Invalid internal record transition");
    }

    static Object copyValue(Object value) {
        if (value instanceof String) { bounded((String) value); return value; }
        if (value instanceof Boolean || value instanceof Integer || value instanceof Long) return value;
        if (value instanceof Float && Float.isFinite((Float) value)) return value;
        if (value instanceof Set<?>) {
            if (((Set<?>) value).size() > MAX_SET_ENTRIES) throw new IllegalArgumentException("Oversized setting set");
            Set<String> copy = new LinkedHashSet<>();
            for (Object item : (Set<?>) value) {
                if (!(item instanceof String)) throw new IllegalArgumentException("Invalid setting set");
                bounded((String) item); copy.add((String) item);
            }
            return Collections.unmodifiableSet(copy);
        }
        throw new IllegalArgumentException("Unsupported setting type");
    }
    static void key(String key) {
        if (key == null || !key.matches("[A-Za-z0-9_.:-]{1,320}"))
            throw new IllegalArgumentException("Invalid setting key");
    }
    private static void bounded(String value) {
        if (value.length() > MAX_TEXT_LENGTH || value.indexOf('\0') >= 0)
            throw new IllegalArgumentException("Oversized setting text");
    }

    @Override public boolean equals(Object other) {
        if (!(other instanceof InternalSubRecord)) return false;
        InternalSubRecord value = (InternalSubRecord) other;
        return revision == value.revision && configurationRevision == value.configurationRevision
                && visible == value.visible && targetId.equals(value.targetId)
                && generation.equals(value.generation) && profile.equals(value.profile)
                && configuration.equals(value.configuration);
    }
    @Override public int hashCode() {
        return Objects.hash(targetId, generation, revision, configurationRevision, visible, profile, configuration);
    }
}
