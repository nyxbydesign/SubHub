package com.subhub.app.configuration;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.AtomicFile;

import com.subhub.app.capture.CustomImageManager;
import com.subhub.app.penance.PayPalCredentialStore;
import com.subhub.app.penance.PenanceManager;
import com.subhub.app.popup.PopupStormSettings;
import com.subhub.app.settings.SettingsRepository;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Retires portable-library ownership while preserving applied configuration and private artwork. */
public final class LegacyPackMigration {
    static final String STATE_PREFS = "subhub_pack_state_v1";
    static final String MIGRATION_KEY = "configuration_migration_version";
    static final int VERSION = 1;
    private static final String JOURNAL = "activation_journal";
    private static final String ENCRYPTED_PAYPAL = "_local_encrypted_paypal";
    private static final Set<String> LEGACY_KEYS = Set.of(
            JOURNAL, "active_pack_id", "active_pack_backup", "active_sections", "creator_device_id");
    private static final Set<String> ENCRYPTED_KEYS = Set.of(
            "environment", "client_id", "client_secret", "verified_boundary", "primary_currency",
            "primary_currency_boundary", "vault_status", "vault_id", "customer_id", "payer_email",
            "payer_account_id", "vault_boundary", "vault_setup_token_id", "vault_setup_customer_id",
            "vault_setup_metadata_id", "vault_setup_approval_url", "vault_setup_boundary");
    private static final Set<File> READY = new HashSet<>();

    interface Committer {
        boolean commit(String name, SharedPreferences.Editor editor);
    }

    private LegacyPackMigration() { }

    /** Run before starting remote recovery or exposing local configuration edits. */
    public static synchronized boolean recoverAndMigrate(Context context) {
        return recoverAndMigrate(context, (name, editor) -> editor.commit());
    }

    static synchronized boolean recoverAndMigrate(Context context, Committer committer) {
        Context app = context.getApplicationContext();
        File identity = app.getFilesDir().getAbsoluteFile();
        READY.remove(identity);
        try {
            SharedPreferences legacy = app.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE);
            SharedPreferences main = app.getSharedPreferences(
                    SettingsRepository.PREFERENCES_NAME, Context.MODE_PRIVATE);
            if (legacy.contains(JOURNAL) && !recoverPending(app, legacy, committer)) return false;

            if (main.getInt(MIGRATION_KEY, 0) != VERSION || hasLegacyState(legacy)) {
                Map<String, String> pointers = new LinkedHashMap<>();
                File files = app.getFilesDir().getCanonicalFile();
                File owned = new File(files, "subhub_studio/active-assets");
                if (!owned.getCanonicalFile().equals(owned)) throw new IOException("Invalid legacy artwork root");
                File target = new File(files, "configuration-artwork/legacy-v1");
                for (String key : List.of(CustomImageManager.PACK_DIR_KEY, PopupStormSettings.K_PACK_DIR)) {
                    String current = main.getString(key, null);
                    if (current == null || current.isEmpty()) continue;
                    File path = new File(current).getAbsoluteFile();
                    File canonical = path.getCanonicalFile();
                    boolean ownedPath = inside(canonical, owned);
                    if (inside(path, owned) && !ownedPath) throw new IOException("Artwork leaves private storage");
                    if (!ownedPath) continue;
                    String group = CustomImageManager.PACK_DIR_KEY.equals(key) ? "censor" : "popup";
                    File destination = copyArtwork(canonical, new File(target, group), files);
                    pointers.put(key, destination.getAbsolutePath());
                }
                SharedPreferences.Editor edit = main.edit();
                for (Map.Entry<String, String> pointer : pointers.entrySet()) {
                    edit.putString(pointer.getKey(), pointer.getValue());
                }
                long previous = main.getLong(CustomImageManager.REVISION_KEY, 0);
                long revision = Math.max(System.currentTimeMillis(), Math.addExact(previous, 1));
                edit.putLong(CustomImageManager.REVISION_KEY, revision).putInt(MIGRATION_KEY, VERSION);
                if (!committer.commit(SettingsRepository.PREFERENCES_NAME, edit)) {
                    // A failed disk commit may already have installed the marker in memory.
                    // A later attempt must commit again before it can admit configuration writes.
                    main.edit().remove(MIGRATION_KEY).commit();
                    return false;
                }
            }

            if (hasLegacyState(legacy)) {
                Map<String, ?> beforeRetirement = new LinkedHashMap<>(legacy.getAll());
                SharedPreferences.Editor retired = legacy.edit();
                for (String key : LEGACY_KEYS) retired.remove(key);
                if (!committer.commit(STATE_PREFS, retired)) {
                    SharedPreferences.Editor retained = legacy.edit();
                    for (String key : LEGACY_KEYS) {
                        if (beforeRetirement.containsKey(key)) put(retained, key, beforeRetirement.get(key));
                    }
                    retained.commit();
                    return false;
                }
            }
            READY.add(identity);
            return true;
        } catch (Exception invalidOrUnavailable) {
            // Keep source assets and the recovery journal for another startup attempt.
            return false;
        }
    }

    /** Readiness is admitted only by a successful migration in this process. */
    public static synchronized boolean isReady(Context context) {
        Context app = context.getApplicationContext();
        if (!READY.contains(app.getFilesDir().getAbsoluteFile())) return false;
        try {
            return app.getSharedPreferences(SettingsRepository.PREFERENCES_NAME, Context.MODE_PRIVATE)
                    .getInt(MIGRATION_KEY, 0) == VERSION
                    && !hasLegacyState(app.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE));
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    private static boolean hasLegacyState(SharedPreferences legacy) {
        for (String key : LEGACY_KEYS) if (legacy.contains(key)) return true;
        return false;
    }

    private static boolean recoverPending(Context context, SharedPreferences state, Committer committer)
            throws Exception {
        String raw = state.getString(JOURNAL, null);
        if (raw == null) return false;
        JSONObject journal = new JSONObject(raw);
        if (!Boolean.TRUE.equals(journal.opt("pending"))) return false;
        if (journal.has("sections")) {
            JSONArray sections = journal.getJSONArray("sections");
            if (sections.length() == 0) return false;
            Set<String> seen = new HashSet<>();
            for (int i = 0; i < sections.length(); i++) {
                Object section = sections.get(i);
                if (!(section instanceof String) || !ConfigurationSchema.SECTIONS.contains(section)
                        || !seen.add((String) section)) return false;
            }
        }
        JSONObject backup = journal.getJSONObject("backup");
        if (backup.length() == 0) return false;
        Map<String, Map<String, Object>> stores = new LinkedHashMap<>();
        JSONObject encrypted = null;
        Iterator<String> names = backup.keys();
        while (names.hasNext()) {
            String name = names.next();
            JSONObject values = backup.getJSONObject(name);
            if (ENCRYPTED_PAYPAL.equals(name)) {
                Iterator<String> keys = values.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    if (!ENCRYPTED_KEYS.contains(key) || !(values.get(key) instanceof String)) return false;
                }
                encrypted = values;
                continue;
            }
            if (!SettingsRepository.PREFERENCES_NAME.equals(name) && !PenanceManager.PREFS_NAME.equals(name)) return false;
            Map<String, Object> decoded = new LinkedHashMap<>();
            Iterator<String> keys = values.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                JSONObject item = values.getJSONObject(key);
                Object present = item.get("present");
                if (!(present instanceof Boolean)) return false;
                decoded.put(key, Boolean.TRUE.equals(present) ? decode(item) : null);
            }
            stores.put(name, decoded);
        }
        // Validate the complete journal before touching any preference store.
        if (encrypted != null && !new PayPalCredentialStore(context).restoreEncryptedLocalSnapshot(encrypted)) return false;
        for (Map.Entry<String, Map<String, Object>> store : stores.entrySet()) {
            SharedPreferences.Editor edit = context.getSharedPreferences(store.getKey(), Context.MODE_PRIVATE).edit();
            for (Map.Entry<String, Object> item : store.getValue().entrySet()) put(edit, item.getKey(), item.getValue());
            if (!committer.commit(store.getKey(), edit)) return false;
        }
        SharedPreferences.Editor recovered = state.edit();
        for (String key : LEGACY_KEYS) recovered.remove(key);
        if (committer.commit(STATE_PREFS, recovered)) return true;
        // A failed commit can already have changed SharedPreferences' memory snapshot.
        state.edit().putString(JOURNAL, raw).commit();
        return false;
    }

    private static Object decode(JSONObject item) throws Exception {
        String type = item.getString("type");
        Object value = item.get("value");
        switch (type) {
            case "boolean":
                if (value instanceof Boolean) return value;
                break;
            case "int":
            case "long":
                if (value instanceof Number) {
                    long whole = new BigDecimal(value.toString()).longValueExact();
                    if ("long".equals(type)) return whole;
                    return Math.toIntExact(whole);
                }
                break;
            case "float":
                if (value instanceof Number && Double.isFinite(((Number) value).doubleValue())) {
                    float number = ((Number) value).floatValue();
                    if (Float.isFinite(number)) return number;
                }
                break;
            case "string":
                if (value instanceof String) return value;
                break;
            case "set":
                if (value instanceof JSONArray) {
                    Set<String> strings = new LinkedHashSet<>();
                    JSONArray array = (JSONArray) value;
                    for (int i = 0; i < array.length(); i++) {
                        if (!(array.get(i) instanceof String)) throw new IOException("Invalid recovery selection");
                        strings.add(array.getString(i));
                    }
                    return strings;
                }
                break;
            default:
                break;
        }
        throw new IOException("Invalid recovery value");
    }

    private static void put(SharedPreferences.Editor editor, String key, Object value) {
        if (value == null) editor.remove(key);
        else if (value instanceof Boolean) editor.putBoolean(key, (Boolean) value);
        else if (value instanceof Integer) editor.putInt(key, (Integer) value);
        else if (value instanceof Long) editor.putLong(key, (Long) value);
        else if (value instanceof Float) editor.putFloat(key, (Float) value);
        else if (value instanceof String) editor.putString(key, (String) value);
        else if (value instanceof Set<?>) {
            Set<String> strings = new LinkedHashSet<>();
            for (Object item : (Set<?>) value) strings.add((String) item);
            editor.putStringSet(key, strings);
        } else throw new IllegalArgumentException("Invalid recovery type");
    }

    private static File copyArtwork(File source, File parent, File files) throws Exception {
        if (!source.isDirectory()) throw new IOException("Applied artwork is unavailable");
        File[] candidates = source.listFiles();
        if (candidates == null) throw new IOException("Applied artwork cannot be read");
        Arrays.sort(candidates, (left, right) -> left.getName().compareTo(right.getName()));
        Map<File, String> selected = new LinkedHashMap<>();
        MessageDigest selection = MessageDigest.getInstance("SHA-256");
        for (File image : candidates) {
            if (!isImageName(image.getName())) continue;
            if (!image.isFile() || !inside(image.getCanonicalFile(), source)) throw new IOException("Invalid applied artwork");
            String digest = digest(image);
            selected.put(image, digest);
            selection.update((image.getName() + '\u0000' + digest + '\n').getBytes(StandardCharsets.UTF_8));
        }
        // A changed source selection gets a different directory, including after a failed attempt.
        File target = new File(parent, hex(selection.digest()));
        if (!inside(target, files) || !target.getCanonicalFile().equals(target)) throw new IOException("Invalid artwork destination");
        if (!target.mkdirs() && !target.isDirectory()) throw new IOException("Artwork storage is unavailable");
        File[] existing = target.listFiles();
        if (existing == null) throw new IOException("Artwork storage cannot be read");
        Set<String> expectedNames = new HashSet<>();
        for (File image : selected.keySet()) expectedNames.add(image.getName());
        for (File file : existing) {
            String name = file.getName();
            if (name.endsWith(".bak") || name.endsWith(".new")) name = name.substring(0, name.length() - 4);
            if (!expectedNames.contains(name)) throw new IOException("Unexpected migrated artwork");
        }
        for (Map.Entry<File, String> entry : selected.entrySet()) {
            File image = entry.getKey();
            String digest = entry.getValue();
            File output = new File(target, image.getName());
            for (File path : List.of(output, new File(output + ".bak"), new File(output + ".new"))) {
                if (!path.getCanonicalFile().equals(path)) throw new IOException("Invalid artwork output");
                if (path.exists() && !path.isFile()) throw new IOException("Artwork output is not a file");
            }
            if (!output.isFile() || output.length() != image.length() || !digest.equals(digest(output))) {
                AtomicFile atomic = new AtomicFile(output);
                FileOutputStream stream = null;
                try {
                    stream = atomic.startWrite();
                    try (FileInputStream input = new FileInputStream(image)) {
                        byte[] buffer = new byte[8192];
                        int count;
                        while ((count = input.read(buffer)) != -1) stream.write(buffer, 0, count);
                    }
                    atomic.finishWrite(stream);
                    stream = null;
                } finally {
                    if (stream != null) atomic.failWrite(stream);
                }
            }
            if (output.length() != image.length() || !digest.equals(digest(output)) || !digest.equals(digest(image))) {
                throw new IOException("Artwork verification failed");
            }
        }
        File[] current = source.listFiles();
        if (current == null) throw new IOException("Applied artwork cannot be read");
        Set<File> currentImages = new HashSet<>();
        for (File file : current) {
            if (!isImageName(file.getName())) continue;
            currentImages.add(file);
            if (!selected.containsKey(file) || !selected.get(file).equals(digest(file))) {
                throw new IOException("Applied artwork changed during migration");
            }
        }
        if (!selected.keySet().equals(currentImages)) throw new IOException("Applied artwork selection changed");
        return target;
    }

    private static boolean isImageName(String value) {
        String name = value.toLowerCase(Locale.ROOT);
        return name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".webp");
    }

    private static boolean inside(File child, File parent) {
        return child.getPath().startsWith(parent.getPath() + File.separator);
    }

    private static String digest(File file) throws Exception {
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) hash.update(buffer, 0, count);
        }
        return hex(hash.digest());
    }

    private static String hex(byte[] bytes) {
        StringBuilder encoded = new StringBuilder(64);
        for (byte value : bytes) encoded.append(String.format(Locale.ROOT, "%02x", value & 255));
        return encoded.toString();
    }
}
