package com.subhub.app.update;

import android.content.Context;
import android.os.Build;

import com.subhub.app.BuildConfig;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Read-only client for SubHub's public GitHub Releases feed. */
public final class GitHubReleaseRepository {
    static final String RELEASES_URL = "https://api.github.com/repos/confiteor48/SubHub/releases?per_page=30";
    static final String LATEST_STABLE_URL = "https://api.github.com/repos/confiteor48/SubHub/releases/latest";
    private static final int MAX_RESPONSE = 2 * 1024 * 1024;
    private final Context context;
    private final UpdateStateStore state;
    private final String releasesUrl;
    private final String latestStableUrl;

    public enum Failure { OFFLINE, RATE_LIMITED, SERVER, INVALID_RELEASE }

    public static final class Result {
        public final UpdateCandidate candidate;
        public final Failure failure;
        public final String detail;
        private Result(UpdateCandidate candidate, Failure failure, String detail) {
            this.candidate = candidate;
            this.failure = failure;
            this.detail = detail;
        }
        public static Result success(UpdateCandidate candidate) { return new Result(candidate, null, ""); }
        public static Result failure(Failure failure, String detail) { return new Result(null, failure, detail); }
        public boolean succeeded() { return failure == null; }
    }

    static final class Release {
        final SemanticVersion version;
        final String tag;
        final String body;
        final String htmlUrl;
        final String manifestUrl;
        final String publishedAt;
        final boolean prerelease;
        Release(SemanticVersion version, String tag, String body, String htmlUrl,
                String manifestUrl, String publishedAt, boolean prerelease) {
            this.version = version;
            this.tag = tag;
            this.body = body;
            this.htmlUrl = htmlUrl;
            this.manifestUrl = manifestUrl;
            this.publishedAt = publishedAt;
            this.prerelease = prerelease;
        }
    }

    public GitHubReleaseRepository(Context context) {
        this(context, RELEASES_URL, LATEST_STABLE_URL);
    }

    GitHubReleaseRepository(Context context, String releasesUrl, String latestStableUrl) {
        this.context = context.getApplicationContext();
        state = new UpdateStateStore(this.context);
        this.releasesUrl = releasesUrl;
        this.latestStableUrl = latestStableUrl;
    }

    public Result check() {
        try {
            final long revision;
            final boolean devUpdates;
            final String etag;
            synchronized (UpdateStateStore.class) {
                revision = state.channelRevision();
                devUpdates = state.devUpdates();
                etag = state.etag();
            }
            HttpResponse response = get(releasesUrl, etag, MAX_RESPONSE);
            if (response.code == HttpURLConnection.HTTP_NOT_MODIFIED) {
                synchronized (UpdateStateStore.class) {
                    if (revision != state.channelRevision()) return Result.success(null);
                    state.setLastCheck(System.currentTimeMillis());
                    UpdateCandidate cached = state.candidate();
                    return Result.success(cached != null && cached.manifest.versionCode > BuildConfig.VERSION_CODE
                            ? cached : null);
                }
            }
            if (response.code == 403 && "0".equals(response.rateRemaining)) {
                return Result.failure(Failure.RATE_LIMITED, "GitHub API rate limit reached");
            }
            if (response.code < 200 || response.code >= 300) {
                return Result.failure(Failure.SERVER, "GitHub returned HTTP " + response.code);
            }
            JSONArray releases = new JSONArray(response.body);
            List<Release> candidates = parseReleases(releases);
            // Frequent dev pushes must not push the latest stable off the bounded feed page.
            HttpResponse stable = get(latestStableUrl, "", MAX_RESPONSE);
            if (stable.code >= 200 && stable.code < 300) {
                List<Release> latest = parseReleases(new JSONArray().put(new JSONObject(stable.body)));
                for (Release release : latest) {
                    if (!release.prerelease && candidates.stream().noneMatch(item -> item.tag.equals(release.tag))) {
                        candidates.add(release);
                    }
                }
            } else if (stable.code != HttpURLConnection.HTTP_NOT_FOUND) {
                return Result.failure(stable.code == 403 && "0".equals(stable.rateRemaining)
                        ? Failure.RATE_LIMITED : Failure.SERVER, "Stable release lookup failed");
            }
            candidates.sort(Comparator.comparing((Release value) -> value.version).reversed());
            List<ReleaseHistoryItem> history = ReleaseHistoryCatalog.merge(
                    ReleaseHistoryCatalog.fromReleases(candidates),
                    ReleaseHistoryCatalog.bundled(context));
            UpdateCandidate available = selectCandidate(candidates, devUpdates, BuildConfig.VERSION_CODE,
                    Build.VERSION.SDK_INT, Build.SUPPORTED_ABIS, release -> {
                HttpResponse manifestResponse = get(release.manifestUrl, "", 1024 * 1024);
                return manifestResponse.code >= 200 && manifestResponse.code < 300
                        ? UpdateManifest.parse(manifestResponse.body) : null;
            });
            history = ReleaseHistoryCatalog.withCandidate(history, available);
            if (!state.saveCheck(revision, response.etag, history, available)) return Result.success(null);
            return Result.success(available);
        } catch (java.net.UnknownHostException | java.net.SocketTimeoutException exception) {
            return Result.failure(Failure.OFFLINE, exception.getClass().getSimpleName());
        } catch (Exception exception) {
            return Result.failure(Failure.INVALID_RELEASE, exception.getClass().getSimpleName());
        }
    }

    interface ManifestLoader { UpdateManifest load(Release release) throws Exception; }

    static UpdateCandidate selectCandidate(List<Release> releases, boolean devUpdates,
            long installedCode, int sdk, String[] abis, ManifestLoader loader) {
        UpdateCandidate available = null;
        for (Release release : releases) {
            if ((!devUpdates && release.prerelease) || release.manifestUrl.isEmpty()) continue;
            try {
                UpdateManifest manifest = loader.load(release);
                if (manifest == null || !release.tag.equals(manifest.tag)
                        || !UpdateManifest.PACKAGE.equals(manifest.packageName)
                        || manifest.versionCode <= installedCode || manifest.minSdk > sdk
                        || manifest.selectAsset(abis) == null) continue;
                // Android's install ordering is authoritative, including dev -> stable transitions.
                if (available == null || manifest.versionCode > available.manifest.versionCode
                        || (manifest.versionCode == available.manifest.versionCode
                        && available.prerelease && !release.prerelease)) {
                    available = new UpdateCandidate(manifest, releaseNotes(manifest, release.body),
                            release.htmlUrl, release.prerelease);
                }
            } catch (Exception ignored) {
                // A malformed or unavailable individual release must not mask a valid newer build.
            }
        }
        return available;
    }

    static List<Release> parseReleases(JSONArray releases) {
        List<Release> parsed = new ArrayList<>();
        for (int index = 0; index < releases.length(); index++) {
            JSONObject release = releases.optJSONObject(index);
            if (release == null || release.optBoolean("draft", true)) continue;
            String tag = release.optString("tag_name", "");
            if (!tag.startsWith("v")) continue;
            SemanticVersion version;
            try { version = SemanticVersion.parse(tag); }
            catch (IllegalArgumentException ignored) { continue; }
            String expectedManifest = "SubHub-" + tag.substring(1) + "-update.json";
            String manifestUrl = "";
            JSONArray assets = release.optJSONArray("assets");
            if (assets != null) for (int assetIndex = 0; assetIndex < assets.length(); assetIndex++) {
                JSONObject asset = assets.optJSONObject(assetIndex);
                if (asset != null && expectedManifest.equals(asset.optString("name"))) {
                    manifestUrl = asset.optString("browser_download_url", "");
                    break;
                }
            }
            parsed.add(new Release(version, tag, release.optString("body", ""),
                    release.optString("html_url", ""), manifestUrl,
                    release.optString("published_at", ""),
                    release.optBoolean("prerelease", false) || version.isPrerelease()));
        }
        return parsed;
    }

    static String releaseNotes(UpdateManifest manifest, String releaseBody) {
        return manifest.releaseNotes.isEmpty()
                ? ReleaseNotesFormatter.changelogOnly(releaseBody) : manifest.releaseNotes;
    }

    static final class HttpResponse {
        final int code;
        final String body;
        final String etag;
        final String rateRemaining;
        HttpResponse(int code, String body, String etag, String rateRemaining) {
            this.code = code;
            this.body = body;
            this.etag = etag;
            this.rateRemaining = rateRemaining;
        }
    }

    static HttpResponse get(String address, String etag, int limit) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(20_000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        connection.setRequestProperty("X-GitHub-Api-Version", "2026-03-10");
        connection.setRequestProperty("User-Agent", "SubHub-Android/" + BuildConfig.VERSION_NAME);
        if (etag != null && !etag.isEmpty()) connection.setRequestProperty("If-None-Match", etag);
        int code = connection.getResponseCode();
        String body = "";
        if (code != HttpURLConnection.HTTP_NOT_MODIFIED) {
            java.io.InputStream raw = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
            if (raw != null) try (BufferedInputStream input = new BufferedInputStream(raw);
                    ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) >= 0) {
                    if (output.size() + count > Math.min(limit, MAX_RESPONSE)) {
                        throw new IllegalStateException("Response too large");
                    }
                    output.write(buffer, 0, count);
                }
                body = output.toString(StandardCharsets.UTF_8.name());
            }
        }
        HttpResponse response = new HttpResponse(code, body, connection.getHeaderField("ETag"),
                connection.getHeaderField("X-RateLimit-Remaining"));
        connection.disconnect();
        return response;
    }
}
