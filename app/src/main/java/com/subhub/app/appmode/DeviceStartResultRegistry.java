package com.subhub.app.appmode;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.ActivityResultRegistry;
import androidx.activity.result.contract.ActivityResultContracts;
import com.subhub.app.runtime.RuntimeStartAttempt;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Main-thread, Activity-lifetime registrations. Each prompt owns a unique key; retired request
 * codes remain reserved by inert callbacks until destruction, never reused for a later attempt.
 * The non-lifecycle overload supports a new prompt while RESUMED. close() explicitly unregisters.
 */
public final class DeviceStartResultRegistry implements AutoCloseable {
    static final int MAX_ROUTES = 128;
    private static final ActivityResultCallback<ActivityResult> IGNORE_ACTIVITY = ignored -> { };
    private static final ActivityResultCallback<Boolean> IGNORE_PERMISSION = ignored -> { };
    private final ActivityResultRegistry registry;
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private Function<RuntimeStartAttempt.Permission, Boolean> observe;
    private boolean closed;

    public DeviceStartResultRegistry(ActivityResultRegistry registry,
            Function<RuntimeStartAttempt.Permission, Boolean> observe) {
        this.registry = Objects.requireNonNull(registry); this.observe = Objects.requireNonNull(observe);
    }
    /** Re-register only the same retained capability. A saved key or permission kind is insufficient. */
    public void restore(DeviceStartViewModel.PromptRoute route) { if (route != null) prepare(route); }

    /** Saved metadata can create only inert tombstones. The live route is a separate retained object. */
    public void restore(Bundle metadata, DeviceStartViewModel.PromptRoute pending) {
        if (metadata != null) {
            ArrayList<String> keys = metadata.getStringArrayList("keys");
            ArrayList<String> kinds = metadata.getStringArrayList("kinds");
            if (keys == null || kinds == null || keys.size() != kinds.size() || keys.size() > MAX_ROUTES)
                throw new IllegalStateException("Invalid permission routing metadata");
            for (int index = 0; index < keys.size(); index++) {
                String key = keys.get(index);
                if (key == null || !key.matches("device-start:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
                    throw new IllegalStateException("Invalid permission routing key");
                if (pending != null && pending.registrationKey.equals(key)) continue;
                RuntimeStartAttempt.Permission kind = RuntimeStartAttempt.Permission.valueOf(kinds.get(index));
                if (!entries.containsKey(key)) {
                    Entry tombstone = new Entry(null, kind, null);
                    entries.put(key, tombstone); retire(key, tombstone);
                }
            }
        }
        restore(pending);
    }
    /** No attempt, prompt, projection Intent or grant is serialized here. */
    public Bundle saveMetadata() {
        discardRetiredResults();
        ArrayList<String> keys = new ArrayList<>(), kinds = new ArrayList<>();
        for (Map.Entry<String, Entry> entry : entries.entrySet()) {
            keys.add(entry.getKey()); kinds.add(entry.getValue().permission.name());
        }
        Bundle metadata = new Bundle(); metadata.putStringArrayList("keys", keys); metadata.putStringArrayList("kinds", kinds);
        return metadata;
    }
    /** AndroidX may buffer an old raw duplicate despite its inert callback. Drop that payload. */
    public void discardRetiredResults() {
        if (closed) return;
        for (Map.Entry<String, Entry> entry : entries.entrySet())
            if (entry.getValue().route == null) retire(entry.getKey(), entry.getValue());
    }

    /**
     * Call after the framework's raw-result dispatch has returned. A launcher that throws may
     * lose AndroidX's launched-key marker even though Android later returns a real result.
     * Registering the SAME key delivers only its already-buffered result, without unregistering,
     * reallocating its request code, relaunching or claiming that an absent result has returned.
     * PromptRoute still posts owner processing until after this outer callback turn returns.
     */
    public void onFrameworkResultReturned() {
        if (closed) return;
        for (Map.Entry<String, Entry> item : new ArrayList<>(entries.entrySet())) {
            Entry entry = item.getValue();
            if (entry.route == null) retire(item.getKey(), entry);
            else registerLive(item.getKey(), entry);
        }
    }

    /** Only settled tombstones may be left behind in a distinct, fresh Home Activity instance. */
    public boolean needsFreshHome() {
        if (closed || entries.size() < MAX_ROUTES) return false;
        for (Entry entry : entries.values()) if (entry.route != null) return false;
        return true;
    }

    /** Registration/capacity failures occur BEFORE the model admits the physical prompt launch. */
    public Prepared prepare(DeviceStartViewModel.PromptRoute route) {
        Objects.requireNonNull(route);
        if (closed) throw new IllegalStateException("Start result registry is closed");
        Entry existing = entries.get(route.registrationKey);
        if (existing != null) {
            if (existing.route != route) throw new SecurityException("Prompt route is retired or replaced");
            return new Prepared(existing);
        }
        if (entries.size() >= MAX_ROUTES)
            throw new IllegalStateException("Permission route limit reached; open a new Home after pending work settles");
        if (route.isRetired()) throw new SecurityException("Cannot restore a retired permission route");
        Entry entry = new Entry(route, route.permission, observe);
        entries.put(route.registrationKey, entry);
        registerLive(route.registrationKey, entry);
        entry.retirement = route.whenRetired(() -> retire(route.registrationKey, entry));
        return new Prepared(entry);
    }
    private void registerLive(String key, Entry entry) {
        DeviceStartViewModel.PromptRoute route = entry.route;
        if (closed || entry.closed || route == null) return;
        if (route.permission == RuntimeStartAttempt.Permission.NOTIFICATIONS) {
            entry.notifications = registry.register(key,
                    new ActivityResultContracts.RequestPermission(), granted ->
                            route.returned(() -> granted, Activity.RESULT_CANCELED, null));
        } else {
            entry.intents = registry.register(key,
                    new ActivityResultContracts.StartActivityForResult(), result -> route.returned(() -> {
                        if (route.permission == RuntimeStartAttempt.Permission.PROJECTION)
                            return result.getResultCode() == Activity.RESULT_OK && result.getData() != null;
                        Function<RuntimeStartAttempt.Permission, Boolean> observation = entry.observe;
                        return observation != null && Boolean.TRUE.equals(observation.apply(route.permission));
                    }, result.getResultCode(), route.permission == RuntimeStartAttempt.Permission.PROJECTION
                            ? result.getData() : null));
        }
    }
    public static final class Prepared {
        private final Entry entry;
        private Prepared(Entry entry) { this.entry = entry; }
        /** The model invokes this only inside the exact prompt's launch admission. */
        public void launch(Intent intent) {
            if (entry.route == null || entry.closed || entry.launched)
                throw new SecurityException("Permission registration is no longer launchable");
            entry.launched = true;
            if (entry.notifications != null) entry.notifications.launch(Manifest.permission.POST_NOTIFICATIONS);
            else entry.intents.launch(Objects.requireNonNull(intent));
        }
    }
    private static final class Entry {
        final RuntimeStartAttempt.Permission permission;
        DeviceStartViewModel.PromptRoute route;
        Function<RuntimeStartAttempt.Permission, Boolean> observe;
        ActivityResultLauncher<Intent> intents;
        ActivityResultLauncher<String> notifications;
        AutoCloseable retirement;
        boolean launched, closed;
        Entry(DeviceStartViewModel.PromptRoute route, RuntimeStartAttempt.Permission permission,
                Function<RuntimeStartAttempt.Permission, Boolean> observe) {
            this.route = route; this.permission = permission; this.observe = observe;
        }
    }
    private void retire(String key, Entry entry) {
        // Clear capability and Activity-observation references before any registry interaction.
        boolean notification = entry.permission == RuntimeStartAttempt.Permission.NOTIFICATIONS;
        entry.route = null; entry.observe = null;
        unsubscribe(entry);
        if (closed || entry.closed) return;
        // Do not unregister: it would free this requestCode for accidental reuse in this Activity.
        if (notification) entry.notifications = registry.register(key,
                new ActivityResultContracts.RequestPermission(), IGNORE_PERMISSION);
        else entry.intents = registry.register(key,
                new ActivityResultContracts.StartActivityForResult(), IGNORE_ACTIVITY);
    }
    private static void unsubscribe(Entry entry) {
        AutoCloseable retirement = entry.retirement; entry.retirement = null;
        if (retirement != null) try { retirement.close(); }
        catch (Exception impossible) { throw new IllegalStateException("Could not detach permission observer", impossible); }
    }
    @Override public void close() {
        if (closed) return;
        closed = true; observe = null;
        for (Entry entry : entries.values()) {
            entry.closed = true; entry.observe = null; entry.route = null;
            unsubscribe(entry);
            if (entry.intents != null) entry.intents.unregister();
            if (entry.notifications != null) entry.notifications.unregister();
        }
        entries.clear();
        // Unregistering is routing cleanup only. It never calls PromptRoute.returned().
    }
}
