package com.subhub.app.update;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public final class GitHubHttpTransportTest {
    private ServerSocket server;
    private String root;
    private Thread responder;
    private final Map<String, byte[]> manifestBodies = new ConcurrentHashMap<>();
    private final AtomicInteger manifestRequests = new AtomicInteger();

    @Before public void start() throws Exception {
        server = new ServerSocket(0, 16, java.net.InetAddress.getByName("127.0.0.1"));
        root = "http://127.0.0.1:" + server.getLocalPort();
        responder = new Thread(this::serve, "update-http-test");
        responder.start();
    }

    @After public void stop() throws Exception {
        server.close();
        responder.join(1000L);
    }

    private void serve() {
        while (!server.isClosed()) {
            try (Socket socket = server.accept()) {
                BufferedReader input = new BufferedReader(new InputStreamReader(
                        socket.getInputStream(), StandardCharsets.US_ASCII));
                String first = input.readLine();
                String path = first == null ? "/" : first.split(" ")[1];
                String header;
                do { header = input.readLine(); } while (header != null && !header.isEmpty());
                OutputStream output = socket.getOutputStream();
                byte[] manifest = manifestBodies.get(path);
                if (manifest != null) {
                    manifestRequests.incrementAndGet();
                    write(output, "HTTP/1.1 200 OK\r\nContent-Length: " + manifest.length
                            + "\r\nConnection: close\r\n\r\n");
                    output.write(manifest);
                    output.flush();
                } else if (path.equals("/cached")) write(output, "HTTP/1.1 304 Not Modified\r\nConnection: close\r\n\r\n");
                else if (path.equals("/limited")) write(output, "HTTP/1.1 403 Forbidden\r\nX-RateLimit-Remaining: 0\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
                else if (path.equals("/redirect")) write(output, "HTTP/1.1 302 Found\r\nLocation: " + root + "/ok\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
                else write(output, "HTTP/1.1 200 OK\r\nETag: test-etag\r\nContent-Length: 12\r\nConnection: close\r\n\r\nrelease-list");
            } catch (Exception ignored) { }
        }
    }

    private static void write(OutputStream output, String value) throws Exception {
        output.write(value.getBytes(StandardCharsets.US_ASCII));
        output.flush();
    }

    @Test public void readsSuccessfulResponseAndEtag() throws Exception {
        GitHubReleaseRepository.HttpResponse response = GitHubReleaseRepository.get(root + "/ok", "", 1024);
        assertEquals(200, response.code);
        assertEquals("release-list", response.body);
        assertEquals("test-etag", response.etag);
    }

    @Test public void preservesNotModifiedStatus() throws Exception {
        assertEquals(304, GitHubReleaseRepository.get(root + "/cached", "test-etag", 1024).code);
    }

    @Test public void followsAssetRedirects() throws Exception {
        assertEquals("release-list", GitHubReleaseRepository.get(root + "/redirect", "", 1024).body);
    }

    @Test public void exposesRateLimitHeaders() throws Exception {
        GitHubReleaseRepository.HttpResponse response = GitHubReleaseRepository.get(root + "/limited", "", 1024);
        assertEquals(403, response.code);
        assertEquals("0", response.rateRemaining);
    }

    @Test public void warmFeedFetchesOnlyTheNewManifestAndPreservesChannelChoice() throws Exception {
        GitHubReleaseRepository.ManifestCache cache = new GitHubReleaseRepository.ManifestCache();
        JSONObject stable = publish("v0.6.4", 100, true);
        JSONArray firstPage = new JSONArray().put(stable);
        JSONArray nextPage = new JSONArray().put(stable);
        for (int sequence = 1; sequence <= 30; sequence++) {
            JSONObject item = publish("v0.6.5-dev." + sequence, 100 + sequence, true);
            firstPage.put(item);
            if (sequence > 1) nextPage.put(item);
        }
        nextPage.put(publish("v0.6.5-dev.31", 131, true));

        assertEquals("v0.6.5-dev.30", select(cache, firstPage, true).manifest.tag);
        assertEquals(31, manifestRequests.get());
        assertEquals("v0.6.5-dev.31", select(cache, nextPage, true).manifest.tag);
        assertEquals(32, manifestRequests.get());
        assertEquals("v0.6.4", select(cache, nextPage, false).manifest.tag);
        assertEquals(32, manifestRequests.get());
    }

    @Test public void changedDigestRequiresMatchingBytesBeforeReuse() throws Exception {
        GitHubReleaseRepository.ManifestCache cache = new GitHubReleaseRepository.ManifestCache();
        String tag = "v0.6.4";
        JSONObject first = publish(tag, 100, true);
        byte[] firstBody = manifestBodies.get("/" + tag);
        assertEquals(100L, select(cache, new JSONArray().put(first), false).manifest.versionCode);

        JSONObject replacement = publish(tag, 101, true);
        byte[] replacementBody = manifestBodies.put("/" + tag, firstBody);
        JSONArray feed = new JSONArray().put(replacement);
        assertNull(select(cache, feed, false));
        manifestBodies.put("/" + tag, replacementBody);
        assertEquals(101L, select(cache, feed, false).manifest.versionCode);
        assertEquals(101L, select(cache, feed, false).manifest.versionCode);
        assertEquals(3, manifestRequests.get());
    }

    @Test public void mutableAndLegacyManifestsKeepFetching() throws Exception {
        GitHubReleaseRepository.ManifestCache cache = new GitHubReleaseRepository.ManifestCache();
        JSONObject mutable = publish("v0.6.3", 100, false);
        JSONObject legacy = publish("v0.6.4", 101, true);
        legacy.getJSONArray("assets").getJSONObject(0).remove("digest");
        JSONObject malformed = publish("v0.6.5", 102, true);
        malformed.getJSONArray("assets").getJSONObject(0).put("digest", "unknown");
        JSONArray feed = new JSONArray().put(mutable).put(legacy).put(malformed);
        assertEquals(102L, select(cache, feed, false).manifest.versionCode);
        assertEquals(102L, select(cache, feed, false).manifest.versionCode);
        assertEquals(6, manifestRequests.get());
    }

    private JSONObject publish(String tag, long code, boolean immutable) throws Exception {
        UpdateManifest manifest = GitHubReleaseRepositoryTest.manifest(tag, code, 26, "universal");
        JSONObject json = new JSONObject(manifest.json()).put("releaseNotes", "Notes — naïve");
        byte[] bytes = json.toString().getBytes(StandardCharsets.UTF_8);
        manifestBodies.put("/" + tag, bytes);
        String digest = java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        JSONObject asset = new JSONObject().put("name", "SubHub-" + tag.substring(1) + "-update.json")
                .put("browser_download_url", root + "/" + tag).put("digest", "sha256:" + digest);
        return new JSONObject().put("tag_name", tag).put("draft", false).put("immutable", immutable)
                .put("assets", new JSONArray().put(asset));
    }

    private static UpdateCandidate select(GitHubReleaseRepository.ManifestCache cache, JSONArray feed,
            boolean dev) {
        return GitHubReleaseRepository.selectCandidate(GitHubReleaseRepository.parseReleases(feed), dev,
                20, 35, new String[]{"arm64-v8a"}, cache::load);
    }
}
