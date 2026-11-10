package com.subhub.app.remote.icons;

import static org.junit.Assert.*;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.remote.RemoteProtocol;
import com.subhub.app.remote.RemoteStore;
import com.subhub.app.util.RemoteAppIcon;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.UUID;
import java.util.zip.CRC32;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Platform PNG validation only; no UI, transport networking, controller state or OS grants. */
@RunWith(AndroidJUnit4.class)
public class AppIconPngInstrumentedTest {
    private static final String PEER = "1111111111111111111111111111111111111111111111111111111111111111";
    private static final String GENERATION = "icon_fixture";

    @Test public void crcValidBrokenCompressedPixelsAreRejectedBeforeCaching() throws Exception {
        byte[] broken = png();
        IconPng.validate(identity(broken), broken); // The native encoder's complete image must still be accepted.
        for (int offset = 8; offset < broken.length;) {
            int length = number(broken, offset);
            if (number(broken, offset + 4) == 0x49444154) {
                Arrays.fill(broken, offset + 8, offset + 8 + length, (byte) 0);
                CRC32 crc = new CRC32(); crc.update(broken, offset + 4, length + 4);
                put(broken, offset + 8 + length, (int) crc.getValue());
            }
            offset += length + 12;
        }
        RemoteAppIcon icon = identity(broken);
        icon.validatePng(broken); // Structural/header/hash validity alone does not prove decodable pixels.
        boolean rejected = false;
        try { IconPng.validate(icon, broken); } catch (IllegalArgumentException invalidPixels) { rejected = true; }
        assertTrue(rejected);
    }

    @Test public void providerOfflinePixelsRequireCurrentReferenceAndRevocationRemovesDelivery() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File parent = context.getCacheDir().getCanonicalFile();
        File owned = new File(parent, "icon-fixture-" + UUID.randomUUID());
        assertTrue(owned.mkdir());
        assertEquals(parent, owned.getCanonicalFile().getParentFile());
        try {
            AppIconCache cache = new AppIconCache(owned); byte[] png = png(); RemoteAppIcon icon = identity(png);
            cache.publishReferences(Collections.singleton(icon));
            assertNull(cache.load(icon)); cache.complete(cache.snapshot(), icon, png, () -> { });
            byte[] loaded = cache.load(icon); assertNotNull(loaded);
            Bitmap decoded = BitmapFactory.decodeByteArray(loaded, 0, loaded.length);
            assertNotNull(decoded);
            try { assertEquals(Color.RED, decoded.getPixel(64, 64)); }
            finally { decoded.recycle(); }
            cache.publishReferences(Collections.emptyList()); assertNull(cache.load(icon));
        } finally {
            // Walk never follows symlinks; remove only this test's freshly created directory.
            try (java.util.stream.Stream<java.nio.file.Path> paths = Files.walk(owned.toPath())) {
                java.util.List<java.nio.file.Path> removals = new java.util.ArrayList<>();
                paths.sorted(Comparator.reverseOrder()).forEach(removals::add);
                for (java.nio.file.Path path : removals) Files.delete(path);
            }
        }
    }

    @Test public void freshDrawableRasterStripsTextMetadataAndProducesBounded128PixelPng() throws Exception {
        byte[] original = png();
        ByteArrayOutputStream tagged = new ByteArrayOutputStream();
        tagged.write(original, 0, 33); // Signature plus IHDR.
        byte[] text = ("fixture" + (char) 0 + "synthetic-original-metadata").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        java.io.DataOutputStream chunk = new java.io.DataOutputStream(tagged);
        chunk.writeInt(text.length); chunk.writeInt(0x74455874); chunk.write(text);
        CRC32 crc = new CRC32(); crc.update(new byte[] {116, 69, 88, 116}); crc.update(text); chunk.writeInt((int) crc.getValue());
        tagged.write(original, 33, original.length - 33);
        byte[] source = tagged.toByteArray();
        Bitmap decoded = BitmapFactory.decodeByteArray(source, 0, source.length); assertNotNull(decoded);
        try {
            Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
            byte[] fresh = AndroidAppIconSource.render(new BitmapDrawable(context.getResources(), decoded), () -> { });
            assertNotNull(fresh); assertTrue(fresh.length <= RemoteAppIcon.MAX_BYTES);
            IconPng.validate(identity(fresh), fresh);
            for (int offset = 8; offset < fresh.length;) {
                int type = number(fresh, offset + 4);
                assertTrue(type != 0x74455874 && type != 0x7a545874 && type != 0x69545874 && type != 0x65584966);
                offset += number(fresh, offset) + 12;
            }
        } finally { decoded.recycle(); }
    }

    @Test public void exactOwnPackageSourceRequiresCurrentPublishedReference() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String own = context.getPackageName();
        AppIconExchange.Authority authority = new AppIconExchange.Authority() {
            @Override public boolean current(RemoteStore.Peer peer) { return true; }
            @Override public boolean published(RemoteStore.Peer peer, String name) { return own.equals(name); }
            @Override public boolean mirrored(RemoteStore.Peer peer, String name) { return false; }
            @Override public java.util.List<String> wanted(RemoteStore.Peer peer) { return Collections.emptyList(); }
        };
        Constructor<RemoteStore.Peer> constructor = RemoteStore.Peer.class.getDeclaredConstructor(String.class, String.class,
                String.class, RemoteProtocol.Role.class, RemoteStore.State.class, long.class, String.class);
        constructor.setAccessible(true);
        RemoteStore.Peer peer = constructor.newInstance(PEER, GENERATION, "Synthetic", RemoteProtocol.Role.SUB, RemoteStore.State.ACTIVE, 0L, "");
        AndroidAppIconSource source = new AndroidAppIconSource(context, authority);
        byte[] png = source.rasterize(peer, own); assertNotNull(png);
        IconPng.validate(new RemoteAppIcon(PEER, GENERATION, own,
                new RemoteAppIcon.Descriptor(RemoteProtocol.sha256(png), png.length, 128, 128)), png);
        boolean denied = false;
        try { source.rasterize(peer, "example.not_shared"); } catch (SecurityException expected) { denied = true; }
        assertTrue(denied);
    }

    @Test public void animatedPngControlChunksAreRejected() throws Exception {
        byte[] original = png(); ByteArrayOutputStream tagged = new ByteArrayOutputStream(); tagged.write(original, 0, 33);
        java.io.DataOutputStream chunk = new java.io.DataOutputStream(tagged);
        byte[] animation = {0, 0, 0, 1, 0, 0, 0, 0}; chunk.writeInt(animation.length); chunk.writeInt(0x6163544c); chunk.write(animation);
        CRC32 crc = new CRC32(); crc.update(new byte[] {97, 99, 84, 76}); crc.update(animation); chunk.writeInt((int) crc.getValue());
        tagged.write(original, 33, original.length - 33); byte[] animated = tagged.toByteArray();
        RemoteAppIcon icon = identity(animated); icon.validatePng(animated);
        boolean rejected = false;
        try { IconPng.validate(icon, animated); } catch (IllegalArgumentException expected) { rejected = true; }
        assertTrue(rejected);
    }

    private static byte[] png() {
        Bitmap bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888); bitmap.eraseColor(Color.RED);
        try { ByteArrayOutputStream output = new ByteArrayOutputStream(); assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)); return output.toByteArray(); }
        finally { bitmap.recycle(); }
    }
    private static RemoteAppIcon identity(byte[] bytes) {
        return new RemoteAppIcon(PEER, GENERATION, "example.shared", new RemoteAppIcon.Descriptor(RemoteProtocol.sha256(bytes), bytes.length, 128, 128));
    }
    private static int number(byte[] bytes, int offset) {
        return (bytes[offset] & 255) << 24 | (bytes[offset + 1] & 255) << 16 | (bytes[offset + 2] & 255) << 8 | bytes[offset + 3] & 255;
    }
    private static void put(byte[] bytes, int offset, int value) {
        bytes[offset] = (byte) (value >>> 24); bytes[offset + 1] = (byte) (value >>> 16);
        bytes[offset + 2] = (byte) (value >>> 8); bytes[offset + 3] = (byte) value;
    }
}
