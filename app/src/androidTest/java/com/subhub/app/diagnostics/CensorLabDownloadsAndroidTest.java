package com.subhub.app.diagnostics;

import static org.junit.Assert.*;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@RunWith(AndroidJUnit4.class)
public final class CensorLabDownloadsAndroidTest {
    @Test public void downloadsPublishesTheCompleteZipWithoutStoragePermission() throws Exception {
        Assume.assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q);
        Context context = ApplicationProvider.getApplicationContext();
        File root = new File(context.getCacheDir(), "censor-lab/exports");
        assertTrue(root.isDirectory() || root.mkdirs());
        File bundle = new File(root, "subhub-censor-lab-test-" + UUID.randomUUID() + ".zip");
        Uri saved = null;
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
                zip.putNextEntry(new ZipEntry("manifest.json"));
                zip.write("{\"synthetic\":true}".getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
                zip.putNextEntry(new ZipEntry("synthetic.bin"));
                byte[] payload = new byte[131_073];
                new java.util.Random(17).nextBytes(payload);
                zip.write(payload);
                zip.closeEntry();
            }
            byte[] expected = bytes.toByteArray();
            try (FileOutputStream output = new FileOutputStream(bundle)) { output.write(expected); }
            saved = CensorLabDownloads.save(context, bundle);
            assertNotNull(saved);
            try (InputStream input = context.getContentResolver().openInputStream(saved)) {
                assertNotNull(input);
                ByteArrayOutputStream actual = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int count;
                while ((count = input.read(buffer)) != -1) actual.write(buffer, 0, count);
                assertArrayEquals(expected, actual.toByteArray());
            }
            try (Cursor row = context.getContentResolver().query(saved, new String[]{
                    MediaStore.Downloads.DISPLAY_NAME, MediaStore.Downloads.IS_PENDING,
                    MediaStore.Downloads.MIME_TYPE, MediaStore.Downloads.RELATIVE_PATH}, null, null, null)) {
                assertNotNull(row);
                assertTrue(row.moveToFirst());
                assertEquals(bundle.getName(), row.getString(0));
                assertEquals(0, row.getInt(1));
                assertEquals("application/zip", row.getString(2));
                assertEquals("Download/", row.getString(3));
            }
        } finally {
            if (saved != null) assertEquals(1, context.getContentResolver().delete(saved, null, null));
            assertTrue(!bundle.exists() || bundle.delete());
        }
    }

    @Test public void rejectsFilesOutsideTheLabExportDirectory() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        File unrelated = new File(context.getCacheDir(), "subhub-censor-lab-unrelated.zip");
        try {
            try (FileOutputStream output = new FileOutputStream(unrelated)) { output.write(7); }
            try {
                CensorLabDownloads.requireBundle(context, unrelated);
                fail("Unrelated private data must not be exported");
            } catch (IOException expected) { assertTrue(unrelated.isFile()); }
        } finally { assertTrue(!unrelated.exists() || unrelated.delete()); }
    }
}
