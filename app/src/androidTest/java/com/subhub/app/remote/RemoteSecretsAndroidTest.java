package com.subhub.app.remote;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

@RunWith(AndroidJUnit4.class)
public class RemoteSecretsAndroidTest {
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private String name,alias;
    private KeyStore keys;
    @Before public void setup() throws Exception {
        name="remote-secret-test-"+UUID.randomUUID(); alias=name;
        keys=KeyStore.getInstance("AndroidKeyStore"); keys.load(null);
    }
    @After public void cleanup() throws Exception {
        context.getSharedPreferences(name,0).edit().clear().commit();
        keys.deleteEntry(alias);
    }

    @Test public void wrappingKeyCannotBeExportedAndIdentitySurvivesReopen() throws Exception {
        RemoteSecrets first=new RemoteSecrets(context,name,alias);
        byte[] seed=first.identitySeed();
        assertEquals(32,seed.length);
        assertArrayEquals(seed,new RemoteSecrets(context,name,alias).identitySeed());
        assertNull(keys.getKey(alias,null).getEncoded());
        assertNotEquals(java.util.Base64.getEncoder().encodeToString(seed),context.getSharedPreferences(name,0).getString("identity",null));
    }

    @Test public void missingWrappingKeyNeverSilentlyReplacesIdentity() throws Exception {
        new RemoteSecrets(context,name,alias).identitySeed();
        String original=context.getSharedPreferences(name,0).getString("identity",null);
        keys.deleteEntry(alias);
        assertThrows(GeneralSecurityException.class, () -> new RemoteSecrets(context,name,alias).identitySeed());
        assertEquals(original,context.getSharedPreferences(name,0).getString("identity",null));
    }

    @Test public void copiedCiphertextCannotBeUsedForAnotherSlot() throws Exception {
        RemoteSecrets store=new RemoteSecrets(context,name,alias); store.identitySeed();
        SharedPreferences prefs=context.getSharedPreferences(name,0);
        prefs.edit().putString("pending_other",prefs.getString("identity",null)).commit();
        assertThrows(GeneralSecurityException.class, () -> store.pendingLink("other"));
        PairingInvite invite=PairingInvite.create(String.join("",Collections.nCopies(64,"a")),"ticket",1000);
        store.savePending(invite);
        assertEquals(invite.link(),store.pendingLink(invite.id));
        store.removePending(invite.id); assertNull(store.pendingLink(invite.id));
    }

    @Test public void concurrentStoreInstancesKeepOneInstallationIdentity() throws Exception {
        ExecutorService workers=Executors.newFixedThreadPool(4);
        try {
            List<Future<byte[]>> values=new ArrayList<>();
            for(int i=0;i<8;i++) values.add(workers.submit(() -> new RemoteSecrets(context,name,alias).identitySeed()));
            byte[] first=values.get(0).get();
            for(Future<byte[]> value:values) assertArrayEquals(first,value.get());
        } finally { workers.shutdownNow(); }
    }
}
