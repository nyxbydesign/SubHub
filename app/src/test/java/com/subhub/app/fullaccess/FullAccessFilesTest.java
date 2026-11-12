package com.subhub.app.fullaccess;

import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class FullAccessFilesTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void devicePermissionGrantedForDemoDoesNotExpandPairedAccess() {
        assertTrue(FullAccessFiles.sharedRootsAuthorized(true, true, -1, -1));
        assertFalse(FullAccessFiles.sharedRootsAuthorized(false, true, -1, 42));
        assertTrue(FullAccessFiles.sharedRootsAuthorized(false, true, 42, 42));
        assertFalse(FullAccessFiles.sharedRootsAuthorized(false, true, 42, 43));
        assertFalse(FullAccessFiles.sharedRootsAuthorized(false, true, 42, -1));
        assertTrue(FullAccessFiles.sharedRootsAuthorized(false, true, 0, 42));
        assertFalse(FullAccessFiles.sharedRootsAuthorized(false, false, 0, 42));
    }

    @Test
    public void legacyVolumeComesFromExactAppStorageDirectory() throws IOException {
        Path primary = temporary.newFolder("primary").toPath().toRealPath();
        Path card = temporary.newFolder("card").toPath().toRealPath();
        String app = "com.subhub.app";
        Path first = primary.resolve("Android").resolve("data").resolve(app).resolve("files");
        Path second = card.resolve("Android").resolve("data").resolve(app).resolve("files");
        assertEquals(primary, FullAccessFiles.legacyVolumeRoot(first, app));
        assertEquals(card, FullAccessFiles.legacyVolumeRoot(second, app));
        assertNull(FullAccessFiles.legacyVolumeRoot(first, "com.another.app"));
        assertNull(FullAccessFiles.legacyVolumeRoot(first.resolve("DCIM"), app));
        assertNull(FullAccessFiles.legacyVolumeRoot(first.subpath(0, first.getNameCount()), app));
    }

    @Test
    public void documentIdsStayInsideTheSelectedVolumeAndFolder() {
        assertTrue(FullAccessFiles.allowedDocument("primary:DCIM", "primary:DCIM/camera.png"));
        assertTrue(FullAccessFiles.allowedDocument("primary:", "primary:Android/media/photo.png"));
        assertFalse(FullAccessFiles.allowedDocument("primary:DCIM", "secondary:DCIM/camera.png"));
        assertFalse(FullAccessFiles.allowedDocument("primary:DCIM", "primary:DCIM-backup/camera.png"));
        assertFalse(FullAccessFiles.allowedDocument("primary:DCIM", "primary:DCIM/../Download/other.txt"));
        assertFalse(FullAccessFiles.allowedDocument("primary:", "primary:Android/DATA/app/private.txt"));
        assertFalse(FullAccessFiles.allowedDocument("primary:", "primary:Android/obb/app/private.txt"));
        assertFalse(FullAccessFiles.allowedDocument("primary:", "primary:Android\\data/private.txt"));
    }

    @Test
    public void directVolumePathRejectsPrivateAndSiblingDirectories() throws IOException {
        Path shared = temporary.newFolder("shared").toPath().toRealPath();
        Path safe = Files.createDirectories(shared.resolve("Android/media/app"));
        Path outside = temporary.newFolder("shared-other").toPath().toRealPath();
        Path hidden = Files.createDirectories(shared.resolve("Android/data/app"));
        assertEquals(shared, FullAccessFiles.checkedPath(shared, shared));
        assertEquals(safe, FullAccessFiles.checkedPath(shared, safe));
        assertThrows(IOException.class, () -> FullAccessFiles.checkedPath(shared, outside));
        assertThrows(IOException.class, () -> FullAccessFiles.checkedPath(shared, shared.resolve("../shared-other")));
        assertThrows(IOException.class, () -> FullAccessFiles.checkedPath(shared, hidden));
    }

    @Test
    public void directVolumePathRejectsSymbolicLinksAndTheirDescendants() throws IOException {
        Path shared = temporary.newFolder("shared").toPath().toRealPath();
        Path outside = temporary.newFolder("outside").toPath().toRealPath();
        Path link = shared.resolve("link");
        Files.createFile(outside.resolve("private.txt"));
        try {
            Files.createSymbolicLink(link, outside);
        } catch (IOException | UnsupportedOperationException unavailable) {
            Assume.assumeNoException(unavailable);
        }
        assertThrows(IOException.class, () -> FullAccessFiles.checkedPath(shared, link));
        assertThrows(IOException.class, () -> FullAccessFiles.checkedPath(shared, link.resolve("private.txt")));
    }

    @Test
    public void downloadNamesCannotBecomePathsOrDeviceNames() {
        assertEquals("File", FullAccessFiles.safeName(".."));
        assertEquals(".._private.txt", FullAccessFiles.safeName("../private.txt"));
        assertEquals("_CON.txt", FullAccessFiles.safeName("CON.txt"));
        assertEquals("C__evil", FullAccessFiles.safeName("C:\\evil"));
        assertEquals("plain document.pdf", FullAccessFiles.safeName("plain document.pdf"));
        assertEquals("a".repeat(120), FullAccessFiles.safeName("a".repeat(150)));
    }
}
