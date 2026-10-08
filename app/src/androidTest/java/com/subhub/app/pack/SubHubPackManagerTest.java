package com.subhub.app.pack;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.net.Uri;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.settings.SettingsRepository;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.io.File;
import java.io.FileOutputStream;

@RunWith(AndroidJUnit4.class)
public final class SubHubPackManagerTest {
    private Context context;

    @Before public void setup() {
        context = ApplicationProvider.getApplicationContext();
        if (!ControllerPinManager.isConfigured(context)) ControllerPinManager.setPin(context, "2468");
        ControllerPinManager.enterDomMode();
        new SubHubPackManager(context).deactivate();
    }

    @Test public void activationDoesNotLockSettingsAndDeactivationRestoresPreviousValue()
            throws Exception {
        SettingsRepository repository = new SettingsRepository(context);
        repository.preferences().edit().putString(SettingsRepository.KEY_CENSOR_TYPE, "blur")
                .commit();
        JSONObject section = new JSONObject().put(SettingsRepository.KEY_CENSOR_TYPE, "box");
        SubHubPack pack = new SubHubPack(UUID.randomUUID().toString(), UUID.randomUUID().toString(), "Test", "", "", "1.0.0",
                1L, 1L, "0.6.0", Map.of(SubHubPackSchema.CENSOR, section),
                new JSONObject(), Map.of());
        SubHubPackManager manager = new SubHubPackManager(context);

        assertTrue(manager.activate(pack, Set.of(SubHubPackSchema.CENSOR)));
        assertEquals("box", repository.preferences().getString(
                SettingsRepository.KEY_CENSOR_TYPE, ""));
        assertFalse(pack.manifestWithoutIntegrity(Map.of()).has("lockGroups"));
        assertFalse(context.getSharedPreferences("subhub_pack_state_v1", Context.MODE_PRIVATE)
                .contains("active_lock_groups"));

        assertTrue(manager.deactivate());
        assertEquals("blur", repository.preferences().getString(
                SettingsRepository.KEY_CENSOR_TYPE, ""));
        assertFalse(pack.manifestWithoutIntegrity(Map.of()).has("lockGroups"));
    }

    @Test public void subSpaceCanApplyOnlyMatchingActivePackUpdate() throws Exception {
        String packId = UUID.randomUUID().toString();
        String deviceId = UUID.randomUUID().toString();
        SubHubPack first = pack(packId, deviceId, "Distance Rules", "Keeper", "box", 10L);
        SubHubPackManager manager = new SubHubPackManager(context);
        manager.addToLibrary(first);
        assertTrue(manager.activate(first, Set.of(SubHubPackSchema.CENSOR)));
        ControllerPinManager.enterSubMode();

        SubHubPack update = pack(packId, deviceId, "Distance Rules", "Keeper", "pixelate", 20L);
        manager.importPack(write(update, "matching-update.subhubpack"));
        assertEquals("pixelate", new SettingsRepository(context).preferences().getString(
                SettingsRepository.KEY_CENSOR_TYPE, ""));
        assertEquals("pixelate", manager.findLibrary(packId)
                .getSection(SubHubPackSchema.CENSOR).getString(SettingsRepository.KEY_CENSOR_TYPE));

        SubHubPack impostor = pack(packId, deviceId, "Distance Rules", "Different author",
                "blur", 30L);
        try {
            manager.importPack(write(impostor, "mismatched-update.subhubpack"));
            fail("Sub Space must reject mismatched pack identity");
        } catch (java.io.IOException expected) {
            assertTrue(expected.getMessage().contains("identity"));
        }
        assertEquals("pixelate", new SettingsRepository(context).preferences().getString(
                SettingsRepository.KEY_CENSOR_TYPE, ""));
    }

    private SubHubPack pack(String id, String deviceId, String name, String author,
            String type, long updatedAt) throws Exception {
        JSONObject section = new JSONObject().put(SettingsRepository.KEY_CENSOR_TYPE, type);
        return new SubHubPack(id, deviceId, name, author, "", "1.0.0", 1L, updatedAt,
                "0.6.0", Map.of(SubHubPackSchema.CENSOR, section),
                new JSONObject(), Map.of());
    }

    private Uri write(SubHubPack pack, String name) throws Exception {
        File file = new File(context.getCacheDir(), name);
        try (FileOutputStream output = new FileOutputStream(file)) {
            SubHubPackArchive.write(pack, output);
        }
        return Uri.fromFile(file);
    }
}
