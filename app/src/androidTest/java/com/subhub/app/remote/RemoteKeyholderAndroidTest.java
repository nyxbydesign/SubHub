package com.subhub.app.remote;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.subhub.app.people.People;
import com.subhub.app.security.ControllerAuthenticator;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.security.KeyholderProvider;
import com.subhub.app.security.Totp;
import com.subhub.app.settings.SettingsRepository;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@RunWith(AndroidJUnit4.class)
public final class RemoteKeyholderAndroidTest {
    private static final String DOM = String.join("", Collections.nCopies(64, "a"));
    private static final String SUB = String.join("", Collections.nCopies(64, "b"));
    private static final String OTHER = String.join("", Collections.nCopies(64, "c"));
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final AtomicLong wall = new AtomicLong(1_700_000_000_000L), elapsed = new AtomicLong(100_000);
    private final AtomicInteger boot = new AtomicInteger(7);
    private String domName, subName;
    private RemoteStore domStore, subStore;
    private RemoteSecrets domSecrets, subSecrets;
    private RemoteKeyholder dom, sub;
    private RemoteStore.Peer domPeer, subPeer;
    private KeyholderProvider original;
    private boolean originalMode;

    @Before public void setup() {
        original = People.keyholder();
        originalMode = ControllerPinManager.isDomModeActive();
        domName = "keyholder-dom-" + UUID.randomUUID();
        subName = "keyholder-sub-" + UUID.randomUUID();
        domStore = new RemoteStore(context, domName + ".db");
        subStore = new RemoteStore(context, subName + ".db");
        domSecrets = new RemoteSecrets(context, domName, domName);
        subSecrets = new RemoteSecrets(context, subName, subName);
        dom = holder(domStore, domSecrets);
        sub = holder(subStore, subSecrets);
    }

    @After public void cleanup() throws Exception {
        People.installKeyholder(original);
        if (originalMode) ControllerPinManager.enterDomMode(); else ControllerPinManager.enterSubMode();
        domStore.close();
        subStore.close();
        KeyStore keys = KeyStore.getInstance("AndroidKeyStore");
        keys.load(null);
        for (String name : new String[]{domName, subName}) {
            context.deleteDatabase(name + ".db");
            context.getSharedPreferences(name, 0).edit().clear().commit();
            keys.deleteEntry(name);
        }
    }

    @Test public void generatedCredentialIsScopedIdempotentAndSurvivesReopen() throws Exception {
        pending();
        assertEquals(KeyholderProvider.State.NONE, sub.state());
        String secret = sub.provisionSub(subPeer, SUB);
        assertEquals(secret, sub.provisionSub(subPeer, SUB));
        dom.receiveDom(domPeer, DOM, secret);
        dom.receiveDom(domPeer, DOM, secret);
        assertThrows(GeneralSecurityException.class, () -> dom.receiveDom(domPeer, DOM, Totp.newSecret()));
        assertThrows(GeneralSecurityException.class, () -> dom.receiveDom(domPeer, OTHER, secret));
        activate();
        RemoteKeyholder reopened = holder(subStore, new RemoteSecrets(context, subName, subName));
        assertEquals(KeyholderProvider.State.READY, reopened.state());
        assertEquals("Dom fixture", reopened.assignedDomName());
        assertEquals(ControllerAuthenticator.Result.SUCCESS, reopened.verifyCode(current(secret)));
        assertEquals(secret, sub.provisionSub(subPeer, SUB));
        assertTrue(subStore.metadata(DOM, subPeer.generation, "controller_auth_v1_provisioned", "0").equals("1"));
        for (Object stored : context.getSharedPreferences(subName, 0).getAll().values()) {
            assertFalse(String.valueOf(stored).contains(secret));
        }
    }

    @Test public void replayAndCooldownPersistAcrossInstancesAndReboot() throws Exception {
        String secret = ready();
        assertEquals(ControllerAuthenticator.Result.SUCCESS, sub.verifyCode(current(secret)));
        RemoteKeyholder reopened = holder(subStore, new RemoteSecrets(context, subName, subName));
        assertEquals(ControllerAuthenticator.Result.INVALID, reopened.verifyCode(current(secret)));
        for (int i = 0; i < 4; i++) assertEquals(ControllerAuthenticator.Result.INVALID, reopened.verifyCode("invalid"));
        assertEquals(ControllerAuthenticator.Result.THROTTLED, sub.verifyCode("invalid"));
        // Wall-clock changes cannot shorten the cooldown during the same boot.
        wall.addAndGet(600_000);
        assertEquals(ControllerAuthenticator.Result.THROTTLED, sub.verifyCode("invalid"));
        wall.addAndGet(-600_000);
        boot.incrementAndGet();
        elapsed.set(0);
        assertEquals(ControllerAuthenticator.Result.THROTTLED, reopened.verifyCode("invalid"));
        wall.addAndGet(31_000);
        assertEquals(ControllerAuthenticator.Result.SUCCESS, reopened.verifyCode(current(secret)));
        assertEquals(ControllerAuthenticator.Result.INVALID, sub.verifyCode(current(secret)));
    }

    @Test public void existingActiveRelationshipCanProvisionOnceAndMissingStagedMaterialCannotRegenerate() throws Exception {
        pending();
        activate();
        assertEquals(KeyholderProvider.State.PENDING, sub.state());
        assertEquals(ControllerAuthenticator.Result.UNAVAILABLE, sub.verifyCode("000000"));
        String secret = sub.provisionSub(subPeer, SUB);
        dom.receiveDom(domPeer, DOM, secret);
        assertEquals(KeyholderProvider.State.READY, sub.state());
        assertEquals(ControllerAuthenticator.Result.SUCCESS, sub.verifyCode(current(secret)));
        subSecrets.removeControllerSecrets(DOM, subPeer.generation);
        assertEquals(KeyholderProvider.State.UNAVAILABLE, sub.state());
        assertThrows(GeneralSecurityException.class, () -> sub.provisionSub(subPeer, SUB));
        domSecrets.removeControllerSecrets(SUB, domPeer.generation);
        assertThrows(GeneralSecurityException.class, () -> dom.receiveDom(domPeer, DOM, secret));
    }

    @Test public void localCredentialAdmissionAndAssignmentShareOneAtomicAuthorityBoundary() throws Exception {
        pending();
        CountDownLatch admitted = new CountDownLatch(1), release = new CountDownLatch(1);
        CountDownLatch attemptingActivation = new CountDownLatch(1);
        AtomicInteger sequence = new AtomicInteger();
        AtomicInteger admissionFinished = new AtomicInteger(), assignmentFinished = new AtomicInteger();
        AtomicBoolean deniedCallback = new AtomicBoolean();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        Thread activation = new Thread(() -> {
            attemptingActivation.countDown();
            subStore.activate(DOM, subPeer.generation);
            ControllerPinManager.lockNow();
            assignmentFinished.set(sequence.incrementAndGet());
        }, "keyholder-activation-fixture");
        try {
            Future<Boolean> first = worker.submit(() -> sub.withLocalAuthority(() -> {
                admitted.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) return false;
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return false;
                }
                ControllerPinManager.enterDomMode();
                admissionFinished.set(sequence.incrementAndGet());
                return true;
            }));
            assertTrue(admitted.await(10, TimeUnit.SECONDS));
            activation.start();
            assertTrue(attemptingActivation.await(10, TimeUnit.SECONDS));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (activation.getState() != Thread.State.BLOCKED && activation.isAlive()
                    && System.nanoTime() < deadline) Thread.yield();
            assertEquals(Thread.State.BLOCKED, activation.getState());
            assertEquals(0, assignmentFinished.get());
            release.countDown();
            assertTrue(first.get(10, TimeUnit.SECONDS));
            activation.join(10_000);
            assertFalse(activation.isAlive());
            assertEquals(1, admissionFinished.get());
            assertEquals(2, assignmentFinished.get());
            assertFalse(ControllerPinManager.isDomModeActive());
            assertFalse(sub.withLocalAuthority(() -> { deniedCallback.set(true); return true; }));
            assertFalse(deniedCallback.get());
        } finally {
            release.countDown();
            worker.shutdownNow();
            activation.join(10_000);
        }
    }

    @Test public void pairedAuthorityDeniesLegacyPinAndAuthenticatorMutationsWithoutChangingTheirBytes() throws Exception {
        ready();
        People.installKeyholder(sub);
        SharedPreferences pins = context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME, 0);
        SharedPreferences legacy = context.getSharedPreferences("subhub_controller_auth", 0);
        Map<String, ?> pinBefore = pins.getAll(), legacyBefore = legacy.getAll();
        ControllerPinManager.enterDomMode();
        assertTrue(ControllerPinManager.hasCredentials(context));
        assertFalse(ControllerPinManager.setPin(context, "123456"));
        assertFalse(ControllerPinManager.verify(context, "123456"));
        assertFalse(ControllerPinManager.removePin(context));
        assertFalse(ControllerPinManager.useWithoutKeyholder(context));
        ControllerAuthenticator authenticator = new ControllerAuthenticator(context);
        assertEquals(ControllerAuthenticator.Result.UNAVAILABLE, authenticator.pair(Totp.newSecret(), "000000"));
        assertFalse(authenticator.remove());
        assertEquals(pinBefore, pins.getAll());
        assertEquals(legacyBefore, legacy.getAll());
    }

    @Test public void legacyVerifierDelegatesOnlyToAssignedRelationshipAndNeverFallsBack() throws Exception {
        String secret = ready();
        People.installKeyholder(sub);
        ControllerPinManager.enterSubMode();
        ControllerAuthenticator authenticator = new ControllerAuthenticator(context);
        assertEquals(ControllerAuthenticator.Result.SUCCESS, authenticator.verify(current(secret)));
        assertTrue(ControllerPinManager.isDomModeActive());
        assertEquals(ControllerAuthenticator.Result.INVALID, authenticator.verify(current(secret)));
        subStore.revoke(DOM, subPeer.generation);
        sub.revoked(DOM, subPeer.generation);
        assertFalse(ControllerPinManager.isDomModeActive());
        assertEquals(KeyholderProvider.State.NONE, sub.state());
        assertEquals(ControllerAuthenticator.Result.UNAVAILABLE, sub.verifyCode(current(secret)));
        assertNull(subSecrets.controllerSecret(RemoteProtocol.Role.SUB, DOM, subPeer.generation));
        assertThrows(SecurityException.class, () -> sub.provisionSub(subPeer, SUB));
    }

    @Test public void wrappingKeyLossAndMissingCiphertextFailClosedWithoutReplacingTheSeed() throws Exception {
        String secret = ready();
        SharedPreferences prefs = context.getSharedPreferences(subName, 0);
        Map<String, ?> originalBytes = prefs.getAll();
        KeyStore keys = KeyStore.getInstance("AndroidKeyStore");
        keys.load(null);
        keys.deleteEntry(subName);
        assertEquals(KeyholderProvider.State.UNAVAILABLE, sub.state());
        assertEquals(ControllerAuthenticator.Result.UNAVAILABLE, sub.verifyCode(current(secret)));
        assertThrows(GeneralSecurityException.class, () -> sub.provisionSub(subPeer, SUB));
        assertEquals(originalBytes, prefs.getAll());
        prefs.edit().clear().commit();
        assertEquals(KeyholderProvider.State.UNAVAILABLE, sub.state());
        assertThrows(GeneralSecurityException.class, () -> sub.provisionSub(subPeer, SUB));
    }

    @Test public void codeRequiresUnlockedDomAndRejectsStaleRevokedOrWrongRolePeers() throws Exception {
        String secret = ready();
        People.installKeyholder(sub);
        ControllerPinManager.enterSubMode();
        assertThrows(SecurityException.class, () -> dom.code(domPeer));
        ControllerPinManager.enterDomMode();
        RemoteKeyholder.Code code = dom.code(domPeer);
        assertEquals(current(secret), code.value);
        assertTrue(code.expiresAt > wall.get());
        assertTrue(code.expiresAt - wall.get() <= Totp.STEP_MILLIS);
        People.installKeyholder(new KeyholderProvider() {
            @Override public State state() { return State.UNAVAILABLE; }
            @Override public String assignedDomName() { return ""; }
            @Override public ControllerAuthenticator.Result verifyCode(String supplied) {
                return ControllerAuthenticator.Result.UNAVAILABLE;
            }
        });
        assertThrows(GeneralSecurityException.class, () -> dom.code(domPeer));
        People.installKeyholder(sub);
        assertThrows(SecurityException.class, () -> sub.code(subPeer));
        domStore.revoke(SUB, domPeer.generation);
        dom.revoked(SUB, domPeer.generation);
        assertThrows(SecurityException.class, () -> dom.code(domPeer));
        assertThrows(SecurityException.class, () -> dom.receiveDom(domPeer, DOM, secret));
    }

    @Test public void copyingCiphertextAcrossIdentityOrPurposeCannotProvisionAnotherRelationship() throws Exception {
        ready();
        SharedPreferences prefs = context.getSharedPreferences(subName, 0);
        String seedSlot = null, descriptorSlot = null;
        for (String slot : prefs.getAll().keySet()) {
            if (slot.startsWith("controller_seed_v1_")) seedSlot = slot;
            if (slot.startsWith("controller_descriptor_v1_")) descriptorSlot = slot;
        }
        assertNotNull(seedSlot);
        assertNotNull(descriptorSlot);
        prefs.edit().putString(seedSlot, prefs.getString(descriptorSlot, null)).commit();
        assertEquals(KeyholderProvider.State.UNAVAILABLE, sub.state());
        assertEquals(ControllerAuthenticator.Result.UNAVAILABLE, sub.verifyCode("000000"));
        assertThrows(GeneralSecurityException.class, () -> sub.provisionSub(subPeer, SUB));
    }

    private RemoteKeyholder holder(RemoteStore store, RemoteSecrets secrets) {
        return new RemoteKeyholder(context, store, secrets, wall::get, elapsed::get, boot::get);
    }
    private String current(String secret) throws GeneralSecurityException {
        return Totp.code(secret, wall.get() / Totp.STEP_MILLIS);
    }
    private void pending() throws Exception {
        PairingInvite invite = PairingInvite.create(DOM, "fixture-route", 1000);
        domStore.saveInvite(invite);
        domStore.bindInvite(invite.id, SUB, invite.tokenHash(), 1001);
        domStore.approveInvite(invite.id, SUB, "Sub fixture", 1002);
        subStore.acceptInvite(invite, "Dom fixture");
        domPeer = domStore.peer(SUB, invite.id);
        subPeer = subStore.peer(DOM, invite.id);
    }
    private void activate() {
        domStore.activate(SUB, domPeer.generation);
        subStore.activate(DOM, subPeer.generation);
    }
    private String ready() throws Exception {
        pending();
        String secret = sub.provisionSub(subPeer, SUB);
        dom.receiveDom(domPeer, DOM, secret);
        activate();
        return secret;
    }
}
