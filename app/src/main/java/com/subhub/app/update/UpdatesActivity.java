package com.subhub.app.update;

import com.subhub.app.util.PrimaryHeader;

import android.app.DownloadManager;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.format.DateFormat;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;

import com.subhub.app.BuildConfig;
import com.subhub.app.R;
import com.subhub.app.databinding.ActivityUpdatesBinding;

import java.io.File;
import java.time.ZoneId;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Maintenance surface shared by Dom and Sub mode for signed GitHub releases. */
public final class UpdatesActivity extends AppCompatActivity {
    private ActivityUpdatesBinding binding;
    private UpdateStateStore state;
    private UpdateDownloadCoordinator downloads;
    private ReleaseHistoryPager history;
    private ReleaseHistoryPager.Request historyRequest;
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private final ExecutorService historyNetwork = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean checking;
    private boolean resumed;
    private boolean changingAutomatic;
    private boolean installAfterPermission;
    private boolean finalizingDownload;
    private boolean verifyingInstall;
    private long lastCheckBeforeFailure = -1L;
    private String historyFingerprint = "";
    private boolean historyDevUpdates;
    private final Map<String, Boolean> historyDisclosureOverrides = new HashMap<>();
    private final Runnable poll = new Runnable() {
        @Override public void run() {
            render();
            if (binding != null && resumed) main.postDelayed(this, 750L);
        }
    };
    private final ActivityResultLauncher<Intent> installPermission = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (installAfterPermission) {
                    installAfterPermission = false;
                    installVerified();
                }
            });

    public static final class HistoryModel extends ViewModel {
        private UpdateStateStore state;
        private ReleaseHistoryPager pager;
    }

    @Override protected void onCreate(Bundle stateBundle) {
        super.onCreate(stateBundle);
        binding = ActivityUpdatesBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        PrimaryHeader.bindSecondary(binding.getRoot(), R.string.update_title, false);
        HistoryModel model = new ViewModelProvider(this).get(HistoryModel.class);
        if (model.pager == null) {
            model.state = new UpdateStateStore(this);
            model.pager = new ReleaseHistoryPager(ReleaseHistoryCatalog.bundled(this), BuildConfig.VERSION_NAME);
        }
        state = model.state;
        history = model.pager;
        downloads = new UpdateDownloadCoordinator(this);
        PrimaryHeader.backButton(binding.getRoot()).setOnClickListener(view -> finish());
        binding.buttonCheck.setOnClickListener(view -> checkNow());
        binding.historyOlder.setOnClickListener(view -> olderReleases());
        binding.historyNewer.setOnClickListener(view -> {
            int page = history.pageIndex();
            history.newer();
            render();
            scrollToHistoryOnPageChange(page);
        });
        binding.buttonCancel.setOnClickListener(view -> {
            downloads.cancel();
            showFeedback(R.string.update_download_failed);
            render();
        });
        binding.automaticChecksTitle.setOnClickListener(view -> binding.automaticChecks.performClick());
        binding.devUpdatesTitle.setOnClickListener(view -> {
            if (binding.devUpdates.isEnabled()) binding.devUpdates.performClick();
        });
        changingAutomatic = true;
        binding.automaticChecks.setChecked(state.automaticChecks());
        changingAutomatic = false;
        binding.automaticChecks.setOnCheckedChangeListener((button, checked) -> {
            if (changingAutomatic) return;
            state.setAutomaticChecks(checked);
            UpdateScheduler.synchronize(this);
        });
        binding.devUpdates.setChecked(state.devUpdates());
        binding.devUpdates.setOnCheckedChangeListener((button, checked) -> {
            if (changingAutomatic) return;
            state.setDevUpdates(checked);
            UpdateScheduler.synchronize(this, false);
            render();
            checkNow();
        });
        render();
    }

    @Override protected void onRestoreInstanceState(Bundle savedInstanceState) {
        changingAutomatic = true;
        try {
            super.onRestoreInstanceState(savedInstanceState);
        } finally {
            changingAutomatic = false;
        }
        render();
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        render();
        main.removeCallbacks(poll);
        main.post(poll);
    }

    private void checkNow() {
        if (checking) return;
        checking = true;
        binding.buttonCheck.setEnabled(false);
        binding.devUpdates.setEnabled(false);
        showFeedback(R.string.update_checking);
        network.execute(() -> {
            GitHubReleaseRepository.Result result = new GitHubReleaseRepository(this).check();
            if (result.succeeded() && result.candidate != null) downloads.prepare(result.candidate, false);
            main.post(() -> {
                if (binding == null) return;
                checking = false;
                binding.buttonCheck.setEnabled(true);
                lastCheckBeforeFailure = result.succeeded() ? -1L : state.lastCheck();
                if (result.succeeded()) showFeedback("");
                else showFeedback(error(result.failure));
                render();
                main.removeCallbacks(poll);
                if (resumed) main.post(poll);
            });
        });
    }

    private int error(GitHubReleaseRepository.Failure failure) {
        if (failure == GitHubReleaseRepository.Failure.OFFLINE) return R.string.update_offline;
        if (failure == GitHubReleaseRepository.Failure.RATE_LIMITED) return R.string.update_rate_limited;
        if (failure == GitHubReleaseRepository.Failure.SERVER) return R.string.update_server_error;
        return R.string.update_invalid_release;
    }

    private void render() {
        if (binding == null) return;
        UpdateCandidate candidate;
        List<ReleaseHistoryItem> cachedHistory;
        long last;
        long channelRevision;
        boolean devUpdates;
        synchronized (UpdateStateStore.class) {
            candidate = state.candidate();
            last = state.lastCheck();
            devUpdates = state.devUpdates();
            cachedHistory = state.releaseHistory();
            channelRevision = state.channelRevision();
        }
        history.update(cachedHistory, candidate, channelRevision, devUpdates);
        if (candidate == null && (state.downloadId() >= 0 || !state.verifiedPath().isEmpty())) {
            downloads.cancel();
        }
        binding.updateCheckSummary.setText(checkSummary(candidate, last));
        int automaticTitle = devUpdates
                ? R.string.update_automatic_dev_toggle : R.string.update_automatic_toggle;
        binding.automaticChecksTitle.setText(automaticTitle);
        binding.automaticChecks.setContentDescription(getText(automaticTitle));
        binding.automaticChecks.setEnabled(true);
        binding.devUpdates.setEnabled(!checking);
        binding.automaticHelp.setText(R.string.update_automatic_help);
        changingAutomatic = true;
        binding.automaticChecks.setChecked(state.automaticChecks());
        binding.devUpdates.setChecked(devUpdates);
        changingAutomatic = false;
        binding.devUpdatesHelp.setText(R.string.update_dev_help);
        renderInstalledVersion();
        renderReleaseHistory(history.rows(), candidate, devUpdates);
        renderHistoryControls();
        binding.downloadGuideCard.setVisibility(candidate == null ? View.GONE : View.VISIBLE);
        if (candidate != null) {
            UpdateManifest.Asset asset = candidate.manifest.selectAsset(Build.SUPPORTED_ABIS);
            binding.downloadGuide.setText(getString(R.string.update_download_guide,
                    asset == null ? getString(R.string.update_download_universal) : asset.name));
        }

        String verifiedPath = state.verifiedPath();
        if (!verifiedPath.isEmpty() && new File(verifiedPath).isFile()) {
            binding.downloadProgress.setVisibility(View.GONE);
            binding.buttonAction.setVisibility(View.VISIBLE);
            binding.buttonAction.setText(R.string.update_install);
            binding.buttonAction.setOnClickListener(view -> installVerified());
            binding.buttonCancel.setVisibility(View.GONE);
            if (!checking) showFeedback(R.string.update_ready_body);
            return;
        }
        UpdateDownloadCoordinator.Status download = downloads.status();
        if (download != null && (download.state == DownloadManager.STATUS_PENDING
                || download.state == DownloadManager.STATUS_RUNNING
                || download.state == DownloadManager.STATUS_PAUSED)) {
            int percent = download.total > 0
                    ? (int) Math.min(100, download.downloaded * 100L / download.total) : 0;
            binding.downloadProgress.setVisibility(View.VISIBLE);
            binding.downloadProgress.setIndeterminate(download.total <= 0);
            binding.downloadProgress.setProgress(percent);
            binding.buttonAction.setVisibility(View.GONE);
            binding.buttonCancel.setVisibility(View.VISIBLE);
            if (!checking) showFeedback(getString(R.string.update_downloading, percent));
            return;
        }
        if (download != null && download.state == DownloadManager.STATUS_SUCCESSFUL) {
            binding.downloadProgress.setVisibility(View.VISIBLE);
            binding.downloadProgress.setIndeterminate(true);
            binding.buttonAction.setVisibility(View.GONE);
            binding.buttonCancel.setVisibility(View.GONE);
            if (!checking) showFeedback(R.string.update_verifying);
            finalizeDownload();
            return;
        }
        if (download != null && download.state == DownloadManager.STATUS_FAILED) {
            downloads.cancel();
            if (!checking) showFeedback(R.string.update_download_failed);
        }
        binding.downloadProgress.setVisibility(View.GONE);
        binding.buttonCancel.setVisibility(View.GONE);
        binding.buttonAction.setVisibility(candidate == null ? View.GONE : View.VISIBLE);
        binding.buttonAction.setText(R.string.update_download);
        binding.buttonAction.setOnClickListener(view -> beginDownload());
    }

    private CharSequence checkSummary(UpdateCandidate candidate, long last) {
        boolean available = candidate != null && candidate.manifest.versionCode > BuildConfig.VERSION_CODE;
        if (last <= 0) return getText(available ? R.string.update_status_available : R.string.update_not_checked);
        String checked = getString(R.string.update_last_checked, DateFormat.getTimeFormat(this).format(new Date(last)));
        if (!available && last == lastCheckBeforeFailure) return checked;
        String status = getString(available ? R.string.update_status_available : R.string.update_status_current);
        return getString(R.string.update_status_with_check, status, checked);
    }

    private void showFeedback(int message) { showFeedback(getText(message)); }

    private void showFeedback(CharSequence message) {
        binding.updateStatus.setText(message);
        binding.updateStatus.setVisibility(message.length() == 0 ? View.GONE : View.VISIBLE);
    }

    private void olderReleases() {
        int page = history.pageIndex();
        ReleaseHistoryPager.Request request = history.older();
        render();
        scrollToHistoryOnPageChange(page);
        if (request == null) return;
        historyRequest = request;
        historyNetwork.execute(() -> {
            GitHubReleaseRepository.HistoryPage result = GitHubReleaseRepository.historyPage(request.page());
            main.post(() -> {
                if (binding == null) return;
                // Observe any worker refresh or channel change before accepting the response.
                synchronized (UpdateStateStore.class) {
                    history.update(state.releaseHistory(), state.candidate(), state.channelRevision(), state.devUpdates());
                }
                historyRequest = null;
                int currentPage = history.pageIndex();
                history.finish(request, result);
                render();
                scrollToHistoryOnPageChange(currentPage);
            });
        });
    }

    private void scrollToHistoryOnPageChange(int page) {
        if (page != history.pageIndex()) {
            binding.getRoot().smoothScrollTo(0, binding.releaseNotesCard.getTop());
        }
    }

    private void renderHistoryControls() {
        boolean loading = history.loading();
        binding.historyNewer.setVisibility(history.hasNewer() ? View.VISIBLE : View.GONE);
        binding.historyNewer.setEnabled(!loading);
        binding.historyOlder.setVisibility(history.hasOlder() ? View.VISIBLE : View.GONE);
        binding.historyOlder.setEnabled(!loading);
        int status = 0;
        if (loading) status = R.string.update_history_loading;
        else if (history.failure() != null) status = error(history.failure());
        else if (history.noOlderMatches()) status = R.string.update_history_no_matches;
        CharSequence text = status == 0 ? "" : getText(status);
        if (!text.equals(binding.historyStatus.getText())) binding.historyStatus.setText(text);
        binding.historyStatus.setVisibility(status == 0 ? View.GONE : View.VISIBLE);
    }

    private void renderReleaseHistory(List<ReleaseHistoryItem> history, UpdateCandidate candidate,
            boolean devUpdates) {
        if (historyDevUpdates != devUpdates) {
            historyDevUpdates = devUpdates;
            historyDisclosureOverrides.clear();
        }
        if (history.isEmpty()) {
            if (!historyFingerprint.isEmpty()) binding.releaseHistoryList.removeAllViews();
            historyFingerprint = "";
            return;
        }
        StringBuilder fingerprint = new StringBuilder();
        fingerprint.append(devUpdates).append(';')
                .append(getResources().getConfiguration().getLocales().toLanguageTags())
                .append(':').append(ZoneId.systemDefault().getId()).append(';')
                .append(candidate == null ? "" : candidate.manifest.tag).append(';');
        for (ReleaseHistoryItem item : history) {
            fingerprint.append(item.tag).append(':').append(item.notes.hashCode())
                    .append(':').append(item.publishedAt).append(':').append(item.prerelease).append(';');
        }
        String value = fingerprint.toString();
        if (value.equals(historyFingerprint)) return;
        historyFingerprint = value;
        binding.releaseHistoryList.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(this);
        for (ReleaseHistoryItem item : history) {
            View row = inflater.inflate(R.layout.item_release_history,
                    binding.releaseHistoryList, false);
            View heading = row.findViewById(R.id.release_item_heading);
            TextView version = row.findViewById(R.id.release_version);
            TextView meta = row.findViewById(R.id.release_meta);
            TextView details = row.findViewById(R.id.release_details);
            android.widget.ImageView chevron = row.findViewById(R.id.release_chevron);
            version.setText(getString(R.string.update_history_version, item.versionName));
            meta.setText(historyMeta(item, candidate));
            details.setText(ReleaseNotesFormatter.forView(details,
                    item.notes, getString(R.string.update_notes_unavailable)));
            heading.setContentDescription(getString(R.string.update_history_heading, version.getText(), meta.getText()));
            androidx.core.view.ViewCompat.setScreenReaderFocusable(heading, true);
            androidx.core.view.ViewCompat.setAccessibilityDelegate(heading,
                    new androidx.core.view.AccessibilityDelegateCompat() {
                        @Override public void onInitializeAccessibilityNodeInfo(View host,
                                androidx.core.view.accessibility.AccessibilityNodeInfoCompat info) {
                            super.onInitializeAccessibilityNodeInfo(host, info);
                            info.setClassName(android.widget.Button.class.getName());
                        }
                    });
            boolean expanded = historyDisclosureOverrides.getOrDefault(item.tag, !devUpdates || item.prerelease);
            renderHistoryDisclosure(heading, details, chevron, expanded);
            heading.setOnClickListener(view -> {
                boolean show = details.getVisibility() != View.VISIBLE;
                renderHistoryDisclosure(heading, details, chevron, show);
                historyDisclosureOverrides.put(item.tag, show);
            });
            binding.releaseHistoryList.addView(row);
        }
    }

    private void renderHistoryDisclosure(View heading, TextView details, View chevron, boolean expanded) {
        details.setVisibility(expanded ? View.VISIBLE : View.GONE);
        chevron.setRotation(expanded ? 90f : 0f);
        androidx.core.view.ViewCompat.setStateDescription(heading,
                getString(expanded ? R.string.section_expanded : R.string.section_collapsed));
    }

    private void renderInstalledVersion() {
        String label = getString(R.string.update_installed_version,
                BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE);
        ReleaseHistoryItem installed = history.installed();
        String timestamp = installed == null ? "" : releaseTimestamp(installed);
        binding.installedVersion.setText(withTimestamp(label, timestamp));
    }

    private String releaseTimestamp(ReleaseHistoryItem item) {
        Locale locale = getResources().getConfiguration().getLocales().get(0);
        return ReleaseTimestampFormatter.format(item.publishedAt,
                locale == null ? Locale.getDefault() : locale, ZoneId.systemDefault());
    }

    private String withTimestamp(String label, String timestamp) {
        return timestamp.isEmpty() ? label : getString(R.string.update_release_with_timestamp, label, timestamp);
    }

    private String historyMeta(ReleaseHistoryItem item, UpdateCandidate candidate) {
        String timestamp = releaseTimestamp(item);
        if (candidate != null && item.tag.equals(candidate.manifest.tag)) {
            return withTimestamp(getString(R.string.update_history_available), timestamp);
        }
        if (ReleaseHistoryCatalog.isInstalled(item, BuildConfig.VERSION_NAME)) {
            return withTimestamp(getString(R.string.update_history_installed), timestamp);
        }
        int releaseType = item.prerelease ? R.string.update_history_preview : R.string.update_history_stable;
        return withTimestamp(getString(releaseType), timestamp);
    }

    private void finalizeDownload() {
        if (finalizingDownload) return;
        long id = state.downloadId();
        if (id < 0) return;
        finalizingDownload = true;
        network.execute(() -> {
            UpdateDownloadFinalizer.Result result = UpdateDownloadFinalizer.finish(this, id);
            main.post(() -> {
                if (binding == null) return;
                finalizingDownload = false;
                if (result == UpdateDownloadFinalizer.Result.FAILED && !checking) {
                    showFeedback(R.string.update_verification_failed);
                }
                render();
            });
        });
    }

    private void beginDownload() {
        UpdateCandidate candidate = state.candidate();
        if (candidate == null) return;
        try {
            downloads.start(candidate);
            showFeedback(getString(R.string.update_downloading, 0));
            main.removeCallbacks(poll);
            main.post(poll);
            render();
        } catch (RuntimeException exception) {
            showFeedback(R.string.update_no_compatible_apk);
        }
    }

    private void installVerified() {
        if (verifyingInstall) return;
        final UpdateCandidate candidate = state.candidate();
        final String path = state.verifiedPath();
        final long revision = state.channelRevision();
        UpdateManifest.Asset asset = candidate == null ? null : candidate.manifest.selectAsset(Build.SUPPORTED_ABIS);
        if (asset == null || path.isEmpty()) { render(); return; }
        verifyingInstall = true;
        network.execute(() -> {
            boolean verified = UpdateVerifier.verify(this, new File(path), candidate.manifest, asset).succeeded();
            main.post(() -> {
                verifyingInstall = false;
                if (binding == null) return;
                synchronized (UpdateStateStore.class) {
                    if (revision != state.channelRevision() || !path.equals(state.verifiedPath())
                            || !UpdateStateStore.sameCandidate(candidate, state.candidate())) { render(); return; }
                    if (!verified) {
                        downloads.cancel();
                        showFeedback(R.string.update_verification_failed);
                        render();
                        return;
                    }
                    launchInstaller();
                }
            });
        });
    }

    private void launchInstaller() {
        if (state.candidate() == null) {
            downloads.cancel();
            render();
            return;
        }
        String path = state.verifiedPath();
        File apk = path.isEmpty() ? null : new File(path);
        if (apk == null || !apk.isFile()) {
            showFeedback(R.string.update_verification_failed);
            state.clearDownload();
            render();
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !getPackageManager().canRequestPackageInstalls()) {
            installAfterPermission = true;
            showFeedback(R.string.update_install_permission);
            installPermission.launch(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + getPackageName())));
            return;
        }
        Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".updates", apk);
        startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(uri,
                        "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
    }

    @Override protected void onPause() {
        resumed = false;
        main.removeCallbacks(poll);
        super.onPause();
    }

    @Override protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
        network.shutdownNow();
        historyNetwork.shutdownNow();
        if (history != null && historyRequest != null) history.cancel(historyRequest);
        binding = null;
        super.onDestroy();
    }
}
