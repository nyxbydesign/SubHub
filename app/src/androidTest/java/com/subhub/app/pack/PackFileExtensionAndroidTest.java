package com.subhub.app.pack;

import static org.junit.Assert.*;

import android.content.Context;
import android.net.Uri;

import androidx.core.content.FileProvider;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONObject;
import org.junit.Test;

import java.io.*;
import java.util.*;

public class PackFileExtensionAndroidTest {
    @Test
    public void subShareKeepsZipMimeAndLegacyLibraryAndImportsRemainReadable() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        com.subhub.app.security.ControllerPinManager.enterDomMode();
        SubHubPack source =
                new SubHubPack(
                        UUID.randomUUID().toString(),
                        UUID.randomUUID().toString(),
                        "Extension fixture",
                        "",
                        "",
                        "1.0.0",
                        1L,
                        2L,
                        "0.6.0",
                        Map.of(SubHubPackSchema.CENSOR, new JSONObject().put("censor_type", "box")),
                        new JSONObject(),
                        Map.of());
        File old =
                new File(
                        context.getFilesDir(),
                        "subhub_studio/library/" + source.getId() + ".subhubpack");
        SubHubPackManager manager = new SubHubPackManager(context);
        try (FileOutputStream output = new FileOutputStream(old)) {
            SubHubPackArchive.write(source, output);
        }
        assertEquals(source.getName(), manager.findLibrary(source.getId()).getName());
        assertTrue(
                manager.listLibrary().stream()
                        .anyMatch(record -> record.pack.getId().equals(source.getId())));
        File shared = manager.exportForShare(source);
        try {
            assertTrue(shared.getName().endsWith(".sub"));
            Uri uri =
                    FileProvider.getUriForFile(
                            context, context.getPackageName() + ".updates", shared);
            assertEquals("application/zip", context.getContentResolver().getType(uri));
            assertEquals(source.getId(), manager.importPack(Uri.fromFile(old)).getId());
            try (InputStream input = context.getContentResolver().openInputStream(uri)) {
                assertEquals(source.getId(), SubHubPackArchive.read(input).getId());
            }
            File apk = new File(context.getFilesDir(), "updates/extension-fixture.apk");
            assertTrue(apk.getParentFile().isDirectory() || apk.getParentFile().mkdirs());
            try (FileOutputStream output = new FileOutputStream(apk)) {
                output.write(0);
            }
            try {
                Uri apkUri =
                        FileProvider.getUriForFile(
                                context, context.getPackageName() + ".updates", apk);
                assertEquals(
                        "application/vnd.android.package-archive",
                        context.getContentResolver().getType(apkUri));
            } finally {
                apk.delete();
            }
        } finally {
            shared.delete();
            old.delete();
        }
    }
}
