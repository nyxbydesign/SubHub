package com.subhub.app.update;

import android.content.Context;

import com.subhub.app.R;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Merges live GitHub notes with immutable-release backfill bundled in the APK. */
public final class ReleaseHistoryCatalog {
    static final int MAX_ITEMS = 16;

    private ReleaseHistoryCatalog() {}

    static List<ReleaseHistoryItem> fromReleases(List<GitHubReleaseRepository.Release> releases) {
        List<ReleaseHistoryItem> result = new ArrayList<>();
        if (releases == null) return result;
        for (GitHubReleaseRepository.Release release : releases) {
            String notes = ReleaseNotesFormatter.changelogOnly(release.body);
            String versionName = release.tag.startsWith("v")
                    ? release.tag.substring(1) : release.tag;
            result.add(new ReleaseHistoryItem(versionName, release.tag, notes,
                    release.htmlUrl, release.publishedAt, release.prerelease));
        }
        return result;
    }

    public static List<ReleaseHistoryItem> bundled(Context context) {
        try (InputStream input = context.getResources().openRawResource(R.raw.release_history);
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
            return ReleaseHistoryItem.decode(output.toString(StandardCharsets.UTF_8.name()));
        } catch (Exception ignored) {
            return new ArrayList<>();
        }
    }

    static List<ReleaseHistoryItem> merge(List<ReleaseHistoryItem> live,
            List<ReleaseHistoryItem> bundled) {
        Map<String, ReleaseHistoryItem> byTag = new LinkedHashMap<>();
        if (bundled != null) for (ReleaseHistoryItem item : bundled) put(byTag, item, false);
        if (live != null) for (ReleaseHistoryItem item : live) put(byTag, item, true);
        List<ReleaseHistoryItem> merged = new ArrayList<>(byTag.values());
        merged.sort(Comparator.comparing(ReleaseHistoryCatalog::version).reversed());
        if (merged.size() > MAX_ITEMS) return new ArrayList<>(merged.subList(0, MAX_ITEMS));
        return merged;
    }

    static List<ReleaseHistoryItem> withCandidate(List<ReleaseHistoryItem> history,
            UpdateCandidate candidate) {
        if (candidate == null) return history == null ? new ArrayList<>() : history;
        ReleaseHistoryItem available = new ReleaseHistoryItem(
                candidate.manifest.versionName, candidate.manifest.tag, candidate.notes,
                candidate.releaseUrl, "", candidate.prerelease);
        List<ReleaseHistoryItem> live = new ArrayList<>();
        live.add(available);
        return merge(live, history);
    }

    private static void put(Map<String, ReleaseHistoryItem> target, ReleaseHistoryItem item,
            boolean preferMetadata) {
        if (item == null || item.tag.isEmpty()) return;
        ReleaseHistoryItem previous = target.get(item.tag);
        if (previous == null) {
            target.put(item.tag, item);
            return;
        }
        String notes = item.notes.isEmpty() ? previous.notes : item.notes;
        String url = item.htmlUrl.isEmpty() ? previous.htmlUrl : item.htmlUrl;
        String published = item.publishedAt.isEmpty() ? previous.publishedAt : item.publishedAt;
        String version = item.versionName.isEmpty() ? previous.versionName : item.versionName;
        boolean preview = preferMetadata ? item.prerelease : previous.prerelease;
        target.put(item.tag, new ReleaseHistoryItem(
                version, item.tag, notes, url, published, preview));
    }

    private static SemanticVersion version(ReleaseHistoryItem item) {
        try {
            return SemanticVersion.parse(item.tag);
        } catch (IllegalArgumentException ignored) {
            return SemanticVersion.parse("0.0.0");
        }
    }
}
