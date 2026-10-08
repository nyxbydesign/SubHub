package com.subhub.app.pack;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.subhub.app.penance.PayPalCredentialStore;
import com.subhub.app.penance.PayPalEnvironment;
import com.subhub.app.penance.PenanceManager;
import com.subhub.app.security.ControllerPinManager;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Map;
import java.util.Set;

/** Synthetic merchant data only. Run on an isolated emulator, never the user's wallet. */
@RunWith(AndroidJUnit4.class)
public final class PackPayPalTransferAndroidTest {
    private static final char[] PASSWORD = "synthetic emulator passphrase".toCharArray();
    private Context context;
    private SubHubPackManager manager;
    private PayPalCredentialStore store;

    @Before public void setup() {
        context = ApplicationProvider.getApplicationContext();
        ControllerPinManager.enterDomMode();
        prefs("subhub_hardcore_auto_pay").edit().clear().commit();
        prefs(PenanceManager.PREFS_NAME).edit().clear().commit();
        manager = new SubHubPackManager(context);
        assertTrue(manager.deactivate());
        store = new PayPalCredentialStore(context);
        store.clear();
    }

    @After public void cleanup() {
        ControllerPinManager.enterDomMode();
        prefs("subhub_hardcore_auto_pay").edit().clear().commit();
        prefs(PenanceManager.PREFS_NAME).edit().clear().commit();
        manager.deactivate();
        store.clear();
    }

    @Test public void archiveImportActivationAndRollbackPreserveOnlyLocalAuthorization() throws Exception {
        assertTrue(store.save(PayPalEnvironment.SANDBOX, "synthetic-merchant", "old-synthetic-secret"));
        assertTrue(store.markCredentialsVerified());
        store.recordVaultResult(store.load(), "VAULTED", "synthetic-vault", "synthetic-customer", "", "");
        assertTrue(store.vaultState().isReady());
        new PenanceManager(context).savePayPalLink("https://paypal.me/old-synthetic");
        Map<String, ?> original = prefs(PayPalCredentialStore.PREFS_NAME).getAll();
        SubHubPack source = encryptedPack("synthetic-merchant");
        SubHubPack imported = manager.importPack(write(source));
        assertEquals("old-synthetic-secret", store.load().secret());
        try (SubHubPackManager.UnlockedPayPal unlocked = manager.unlockPayPal(imported, PASSWORD)) {
            assertTrue(manager.activate(imported, Set.of(SubHubPackSchema.WALLET), unlocked));
        }
        assertEquals("new-synthetic-secret", store.load().secret());
        assertFalse(store.hasVerifiedCredentials());
        assertFalse(store.vaultState().isReady());
        assertFalse(store.pendingVaultSetup().isPresent());
        assertEquals("https://paypal.me/new-synthetic", new PenanceManager(context).getPayPalLink());
        assertTrue(SubHubPackLocks.isLocked(context, SubHubPackSchema.WALLET));
        String persisted = prefs("subhub_pack_state_v1").getAll().toString()
                + prefs(PayPalCredentialStore.PREFS_NAME).getAll();
        assertFalse(persisted.contains("old-synthetic-secret"));
        assertFalse(persisted.contains("new-synthetic-secret"));
        assertFalse(persisted.contains("synthetic-vault"));
        assertFalse(persisted.contains(new String(PASSWORD)));
        assertTrue(manager.deactivate());
        assertEquals(original, prefs(PayPalCredentialStore.PREFS_NAME).getAll());
        assertEquals("https://paypal.me/old-synthetic", new PenanceManager(context).getPayPalLink());
        assertFalse(SubHubPackLocks.isLocked(context, SubHubPackSchema.WALLET));
    }

    @Test public void wrongPasswordSubModeAndChangedEnvelopeCannotMutateWallet() throws Exception {
        assertTrue(store.save(PayPalEnvironment.SANDBOX, "original-id", "original-secret"));
        Map<String, ?> original = prefs(PayPalCredentialStore.PREFS_NAME).getAll();
        SubHubPack pack = encryptedPack("synthetic-merchant");
        assertFalse(manager.activate(pack, Set.of(SubHubPackSchema.WALLET)));
        try { manager.unlockPayPal(pack, "wrong password long enough".toCharArray()); fail(); }
        catch (java.security.GeneralSecurityException expected) { }
        try (SubHubPackManager.UnlockedPayPal unlocked = manager.unlockPayPal(pack, PASSWORD)) {
            ControllerPinManager.enterSubMode();
            assertFalse(manager.activate(pack, Set.of(SubHubPackSchema.WALLET), unlocked));
            try { manager.unlockPayPal(pack, PASSWORD); fail(); }
            catch (java.security.GeneralSecurityException expected) { }
            ControllerPinManager.enterDomMode();
            pack.setEncryptedPayPal(encryptedPack("different-merchant").getEncryptedPayPal());
            assertFalse(manager.activate(pack, Set.of(SubHubPackSchema.WALLET), unlocked));
        }
        assertEquals(original, prefs(PayPalCredentialStore.PREFS_NAME).getAll());
        assertNull(manager.activePackId());
    }

    @Test public void captureRemainsOptInAndDeselectedWalletLeavesCredentialsAlone() throws Exception {
        assertTrue(store.save(PayPalEnvironment.LIVE, "original-id", "original-secret"));
        assertFalse(manager.captureCurrent().hasEncryptedPayPal());
        SubHubPack pack = encryptedPack("synthetic-merchant");
        pack.setSection(SubHubPackSchema.CENSOR, new JSONObject().put("censor_type", "box"));
        assertTrue(manager.activate(pack, Set.of(SubHubPackSchema.CENSOR)));
        assertEquals("original-secret", store.load().secret());
        assertEquals(PayPalEnvironment.LIVE, store.selectedEnvironment());
        assertTrue(manager.deactivate());
    }

    @Test public void activeEncryptedUpdateCannotBypassDomOrDecryption() throws Exception {
        SubHubPack pack = encryptedPack("synthetic-merchant");
        manager.addToLibrary(pack);
        try (SubHubPackManager.UnlockedPayPal unlocked = manager.unlockPayPal(pack, PASSWORD)) {
            assertTrue(manager.activate(pack, Set.of(SubHubPackSchema.WALLET), unlocked));
        }
        ControllerPinManager.enterSubMode();
        try { manager.importPack(write(pack)); fail("Must not auto-apply encrypted update"); }
        catch (java.io.IOException expected) { }
        assertFalse(manager.deactivate());
        assertFalse(manager.deleteLibrary(pack.getId()));
        assertEquals("new-synthetic-secret", store.load().secret());
        // A saved Library revision is not evidence about the actual active credential state.
        pack.setEncryptedPayPal(null);
        manager.addToLibrary(pack);
        try { manager.importPack(write(pack)); fail("Removing the attachment must not bypass active state"); }
        catch (java.io.IOException expected) { }
        assertEquals("new-synthetic-secret", store.load().secret());
    }

    @Test public void interruptedActivationRestoresKeystoreCiphertextFromJournal() throws Exception {
        assertTrue(store.save(PayPalEnvironment.LIVE, "original-id", "original-secret"));
        Map<String, ?> original = prefs(PayPalCredentialStore.PREFS_NAME).getAll();
        SubHubPack pack = encryptedPack("synthetic-merchant");
        try (SubHubPackManager.UnlockedPayPal unlocked = manager.unlockPayPal(pack, PASSWORD)) {
            assertTrue(manager.activate(pack, Set.of(SubHubPackSchema.WALLET), unlocked));
        }
        SharedPreferences state = prefs("subhub_pack_state_v1");
        JSONObject backup = new JSONObject(state.getString("active_pack_backup", "{}"));
        state.edit().putString("activation_journal", new JSONObject().put("pending", true)
                .put("backup", backup).toString()).commit();
        ControllerPinManager.enterSubMode();
        SubHubPackManager recovered = new SubHubPackManager(context);
        assertNull(recovered.activePackId());
        assertFalse(state.contains("activation_journal"));
        assertEquals(original, prefs(PayPalCredentialStore.PREFS_NAME).getAll());
    }

    @Test public void configuredAutomaticSettlementBlocksMerchantReplacementAndRestoration() throws Exception {
        SubHubPack pack = encryptedPack("synthetic-merchant");
        try (SubHubPackManager.UnlockedPayPal unlocked = manager.unlockPayPal(pack, PASSWORD)) {
            prefs("subhub_hardcore_auto_pay").edit().putBoolean("enabled", true).commit();
            assertFalse(manager.activate(pack, Set.of(SubHubPackSchema.WALLET), unlocked));
            prefs("subhub_hardcore_auto_pay").edit().putBoolean("enabled", false).commit();
            assertTrue(manager.activate(pack, Set.of(SubHubPackSchema.WALLET), unlocked));
            prefs("subhub_hardcore_auto_pay").edit().putBoolean("enabled", true).commit();
            assertFalse(manager.deactivate());
            assertEquals(pack.getId(), manager.activePackId());
        }
    }

    @Test public void pendingCheckoutBlocksReplacementWithoutCancellingOrLosingLedger() throws Exception {
        SubHubPack pack = encryptedPack("synthetic-merchant");
        String ledger = "synthetic-event,1,1,100,1,CHECKOUT,synthetic-settlement,NEW_DETECTION";
        prefs(PenanceManager.PREFS_NAME).edit().putString("events_v1", ledger).commit();
        assertEquals("synthetic-settlement", new PenanceManager(context).getActiveSettlementId());
        try (SubHubPackManager.UnlockedPayPal unlocked = manager.unlockPayPal(pack, PASSWORD)) {
            assertFalse(manager.activate(pack, Set.of(SubHubPackSchema.WALLET), unlocked));
        }
        assertEquals(ledger, prefs(PenanceManager.PREFS_NAME).getString("events_v1", ""));
        assertFalse(store.hasCredentials());
    }

    private SubHubPack encryptedPack(String merchant) throws Exception {
        SubHubPack pack = manager.createBlank();
        pack.setSection(SubHubPackSchema.WALLET, new JSONObject().put("daily_cap_cents", 1234));
        pack.setGroupLocked(SubHubPackSchema.WALLET, true);
        try (PackPayPalCipher.Payload value = new PackPayPalCipher.Payload("SANDBOX", merchant,
                "new-synthetic-secret", "https://paypal.me/new-synthetic")) {
            pack.setEncryptedPayPal(PackPayPalCipher.encrypt(pack.getId(), pack.getOriginDeviceId(), value, PASSWORD));
        }
        return pack;
    }
    private SharedPreferences prefs(String name) { return context.getSharedPreferences(name, Context.MODE_PRIVATE); }
    private Uri write(SubHubPack pack) throws Exception {
        File file = new File(context.getCacheDir(), "synthetic-paypal.subhubpack");
        try (FileOutputStream out = new FileOutputStream(file)) { SubHubPackArchive.write(pack, out); }
        return Uri.fromFile(file);
    }
}
