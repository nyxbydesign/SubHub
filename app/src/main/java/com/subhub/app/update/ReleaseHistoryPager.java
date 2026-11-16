package com.subhub.app.update;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Session state for browsing release notes independently of update checks. */
final class ReleaseHistoryPager {
    record Request(int page, long generation) {}

    private final List<ReleaseHistoryItem> bundled;
    private final String installedVersion;
    private final Map<String, ReleaseHistoryItem> fetchedByTag = new LinkedHashMap<>();
    private List<ReleaseHistoryItem> cached;
    private ReleaseHistoryItem candidate;
    private long channelRevision = -1L;
    private boolean development;
    private long generation;
    private int nextPage = 1;
    private int displayPage;
    private List<ReleaseHistoryItem> eligible = List.of();
    private ReleaseHistoryItem installed;
    private Request inFlight;
    private GitHubReleaseRepository.Failure failure;
    private boolean noOlderMatches;

    ReleaseHistoryPager(List<ReleaseHistoryItem> bundled, String installedVersion) {
        this.bundled = bundled;
        this.installedVersion = installedVersion;
    }

    void update(List<ReleaseHistoryItem> cache, UpdateCandidate available, long revision, boolean devUpdates) {
        boolean channelChanged = revision != channelRevision || development != devUpdates;
        boolean cacheChanged = cache != cached;
        boolean candidateChanged = !sameCandidate(candidate, available);
        if (!channelChanged && !cacheChanged && !candidateChanged) return;

        if (channelChanged) displayPage = 0;
        if (channelChanged || cacheChanged) {
            generation++;
            failure = null;
            noOlderMatches = false;
        }
        if (cacheChanged) {
            // A refreshed head can shift the numbered GitHub pages. Keep known releases and
            // verify the feed again before trusting the old cursor or any in-flight response.
            nextPage = 1;
        }
        cached = cache;
        if (candidateChanged) candidate = available == null ? null : ReleaseHistoryCatalog.candidateItem(available);
        channelRevision = revision;
        development = devUpdates;
        rebuild();
    }

    Request older() {
        if (loading()) return null;
        failure = null;
        noOlderMatches = false;
        if (hasOlderRows()) {
            displayPage++;
        } else if (nextPage > 0) {
            inFlight = new Request(nextPage, generation);
        }
        return inFlight;
    }

    void newer() {
        if (displayPage > 0) displayPage--;
        failure = null;
        noOlderMatches = false;
    }

    void finish(Request request, GitHubReleaseRepository.HistoryPage page) {
        if (request != inFlight) return;
        inFlight = null;
        if (request.generation() != generation) return;
        failure = page.failure();
        if (failure != null) return;

        boolean wasFull = start() + ReleaseHistoryCatalog.PAGE_SIZE <= eligible.size();
        int previousCount = eligible.size();
        for (ReleaseHistoryItem item : page.items()) fetchedByTag.put(item.tag, item);
        nextPage = page.hasNext() ? request.page() + 1 : 0;
        rebuild();
        if (wasFull && hasOlderRows()) displayPage++;
        noOlderMatches = previousCount == eligible.size() && nextPage > 0;
    }

    void cancel(Request request) {
        if (request == inFlight) inFlight = null;
    }

    boolean loading() { return inFlight != null; }
    boolean hasNewer() { return displayPage > 0; }
    boolean hasOlder() { return hasOlderRows() || nextPage > 0; }
    int pageIndex() { return displayPage; }
    ReleaseHistoryItem installed() { return installed; }
    GitHubReleaseRepository.Failure failure() { return failure; }
    boolean noOlderMatches() { return noOlderMatches; }

    List<ReleaseHistoryItem> rows() {
        int start = start();
        return eligible.subList(start, Math.min(eligible.size(), start + ReleaseHistoryCatalog.PAGE_SIZE));
    }

    private boolean hasOlderRows() {
        return start() + ReleaseHistoryCatalog.PAGE_SIZE < eligible.size();
    }

    private int start() {
        return displayPage * ReleaseHistoryCatalog.PAGE_SIZE;
    }

    private void rebuild() {
        // Fresh worker data and the compatible candidate take precedence over older observations.
        List<ReleaseHistoryItem> history = ReleaseHistoryCatalog.mergeSources(
                bundled, fetchedByTag.values(), cached, candidate == null ? List.of() : List.of(candidate));
        installed = null;
        for (ReleaseHistoryItem item : history) {
            if (ReleaseHistoryCatalog.isInstalled(item, installedVersion)) {
                installed = item;
                break;
            }
        }
        eligible = ReleaseHistoryCatalog.forDisplay(history, development, installedVersion);
        displayPage = Math.min(displayPage, Math.max(0, eligible.size() - 1) / ReleaseHistoryCatalog.PAGE_SIZE);
    }

    private static boolean sameCandidate(ReleaseHistoryItem item, UpdateCandidate available) {
        if (item == null || available == null) return item == null && available == null;
        return item.prerelease == available.prerelease && item.tag.equals(available.manifest.tag)
                && item.versionName.equals(available.manifest.versionName) && item.notes.equals(available.notes.trim())
                && item.publishedAt.equals(available.publishedAt.trim()) && item.htmlUrl.equals(available.releaseUrl.trim());
    }
}
