package com.subhub.app.internal;

import android.content.SharedPreferences;
import com.subhub.app.commitment.PactDuration;
import com.subhub.app.configuration.ConfigurationSchema;
import com.subhub.app.configuration.ConfigurationSettingCatalog;
import com.subhub.app.settings.SettingsRepository;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Normal configuration data, not permission, runtime state, Wallet accounts or side effects. */
public final class InternalConfigurationStore {
    public static final String DURATION_PREFERENCES = "subhub_service_duration_ui";
    public static final String PRESET_PREFERENCES = "subhub_censor_looks";
    public static final String APPS = "apps", DURATION = "duration", ARTWORK = "artwork";
    public static final String ALLOWANCES = "allowances", WALLET_DISPLAY = "wallet_display",
            POPUP_LOCAL = "popup_local", PRESETS = "presets";
    public static final String ALLOWANCE_PREFIX = "app_timer_allowance_minutes:";
    private static final String SELECTED = "app_included_packages_v1", ALL_APPS = "app_include_all_v1";
    private static final String CATALOG = "app_include_all_packages_v1";
    private static final String SCOPE_REVISION = "app_include_revision_v1", ARTWORK_REVISION = "custom_images_revision";
    private static final String RULE_SCHEMA = "rule_participation_migrated_v1";
    private final InternalSubStore store;

    public InternalConfigurationStore(InternalSubStore store) { this.store = Objects.requireNonNull(store); }

    /** Production entry: every read/write, including visibility, validates the complete schema. */
    public static InternalSubStore open(FileInternalSubBackend backend) {
        InternalSubStore store = new InternalSubStore(backend, record -> {
            if (!validate(record.configuration()).equals(record.configuration()))
                throw new IllegalStateException("Noncanonical internal configuration");
            InternalProfileAdapter.toProfileData(record.profile);
        });
        store.read();
        return store;
    }

    /** Defaults come from the normal catalog, never from this phone's preferences. */
    public static Map<String, Map<String, Object>> defaults() {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        for (String section : ConfigurationSchema.SECTIONS) {
            Map<String, Object> fields = new LinkedHashMap<>();
            for (ConfigurationSettingCatalog.Field field : ConfigurationSettingCatalog.fields(section))
                fields.put(field.key, preferenceValue(field.defaultValue()));
            result.put(section, fields);
        }
        result.put(APPS, new LinkedHashMap<>(Map.of(SELECTED, Collections.emptySet(), ALL_APPS, false,
                CATALOG, Collections.emptySet(), SCOPE_REVISION, 0L)));
        result.put(ALLOWANCES, Collections.emptyMap());
        result.put(DURATION, new LinkedHashMap<>(Map.of("duration", -1L, "minimum", 3_600_000L,
                "maximum", 86_400_000L, "hidden", false)));
        // Owned asset IDs, never absolute paths, content URIs, folder grants or remote references.
        result.put(ARTWORK, new LinkedHashMap<>(Map.of("censor_assets", Set.of(), "popup_assets", Set.of(),
                "disabled_assets", Set.of(), ARTWORK_REVISION, 0L)));
        result.put(WALLET_DISPLAY, Map.of("wallet_currency", "EUR", RULE_SCHEMA, true));
        result.put(POPUP_LOCAL, Map.of("popup_storm_photosensitivity_acknowledged", false));
        result.put(PRESETS, Map.of("looks_v1", "{\"version\":1,\"looks\":[]}"));
        return validate(result);
    }

    public InternalSubRecord read() {
        InternalSubRecord record = store.read();
        if (!validate(record.configuration()).equals(record.configuration()))
            throw new IllegalStateException("Noncanonical internal configuration");
        return record;
    }

    public EditorDraft edit(InternalSubRecord acknowledged) {
        validate(acknowledged.configuration());
        return new EditorDraft(acknowledged);
    }

    /** Immutable data source for SettingsRepository.forPreferences; never a runtime capability. */
    public SharedPreferences snapshotPreferences(InternalSubRecord acknowledged, String name) {
        SharedPreferences source = edit(acknowledged).preferences(name);
        return new SharedPreferences() {
            @Override public Map<String, ?> getAll() { return source.getAll(); }
            @Override public String getString(String key, String fallback) { return source.getString(key, fallback); }
            @Override public Set<String> getStringSet(String key, Set<String> fallback) { return source.getStringSet(key, fallback); }
            @Override public int getInt(String key, int fallback) { return source.getInt(key, fallback); }
            @Override public long getLong(String key, long fallback) { return source.getLong(key, fallback); }
            @Override public float getFloat(String key, float fallback) { return source.getFloat(key, fallback); }
            @Override public boolean getBoolean(String key, boolean fallback) { return source.getBoolean(key, fallback); }
            @Override public boolean contains(String key) { return source.contains(key); }
            @Override public Editor edit() { throw new UnsupportedOperationException("Immutable internal configuration snapshot"); }
            @Override public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) { }
            @Override public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) { }
        };
    }

    /**
     * Immutable write image only, never an authority permit. Freeze before admission, admit this
     * exact base/image, then flushPrepared outside controller/policy locks. Discard on denied
     * admission; preparation holds no persistent lock or reservation requiring cancellation.
     */
    public static final class PreparedEdit {
        private final InternalConfigurationStore owner;
        private final EditorDraft draft;
        private final InternalSubRecord base;
        private final Map<String, Map<String, Object>> configuration;
        private boolean used;
        private PreparedEdit(InternalConfigurationStore owner, EditorDraft draft, InternalSubRecord base,
                Map<String, Map<String, Object>> configuration) {
            this.owner = owner; this.draft = draft; this.base = base; this.configuration = configuration;
        }
        public InternalSubRecord base() { return base; }
        public Map<String, Map<String, Object>> configuration() { return configuration; }
    }

    public PreparedEdit prepare(EditorDraft draft) {
        Objects.requireNonNull(draft);
        if (draft.owner != this) throw new IllegalArgumentException("Different internal editor owner");
        synchronized (draft) {
            if (draft.flushing) throw new IllegalStateException("Serialize internal draft flushes");
            return new PreparedEdit(this, draft, draft.base, validate(draft.sections));
        }
    }

    /** One-use CAS of exactly the prepared image; later draft edits are never included implicitly. */
    public InternalSubRecord flushPrepared(PreparedEdit prepared) {
        Objects.requireNonNull(prepared);
        if (prepared.owner != this) throw new IllegalArgumentException("Different prepared editor owner");
        EditorDraft draft = prepared.draft;
        synchronized (draft) {
            if (prepared.used) throw new IllegalStateException("Prepared edit already used");
            prepared.used = true;
            if (draft.flushing) throw new IllegalStateException("Serialize internal draft flushes");
            if (!draft.base.equals(prepared.base)) throw new InternalSubStore.PersistenceException(InternalSubStore.Outcome.CONFLICT);
            draft.flushing = true;
        }
        try {
            // No draft monitor or serialization lock spans durable listeners, which may enter runtime.
            InternalSubRecord acknowledged = store.saveConfiguration(prepared.base, prepared.configuration);
            synchronized (draft) { draft.base = acknowledged; }
            return acknowledged; // Newer edits remain staged and will use this base on the next flush.
        } finally { synchronized (draft) { draft.flushing = false; } }
    }

    /** Convenience for callers that do not need a distinct external write-admission point. */
    public InternalSubRecord flush(EditorDraft draft) { return flushPrepared(prepare(draft)); }

    static Map<String, Map<String, Object>> validate(Map<String, ? extends Map<String, ?>> source) {
        return validate(source, null);
    }
    private static Map<String, Map<String, Object>> validate(Map<String, ? extends Map<String, ?>> source,
            Map<String, Map<String, Object>> knownValid) {
        Set<String> expectedSections = new LinkedHashSet<>(ConfigurationSchema.SECTIONS);
        expectedSections.add(APPS); expectedSections.add(DURATION); expectedSections.add(ARTWORK);
        expectedSections.add(ALLOWANCES); expectedSections.add(WALLET_DISPLAY);
        expectedSections.add(POPUP_LOCAL); expectedSections.add(PRESETS);
        if (!source.keySet().equals(expectedSections)) throw new IllegalArgumentException("Invalid internal sections");
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        for (String section : ConfigurationSchema.SECTIONS) {
            Map<String, ?> values = source.get(section);
            if (!values.keySet().equals(ConfigurationSchema.keysFor(section)))
                throw new SecurityException("Non-configuration field in internal editor");
            Map<String, Object> clean = new LinkedHashMap<>();
            JSONObject json = new JSONObject();
            for (ConfigurationSettingCatalog.Field field : ConfigurationSettingCatalog.fields(section)) {
                if (ConfigurationSchema.isSecretOrRuntimeKey(field.key))
                    throw new SecurityException("Unsafe configuration catalog field");
                Object value = field.normalize(values.get(field.key));
                clean.put(field.key, preferenceValue(value));
                try { json.put(field.key, value); }
                catch (org.json.JSONException invalid) { throw new IllegalArgumentException(invalid); }
            }
            ConfigurationSettingCatalog.validateRelationships(json);
            result.put(section, Collections.unmodifiableMap(clean));
        }
        Map<String, ?> apps = source.get(APPS);
        exact(apps, Set.of(SELECTED, ALL_APPS, CATALOG, SCOPE_REVISION));
        if (!(apps.get(ALL_APPS) instanceof Boolean) || !(apps.get(SCOPE_REVISION) instanceof Long)
                || (Long) apps.get(SCOPE_REVISION) < 0)
            throw new IllegalArgumentException("Invalid internal app selection");
        result.put(APPS, Map.of(SELECTED, packageSet(apps.get(SELECTED)), ALL_APPS, apps.get(ALL_APPS),
                CATALOG, packageSet(apps.get(CATALOG)), SCOPE_REVISION, apps.get(SCOPE_REVISION)));
        Map<String, Object> allowances = new LinkedHashMap<>();
        if (source.get(ALLOWANCES).size() > InternalSubRecord.MAX_SET_ENTRIES)
            throw new IllegalArgumentException("Too many internal allowances");
        for (Map.Entry<String, ?> entry : source.get(ALLOWANCES).entrySet()) {
            requirePackage(entry.getKey());
            if (!(entry.getValue() instanceof Integer) || (Integer) entry.getValue() < 1 || (Integer) entry.getValue() > 1440)
                throw new IllegalArgumentException("Invalid internal allowance");
            allowances.put(entry.getKey(), entry.getValue());
        }
        result.put(ALLOWANCES, Collections.unmodifiableMap(allowances));
        Map<String, ?> duration = source.get(DURATION);
        exact(duration, Set.of("duration", "minimum", "maximum", "hidden"));
        if (!(duration.get("duration") instanceof Long) || !(duration.get("minimum") instanceof Long)
                || !(duration.get("maximum") instanceof Long) || !(duration.get("hidden") instanceof Boolean))
            throw new IllegalArgumentException("Invalid internal duration");
        long choice = (Long) duration.get("duration");
        if (choice != -1 && choice != -2) PactDuration.validate(choice, choice);
        PactDuration.validate((Long) duration.get("minimum"), (Long) duration.get("maximum"));
        result.put(DURATION, Collections.unmodifiableMap(new LinkedHashMap<>(duration)));
        Map<String, ?> artwork = source.get(ARTWORK); exact(artwork, Set.of("censor_assets", "popup_assets", "disabled_assets", ARTWORK_REVISION));
        if (!(artwork.get(ARTWORK_REVISION) instanceof Long) || (Long) artwork.get(ARTWORK_REVISION) < 0)
            throw new IllegalArgumentException("Invalid artwork revision");
        Set<String> censor = assetIds(artwork.get("censor_assets")), popup = assetIds(artwork.get("popup_assets"));
        Set<String> disabled = assetIds(artwork.get("disabled_assets")), all = new LinkedHashSet<>(censor); all.addAll(popup);
        if (censor.size() + popup.size() > 512 || !all.containsAll(disabled)) throw new IllegalArgumentException("Invalid artwork selection");
        result.put(ARTWORK, Map.of("censor_assets", censor, "popup_assets", popup, "disabled_assets", disabled,
                ARTWORK_REVISION, artwork.get(ARTWORK_REVISION)));
        Map<String, ?> wallet = source.get(WALLET_DISPLAY); exact(wallet, Set.of("wallet_currency", RULE_SCHEMA));
        if (!(wallet.get("wallet_currency") instanceof String) || !Boolean.TRUE.equals(wallet.get(RULE_SCHEMA)))
            throw new IllegalArgumentException("Invalid internal Wallet display");
        com.subhub.app.penance.WalletCurrency.requireSupported((String) wallet.get("wallet_currency"));
        result.put(WALLET_DISPLAY, Collections.unmodifiableMap(new LinkedHashMap<>(wallet)));
        Map<String, ?> popupLocal = source.get(POPUP_LOCAL); exact(popupLocal, Set.of("popup_storm_photosensitivity_acknowledged"));
        if (!(popupLocal.get("popup_storm_photosensitivity_acknowledged") instanceof Boolean))
            throw new IllegalArgumentException("Invalid Popup acknowledgement");
        result.put(POPUP_LOCAL, Collections.unmodifiableMap(new LinkedHashMap<>(popupLocal)));
        Map<String, ?> presets = source.get(PRESETS); exact(presets, Set.of("looks_v1"));
        // Already validated saved looks can be large; do not reparse them for every slider event.
        if (knownValid == null || !presets.equals(knownValid.get(PRESETS))) validatePresets(presets.get("looks_v1"));
        result.put(PRESETS, Collections.unmodifiableMap(new LinkedHashMap<>(presets)));
        return Collections.unmodifiableMap(result);
    }

    private static Set<String> packageSet(Object value) {
        if (!(value instanceof Set<?>)) throw new IllegalArgumentException("Invalid package collection");
        Set<String> result = new LinkedHashSet<>();
        for (Object item : (Set<?>) value) {
            if (!(item instanceof String)) throw new IllegalArgumentException("Invalid package");
            requirePackage((String) item); result.add((String) item);
        }
        if (result.size() > InternalSubRecord.MAX_SET_ENTRIES) throw new IllegalArgumentException("Too many packages");
        return Collections.unmodifiableSet(result);
    }
    private static void requirePackage(String value) {
        if (value.length() > 255 || !value.matches("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+"))
            throw new IllegalArgumentException("Invalid internal package");
    }
    private static Set<String> assetIds(Object value) {
        if (!(value instanceof Set<?>) || ((Set<?>) value).size() > 512) throw new IllegalArgumentException("Invalid artwork set");
        Set<String> result = new LinkedHashSet<>();
        for (Object item : (Set<?>) value) {
            if (!(item instanceof String) || !((String) item).matches("[a-f0-9]{64}"))
                throw new IllegalArgumentException("Artwork must name an owned content hash");
            result.add((String) item);
        }
        return Collections.unmodifiableSet(result);
    }
    private static void validatePresets(Object value) {
        if (!(value instanceof String) || ((String) value).length() > 512_000)
            throw new IllegalArgumentException("Invalid saved looks");
        try {
            JSONObject root = new JSONObject((String) value);
            if (root.length() != 2 || root.getInt("version") != 1) throw new IllegalArgumentException("Invalid looks schema");
            JSONArray looks = root.getJSONArray("looks");
            if (looks.length() > com.subhub.app.settings.CensorLookStore.MAX_SAVED) throw new IllegalArgumentException("Too many looks");
            Set<String> ids = new LinkedHashSet<>();
            for (int i = 0; i < looks.length(); i++) {
                JSONObject encoded = looks.getJSONObject(i), settings = encoded.getJSONObject("settings");
                if (encoded.length() != 3) throw new IllegalArgumentException("Invalid look fields");
                java.util.Iterator<String> keys = settings.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    if (!com.subhub.app.settings.CensorLook.keys().contains(key) || settings.getJSONObject(key).length() != 2)
                        throw new IllegalArgumentException("Invalid look setting");
                }
                com.subhub.app.settings.CensorLook look = com.subhub.app.settings.CensorLook.decode(encoded);
                if (!ids.add(look.id())) throw new IllegalArgumentException("Duplicate saved look");
            }
        } catch (org.json.JSONException invalid) { throw new IllegalArgumentException("Invalid saved looks", invalid); }
    }

    private static void exact(Map<String, ?> values, Set<String> keys) {
        if (values == null || !values.keySet().equals(keys)) throw new SecurityException("Invalid internal fields");
    }
    private static Object preferenceValue(Object value) {
        if (value instanceof JSONArray) {
            Set<String> items = new LinkedHashSet<>(); JSONArray array = (JSONArray) value;
            for (int i = 0; i < array.length(); i++) items.add(array.optString(i));
            return InternalSubRecord.copyValue(items);
        }
        return InternalSubRecord.copyValue(value);
    }

    /**
     * apply/commit acknowledge only a responsive in-memory draft, as FeatureEditorSession does.
     * Only flush returns a durable revision. Callers debounce/serialize it and surface failures.
     * UI listeners observe drafts; InternalSubStore listeners observe acknowledged persistence.
     */
    public final class EditorDraft {
        private final InternalConfigurationStore owner = InternalConfigurationStore.this;
        private boolean flushing;
        private InternalSubRecord base;
        private Map<String, Map<String, Object>> sections;
        private final Map<String, Preferences> preferences = new LinkedHashMap<>();
        private EditorDraft(InternalSubRecord record) { base = record; sections = validate(record.configuration()); }
        public synchronized InternalSubRecord acknowledgedBase() { return base; }
        public synchronized boolean hasUnflushedChanges() { return !sections.equals(base.configuration()); }

        public synchronized SharedPreferences preferences(String name) {
            if (!SettingsRepository.PREFERENCES_NAME.equals(name)
                    && !ConfigurationSchema.preferenceStore(ConfigurationSchema.WALLET).equals(name)
                    && !PRESET_PREFERENCES.equals(name)
                    && !DURATION_PREFERENCES.equals(name))
                throw new SecurityException("No internal configuration store for this name");
            return preferences.computeIfAbsent(name, Preferences::new);
        }

        /** Asset import/validation belongs to a separate owned adapter before this reference is saved. */
        public void setArtwork(String kind, Set<String> ownedAssetIds) {
            if (!Set.of("censor_assets", "popup_assets", "disabled_assets").contains(kind)) throw new IllegalArgumentException("Unknown artwork");
            Preferences observer;
            synchronized (this) {
                if (sections.get(ARTWORK).get(kind).equals(ownedAssetIds)) return;
                Map<String, Map<String, Object>> changed = mutableSections();
                changed.get(ARTWORK).put(kind, ownedAssetIds);
                changed.get(ARTWORK).put(ARTWORK_REVISION, Math.addExact((Long) sections.get(ARTWORK).get(ARTWORK_REVISION), 1L));
                sections = validate(changed, sections); observer = preferences.get(SettingsRepository.PREFERENCES_NAME);
            }
            if (observer != null) observer.notifyChanged(ARTWORK_REVISION);
        }
        public void setArtwork(Set<String> censor, Set<String> popup, Set<String> disabled) {
            Preferences observer;
            synchronized (this) {
                Map<String, Object> old = sections.get(ARTWORK);
                if (old.get("censor_assets").equals(censor) && old.get("popup_assets").equals(popup)
                        && old.get("disabled_assets").equals(disabled)) return;
                Map<String, Map<String, Object>> changed = mutableSections();
                changed.put(ARTWORK, Map.of("censor_assets", censor, "popup_assets", popup, "disabled_assets", disabled,
                        ARTWORK_REVISION, Math.addExact((Long) old.get(ARTWORK_REVISION), 1L)));
                sections = validate(changed, sections); observer = preferences.get(SettingsRepository.PREFERENCES_NAME);
            }
            if (observer != null) observer.notifyChanged(ARTWORK_REVISION);
        }

        private Map<String, Map<String, Object>> mutableSections() {
            Map<String, Map<String, Object>> copy = new LinkedHashMap<>();
            for (Map.Entry<String, Map<String, Object>> entry : sections.entrySet())
                copy.put(entry.getKey(), new LinkedHashMap<>(entry.getValue()));
            return copy;
        }

        private final class Preferences implements SharedPreferences {
            private final Map<String, String> sectionByKey = new LinkedHashMap<>();
            private final Set<OnSharedPreferenceChangeListener> listeners = new LinkedHashSet<>();
            private final boolean main;
            Preferences(String name) {
                main = SettingsRepository.PREFERENCES_NAME.equals(name);
                for (String section : sections.keySet()) {
                    String storeName = DURATION.equals(section) ? DURATION_PREFERENCES
                            : PRESETS.equals(section) ? PRESET_PREFERENCES
                            : ConfigurationSchema.WALLET.equals(section) || WALLET_DISPLAY.equals(section)
                            ? ConfigurationSchema.preferenceStore(ConfigurationSchema.WALLET)
                            : SettingsRepository.PREFERENCES_NAME;
                    if (!ARTWORK.equals(section) && !ALLOWANCES.equals(section) && name.equals(storeName))
                        for (String key : sections.get(section).keySet()) sectionByKey.put(key, section);
                }
                if (main) sectionByKey.put(ARTWORK_REVISION, ARTWORK);
            }
            private String section(String key) {
                if (main && key != null && key.startsWith(ALLOWANCE_PREFIX)) {
                    requirePackage(key.substring(ALLOWANCE_PREFIX.length())); return ALLOWANCES;
                }
                String section = sectionByKey.get(key);
                if (section == null) throw new SecurityException("Not an internal configuration key");
                return section;
            }
            private Object value(String key) {
                synchronized (EditorDraft.this) { return sections.get(section(key)).get(storageKey(key)); }
            }
            private String storageKey(String key) { return ALLOWANCES.equals(section(key)) ? key.substring(ALLOWANCE_PREFIX.length()) : key; }
            private Set<String> keys(Map<String, Map<String, Object>> values) {
                Set<String> result = new LinkedHashSet<>(sectionByKey.keySet());
                if (main) for (String name : values.get(ALLOWANCES).keySet()) result.add(ALLOWANCE_PREFIX + name);
                return result;
            }
            @Override public Map<String, ?> getAll() {
                synchronized (EditorDraft.this) {
                    Map<String, Object> result = new LinkedHashMap<>();
                    for (String key : keys(sections)) result.put(key, value(key));
                    return Collections.unmodifiableMap(result);
                }
            }
            @Override public String getString(String key, String fallback) { return (String) value(key); }
            @Override @SuppressWarnings("unchecked") public Set<String> getStringSet(String key, Set<String> fallback) {
                return new LinkedHashSet<>((Set<String>) value(key));
            }
            @Override public int getInt(String key, int fallback) { Object found = value(key); return found == null ? fallback : (Integer) found; }
            @Override public long getLong(String key, long fallback) { return (Long) value(key); }
            @Override public float getFloat(String key, float fallback) { return (Float) value(key); }
            @Override public boolean getBoolean(String key, boolean fallback) { return (Boolean) value(key); }
            @Override public boolean contains(String key) { return value(key) != null; }
            @Override public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {
                synchronized (EditorDraft.this) { listeners.add(Objects.requireNonNull(listener)); }
            }
            @Override public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {
                synchronized (EditorDraft.this) { listeners.remove(listener); }
            }
            @Override public SharedPreferences.Editor edit() { return new Changes(); }

            private void notifyChanged(String key) {
                ArrayList<OnSharedPreferenceChangeListener> observers;
                synchronized (EditorDraft.this) { observers = new ArrayList<>(listeners); }
                for (OnSharedPreferenceChangeListener observer : observers) observer.onSharedPreferenceChanged(this, key);
            }

            private final class Changes implements SharedPreferences.Editor {
                private final Map<String, Object> pending = new LinkedHashMap<>();
                private boolean clear;
                private SharedPreferences.Editor put(String key, Object value) {
                    if (ARTWORK_REVISION.equals(key)) throw new SecurityException("Use the owned artwork adapter");
                    synchronized (EditorDraft.this) {
                        section(key); pending.put(key, value == null ? null : InternalSubRecord.copyValue(value)); return this;
                    }
                }
                @Override public SharedPreferences.Editor putString(String key, String value) { return put(key, value); }
                @Override public SharedPreferences.Editor putStringSet(String key, Set<String> value) { return put(key, value); }
                @Override public SharedPreferences.Editor putInt(String key, int value) { return put(key, value); }
                @Override public SharedPreferences.Editor putLong(String key, long value) { return put(key, value); }
                @Override public SharedPreferences.Editor putFloat(String key, float value) { return put(key, value); }
                @Override public SharedPreferences.Editor putBoolean(String key, boolean value) { return put(key, value); }
                @Override public SharedPreferences.Editor remove(String key) { return put(key, null); }
                @Override public SharedPreferences.Editor clear() { synchronized (EditorDraft.this) { clear = true; return this; } }
                @Override public void apply() { commit(); }
                @Override public boolean commit() {
                    ArrayList<OnSharedPreferenceChangeListener> observers; Set<String> changed = new LinkedHashSet<>();
                    synchronized (EditorDraft.this) {
                        Map<String, Map<String, Object>> next = mutableSections();
                        Map<String, Map<String, Object>> defaults = InternalConfigurationStore.defaults();
                        if (clear) for (String key : sectionByKey.keySet())
                            if (!ARTWORK_REVISION.equals(key)) next.get(section(key)).put(key, defaults.get(section(key)).get(key));
                        if (clear && main) next.get(ALLOWANCES).clear();
                        for (Map.Entry<String, Object> entry : pending.entrySet()) {
                            String section = section(entry.getKey());
                            String key = storageKey(entry.getKey());
                            if (ALLOWANCES.equals(section) && entry.getValue() == null) next.get(section).remove(key);
                            else next.get(section).put(key, entry.getValue() == null
                                    ? defaults.get(section).get(key) : entry.getValue());
                        }
                        Map<String, Map<String, Object>> clean = validate(next, sections);
                        Set<String> observed = keys(sections); observed.addAll(keys(clean));
                        for (String key : observed) if (!Objects.equals(value(key), clean.get(section(key)).get(storageKey(key))))
                            changed.add(key);
                        sections = clean;
                        // Android Editors are reusable. Only successful application consumes the
                        // pending operations; clear these before any reentrant observer uses us.
                        pending.clear(); clear = false;
                        observers = new ArrayList<>(listeners);
                    }
                    for (String key : changed) for (OnSharedPreferenceChangeListener observer : observers)
                        observer.onSharedPreferenceChanged(Preferences.this, key);
                    return true;
                }
            }
        }
    }
}
