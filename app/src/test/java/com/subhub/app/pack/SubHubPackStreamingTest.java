package com.subhub.app.pack;

import static org.junit.Assert.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.json.JSONObject;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.zip.*;

public class SubHubPackStreamingTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();
    @Test public void streamingArchiveRoundTripsMetadataAndVerifiedArtwork() throws Exception {
        SubHubPack pack=SubHubPack.blank(UUID.randomUUID().toString());pack.setSection("censor",new JSONObject().put("censor_type","box"));
        File artwork=temporary.newFile("artwork");byte[] bytes=new byte[1024*1024];new Random(7).nextBytes(bytes);Files.write(artwork.toPath(),bytes);
        File archive=temporary.newFile("contract.sub");try(FileOutputStream output=new FileOutputStream(archive)){SubHubPackArchive.writeStreaming(pack,Map.of("assets/censor/image.png",artwork),output);}
        try(SubHubPackArchive.Verified verified=SubHubPackArchive.openVerified(archive)) {
            assertEquals(pack.getId(),verified.pack.getId());assertTrue(verified.pack.getAssets().isEmpty());
            assertEquals(bytes.length,verified.assets.get("assets/censor/image.png").length);
            try(InputStream input=verified.openAsset("assets/censor/image.png")){assertArrayEquals(bytes,input.readAllBytes());}
            assertThrows(IOException.class,()->verified.openAsset("assets/censor/unknown.png"));
        }
        try(FileInputStream input=new FileInputStream(archive)){assertArrayEquals(bytes,SubHubPackArchive.read(input).getAssets().get("assets/censor/image.png"));}
    }
    @Test public void verifiedArchiveRejectsCorruptArtworkAndUnexpectedEntries() throws Exception {
        SubHubPack pack=SubHubPack.blank(UUID.randomUUID().toString());pack.setSection("censor",new JSONObject().put("censor_type","box"));
        File artwork=temporary.newFile("artwork");Files.write(artwork.toPath(),new byte[]{1,2,3});
        File archive=temporary.newFile("contract.sub");try(FileOutputStream output=new FileOutputStream(archive)){SubHubPackArchive.writeStreaming(pack,Map.of("assets/censor/image.png",artwork),output);}
        File corrupt=temporary.newFile("corrupt.sub");
        try(ZipFile original=new ZipFile(archive);ZipOutputStream output=new ZipOutputStream(new FileOutputStream(corrupt))) {
            Enumeration<? extends ZipEntry> entries=original.entries();while(entries.hasMoreElements()) {
                ZipEntry entry=entries.nextElement();output.putNextEntry(new ZipEntry(entry.getName()));
                if(entry.getName().startsWith("assets/"))output.write(new byte[]{4,5,6});else try(InputStream input=original.getInputStream(entry)){input.transferTo(output);}output.closeEntry();
            }
        }
        assertThrows(IOException.class,()->SubHubPackArchive.openVerified(corrupt));
        assertThrows(IOException.class,()->SubHubPackArchive.writeStreaming(pack,Map.of("assets/../unsafe",artwork),new ByteArrayOutputStream()));
    }
}
