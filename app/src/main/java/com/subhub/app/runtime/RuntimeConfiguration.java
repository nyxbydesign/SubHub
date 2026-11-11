package com.subhub.app.runtime;

import android.content.SharedPreferences;
import com.subhub.app.internal.InternalConfigurationStore;
import com.subhub.app.internal.InternalSubRecord;
import com.subhub.app.settings.SettingsRepository;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * One acknowledged data image bound to a runtime identity. It is not start/effect authority and
 * contains no Context, live settings handle, path grant, remote account or financial ledger.
 */
public final class RuntimeConfiguration {
    public final RuntimeLease.Identity owner;
    public final long configRevision, recordRevision, artworkRevision;
    public final SharedPreferences main, penance, duration;
    public final Set<String> popupAssets, censorAssets, disabledAssets;

    private RuntimeConfiguration(RuntimeLease lease, InternalConfigurationStore configuration,
            InternalSubRecord acknowledged) {
        owner = Objects.requireNonNull(lease).identity;
        Objects.requireNonNull(configuration); Objects.requireNonNull(acknowledged);
        if (owner.target.kind != RuntimeTarget.Kind.INTERNAL
                || !owner.target.equals(RuntimeTarget.internal(acknowledged.targetId))
                || !owner.profileGeneration.equals(acknowledged.generation) || !acknowledged.visible
                || acknowledged.configurationRevision < owner.initialConfigurationRevision)
            throw new SecurityException("Configuration belongs to another runtime owner");
        configRevision = acknowledged.configurationRevision; recordRevision = acknowledged.revision;
        main = configuration.snapshotPreferences(acknowledged, SettingsRepository.PREFERENCES_NAME);
        penance = configuration.snapshotPreferences(acknowledged, "subhub_penance");
        duration = configuration.snapshotPreferences(acknowledged, InternalConfigurationStore.DURATION_PREFERENCES);
        Map<String, Object> artwork = acknowledged.configuration().get(InternalConfigurationStore.ARTWORK);
        popupAssets = hashes(artwork.get("popup_assets"));
        censorAssets = hashes(artwork.get("censor_assets"));
        disabledAssets = hashes(artwork.get("disabled_assets"));
        Object revision = artwork.get("custom_images_revision");
        if (!(revision instanceof Long) || (Long) revision < 0) throw new IllegalArgumentException("Invalid artwork revision");
        artworkRevision = (Long) revision;
    }

    /** Owner worker: pass an already acknowledged immutable record, never a mutable editor draft. */
    public static RuntimeConfiguration internal(RuntimeLease lease, InternalConfigurationStore configuration,
            InternalSubRecord acknowledged) {
        return new RuntimeConfiguration(lease, configuration, acknowledged);
    }

    private static Set<String> hashes(Object value) {
        if (!(value instanceof Set<?>)) throw new IllegalArgumentException("Invalid owned artwork manifest");
        LinkedHashSet<String> hashes = new LinkedHashSet<>();
        for (Object member : (Set<?>) value) {
            if (!(member instanceof String) || !((String) member).matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("Invalid owned artwork identity");
            hashes.add((String) member);
        }
        return Collections.unmodifiableSet(hashes);
    }
}
