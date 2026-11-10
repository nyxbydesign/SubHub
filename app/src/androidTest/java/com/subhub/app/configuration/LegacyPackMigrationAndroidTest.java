package com.subhub.app.configuration;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.system.Os;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.capture.CustomImageManager;
import com.subhub.app.penance.PayPalCredentialStore;
import com.subhub.app.penance.PayPalEnvironment;
import com.subhub.app.penance.PenanceManager;
import com.subhub.app.popup.PopupStormSettings;
import com.subhub.app.settings.SettingsRepository;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@RunWith(AndroidJUnit4.class)
public final class LegacyPackMigrationAndroidTest {
    private Context target, context;
    private String prefix;
    private File root;
    private final Set<String> preferences = new HashSet<>();

    @Before public void setup() throws Exception {
        target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        prefix = "configuration-upgrade-" + UUID.randomUUID();
        root = new File(target.getCacheDir(), prefix);
        assertTrue(root.mkdirs());
        context = new ContextWrapper(target) {
            @Override public Context getApplicationContext() { return this; }
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                preferences.add(name);
                return target.getSharedPreferences(prefix + name, mode);
            }
            @Override public File getFilesDir() {
                File directory = new File(root, "files");
                if (!directory.mkdirs() && !directory.isDirectory()) throw new IllegalStateException("Fixture directory unavailable");
                return directory;
            }
        };
    }

    @After public void cleanup() throws Exception {
        for (String name : new HashSet<>(preferences)) target.deleteSharedPreferences(prefix + name);
        if (root != null && root.exists()) {
            assertTrue(root.getCanonicalPath().startsWith(target.getCacheDir().getCanonicalPath() + File.separator));
            try (java.util.stream.Stream<Path> paths = Files.walk(root.toPath())) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new)) Files.delete(path);
            }
        }
    }

    @Test public void completedActivationKeepsLiveSettingsCredentialsAndBothArtworkSelections() throws Exception {
        PayPalCredentialStore credentials = new PayPalCredentialStore(context);
        assertTrue(credentials.save(PayPalEnvironment.LIVE, "original-id", "original-secret"));
        JSONObject originalCredentials = credentials.encryptedLocalSnapshot();
        assertTrue(credentials.save(PayPalEnvironment.SANDBOX, "current-id", "current-secret"));
        Map<String, ?> currentCredentials = new HashMap<>(prefs(PayPalCredentialStore.PREFS_NAME).getAll());
        File censor = image("subhub_studio/active-assets/censor", "image-0.png", Color.GREEN);
        File popup = image("subhub_studio/active-assets/popup", "image-0.png", Color.MAGENTA);
        JSONObject beforePack = new JSONObject().put(SettingsRepository.PREFERENCES_NAME,
                new JSONObject().put("show_border", typed("boolean", true)))
                .put("_local_encrypted_paypal", originalCredentials);
        assertTrue(main().edit().putBoolean("show_border", false).putBoolean("app_mode_armed", true)
                .putLong("commitment_end", 123456789L).putString("custom_phrases_fixture", "Current configuration")
                .putString(CustomImageManager.PACK_DIR_KEY, censor.getParent())
                .putString(PopupStormSettings.K_PACK_DIR, popup.getParent())
                .putLong(CustomImageManager.REVISION_KEY, 7).commit());
        assertTrue(wallet().edit().putInt("rule_new_detection_cents", 123).putString("wallet_currency", "EUR")
                .putLong("unpaid_ledger_fixture", 999L).commit());
        Map<String, ?> currentMain = withoutMigrationFields(main().getAll());
        Map<String, ?> currentWallet = new HashMap<>(wallet().getAll());
        assertTrue(legacy().edit().putString("active_pack_id", "completed-fixture")
                .putString("active_pack_backup", beforePack.toString())
                .putStringSet("active_sections", Set.of("censor", "popup", "wallet"))
                .putString("creator_device_id", "old-creator").putString("unrelated_fixture", "keep").commit());
        assertFalse(LegacyPackMigration.isReady(context));

        assertTrue(LegacyPackMigration.recoverAndMigrate(context));
        assertTrue(LegacyPackMigration.isReady(context));
        assertEquals(currentMain, withoutMigrationFields(main().getAll()));
        assertEquals(currentWallet, wallet().getAll());
        assertEquals(currentCredentials, prefs(PayPalCredentialStore.PREFS_NAME).getAll());
        assertEquals("current-secret", credentials.load().secret());
        assertEquals(Map.of("unrelated_fixture", "keep"), legacy().getAll());
        assertMigrated(censor, CustomImageManager.PACK_DIR_KEY);
        assertMigrated(popup, PopupStormSettings.K_PACK_DIR);
        List<Bitmap> rendered = new CustomImageManager(context).loadEnabledBitmaps(16);
        try {
            assertEquals(1, rendered.size());
            assertEquals(Color.GREEN, rendered.get(0).getPixel(0, 0));
        } finally {
            for (Bitmap bitmap : rendered) bitmap.recycle();
        }
        Map<String, ?> migrated = new HashMap<>(main().getAll());
        assertTrue(LegacyPackMigration.recoverAndMigrate(context));
        assertEquals(migrated, main().getAll());
    }

    @Test public void pendingActivationRestoresAllPreferenceTypesAndEncryptedLocalCredentials() throws Exception {
        PayPalCredentialStore credentials = new PayPalCredentialStore(context);
        assertTrue(credentials.save(PayPalEnvironment.LIVE, "original-id", "original-secret"));
        JSONObject encrypted = credentials.encryptedLocalSnapshot();
        Map<String, ?> originalCredentials = new HashMap<>(prefs(PayPalCredentialStore.PREFS_NAME).getAll());
        assertTrue(credentials.save(PayPalEnvironment.SANDBOX, "interrupted-id", "interrupted-secret"));
        File interruptedImage = image("subhub_studio/active-assets/censor", "image-0.png", Color.RED);
        JSONObject values = new JSONObject().put("show_border", typed("boolean", false))
                .put("censor_intensity", typed("int", 37)).put("fixture_long", typed("long", 9000000123L))
                .put("fixture_float", typed("float", .25f)).put("fixture_text", typed("string", "original"))
                .put("fixture_set", typed("set", new JSONArray().put("First").put("Second")))
                .put("temporary_fixture", absent()).put(CustomImageManager.PACK_DIR_KEY, absent());
        JSONObject backup = new JSONObject().put(SettingsRepository.PREFERENCES_NAME, values)
                .put(PenanceManager.PREFS_NAME, new JSONObject().put("rule_new_detection_cents", typed("int", 123)))
                .put("_local_encrypted_paypal", encrypted);
        assertTrue(main().edit().putBoolean("show_border", true).putInt("censor_intensity", 88)
                .putLong("fixture_long", 1).putFloat("fixture_float", .75f).putString("fixture_text", "partial")
                .putStringSet("fixture_set", Set.of("Partial")).putString("temporary_fixture", "partial")
                .putString(CustomImageManager.PACK_DIR_KEY, interruptedImage.getParent())
                .putString("unrelated_fixture", "keep").commit());
        assertTrue(wallet().edit().putInt("rule_new_detection_cents", 999).putLong("ledger_fixture", 555L).commit());
        pending(backup);

        assertTrue(LegacyPackMigration.recoverAndMigrate(context));
        assertTrue(LegacyPackMigration.isReady(context));
        assertFalse(main().getBoolean("show_border", true));
        assertEquals(37, main().getInt("censor_intensity", 0));
        assertEquals(9000000123L, main().getLong("fixture_long", 0));
        assertEquals(.25f, main().getFloat("fixture_float", 0), 0);
        assertEquals("original", main().getString("fixture_text", ""));
        assertEquals(Set.of("First", "Second"), main().getStringSet("fixture_set", Set.of()));
        assertFalse(main().contains("temporary_fixture"));
        assertFalse(main().contains(CustomImageManager.PACK_DIR_KEY));
        assertEquals("keep", main().getString("unrelated_fixture", ""));
        assertEquals(123, wallet().getInt("rule_new_detection_cents", 0));
        assertEquals(555L, wallet().getLong("ledger_fixture", 0));
        assertEquals(originalCredentials, prefs(PayPalCredentialStore.PREFS_NAME).getAll());
        assertEquals("original-secret", credentials.load().secret());
        assertTrue(legacy().getAll().isEmpty());
        assertTrue(interruptedImage.isFile());
    }

    @Test public void malformedPendingJournalBlocksReadinessWithoutPartialRestore() throws Exception {
        assertTrue(main().edit().putBoolean("show_border", true).commit());
        assertTrue(wallet().edit().putInt("rule_new_detection_cents", 999).commit());
        JSONObject valid = new JSONObject().put("show_border", typed("boolean", false));
        List<JSONObject> malformed = new ArrayList<>();
        malformed.add(new JSONObject().put(SettingsRepository.PREFERENCES_NAME, valid)
                .put(PenanceManager.PREFS_NAME, new JSONObject().put("rule_new_detection_cents", typed("int", "123"))));
        malformed.add(new JSONObject().put(SettingsRepository.PREFERENCES_NAME, valid)
                .put("_local_encrypted_paypal", new JSONObject().put("unknown-key", "synthetic")));
        malformed.add(new JSONObject().put(SettingsRepository.PREFERENCES_NAME,
                new JSONObject().put("show_border", typed("boolean", "false"))));
        malformed.add(new JSONObject().put(SettingsRepository.PREFERENCES_NAME,
                new JSONObject().put("fixture_long", typed("long", "9223372036854775808"))));
        malformed.add(new JSONObject());
        for (JSONObject backup : malformed) {
            pending(backup);
            String journal = legacy().getString("activation_journal", "");
            Map<String, ?> before = new HashMap<>(main().getAll());
            assertFalse(LegacyPackMigration.recoverAndMigrate(context));
            assertFalse(LegacyPackMigration.isReady(context));
            assertEquals(before, main().getAll());
            assertEquals(999, wallet().getInt("rule_new_detection_cents", 0));
            assertEquals(journal, legacy().getString("activation_journal", ""));
        }
    }

    @Test public void malformedJournalEnvelopeAndSectionsAreRetained() throws Exception {
        for (String raw : new String[] {"not-json", "{}", "{\"pending\":\"true\",\"backup\":{}}",
                "{\"pending\":true,\"sections\":[\"unknown\"],\"backup\":{}}",
                "{\"pending\":true,\"sections\":[],\"backup\":{}}"}) {
            assertTrue(legacy().edit().putString("activation_journal", raw).commit());
            assertFalse(LegacyPackMigration.recoverAndMigrate(context));
            assertFalse(LegacyPackMigration.isReady(context));
            assertEquals(raw, legacy().getString("activation_journal", ""));
            assertFalse(main().contains(LegacyPackMigration.MIGRATION_KEY));
        }
    }

    @Test public void failedPointerCommitRetainsSourceAndRetryUsesTheExactCurrentSelection() throws Exception {
        File first = image("subhub_studio/active-assets/censor", "image-0.png", Color.BLUE);
        File second = image("subhub_studio/active-assets/censor", "image-1.png", Color.RED);
        assertTrue(main().edit().putString(CustomImageManager.PACK_DIR_KEY, first.getParent()).commit());
        assertTrue(legacy().edit().putString("active_pack_id", "completed").putString("active_pack_backup", "unused").commit());
        assertFalse(LegacyPackMigration.recoverAndMigrate(context,
                (name, editor) -> !SettingsRepository.PREFERENCES_NAME.equals(name) && editor.commit()));
        assertFalse(LegacyPackMigration.isReady(context));
        assertEquals(first.getParent(), main().getString(CustomImageManager.PACK_DIR_KEY, ""));
        assertTrue(first.isFile());
        assertTrue(second.isFile());
        assertFalse(main().contains(LegacyPackMigration.MIGRATION_KEY));
        assertTrue(second.delete());

        assertTrue(LegacyPackMigration.recoverAndMigrate(context));
        File migrated = assertMigrated(first, CustomImageManager.PACK_DIR_KEY).getParentFile();
        File[] images = migrated.listFiles();
        assertNotNull(images);
        assertEquals(1, images.length);
        assertEquals("image-0.png", images[0].getName());
    }

    @Test public void failedLegacyRetirementRemainsBlockedAndRetryPreservesCommittedPointers() throws Exception {
        File image = image("subhub_studio/active-assets/censor", "image-0.png", Color.CYAN);
        assertTrue(main().edit().putString(CustomImageManager.PACK_DIR_KEY, image.getParent()).commit());
        assertTrue(legacy().edit().putString("active_pack_id", "completed").commit());
        assertFalse(LegacyPackMigration.recoverAndMigrate(context,
                (name, editor) -> !LegacyPackMigration.STATE_PREFS.equals(name) && editor.commit()));
        assertFalse(LegacyPackMigration.isReady(context));
        String migrated = main().getString(CustomImageManager.PACK_DIR_KEY, "");
        assertTrue(migrated.contains("configuration-artwork"));
        assertTrue(legacy().contains("active_pack_id"));
        assertTrue(LegacyPackMigration.recoverAndMigrate(context));
        assertTrue(LegacyPackMigration.isReady(context));
        assertEquals(migrated, main().getString(CustomImageManager.PACK_DIR_KEY, ""));
        assertMigrated(image, CustomImageManager.PACK_DIR_KEY);
    }

    @Test public void failedMainCommitCannotAdmitAnInMemoryMarkerOnRetry() throws Exception {
        assertFalse(LegacyPackMigration.recoverAndMigrate(context, (name, editor) -> {
            boolean saved = editor.commit();
            return saved && !SettingsRepository.PREFERENCES_NAME.equals(name);
        }));
        assertFalse(LegacyPackMigration.isReady(context));
        assertFalse(main().contains(LegacyPackMigration.MIGRATION_KEY));
        int[] attempted = {0};
        assertFalse(LegacyPackMigration.recoverAndMigrate(context, (name, editor) -> {
            if (SettingsRepository.PREFERENCES_NAME.equals(name)) { attempted[0]++; return false; }
            return editor.commit();
        }));
        assertEquals(1, attempted[0]);
        assertFalse(LegacyPackMigration.isReady(context));
        assertTrue(LegacyPackMigration.recoverAndMigrate(context));
        assertTrue(LegacyPackMigration.isReady(context));
    }

    @Test public void failedCompletedRetirementRestoresItsInMemoryRetryState() throws Exception {
        assertTrue(legacy().edit().putString("active_pack_id", "completed")
                .putString("active_pack_backup", "unused").putStringSet("active_sections", Set.of("censor")).commit());
        assertFalse(LegacyPackMigration.recoverAndMigrate(context, (name, editor) -> {
            boolean saved = editor.commit();
            return saved && !LegacyPackMigration.STATE_PREFS.equals(name);
        }));
        assertFalse(LegacyPackMigration.isReady(context));
        assertEquals("completed", legacy().getString("active_pack_id", ""));
        assertEquals("unused", legacy().getString("active_pack_backup", ""));
        assertEquals(Set.of("censor"), legacy().getStringSet("active_sections", Set.of()));
        assertTrue(LegacyPackMigration.recoverAndMigrate(context));
        assertTrue(LegacyPackMigration.isReady(context));
        assertTrue(legacy().getAll().isEmpty());
    }

    @Test public void failedPendingRetirementRetainsJournalEvenAfterAnInMemoryCommit() throws Exception {
        assertTrue(main().edit().putBoolean("show_border", true).commit());
        pending(new JSONObject().put(SettingsRepository.PREFERENCES_NAME,
                new JSONObject().put("show_border", typed("boolean", false))));
        String journal = legacy().getString("activation_journal", "");
        assertFalse(LegacyPackMigration.recoverAndMigrate(context, (name, editor) -> {
            boolean saved = editor.commit();
            return saved && !LegacyPackMigration.STATE_PREFS.equals(name);
        }));
        assertFalse(LegacyPackMigration.isReady(context));
        assertEquals(journal, legacy().getString("activation_journal", ""));
        assertFalse(main().getBoolean("show_border", true));
        assertTrue(LegacyPackMigration.recoverAndMigrate(context));
        assertTrue(LegacyPackMigration.isReady(context));
    }

    @Test public void remoteAndUserArtworkPointersRemainUnchanged() throws Exception {
        File remote = image("remote-configuration/assets/command/censor", "image.png", Color.YELLOW);
        File user = image("custom-popup-artwork", "image.png", Color.WHITE);
        assertTrue(main().edit().putString(CustomImageManager.PACK_DIR_KEY, remote.getParent())
                .putString(PopupStormSettings.K_PACK_DIR, user.getParent()).commit());
        assertTrue(legacy().edit().putString("active_pack_id", "old-metadata").commit());
        byte[] remoteBytes = Files.readAllBytes(remote.toPath());
        byte[] userBytes = Files.readAllBytes(user.toPath());
        assertTrue(LegacyPackMigration.recoverAndMigrate(context));
        assertEquals(remote.getParent(), main().getString(CustomImageManager.PACK_DIR_KEY, ""));
        assertEquals(user.getParent(), main().getString(PopupStormSettings.K_PACK_DIR, ""));
        assertArrayEquals(remoteBytes, Files.readAllBytes(remote.toPath()));
        assertArrayEquals(userBytes, Files.readAllBytes(user.toPath()));
        assertFalse(new File(context.getFilesDir(), "configuration-artwork").exists());
    }

    @Test public void artworkSymlinkEscapingOwnedSourceBlocksMigration() throws Exception {
        File external = image("unrelated-artwork", "image.png", Color.BLACK);
        File source = new File(context.getFilesDir(), "subhub_studio/active-assets/censor");
        assertTrue(source.mkdirs());
        File link = new File(source, "image-0.png");
        Os.symlink(external.getAbsolutePath(), link.getAbsolutePath());
        assertTrue(main().edit().putString(CustomImageManager.PACK_DIR_KEY, source.getAbsolutePath()).commit());
        assertTrue(legacy().edit().putString("active_pack_id", "completed").commit());
        byte[] bytes = Files.readAllBytes(external.toPath());
        assertFalse(LegacyPackMigration.recoverAndMigrate(context));
        assertFalse(LegacyPackMigration.isReady(context));
        assertEquals(source.getAbsolutePath(), main().getString(CustomImageManager.PACK_DIR_KEY, ""));
        assertArrayEquals(bytes, Files.readAllBytes(external.toPath()));
        assertTrue(legacy().contains("active_pack_id"));
    }

    private SharedPreferences prefs(String name) { return context.getSharedPreferences(name, Context.MODE_PRIVATE); }
    private SharedPreferences main() { return prefs(SettingsRepository.PREFERENCES_NAME); }
    private SharedPreferences wallet() { return prefs(PenanceManager.PREFS_NAME); }
    private SharedPreferences legacy() { return prefs(LegacyPackMigration.STATE_PREFS); }

    private JSONObject typed(String type, Object value) throws Exception {
        return new JSONObject().put("present", true).put("type", type).put("value", value);
    }

    private JSONObject absent() throws Exception { return new JSONObject().put("present", false); }

    private void pending(JSONObject backup) throws Exception {
        assertTrue(legacy().edit().putString("activation_journal", new JSONObject().put("pending", true)
                .put("sections", new JSONArray().put("censor").put("wallet")).put("backup", backup).toString()).commit());
    }

    private File image(String directory, String name, int color) throws Exception {
        File parent = new File(context.getFilesDir(), directory);
        assertTrue(parent.mkdirs() || parent.isDirectory());
        File image = new File(parent, name);
        Bitmap bitmap = Bitmap.createBitmap(3, 3, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(color);
        try (FileOutputStream output = new FileOutputStream(image)) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
            output.getFD().sync();
        } finally {
            bitmap.recycle();
        }
        return image;
    }

    private File assertMigrated(File source, String key) throws Exception {
        String pointer = main().getString(key, "");
        assertTrue(pointer.contains("configuration-artwork"));
        File result = new File(pointer, source.getName());
        assertTrue(result.isFile());
        assertArrayEquals(Files.readAllBytes(source.toPath()), Files.readAllBytes(result.toPath()));
        assertTrue(source.isFile());
        return result;
    }

    private Map<String, ?> withoutMigrationFields(Map<String, ?> values) {
        Map<String, Object> copy = new HashMap<>(values);
        for (String key : Set.of(CustomImageManager.PACK_DIR_KEY, PopupStormSettings.K_PACK_DIR,
                CustomImageManager.REVISION_KEY, LegacyPackMigration.MIGRATION_KEY)) copy.remove(key);
        return copy;
    }
}
