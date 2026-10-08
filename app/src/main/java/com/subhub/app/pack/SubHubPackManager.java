package com.subhub.app.pack;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import com.subhub.app.BuildConfig;
import com.subhub.app.capture.CustomImageManager;
import com.subhub.app.penance.PenanceManager;
import com.subhub.app.penance.PayPalCredentialStore;
import com.subhub.app.penance.PayPalEnvironment;
import com.subhub.app.popup.PopupStormSettings;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.settings.SettingsRepository;
import com.subhub.app.update.SemanticVersion;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Draft/library storage plus reversible, journaled activation for portable SubHub packs. */
public final class SubHubPackManager {
    private static final String STATE_PREFS = "subhub_pack_state_v1";
    private static final String KEY_ACTIVE_ID = "active_pack_id";
    private static final String KEY_ACTIVE_BACKUP = "active_pack_backup";
    private static final String KEY_JOURNAL = "activation_journal";
    private static final String KEY_ACTIVE_SECTIONS = "active_sections";
    private static final String KEY_DEVICE_ID = "creator_device_id";
    private static final String LOCAL_PAYPAL_BACKUP = "_local_encrypted_paypal";
    private static final Set<String> STRING_SET_KEYS = Set.of(
            SettingsRepository.KEY_ENABLED_CATEGORIES,
            SettingsRepository.KEY_TEXT_SMUT_CATEGORIES,
            SettingsRepository.KEY_ENABLED_PHRASE_CATEGORIES,
            com.subhub.app.subliminal.SubliminalSettingsRepository.KEY_PACKS);
    private static final Set<String> LONG_KEYS = Set.of(
            com.subhub.app.subliminal.SubliminalSettingsRepository.KEY_VISIBLE_MS,
            com.subhub.app.subliminal.SubliminalSettingsRepository.KEY_MIN_INTERVAL_MS,
            com.subhub.app.subliminal.SubliminalSettingsRepository.KEY_MAX_INTERVAL_MS);
    private static final Set<String> FLOAT_KEYS = Set.of(
            SettingsRepository.KEY_CENSOR_INTENSITY,
            SettingsRepository.KEY_CONFIDENCE,
            SettingsRepository.KEY_TEXT_SMUT_SENSITIVITY,
            PopupStormSettings.K_SPAWN_RATE,
            PopupStormSettings.K_DISPLAY_DURATION,
            PopupStormSettings.K_BURST_FREQUENCY,
            PopupStormSettings.K_BURST_DURATION,
            PopupStormSettings.K_BURST_MULTIPLIER);

    private final Context context;
    private final File root;
    private final File drafts;
    private final File library;
    private final File activeAssets;
    private final SharedPreferences state;

    public SubHubPackManager(Context context) {
        this.context = context.getApplicationContext();
        root = new File(this.context.getFilesDir(), "subhub_studio");
        drafts = new File(root, "drafts");
        library = new File(root, "library");
        activeAssets = new File(root, "active-assets");
        ensureDirectory(root);
        ensureDirectory(drafts);
        ensureDirectory(library);
        state = this.context.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE);
        recoverInterruptedActivation();
        // PackManager initializes legacy .bbpack key locks first. Do not erase them when no
        // modern arrangement is active; modern group locks supersede them only while active.
        if (activePackId() != null) {
            synchronizeLegacyLocks(SubHubPackLocks.groups(this.context));
        }
    }

    public SubHubPack captureCurrent() {
        SubHubPack pack = createBlank();
        SharedPreferences main = preferences(SettingsRepository.PREFERENCES_NAME);
        for (String section : SubHubPackSchema.SECTIONS) {
            JSONObject values = SubHubPackSchema.WALLET.equals(section)
                    ? SubHubPackSchema.captureWallet(preferences(PenanceManager.PREFS_NAME))
                    : SubHubPackSchema.captureMainSection(section, main);
            pack.setSection(section, values);
        }
        int index = 0;
        for (File image : new CustomImageManager(context).enabledFilesForPackExport()) {
            try (FileInputStream input = new FileInputStream(image)) {
                pack.putAsset(String.format(Locale.ROOT, "assets/censor/image-%02d.png", index++),
                        readBounded(input, 25L * 1024L * 1024L));
            } catch (IOException ignored) {
                // A missing private image is omitted without weakening the remaining draft.
            }
        }
        JSONObject recommendations = new JSONObject();
        try {
            recommendations.put("hardcoreSuggested", false);
            recommendations.put("serviceDurationMillis", -1L);
        } catch (Exception ignored) {}
        pack.setRecommendations(recommendations);
        return pack;
    }

    /** Creates a new arrangement tied to this installation's private, random creator identity. */
    public SubHubPack createBlank() {
        return SubHubPack.blank(deviceIdentifier());
    }

    public JSONObject captureSection(String section) {
        return SubHubPackSchema.WALLET.equals(section)
                ? SubHubPackSchema.captureWallet(preferences(PenanceManager.PREFS_NAME))
                : SubHubPackSchema.captureMainSection(section,
                        preferences(SettingsRepository.PREFERENCES_NAME));
    }

    public synchronized void saveDraft(SubHubPack pack) throws IOException {
        write(pack, fileFor(drafts, pack.getId()));
    }

    public synchronized void addToLibrary(SubHubPack pack) throws IOException {
        write(pack, fileFor(library, pack.getId()));
    }

    public synchronized SubHubPack importPack(Uri uri) throws IOException {
        try (InputStream input = context.getContentResolver().openInputStream(uri)) {
            SubHubPack pack = SubHubPackArchive.read(input);
            if (!isCompatible(pack)) {
                throw new IOException("This arrangement needs SubHub "
                        + pack.getMinimumSubHubVersion() + " or newer");
            }
            SubHubPack installed = findLibrary(pack.getId());
            boolean replacing = installed != null;
            if (replacing && !ControllerPinManager.isDomModeActive()
                    && !samePackIdentity(installed, pack)) {
                throw new IOException("Arrangement identity differs; unlock Dom Space to replace it");
            }
            if (pack.getId().equals(activePackId())) {
                if (pack.hasEncryptedPayPal() || activeHasPayPalBackup()
                        || (installed != null && installed.hasEncryptedPayPal())) {
                    throw new IOException("Deactivate in Dom Space before updating an encrypted PayPal arrangement");
                }
                if (installed == null || !samePackIdentity(installed, pack)) {
                    throw new IOException("Active arrangement identity does not match this update");
                }
                if (!replaceActivePack(installed, pack)) {
                    throw new IOException("Could not apply the active arrangement update");
                }
            } else {
                addToLibrary(pack);
            }
            return pack;
        }
    }

    public synchronized File exportForShare(SubHubPack pack) throws IOException {
        File share = new File(context.getCacheDir(), "shared-packs");
        ensureDirectory(share);
        String slug = pack.getName().replaceAll("[^A-Za-z0-9._-]+", "-")
                .replaceAll("^-+|-+$", "");
        if (slug.isBlank()) slug = "SubHub-arrangement";
        File output = new File(share, slug + SubHubPackArchive.EXTENSION);
        write(pack, output);
        return output;
    }

    public List<Record> listDrafts() { return list(drafts, true); }
    public List<Record> listLibrary() { return list(library, false); }

    public SubHubPack findDraft(String id) { return read(fileFor(drafts, id)); }
    public SubHubPack findLibrary(String id) { return read(fileFor(library, id)); }

    public synchronized void deleteDraft(String id) { deleteFile(fileFor(drafts, id)); }

    public synchronized boolean deleteLibrary(String id) {
        if (id != null && id.equals(activePackId()) && !ControllerPinManager.isDomModeActive()) {
            return false;
        }
        if (id != null && id.equals(activePackId()) && !deactivate()) return false;
        return deleteFile(fileFor(library, id));
    }

    public String activePackId() { return state.getString(KEY_ACTIVE_ID, null); }

    public Set<String> activeSections() {
        Set<String> values = state.getStringSet(KEY_ACTIVE_SECTIONS, Set.of());
        return Collections.unmodifiableSet(new LinkedHashSet<>(values == null ? Set.of() : values));
    }

    static boolean samePackIdentity(SubHubPack installed, SubHubPack update) {
        return installed != null && update != null
                && Objects.equals(installed.getId(), update.getId())
                && Objects.equals(installed.getOriginDeviceId(), update.getOriginDeviceId())
                && Objects.equals(installed.getName(), update.getName())
                && Objects.equals(installed.getAuthor(), update.getAuthor());
    }

    public List<String> diff(SubHubPack pack, Set<String> requestedSections) {
        List<String> result = new ArrayList<>();
        if (pack == null) return result;
        Set<String> selected = sanitizeSelected(pack, requestedSections);
        for (String section : selected) {
            JSONObject after = pack.getSection(section);
            SharedPreferences current = preferences(SubHubPackSchema.preferenceStore(section));
            List<String> changed = new ArrayList<>();
            Iterator<String> keys = after.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                Object before = current.getAll().get(key);
                Object proposed = after.opt(key);
                if (!valuesEqual(before, proposed)) changed.add(humanize(key));
            }
            if (!changed.isEmpty()) result.add(title(section) + ": " + String.join(", ", changed));
        }
        if (selected.contains(SubHubPackSchema.WALLET) && pack.hasEncryptedPayPal()) {
            result.add("PayPal: encrypted merchant credentials and recipient link. Unlock and confirm "
                    + "before activation. Existing payer authorization will not be reused.");
        }
        if (result.isEmpty()) result.add("No setting values would change.");
        JSONObject recommendations = pack.getRecommendations();
        if (recommendations.optBoolean("hardcoreSuggested", false)) {
            result.add("Recommendation: consider Hardcore Mode. It will not be enabled automatically.");
        }
        if (recommendations.has("serviceDurationMillis")) {
            result.add("Recommended service duration: "
                    + durationLabel(recommendations.optLong("serviceDurationMillis"))
                    + ". It will not start service or select the duration automatically.");
        }
        return result;
    }

    public synchronized boolean activate(SubHubPack pack, Set<String> requestedSections) {
        return activate(pack, requestedSections, null);
    }

    /** Run on a worker: no state changes or network calls while deriving the key. */
    public UnlockedPayPal unlockPayPal(SubHubPack pack, char[] password)
            throws java.security.GeneralSecurityException {
        if (!ControllerPinManager.isDomModeActive() || pack == null || !pack.hasEncryptedPayPal()) {
            throw new java.security.GeneralSecurityException("Unlock Dom Space first");
        }
        JSONObject envelope = pack.getEncryptedPayPal();
        return new UnlockedPayPal(pack.getId(), pack.getOriginDeviceId(), envelope.toString(),
                PackPayPalCipher.decrypt(pack.getId(), pack.getOriginDeviceId(), envelope, password));
    }

    public static final class UnlockedPayPal implements AutoCloseable {
        private final String id, origin, envelope;
        private PackPayPalCipher.Payload payload;
        private UnlockedPayPal(String id, String origin, String envelope, PackPayPalCipher.Payload payload) {
            this.id = id; this.origin = origin; this.envelope = envelope; this.payload = payload;
        }
        public String summary() { return payload == null ? "Locked" : payload.summary(); }
        private boolean matches(SubHubPack pack) {
            return payload != null && id.equals(pack.getId()) && origin.equals(pack.getOriginDeviceId())
                    && envelope.equals(String.valueOf(pack.getEncryptedPayPal()));
        }
        @Override public void close() { if (payload != null) payload.close(); payload = null; }
        @Override public String toString() { return "Unlocked PayPal [redacted]"; }
    }

    public synchronized boolean activate(SubHubPack pack, Set<String> requestedSections,
            UnlockedPayPal unlocked) {
        if (pack == null || !isCompatible(pack) || !ControllerPinManager.isDomModeActive()) {
            return false;
        }
        Set<String> selected = sanitizeSelected(pack, requestedSections);
        if (selected.isEmpty()) return false;
        boolean usePayPal = selected.contains(SubHubPackSchema.WALLET) && pack.hasEncryptedPayPal();
        if (usePayPal && (unlocked == null || !unlocked.matches(pack) || !canChangeMerchant())) return false;
        // Failed recovery must retain its journal and block another activation.
        if (state.contains(KEY_JOURNAL)) return false;
        if (activePackId() != null && !deactivate()) return false;
        new PackManager(context).deactivate();
        try {
            JSONObject backup = backup(pack, selected);
            JSONObject journal = new JSONObject();
            journal.put("pending", true);
            journal.put("backup", backup);
            if (!state.edit().putString(KEY_JOURNAL, journal.toString()).commit()) return false;
            if (!ControllerPinManager.isDomModeActive() || !applyPayPal(usePayPal, unlocked)
                    || !apply(pack, selected)) {
                if (restore(backup)) state.edit().remove(KEY_JOURNAL).commit();
                return false;
            }
            installAssets(pack);
            Set<String> locks = new LinkedHashSet<>(pack.getLockGroups());
            locks.retainAll(selected);
            SubHubPackLocks.set(context, locks);
            synchronizeLegacyLocks(locks);
            boolean committed = state.edit().putString(KEY_ACTIVE_ID, pack.getId())
                    .putString(KEY_ACTIVE_BACKUP, backup.toString())
                    .putStringSet(KEY_ACTIVE_SECTIONS, selected)
                    .remove(KEY_JOURNAL).commit();
            if (!committed) {
                // SharedPreferences updates memory even when its disk commit fails. Reinsert
                // recovery state before another operation can flush a journal-free snapshot.
                state.edit().putString(KEY_JOURNAL, journal.toString()).commit();
                if (restore(backup)) {
                    clearActiveAssets();
                    SubHubPackLocks.clear(context);
                    LockedSettings.clear();
                    state.edit().remove(KEY_ACTIVE_ID).remove(KEY_ACTIVE_BACKUP)
                            .remove(KEY_ACTIVE_SECTIONS).remove(KEY_JOURNAL).commit();
                }
            }
            return committed;
        } catch (Exception error) {
            recoverInterruptedActivation();
            return false;
        }
    }

    public synchronized boolean deactivate() {
        if (activePackId() == null) return true;
        if (!ControllerPinManager.isDomModeActive()) return false;
        String raw = state.getString(KEY_ACTIVE_BACKUP, null);
        if (raw == null) return false;
        try {
            JSONObject backup = new JSONObject(raw);
            if (backup.has(LOCAL_PAYPAL_BACKUP) && !canChangeMerchant()) return false;
            if (!restore(backup)) return false;
        } catch (Exception ignored) { return false; }
        clearActiveAssets();
        SubHubPackLocks.clear(context);
        LockedSettings.clear();
        return state.edit().remove(KEY_ACTIVE_ID).remove(KEY_ACTIVE_BACKUP)
                .remove(KEY_ACTIVE_SECTIONS).remove(KEY_JOURNAL).commit();
    }

    /** Replaces an active pack without releasing its original pre-pack backup or requiring Dom. */
    private boolean replaceActivePack(SubHubPack installed, SubHubPack update) {
        if (installed.hasEncryptedPayPal() || update.hasEncryptedPayPal() || activeHasPayPalBackup()
                || state.contains(KEY_JOURNAL)) return false;
        Set<String> previousSections = activeSections();
        Set<String> updatedSections = new LinkedHashSet<>(previousSections);
        updatedSections.retainAll(update.getIncludedSections());
        if (updatedSections.isEmpty()) return false;
        String originalRaw = state.getString(KEY_ACTIVE_BACKUP, null);
        if (originalRaw == null) return false;
        Set<String> previousLocks = new LinkedHashSet<>(installed.getLockGroups());
        previousLocks.retainAll(previousSections);
        JSONObject currentSnapshot = null;
        try {
            JSONObject originalBackup = new JSONObject(originalRaw);
            currentSnapshot = backup(installed, previousSections);
            mergeMissingBackup(currentSnapshot, backup(update, updatedSections));
            mergeMissingBackup(originalBackup, backup(update, updatedSections));

            if (!restore(originalBackup)) return false;
            if (!apply(update, updatedSections)) {
                restore(currentSnapshot);
                return false;
            }
            installAssets(update);
            Set<String> updatedLocks = new LinkedHashSet<>(update.getLockGroups());
            updatedLocks.retainAll(updatedSections);
            SubHubPackLocks.set(context, updatedLocks);
            synchronizeLegacyLocks(updatedLocks);
            boolean committed = state.edit()
                    .putString(KEY_ACTIVE_BACKUP, originalBackup.toString())
                    .putStringSet(KEY_ACTIVE_SECTIONS, updatedSections)
                    .commit();
            if (!committed) throw new IOException("Could not save active pack state");
            write(update, fileFor(library, update.getId()));
            return true;
        } catch (Exception error) {
            try {
                if (currentSnapshot != null) restore(currentSnapshot);
                installAssets(installed);
                SubHubPackLocks.set(context, previousLocks);
                synchronizeLegacyLocks(previousLocks);
                state.edit().putString(KEY_ACTIVE_BACKUP, originalRaw)
                        .putStringSet(KEY_ACTIVE_SECTIONS, previousSections).commit();
            } catch (Exception ignored) {
                // The existing recovery journal remains the final fallback for damaged state.
            }
            return false;
        }
    }

    private static void mergeMissingBackup(JSONObject target, JSONObject source) throws Exception {
        Iterator<String> stores = source.keys();
        while (stores.hasNext()) {
            String store = stores.next();
            JSONObject sourceValues = source.optJSONObject(store);
            if (sourceValues == null) continue;
            JSONObject targetValues = target.optJSONObject(store);
            if (targetValues == null) {
                targetValues = new JSONObject();
                target.put(store, targetValues);
            }
            Iterator<String> keys = sourceValues.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if (!targetValues.has(key)) targetValues.put(key, sourceValues.opt(key));
            }
        }
    }

    private boolean apply(SubHubPack pack, Set<String> selected) {
        Map<String, SharedPreferences.Editor> editors = new LinkedHashMap<>();
        for (String section : selected) {
            String store = SubHubPackSchema.preferenceStore(section);
            SharedPreferences.Editor editor = editors.computeIfAbsent(store,
                    unused -> preferences(store).edit());
            JSONObject values = pack.getSection(section);
            Iterator<String> keys = values.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if (SubHubPackSchema.isSecretOrRuntimeKey(key)) continue;
                applyJson(editor, key, values.opt(key));
            }
        }
        for (SharedPreferences.Editor editor : editors.values()) if (!editor.commit()) return false;
        return true;
    }

    private boolean applyPayPal(boolean use, UnlockedPayPal unlocked) {
        if (!use) return true;
        if (!ControllerPinManager.isDomModeActive() || unlocked == null || unlocked.payload == null) return false;
        PackPayPalCipher.Payload value = unlocked.payload;
        return new PayPalCredentialStore(context).saveImported(PayPalEnvironment.valueOf(value.environment()),
                value.clientId(), value.secret())
                && preferences(PenanceManager.PREFS_NAME).edit()
                .putString(PenanceManager.KEY_PAYPAL_LINK, value.recipientLink()).commit();
    }

    private boolean canChangeMerchant() {
        return new PenanceManager(context).getActiveSettlementId().isEmpty()
                && !new com.subhub.app.penance.HardcoreAutoPayManager(context).isConfigured();
    }

    private boolean activeHasPayPalBackup() {
        String raw = state.getString(KEY_ACTIVE_BACKUP, null);
        if (raw == null) return false;
        try { return new JSONObject(raw).has(LOCAL_PAYPAL_BACKUP); }
        catch (Exception ignored) { return true; }
    }

    private JSONObject backup(SubHubPack pack, Set<String> selected) throws Exception {
        JSONObject root = new JSONObject();
        for (String section : selected) {
            String store = SubHubPackSchema.preferenceStore(section);
            JSONObject storeBackup = root.optJSONObject(store);
            if (storeBackup == null) {
                storeBackup = new JSONObject();
                root.put(store, storeBackup);
            }
            Map<String, ?> all = preferences(store).getAll();
            JSONObject values = pack.getSection(section);
            Iterator<String> keys = values.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                JSONObject item = new JSONObject();
                item.put("present", all.containsKey(key));
                if (all.containsKey(key)) encode(item, all.get(key));
                storeBackup.put(key, item);
            }
        }
        if (selected.contains(SubHubPackSchema.WALLET) && pack.hasEncryptedPayPal()) {
            root.put(LOCAL_PAYPAL_BACKUP, new PayPalCredentialStore(context).encryptedLocalSnapshot());
            JSONObject item = new JSONObject();
            SharedPreferences wallet = preferences(PenanceManager.PREFS_NAME);
            item.put("present", wallet.contains(PenanceManager.KEY_PAYPAL_LINK));
            if (wallet.contains(PenanceManager.KEY_PAYPAL_LINK)) {
                encode(item, wallet.getString(PenanceManager.KEY_PAYPAL_LINK, ""));
            }
            root.getJSONObject(PenanceManager.PREFS_NAME).put(PenanceManager.KEY_PAYPAL_LINK, item);
        }
        return root;
    }

    private boolean restore(JSONObject backup) {
        boolean restored = true;
        Iterator<String> stores = backup.keys();
        while (stores.hasNext()) {
            String store = stores.next();
            if (LOCAL_PAYPAL_BACKUP.equals(store)) {
                restored &= new PayPalCredentialStore(context)
                        .restoreEncryptedLocalSnapshot(backup.optJSONObject(store));
                continue;
            }
            JSONObject values = backup.optJSONObject(store);
            if (values == null) continue;
            SharedPreferences.Editor editor = preferences(store).edit();
            Iterator<String> keys = values.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                JSONObject item = values.optJSONObject(key);
                if (item == null || !item.optBoolean("present")) editor.remove(key);
                else applyEncoded(editor, key, item);
            }
            restored &= editor.commit();
        }
        return restored;
    }

    private void recoverInterruptedActivation() {
        String raw = state.getString(KEY_JOURNAL, null);
        if (raw == null) return;
        try {
            JSONObject journal = new JSONObject(raw);
            JSONObject backup = journal.optJSONObject("backup");
            if (backup == null || !restore(backup)) return;
        } catch (Exception ignored) { return; }
        clearActiveAssets();
        SubHubPackLocks.clear(context);
        LockedSettings.clear();
        state.edit().remove(KEY_JOURNAL).remove(KEY_ACTIVE_ID).remove(KEY_ACTIVE_BACKUP)
                .remove(KEY_ACTIVE_SECTIONS).commit();
    }

    private void synchronizeLegacyLocks(Set<String> groups) {
        Set<String> keys = new LinkedHashSet<>();
        if (groups.contains(SubHubPackSchema.CENSOR)) {
            keys.addAll(SubHubPackSchema.keysFor(SubHubPackSchema.CENSOR));
            keys.add(CustomImageManager.PREFS_KEY);
        }
        LockedSettings.set(keys);
    }

    private void installAssets(SubHubPack pack) throws IOException {
        clearActiveAssets();
        File censor = new File(activeAssets, "censor");
        File popup = new File(activeAssets, "popup");
        ensureDirectory(censor);
        ensureDirectory(popup);
        int censorCount = 0;
        int popupCount = 0;
        for (Map.Entry<String, byte[]> asset : pack.getAssets().entrySet()) {
            File destination = null;
            if (asset.getKey().startsWith("assets/censor/")) {
                destination = new File(censor, "image-" + censorCount++ + ".png");
            } else if (asset.getKey().startsWith("assets/popup/")) {
                destination = new File(popup, "image-" + popupCount++ + ".png");
            }
            if (destination != null) try (FileOutputStream output = new FileOutputStream(destination)) {
                output.write(asset.getValue());
            }
        }
        SharedPreferences.Editor editor = preferences(SettingsRepository.PREFERENCES_NAME).edit();
        if (censorCount > 0) editor.putString(CustomImageManager.PACK_DIR_KEY, censor.getAbsolutePath());
        else editor.remove(CustomImageManager.PACK_DIR_KEY);
        if (popupCount > 0) editor.putString(PopupStormSettings.K_PACK_DIR, popup.getAbsolutePath());
        else editor.remove(PopupStormSettings.K_PACK_DIR);
        editor.putLong(CustomImageManager.REVISION_KEY, System.currentTimeMillis()).commit();
    }

    private void clearActiveAssets() {
        deleteTree(activeAssets);
        ensureDirectory(activeAssets);
        preferences(SettingsRepository.PREFERENCES_NAME).edit()
                .remove(CustomImageManager.PACK_DIR_KEY).remove(PopupStormSettings.K_PACK_DIR)
                .putLong(CustomImageManager.REVISION_KEY, System.currentTimeMillis()).commit();
    }

    private Set<String> sanitizeSelected(SubHubPack pack, Set<String> requested) {
        Set<String> selected = new LinkedHashSet<>(requested == null
                ? pack.getIncludedSections() : requested);
        selected.retainAll(pack.getIncludedSections());
        selected.retainAll(SubHubPackSchema.SECTIONS);
        return selected;
    }

    private List<Record> list(File directory, boolean draft) {
        List<Record> result = new ArrayList<>();
        File[] files = directory.listFiles(file -> file.isFile()
                && file.getName().endsWith(SubHubPackArchive.EXTENSION));
        if (files != null) for (File file : files) {
            SubHubPack pack = read(file);
            if (pack != null) result.add(new Record(pack, draft,
                    pack.getId().equals(activePackId())));
        }
        result.sort(Comparator.comparingLong((Record value) -> value.pack.getUpdatedAt()).reversed());
        return Collections.unmodifiableList(result);
    }

    private SubHubPack read(File file) {
        if (file == null || !file.isFile()) return null;
        try (FileInputStream input = new FileInputStream(file)) {
            return SubHubPackArchive.read(input);
        } catch (Exception ignored) { return null; }
    }

    private void write(SubHubPack pack, File file) throws IOException {
        ensureDirectory(file.getParentFile());
        File temporary = new File(file.getParentFile(), file.getName() + ".tmp");
        try (FileOutputStream output = new FileOutputStream(temporary)) {
            SubHubPackArchive.write(pack, output);
        }
        if (file.exists() && !file.delete()) throw new IOException("Could not replace pack");
        if (!temporary.renameTo(file)) throw new IOException("Could not finalize pack");
    }

    private SharedPreferences preferences(String name) {
        return context.getSharedPreferences(name, Context.MODE_PRIVATE);
    }

    private String deviceIdentifier() {
        String existing = state.getString(KEY_DEVICE_ID, null);
        if (existing != null && !existing.isBlank()) return existing;
        String created = UUID.randomUUID().toString();
        state.edit().putString(KEY_DEVICE_ID, created).commit();
        return created;
    }

    private static boolean isCompatible(SubHubPack pack) {
        try {
            return SemanticVersion.parse(BuildConfig.VERSION_NAME).compareTo(
                    SemanticVersion.parse(pack.getMinimumSubHubVersion())) >= 0;
        } catch (IllegalArgumentException error) {
            return false;
        }
    }

    private static File fileFor(File directory, String id) {
        String safe = id == null ? "invalid" : id.replaceAll("[^A-Za-z0-9-]", "");
        return new File(directory, safe + SubHubPackArchive.EXTENSION);
    }

    private static void applyJson(SharedPreferences.Editor editor, String key, Object value) {
        if (value == null || value == JSONObject.NULL) { editor.remove(key); return; }
        if (STRING_SET_KEYS.contains(key) || value instanceof JSONArray) {
            Set<String> values = new LinkedHashSet<>();
            JSONArray array = value instanceof JSONArray ? (JSONArray) value : new JSONArray();
            for (int index = 0; index < array.length(); index++) {
                String item = array.optString(index, "");
                if (!item.isBlank()) values.add(item);
            }
            editor.putStringSet(key, values);
        } else if (LONG_KEYS.contains(key)) editor.putLong(key, ((Number) value).longValue());
        else if (FLOAT_KEYS.contains(key)) editor.putFloat(key, ((Number) value).floatValue());
        else if (value instanceof Boolean) editor.putBoolean(key, (Boolean) value);
        else if (value instanceof Number) editor.putInt(key, ((Number) value).intValue());
        else editor.putString(key, String.valueOf(value));
    }

    private static void encode(JSONObject target, Object value) throws Exception {
        if (value instanceof Boolean) { target.put("type", "boolean"); target.put("value", value); }
        else if (value instanceof Integer) { target.put("type", "int"); target.put("value", value); }
        else if (value instanceof Long) { target.put("type", "long"); target.put("value", value); }
        else if (value instanceof Float || value instanceof Double) {
            target.put("type", "float"); target.put("value", ((Number) value).doubleValue());
        } else if (value instanceof Set<?>) {
            target.put("type", "set"); target.put("value", new JSONArray((Set<?>) value));
        } else { target.put("type", "string"); target.put("value", String.valueOf(value)); }
    }

    private static void applyEncoded(SharedPreferences.Editor editor, String key, JSONObject item) {
        String type = item.optString("type", "string");
        switch (type) {
            case "boolean": editor.putBoolean(key, item.optBoolean("value")); break;
            case "int": editor.putInt(key, item.optInt("value")); break;
            case "long": editor.putLong(key, item.optLong("value")); break;
            case "float": editor.putFloat(key, (float) item.optDouble("value")); break;
            case "set":
                Set<String> values = new LinkedHashSet<>();
                JSONArray array = item.optJSONArray("value");
                if (array != null) for (int index = 0; index < array.length(); index++) {
                    values.add(array.optString(index));
                }
                editor.putStringSet(key, values);
                break;
            default: editor.putString(key, item.optString("value"));
        }
    }

    private static boolean valuesEqual(Object before, Object proposed) {
        if (before == null && (proposed == null || proposed == JSONObject.NULL)) return true;
        if (before instanceof Set<?> && proposed instanceof JSONArray) {
            Set<String> after = new LinkedHashSet<>();
            JSONArray array = (JSONArray) proposed;
            for (int index = 0; index < array.length(); index++) after.add(array.optString(index));
            return before.equals(after);
        }
        if (before instanceof Number && proposed instanceof Number) {
            return Double.compare(((Number) before).doubleValue(),
                    ((Number) proposed).doubleValue()) == 0;
        }
        return before != null && before.equals(proposed);
    }

    private static String humanize(String key) {
        String value = key.replace("popup_storm_", "").replace("subliminal_", "")
                .replace("app_timer_", "").replace("rule_enabled_", "")
                .replace("rule_cents_", "").replace('_', ' ');
        return value.isBlank() ? key : Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    private static String title(String section) {
        return Character.toUpperCase(section.charAt(0)) + section.substring(1);
    }

    private static String durationLabel(long millis) {
        if (millis == -1L) return "Permanent";
        if (millis == 3_600_000L) return "1 hour";
        if (millis == 86_400_000L) return "24 hours";
        if (millis == 604_800_000L) return "7 days";
        if (millis == 2_592_000_000L) return "30 days";
        return "None";
    }

    private static byte[] readBounded(InputStream input, long maximum) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PackInputLimiter.copy(input, output, maximum, "Pack image");
        return output.toByteArray();
    }

    private static void ensureDirectory(File directory) {
        if (directory != null && !directory.exists() && !directory.mkdirs()) {
            throw new IllegalStateException("Could not create Studio storage");
        }
    }

    private static boolean deleteFile(File file) { return !file.exists() || file.delete(); }

    private static void deleteTree(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteTree(child);
        }
        if (!file.delete()) file.deleteOnExit();
    }

    public static final class Record {
        public final SubHubPack pack;
        public final boolean draft;
        public final boolean active;
        Record(SubHubPack pack, boolean draft, boolean active) {
            this.pack = pack;
            this.draft = draft;
            this.active = active;
        }
    }
}
