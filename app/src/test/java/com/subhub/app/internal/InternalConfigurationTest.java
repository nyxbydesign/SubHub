package com.subhub.app.internal;

import android.content.SharedPreferences;
import com.subhub.app.configuration.ConfigurationSchema;
import com.subhub.app.people.PersonId;
import com.subhub.app.remote.GagContractFields;
import com.subhub.app.runtime.ActiveRuntimeCoordinator;
import com.subhub.app.runtime.RuntimeAdmissionAuthority;
import com.subhub.app.runtime.RuntimeConfiguration;
import com.subhub.app.runtime.RuntimeMutex;
import com.subhub.app.runtime.RuntimeOrigin;
import com.subhub.app.runtime.RuntimeOwnerStore;
import com.subhub.app.runtime.RuntimeTarget;
import com.subhub.app.settings.SettingsRepository;
import com.subhub.app.stats.AchievementManager;
import org.junit.Test;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

/** Canonical JUnit uses the real configuration catalog and JSON dependency, not substitute schemas. */
public final class InternalConfigurationTest {
    @Test public void freshConfigurationUsesCatalogDefaultsAndSeparateTarget() {
        Fixture fixture = new Fixture();
        assertFalse(new PersonId(fixture.storage.read().targetId).isLocal());
        assertEquals(0, fixture.config.read().configurationRevision);
        assertFalse(fixture.config.read().visible);
        assertEquals(Set.of(), fixture.config.read().configuration().get("apps").get("app_included_packages_v1"));
    }

    @Test public void applyIsResponsiveDraftAndFlushIsDurableLiveRevision() {
        Fixture f = new Fixture(); InternalConfigurationStore.EditorDraft draft = f.config.edit(f.config.read());
        SharedPreferences preferences = draft.preferences(SettingsRepository.PREFERENCES_NAME);
        AtomicInteger drafts = new AtomicInteger(), durable = new AtomicInteger();
        preferences.registerOnSharedPreferenceChangeListener((p, key) -> drafts.incrementAndGet());
        f.storage.addListener((before, current) -> durable.incrementAndGet());
        preferences.edit().putInt("censor_intensity", 72).apply();
        assertEquals(1, drafts.get()); assertEquals(0, durable.get());
        assertEquals(50, f.config.read().configuration().get("censor").get("censor_intensity"));
        assertTrue(draft.hasUnflushedChanges());
        InternalSubRecord saved = f.config.flush(draft);
        assertEquals(1, saved.configurationRevision); assertEquals(1, durable.get());
        assertEquals(72, saved.configuration().get("censor").get("censor_intensity"));
        assertFalse(draft.hasUnflushedChanges());
    }

    @Test public void legacyConfigurationCanPersistOnlyValidatedGagRules() throws Exception {
        Fixture f = new Fixture();
        InternalSubRecord legacy = f.config.read();
        InternalConfigurationStore.EditorDraft draft = f.config.edit(legacy);
        assertFalse(legacy.configuration().containsKey(GagContractFields.SECTION));
        assertFalse(draft.gagRules().getBoolean("enabled"));
        assertFalse(draft.hasUnflushedChanges());
        assertThrows(IllegalArgumentException.class,
                () -> draft.editGagRules(GagContractFields.defaults().put("keyboard_selected", true)));
        assertFalse(draft.hasUnflushedChanges());

        draft.editGagRules(GagContractFields.defaults().put("enabled", true).put("replacements", "fixture"));
        assertTrue(draft.hasUnflushedChanges());
        assertEquals(legacy, f.config.read());
        InternalSubRecord saved = f.config.flush(draft);
        assertEquals(legacy.configurationRevision + 1, saved.configurationRevision);
        assertEquals("fixture", f.config.edit(f.config.read()).gagRules().getString("replacements"));
        assertFalse(f.config.edit(saved).preferences(SettingsRepository.PREFERENCES_NAME).getAll().containsKey("replacements"));
        assertFalse(draft.hasUnflushedChanges());
    }

    @Test public void immutableRuntimeViewDoesNotBorrowLaterDraftOrDeviceState() {
        Fixture f = new Fixture(); InternalSubRecord initial = f.config.read();
        SharedPreferences frozen = f.config.snapshotPreferences(initial, SettingsRepository.PREFERENCES_NAME);
        InternalConfigurationStore.EditorDraft draft = f.config.edit(initial);
        draft.preferences(SettingsRepository.PREFERENCES_NAME).edit().putInt("censor_intensity", 77).apply();
        f.config.flush(draft);
        assertEquals(50, frozen.getInt("censor_intensity", 0));
        var popup = com.subhub.app.popup.PopupStormSettings.forPreferences(frozen);
        assertTrue(popup.getFolders().isEmpty());
        assertEquals("", popup.getPackImageDir());
        assertTrue(com.subhub.app.popup.PopupStormSettings.forPreferences(draft.preferences(
                SettingsRepository.PREFERENCES_NAME)).getFolders().isEmpty());
        assertFalse(frozen.contains("controller_pin_hash"));
        assertEquals("fallback", frozen.getString("controller_pin_hash", "fallback"));
        assertThrows(UnsupportedOperationException.class, frozen::edit);
    }

    @Test public void projectionAchievementsUseOnlyCapturedOwnedCapabilities() {
        Fixture f = new Fixture();
        InternalSubRecord visible = f.storage.setVisible(f.config.read(), true, 1L);
        InternalConfigurationStore.EditorDraft draft = f.config.edit(visible);
        draft.preferences(SettingsRepository.PREFERENCES_NAME).edit()
                .putStringSet("app_included_packages_v1", Set.of("com.example.reader"))
                .putStringSet("app_include_all_packages_v1", Set.of("com.example.reader", "com.example.music"))
                .putBoolean("app_include_all_v1", true).putBoolean("app_timer_per_app_enabled", true)
                .putBoolean("app_timer_total_enabled", true).putBoolean("text_smut_enabled", true).apply();
        draft.preferences(ConfigurationSchema.preferenceStore(ConfigurationSchema.WALLET)).edit()
                .putBoolean("rule_new_detection_enabled", true).putBoolean("paid_pause_enabled", true).apply();
        InternalSubRecord captured = f.config.flush(draft);
        RuntimeTarget target = RuntimeTarget.internal(captured.targetId);
        RuntimeMutex mutex = new RuntimeMutex(new Object());
        RuntimeAdmissionAuthority authority = new RuntimeAdmissionAuthority(mutex);
        RuntimeOwnerStore.Backend backend = new RuntimeOwnerStore.Backend() {
            private RuntimeOwnerStore.Record stored = RuntimeOwnerStore.Record.idle(0);
            @Override public RuntimeOwnerStore.Record read() { return stored; }
            @Override public RuntimeOwnerStore.Outcome compareAndSet(RuntimeOwnerStore.Record expected,
                    RuntimeOwnerStore.Record next) {
                if (!expected.equals(stored)) return RuntimeOwnerStore.Outcome.CONFLICT;
                stored = next;
                return RuntimeOwnerStore.Outcome.COMMITTED;
            }
        };
        ActiveRuntimeCoordinator coordinator = new ActiveRuntimeCoordinator(new RuntimeOwnerStore(mutex, backend),
                authority, () -> ActiveRuntimeCoordinator.Occupancy.CLEAR, UUID.randomUUID().toString());
        assertEquals(ActiveRuntimeCoordinator.Result.OK, coordinator.reconcileStartup());
        assertTrue(authority.completeAvailability(authority.beginAvailability(target), captured.generation,
                captured.configurationRevision, true));
        assertTrue(authority.completeAuthorization(authority.beginAuthorization(target, RuntimeOrigin.LOCAL_UI), 1, true));
        ActiveRuntimeCoordinator.Admission admitted = coordinator.reserve(authority.capture(target, RuntimeOrigin.LOCAL_UI),
                Set.of("projection"));
        assertEquals(ActiveRuntimeCoordinator.Result.OK, admitted.result);
        AchievementManager.OwnerFacts facts = AchievementManager.forInternalProjection(
                RuntimeConfiguration.internal(admitted.lease, f.config, captured));
        assertEquals(2L, facts.current("app_assignment_curator"));
        assertEquals(1L, facts.current("limits_dual_guard"));
        assertEquals(1L, facts.current("wallet_keeper"));
        assertEquals(0L, facts.current("paid_pause"));
        assertEquals(0L, facts.current("wallet_paid_10"));
        assertEquals(0L, facts.current("text_filter_enabled"));
        draft.preferences(SettingsRepository.PREFERENCES_NAME).edit().putBoolean("app_timer_total_enabled", false).apply();
        f.config.flush(draft);
        assertEquals(1L, facts.current("limits_dual_guard"));
    }

    @Test public void unsafeReadsAndWritesNeverFallBackToRealPreferences() {
        Fixture f = new Fixture(); InternalConfigurationStore.EditorDraft draft = f.config.edit(f.config.read());
        SharedPreferences prefs = draft.preferences(SettingsRepository.PREFERENCES_NAME);
        for (String key : Set.of("controller_pin_hash", "controller_pin_salt", "device_role_mode",
                "app_mode_armed", "commitment_ends_at", "hardcore_mode_enabled")) {
            assertThrows(SecurityException.class, () -> prefs.getString(key, "fallback"));
            assertThrows(SecurityException.class, () -> prefs.edit().putString(key, "forbidden"));
        }
        SharedPreferences wallet = draft.preferences(ConfigurationSchema.preferenceStore(ConfigurationSchema.WALLET));
        for (String key : Set.of("paypal_payment_link", "active_order_id", "events_v1", "total_paid_cents_v1"))
            assertThrows(SecurityException.class, () -> wallet.edit().putString(key, "forbidden"));
        assertThrows(SecurityException.class, () -> draft.preferences("subhub_profile_names"));
        assertEquals(0, f.config.read().revision);
    }

    @Test public void appSelectionAndDurationHaveExplicitIndependentSchemas() {
        Fixture f = new Fixture(); InternalConfigurationStore.EditorDraft draft = f.config.edit(f.config.read());
        draft.preferences(SettingsRepository.PREFERENCES_NAME).edit()
                .putStringSet("app_included_packages_v1", Set.of("com.example.reader"))
                .putStringSet("app_include_all_packages_v1", Set.of("com.example.reader", "com.example.music"))
                .putBoolean("app_include_all_v1", true).apply();
        draft.preferences(InternalConfigurationStore.DURATION_PREFERENCES).edit()
                .putLong("duration", -2L).putLong("minimum", 3_600_000L)
                .putLong("maximum", 7_200_000L).putBoolean("hidden", true).apply();
        InternalSubRecord saved = f.config.flush(draft);
        assertEquals(Set.of("com.example.reader"), saved.configuration().get("apps").get("app_included_packages_v1"));
        assertEquals(Set.of("com.example.reader", "com.example.music"), saved.configuration().get("apps").get("app_include_all_packages_v1"));
        assertEquals(-2L, saved.configuration().get("duration").get("duration"));
        assertThrows(IllegalArgumentException.class, () -> draft.preferences(InternalConfigurationStore.DURATION_PREFERENCES)
                .edit().putLong("minimum", 9_000_000L).apply());
        assertEquals(3_600_000L, draft.preferences(InternalConfigurationStore.DURATION_PREFERENCES).getLong("minimum", 0));
    }

    @Test public void perAppAllowancesUseNormalKeysAndRetainExcludedApps() {
        Fixture f = new Fixture(); InternalConfigurationStore.EditorDraft draft = f.config.edit(f.config.read());
        SharedPreferences prefs = draft.preferences(SettingsRepository.PREFERENCES_NAME);
        String reader = InternalConfigurationStore.ALLOWANCE_PREFIX + "com.example.reader";
        String music = InternalConfigurationStore.ALLOWANCE_PREFIX + "com.example.music";
        assertFalse(prefs.contains(reader)); assertEquals(30, prefs.getInt(reader, 30));
        AtomicInteger changes = new AtomicInteger(); prefs.registerOnSharedPreferenceChangeListener((p, key) -> changes.incrementAndGet());
        prefs.edit().putInt(reader, 45).putInt(music, 60).apply();
        prefs.edit().putStringSet("app_included_packages_v1", Set.of("com.example.reader")).apply();
        assertEquals(60, prefs.getInt(music, 30)); assertTrue(prefs.getAll().containsKey(music));
        InternalSubRecord saved = f.config.flush(draft);
        assertEquals(Map.of("com.example.reader", 45, "com.example.music", 60), saved.configuration().get("allowances"));
        assertThrows(IllegalArgumentException.class, () -> prefs.edit().putInt(reader, 0).apply());
        assertThrows(IllegalArgumentException.class, () -> prefs.edit().putInt(reader, 1441).apply());
        assertThrows(IllegalArgumentException.class, () -> prefs.edit().putInt(InternalConfigurationStore.ALLOWANCE_PREFIX + "../bad", 30));
        prefs.edit().remove(reader).apply(); assertFalse(prefs.contains(reader)); assertEquals(30, prefs.getInt(reader, 30));
        assertEquals(60, prefs.getInt(music, 30)); assertEquals(4, changes.get());
    }

    @Test public void internalWalletDisplayAndModernSchemaCannotEnablePaymentAccess() {
        Fixture f = new Fixture(); InternalConfigurationStore.EditorDraft draft = f.config.edit(f.config.read());
        SharedPreferences wallet = draft.preferences(ConfigurationSchema.preferenceStore(ConfigurationSchema.WALLET));
        assertEquals("EUR", wallet.getString("wallet_currency", ""));
        assertTrue(wallet.getBoolean("rule_participation_migrated_v1", false));
        wallet.edit().putString("wallet_currency", "USD").apply();
        assertThrows(IllegalArgumentException.class, () -> wallet.edit().putString("wallet_currency", "BAD").apply());
        assertThrows(IllegalArgumentException.class, () -> wallet.edit().putBoolean("rule_participation_migrated_v1", false).apply());
        assertEquals("USD", f.config.flush(draft).configuration().get("wallet_display").get("wallet_currency"));
        assertThrows(SecurityException.class, () -> wallet.getString("active_paypal_boundary", ""));
    }

    @Test public void savedLooksAndArtworkRemainInternalAndBounded() throws Exception {
        Fixture f = new Fixture(); InternalConfigurationStore.EditorDraft draft = f.config.edit(f.config.read());
        SharedPreferences presets = draft.preferences(InternalConfigurationStore.PRESET_PREFERENCES);
        com.subhub.app.settings.CensorLook look = new com.subhub.app.settings.CensorLook("owned", "My look", Map.of("censor_intensity", 60));
        String encoded = new org.json.JSONObject().put("version", 1).put("looks", new org.json.JSONArray().put(look.encode())).toString();
        presets.edit().putString("looks_v1", encoded).apply();
        String asset = "a".repeat(64), other = "b".repeat(64);
        draft.setArtwork(Set.of(asset), Set.of(other), Set.of(asset));
        InternalSubRecord saved = f.config.flush(draft);
        assertEquals(encoded, saved.configuration().get("presets").get("looks_v1"));
        assertEquals(Set.of(asset), saved.configuration().get("artwork").get("disabled_assets"));
        assertThrows(IllegalArgumentException.class, () -> draft.setArtwork(Set.of("content://not-owned"), Set.of(), Set.of()));
        assertThrows(IllegalArgumentException.class, () -> draft.setArtwork(Set.of(), Set.of(), Set.of(asset)));
        String injected = "{\"version\":1,\"looks\":[{\"id\":\"x\",\"name\":\"X\",\"settings\":{\"controller_pin_hash\":{\"type\":\"string\",\"value\":\"bad\"}}}]}";
        assertThrows(IllegalArgumentException.class, () -> presets.edit().putString("looks_v1", injected).apply());
        assertFalse(draft.preferences(SettingsRepository.PREFERENCES_NAME).getBoolean("popup_storm_photosensitivity_acknowledged", true));
    }

    @Test public void staleEditorAfterVisibilityGenerationChangeCannotSave() {
        Fixture f = new Fixture(); InternalSubRecord initial = f.config.read();
        InternalConfigurationStore.EditorDraft draft = f.config.edit(initial);
        draft.preferences(SettingsRepository.PREFERENCES_NAME).edit().putInt("censor_intensity", 71).apply();
        f.storage.setVisible(initial, true);
        assertThrows(InternalSubStore.PersistenceException.class, () -> f.config.flush(draft));
        assertEquals(0, f.config.read().configurationRevision);
        assertTrue(draft.hasUnflushedChanges());
    }

    @Test public void failedPersistenceDoesNotPublishOrAdvanceEditorBase() {
        Fixture f = new Fixture(); InternalSubRecord initial = f.config.read();
        InternalConfigurationStore.EditorDraft draft = f.config.edit(initial);
        draft.preferences(SettingsRepository.PREFERENCES_NAME).edit().putInt("censor_intensity", 71).apply();
        AtomicInteger acknowledged = new AtomicInteger(); f.storage.addListener((a, b) -> acknowledged.incrementAndGet());
        f.backend.outcome = InternalSubStore.Outcome.UNCERTAIN;
        assertThrows(InternalSubStore.PersistenceException.class, () -> f.config.flush(draft));
        assertEquals(initial, draft.acknowledgedBase()); assertEquals(0, acknowledged.get());
        assertThrows(InternalSubStore.PersistenceException.class, f.config::read);
    }

    @Test public void newerEditsDuringFlushRemainStagedAndListenersAreOutsideDraftLock() {
        Fixture f = new Fixture(); InternalConfigurationStore.EditorDraft draft = f.config.edit(f.config.read());
        SharedPreferences prefs = draft.preferences(SettingsRepository.PREFERENCES_NAME);
        prefs.edit().putInt("censor_intensity", 70).apply();
        f.backend.beforeCommit = () -> prefs.edit().putInt("censor_intensity", 71).apply();
        f.storage.addListener((a, b) -> {
            assertFalse(Thread.holdsLock(draft)); assertFalse(Thread.holdsLock(f.storage));
        });
        InternalSubRecord first = f.config.flush(draft);
        assertEquals(70, first.configuration().get("censor").get("censor_intensity"));
        assertTrue(draft.hasUnflushedChanges()); assertEquals(71, prefs.getInt("censor_intensity", 0));
        f.backend.beforeCommit = null;
        assertEquals(2, f.config.flush(draft).configurationRevision);
        assertFalse(draft.hasUnflushedChanges());
    }

    @Test public void corruptEstablishedSchemaIsNotCompletedFromDefaults() {
        Fixture f = new Fixture(); InternalSubRecord record = f.storage.read();
        Map<String, Map<String, Object>> sections = new LinkedHashMap<>(record.configuration());
        sections.remove("censor");
        f.backend.record = new InternalSubRecord(record.targetId, record.generation, 0, 0, false, record.profile, sections);
        assertThrows(IllegalArgumentException.class, f.config::read);
    }

    @Test public void invalidCombinedSettingsAreRejectedAtomicallyAndClearKeepsAllowedDefaults() {
        Fixture f = new Fixture(); InternalConfigurationStore.EditorDraft draft = f.config.edit(f.config.read());
        SharedPreferences prefs = draft.preferences(SettingsRepository.PREFERENCES_NAME);
        assertThrows(IllegalArgumentException.class, () -> prefs.edit().putInt("censor_intensity", 75)
                .putInt("detection_confidence_percent", 999).apply());
        assertEquals(50, prefs.getInt("censor_intensity", 0));
        prefs.edit().putInt("censor_intensity", 70).apply();
        prefs.edit().clear().apply();
        assertEquals(50, prefs.getInt("censor_intensity", 0)); assertFalse(draft.hasUnflushedChanges());
        assertFalse(prefs.getAll().containsKey("controller_pin_hash"));
    }

    @Test public void reusedEditorDoesNotReplayCommittedChangesAcrossOtherEditors() {
        Fixture f = new Fixture(); InternalConfigurationStore.EditorDraft draft = f.config.edit(f.config.read());
        SharedPreferences prefs = draft.preferences(SettingsRepository.PREFERENCES_NAME);
        SharedPreferences.Editor reused = prefs.edit();
        reused.putInt("censor_intensity", 60).apply();
        prefs.edit().putInt("censor_intensity", 70).apply();
        reused.putBoolean("show_border", false).commit();
        assertEquals(70, prefs.getInt("censor_intensity", 0));
        assertFalse(prefs.getBoolean("show_border", true));
        reused.commit();
        assertEquals(70, prefs.getInt("censor_intensity", 0));
    }

    @Test public void reusedClearIsConsumedBeforeInterleavingAndReentrantCallbacks() {
        Fixture f = new Fixture(); InternalConfigurationStore.EditorDraft draft = f.config.edit(f.config.read());
        SharedPreferences prefs = draft.preferences(SettingsRepository.PREFERENCES_NAME);
        SharedPreferences.Editor reused = prefs.edit();
        prefs.edit().putInt("censor_intensity", 75).apply();
        reused.clear().commit();
        prefs.edit().putInt("censor_intensity", 80).apply();
        reused.putBoolean("show_border", false).apply();
        assertEquals(80, prefs.getInt("censor_intensity", 0));
        AtomicInteger callbacks = new AtomicInteger();
        prefs.registerOnSharedPreferenceChangeListener((p, key) -> {
            if (key.equals("censor_intensity") && callbacks.getAndIncrement() == 0) {
                p.edit().putInt("censor_intensity", 90).apply();
                reused.putBoolean("show_text", false).apply();
            }
        });
        reused.putInt("censor_intensity", 85).apply();
        assertEquals(90, prefs.getInt("censor_intensity", 0));
        assertFalse(prefs.getBoolean("show_text", true));
    }

    @Test public void rejectedReusedEditorRemainsAtomicAndCanBeCorrected() {
        Fixture f = new Fixture(); InternalConfigurationStore.EditorDraft draft = f.config.edit(f.config.read());
        SharedPreferences prefs = draft.preferences(SettingsRepository.PREFERENCES_NAME);
        prefs.edit().putInt("censor_intensity", 75).apply();
        SharedPreferences.Editor reused = prefs.edit().clear().putInt("detection_confidence_percent", 999);
        assertThrows(IllegalArgumentException.class, reused::commit);
        assertEquals(75, prefs.getInt("censor_intensity", 0));
        reused.putInt("detection_confidence_percent", 30).commit();
        assertEquals(50, prefs.getInt("censor_intensity", 0));
        prefs.edit().putInt("censor_intensity", 80).apply();
        reused.commit();
        assertEquals(80, prefs.getInt("censor_intensity", 0));
    }

    @Test public void preparedWriteFreezesBeforeAdmissionAndPreservesLaterDraftEdits() {
        Fixture f = new Fixture(); InternalConfigurationStore.EditorDraft draft = f.config.edit(f.config.read());
        SharedPreferences prefs = draft.preferences(SettingsRepository.PREFERENCES_NAME);
        prefs.edit().putInt("censor_intensity", 70).apply();
        InternalConfigurationStore.PreparedEdit prepared = f.config.prepare(draft);
        assertEquals(0, f.config.read().configurationRevision);
        assertThrows(UnsupportedOperationException.class, () -> prepared.configuration().get("censor").put("censor_intensity", 1));
        // Controller admission observes only prepared.base()/configuration(), not later staged work.
        assertEquals(draft.acknowledgedBase(), prepared.base());
        prefs.edit().putInt("censor_intensity", 80).apply();
        InternalSubRecord saved = f.config.flushPrepared(prepared);
        assertEquals(70, saved.configuration().get("censor").get("censor_intensity"));
        assertEquals(80, prefs.getInt("censor_intensity", 0));
        assertTrue(draft.hasUnflushedChanges());
        assertThrows(IllegalStateException.class, () -> f.config.flushPrepared(prepared));
        assertEquals(2, f.config.flush(draft).configurationRevision);
    }

    @Test public void preparedWriteIsOwnerBoundAndRejectsStaleBaseOrOverlappingFlush() {
        Fixture f = new Fixture(); InternalConfigurationStore.EditorDraft draft = f.config.edit(f.config.read());
        SharedPreferences prefs = draft.preferences(SettingsRepository.PREFERENCES_NAME);
        prefs.edit().putInt("censor_intensity", 70).apply();
        InternalConfigurationStore.PreparedEdit first = f.config.prepare(draft), stale = f.config.prepare(draft);
        assertThrows(IllegalArgumentException.class, () -> new Fixture().config.flushPrepared(first));
        f.backend.beforeCommit = () -> {
            assertThrows(IllegalStateException.class, () -> f.config.prepare(draft));
            assertThrows(IllegalStateException.class, () -> f.config.flushPrepared(stale));
        };
        f.config.flushPrepared(first); f.backend.beforeCommit = null;
        assertThrows(IllegalStateException.class, () -> f.config.flushPrepared(stale));
        prefs.edit().putInt("censor_intensity", 80).apply();
        InternalConfigurationStore.PreparedEdit newer = f.config.prepare(draft), nowStale = f.config.prepare(draft);
        f.config.flushPrepared(newer);
        assertThrows(InternalSubStore.PersistenceException.class, () -> f.config.flushPrepared(nowStale));
    }

    @Test public void deniedPreparedWriteNeedsNoCleanupAndUncertainOneNeverAcknowledges() {
        Fixture f = new Fixture(); InternalConfigurationStore.EditorDraft draft = f.config.edit(f.config.read());
        draft.preferences(SettingsRepository.PREFERENCES_NAME).edit().putInt("censor_intensity", 70).apply();
        InternalConfigurationStore.PreparedEdit denied = f.config.prepare(draft);
        // A denied external admission simply discards this image. It holds no reservation.
        InternalConfigurationStore.PreparedEdit accepted = f.config.prepare(draft);
        assertEquals(denied.base(), accepted.base());
        f.backend.outcome = InternalSubStore.Outcome.UNCERTAIN;
        assertThrows(InternalSubStore.PersistenceException.class, () -> f.config.flushPrepared(accepted));
        assertEquals(denied.base(), draft.acknowledgedBase());
        assertThrows(IllegalStateException.class, () -> f.config.flushPrepared(accepted));
        assertThrows(InternalSubStore.PersistenceException.class, f.config::read);
    }

    private static final class Fixture {
        final Backend backend = new Backend(); final InternalSubStore storage = new InternalSubStore(backend);
        final InternalConfigurationStore config = new InternalConfigurationStore(storage);
    }
    private static final class Backend implements InternalSubStore.Backend {
        InternalSubRecord record = InternalSubStore.initial(new InternalProfile("Internal", "", "", "", 0),
                InternalConfigurationStore.defaults());
        InternalSubStore.Outcome outcome = InternalSubStore.Outcome.COMMITTED;
        Runnable beforeCommit;
        @Override public InternalSubRecord read() { return record; }
        @Override public InternalSubStore.Outcome compareAndSet(InternalSubRecord expected, InternalSubRecord next) {
            if (beforeCommit != null) beforeCommit.run();
            if (!record.equals(expected)) return InternalSubStore.Outcome.CONFLICT;
            if (outcome == InternalSubStore.Outcome.COMMITTED || outcome == InternalSubStore.Outcome.UNCERTAIN) record = next;
            return outcome;
        }
    }
}
