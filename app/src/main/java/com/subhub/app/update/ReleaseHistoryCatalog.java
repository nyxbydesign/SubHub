package com.subhub.app.update;

import android.content.Context;

import com.subhub.app.R;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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

    /** Retain stable and development rows until the chosen channel is projected for display. */
    static List<ReleaseHistoryItem> merge(List<ReleaseHistoryItem> live,
            List<ReleaseHistoryItem> bundled) {
        Map<String, ReleaseHistoryItem> byTag = new LinkedHashMap<>();
        if (bundled != null) for (ReleaseHistoryItem item : bundled) put(byTag, item, false);
        if (live != null) for (ReleaseHistoryItem item : live) put(byTag, item, true);
        List<ReleaseHistoryItem> merged = new ArrayList<>(byTag.values());
        merged.sort(Comparator.comparing(ReleaseHistoryCatalog::publication)
                .thenComparing(ReleaseHistoryCatalog::version).reversed());
        return merged;
    }

    static boolean isInstalled(ReleaseHistoryItem item, String installedVersion) {
        return item.tag.equals("v" + installedVersion);
    }

    static List<ReleaseHistoryItem> visible(List<ReleaseHistoryItem> sortedHistory, boolean devUpdates,
            String installedVersion) {
        if (devUpdates) return sortedHistory.subList(0, Math.min(sortedHistory.size(), MAX_ITEMS));
        List<ReleaseHistoryItem> result = new ArrayList<>(MAX_ITEMS);
        ReleaseHistoryItem installed = null;
        for (ReleaseHistoryItem item : sortedHistory) {
            if (isInstalled(item, installedVersion)) {
                installed = item;
                if (result.size() < MAX_ITEMS) result.add(item);
            } else if (!item.prerelease && result.size() < MAX_ITEMS) {
                result.add(item);
            }
        }
        if (installed == null && isPrerelease(installedVersion)) {
            // The installed development build may predate the feed or have no cached notes.
            installed = new ReleaseHistoryItem(installedVersion, "v" + installedVersion,
                    "", "", "", true);
        }
        if (installed != null && !result.contains(installed)) {
            if (result.size() == MAX_ITEMS) result.remove(MAX_ITEMS - 1);
            result.add(installed);
        }
        return result;
    }

    private static boolean isPrerelease(String versionName) {
        try {
            return SemanticVersion.parse(versionName).isPrerelease();
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    static List<ReleaseHistoryItem> withCandidate(List<ReleaseHistoryItem> history,
            UpdateCandidate candidate) {
        if (candidate == null) return merge(null, history);
        ReleaseHistoryItem available = new ReleaseHistoryItem(
                candidate.manifest.versionName, candidate.manifest.tag, candidate.notes,
                candidate.releaseUrl, candidate.publishedAt, candidate.prerelease);
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

    private static Instant publication(ReleaseHistoryItem item) {
        String published = item.publishedAt;
        try {
            // A bundled historical date has day-level precision only. It sorts
            // before known releases later that day without inventing display text.
            return published.length() == 10
                    ? LocalDate.parse(published).atStartOfDay().toInstant(ZoneOffset.UTC)
                    : OffsetDateTime.parse(published).toInstant();
        } catch (DateTimeException ignored) {
            return Instant.MIN;
        }
    }
}
