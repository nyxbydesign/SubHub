package com.subhub.app.capture.export;

import android.Manifest;
import android.app.Activity;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.*;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.view.View;
import android.widget.*;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.IntentSenderRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.ContextCompat;
import com.subhub.app.R;
import com.subhub.app.capture.CensorRenderer;
import com.subhub.app.detection.DetectionEngine;
import com.subhub.app.security.ControllerPinGate;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.settings.SettingsRepository;
import com.subhub.app.util.PreferencePage;
import com.subhub.app.util.StateToggle;
import com.subhub.app.util.ThemedDialogs;
import java.io.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Configures and observes durable gallery jobs. Leaving this screen never cancels the service. */
public class ExportWorkspaceActivity extends PreferencePage {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Uri> selected = new ArrayList<>();
    private final List<Long> pendingDeleteIds = new ArrayList<>();
    private ExportJobStore store;
    private String job, preparedFingerprint, resultsFingerprint;
    private volatile boolean closed;
    private boolean preparing, deleteOriginals, suppressDelete;
    private Bitmap previewBitmap;
    private int previewPosition;
    private int previewIndex;
    private Spinner previewSelection;
    private TextView selectedSummary, settingsSummary, status;
    private LinearLayout results, resultCard;
    private ImageView preview;
    private Button pick, configure, start, previewButton, quality, retry, delete, cancel;
    private StateToggle faster, mute, deletion;
    private ActivityResultLauncher<String[]> picker;
    private ActivityResultLauncher<String> storagePermission;
    private ActivityResultLauncher<IntentSenderRequest> deleteConsent;
    private final Runnable refresh = new Runnable() {
        @Override public void run() { if (!closed) { renderResults(); main.postDelayed(this, 750); } }
    };
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state); ExportJobStore.recoverProcess(this); store = new ExportJobStore(this);
        picker = registerForActivityResult(new ActivityResultContracts.OpenMultipleDocuments(), this::select);
        storagePermission = registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
            if (granted) prepare(true); else notice(getString(R.string.export_storage_needed));
        });
        deleteConsent = registerForActivityResult(new ActivityResultContracts.StartIntentSenderForResult(), result -> {
            if (result.getResultCode() == Activity.RESULT_OK && job != null) {
                int count = 0;
                for (ExportJobStore.Item item : store.items(job)) if (pendingDeleteIds.contains(item.id)) { markDeleted(item); count++; }
                notice(getString(R.string.export_deleted_count, count));
            }
            pendingDeleteIds.clear(); renderResults();
        });
        if (state != null) {
            job = state.getString("job"); preparedFingerprint = state.getString("fingerprint");
            ArrayList<String> uris = state.getStringArrayList("selected"); if (uris != null) for (String uri : uris) selected.add(Uri.parse(uri));
            long[] pending = state.getLongArray("pending_delete"); if (pending != null) for (long id : pending) pendingDeleteIds.add(id);
            deleteOriginals = state.getBoolean("delete_originals", false);
        } else job = store.latest();
        build();
    }
    private void build() {
        page(R.string.export_workspace_title); text(page, getString(R.string.export_workspace_help), 14, true);
        LinearLayout selection = card(page);
        pick = button(selection, getString(R.string.export_choose_media), () -> picker.launch(new String[] {"image/*", "video/*"}));
        pick.setId(R.id.button_pick_images);
        selectedSummary = text(selection, getString(R.string.export_selected_count, selected.size()), 14, true);
        LinearLayout options = card(page);
        settingsSummary = text(options, "", 15, false); settingsSummary.setId(R.id.export_settings_summary);
        configure = button(options, getString(R.string.export_appearance_title), () ->
                ControllerPinGate.require(this, () -> startActivity(new Intent(this, ExportAppearanceActivity.class)), false));
        quality = button(options, "", this::chooseQuality);
        ExportOptions current = ExportSettings.options(this);
        faster = (StateToggle) toggle(options, R.string.export_faster, current.detectEvery == 2, checked -> {
            ExportSettings.preferences(this).edit().putBoolean("export_fast", checked).apply(); invalidatePreview();
        });
        text(options, getString(R.string.export_faster_help), 13, true);
        mute = (StateToggle) toggle(options, R.string.export_mute, current.mute, checked -> {
            ExportSettings.preferences(this).edit().putBoolean("export_mute", checked).apply(); invalidatePreview();
        });
        deletion = (StateToggle) toggle(options, R.string.export_delete_after, deleteOriginals, this::setDelete);
        deletion.setId(R.id.switch_delete_originals);
        LinearLayout review = card(page); text(review, getString(R.string.export_preview_help), 13, true);
        previewSelection = new Spinner(this); previewSelection.setContentDescription(getString(R.string.export_preview_item));
        review.addView(previewSelection, new LinearLayout.LayoutParams(-1, dp(48)));
        previewSelection.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) { previewIndex = position; }
            public void onNothingSelected(AdapterView<?> parent) { previewIndex = 0; }
        });
        updatePreviewSelection();
        previewButton = button(review, getString(R.string.export_preview), () -> prepare(false)); previewButton.setId(R.id.export_preview_button);
        SeekBar position = new SeekBar(this); position.setMax(100); position.setContentDescription(getString(R.string.export_preview_frame));
        review.addView(position, new LinearLayout.LayoutParams(-1, dp(48)));
        position.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar view, int progress, boolean user) { if (user) previewPosition = progress; }
            public void onStartTrackingTouch(SeekBar view) { }
            public void onStopTrackingTouch(SeekBar view) { if (!selected.isEmpty() && !preparing) prepare(false); }
        });
        preview = new ImageView(this); preview.setId(R.id.export_preview_image); preview.setAdjustViewBounds(true);
        preview.setContentDescription(getString(R.string.export_preview)); preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        review.addView(preview, new LinearLayout.LayoutParams(-1, dp(220))); preview.setVisibility(View.GONE);
        start = button(page, getString(R.string.export_start_batch), this::startExport); start.setId(R.id.export_start_batch_button);
        status = text(page, "", 14, true); status.setId(R.id.export_status);
        resultCard = card(page); text(resultCard, getString(R.string.export_results), 18, false);
        results = new LinearLayout(this); results.setOrientation(LinearLayout.VERTICAL); results.setId(R.id.export_results_list); resultCard.addView(results);
        cancel = button(resultCard, getString(android.R.string.cancel), () -> {
            if (ExportService.isRunning()) startService(new Intent(this, ExportService.class).setAction(ExportService.ACTION_CANCEL));
        });
        cancel.setId(R.id.button_cancel_export);
        retry = button(resultCard, getString(R.string.export_retry_batch), () -> {
            if (job != null && !ExportService.isRunning()) { store.retry(job); launchJob(job); }
        });
        delete = button(resultCard, getString(R.string.export_delete_saved), this::deleteSavedOriginals);
        refreshSummary(); renderResults();
    }
    private void select(List<Uri> uris) {
        if (uris == null || uris.isEmpty()) return;
        selected.clear(); selected.addAll(uris);
        for (Uri uri : uris) try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); }
        catch (SecurityException ignored) { /* Provider may grant only transient access. */ }
        selectedSummary.setText(getString(R.string.export_selected_count, selected.size())); invalidatePreview(); renderResults();
        updatePreviewSelection();
    }
    private void updatePreviewSelection() {
        List<String> labels = new ArrayList<>();
        for (int i = 0; i < selected.size(); i++) labels.add(getString(R.string.export_preview_item_number, i + 1));
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item); previewSelection.setAdapter(adapter);
        previewSelection.setVisibility(selected.size() > 1 ? View.VISIBLE : View.GONE);
        previewIndex = 0;
    }
    private void setDelete(boolean checked) {
        if (suppressDelete) return;
        if (!checked) { deleteOriginals = false; invalidatePreview(); return; }
        suppressDelete = true; deletion.setChecked(false); suppressDelete = false;
        ControllerPinGate.require(this, () -> ThemedDialogs.builder(this).setTitle(R.string.export_delete_warning_title)
                .setMessage(R.string.export_delete_ready_help).setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.export_delete_warning_enable, (d, which) -> {
                    if (!ControllerPinManager.isDomModeActive()) return;
                    deleteOriginals = true; suppressDelete = true; deletion.setChecked(true); suppressDelete = false; invalidatePreview();
                }).show(), false);
    }
    private void chooseQuality() {
        ExportOptions.Quality[] values = ExportOptions.Quality.values(); String[] labels = new String[values.length];
        for (int i = 0; i < values.length; i++) labels[i] = ExportAppearanceActivity.label(values[i].name().toLowerCase(Locale.ROOT));
        ThemedDialogs.builder(this).setTitle(R.string.export_quality_title)
                .setSingleChoiceItems(labels, ExportSettings.options(this).quality.ordinal(), (d, which) -> {
                    ExportSettings.preferences(this).edit().putString("export_quality", values[which].name()).apply();
                    d.dismiss(); invalidatePreview(); refreshSummary();
                }).setNegativeButton(android.R.string.cancel, null).show();
    }
    private void refreshSummary() {
        SettingsRepository settings = SettingsRepository.forPreferences(ExportSettings.preferences(this));
        settingsSummary.setText(getString(R.string.export_style_value, ExportAppearanceActivity.label(settings.loadAppearance().getType().getPreferenceValue())));
        quality.setText(getString(R.string.export_quality_value, ExportAppearanceActivity.label(ExportSettings.options(this).quality.name().toLowerCase(Locale.ROOT))));
    }
    private void invalidatePreview() {
        preparedFingerprint = null;
        if (preview != null) { preview.setImageDrawable(null); preview.setVisibility(View.GONE); }
        if (previewBitmap != null) { previewBitmap.recycle(); previewBitmap = null; }
    }
    private void startExport() {
        if (Build.VERSION.SDK_INT < 29 && ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE); return;
        }
        prepare(true);
    }
    private void prepare(boolean export) {
        if (preparing) return;
        if (ExportService.isRunning()) { notice(getString(R.string.export_wait_running)); return; }
        if (selected.isEmpty()) { notice(getString(R.string.export_select_first)); return; }
        final List<Uri> sources = new ArrayList<>(selected);
        final ExportOptions current = ExportSettings.options(this);
        final ExportOptions options = new ExportOptions(current.quality, current.detectEvery, current.mute, deleteOriginals);
        final org.json.JSONObject snapshot;
        try { snapshot = ExportSettings.snapshot(this); } catch (Exception e) { notice(getString(R.string.export_preview_failed)); return; }
        String fingerprint = snapshot.toString() + options.quality + options.detectEvery + options.mute + options.deleteOriginals + sources;
        boolean reusable = job != null && fingerprint.equals(preparedFingerprint)
                && store.items(job).stream().allMatch(item -> item.state == ExportJobStore.State.DRAFT);
        final String previousJob = job; final int position = previewPosition;
        final Uri previewSource = sources.get(Math.min(previewIndex, sources.size() - 1));
        preparing = true; status.setText(R.string.export_preparing); renderResults();
        worker.execute(() -> {
            try (ExportJobStore backgroundStore = new ExportJobStore(this)) {
                String readyJob = reusable ? previousJob : backgroundStore.createDraft(sources, snapshot, options);
                if (!reusable && previousJob != null) backgroundStore.discardDraft(previousJob);
                Bitmap image = export ? null : previewFrame(backgroundStore, readyJob, previewSource, position);
                if (export) {
                    backgroundStore.enqueue(readyJob);
                    try { ContextCompat.startForegroundService(getApplicationContext(), new Intent(getApplicationContext(), ExportService.class)
                            .setAction(ExportService.ACTION_START).putExtra(ExportService.EXTRA_JOB, readyJob)); }
                    catch (RuntimeException rejected) { backgroundStore.recover(); throw rejected; }
                }
                main.post(() -> {
                    if (closed) { if (image != null) image.recycle(); return; }
                    job = readyJob; preparedFingerprint = fingerprint; preparing = false;
                    if (export) status.setText(R.string.export_ready_snapshot);
                    else {
                        preview.setImageBitmap(image); preview.setVisibility(View.VISIBLE);
                        if (previewBitmap != null && previewBitmap != image) previewBitmap.recycle(); previewBitmap = image;
                        status.setText(R.string.export_ready_snapshot);
                    }
                    renderResults();
                });
            } catch (Exception error) { main.post(() -> { if (!closed) { preparing = false; status.setText(R.string.export_preview_failed); renderResults(); } }); }
        });
    }
    private Bitmap previewFrame(ExportJobStore backgroundStore, String prepared, Uri source, int position) throws Exception {
        SettingsRepository settings = SettingsRepository.forPreferences(ExportSettings.frozen(backgroundStore.snapshot(prepared)));
        Bitmap image;
        if (ExportMedia.isVideo(this, source)) {
            MediaMetadataRetriever reader = new MediaMetadataRetriever();
            try {
                reader.setDataSource(this, source);
                String durationString = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                long duration = durationString == null ? 0 : Long.parseLong(durationString);
                image = reader.getFrameAtTime(duration * 1000 * position / 100, MediaMetadataRetriever.OPTION_CLOSEST);
            } finally { reader.release(); }
            if (image == null) throw new IOException("Video preview unavailable");
            int[] size = backgroundStore.options(prepared).dimensions(image.getWidth(), image.getHeight());
            Bitmap scaled = Bitmap.createScaledBitmap(image, size[0], size[1], true);
            if (scaled != image) image.recycle(); image = scaled;
        } else image = ExportMedia.image(this, source, backgroundStore.options(prepared).quality.longestEdge);
        try (DetectionEngine engine = new DetectionEngine(this, settings.loadDetectorConfig());
             CensorRenderer renderer = new CensorRenderer(this, ExportMedia.assets(backgroundStore.directory(prepared),
                     settings.loadDetectionPreset().getCustomImageDimension(), settings.loadDetectionPreset().getCustomImageCount()))) {
            engine.initialize(); Bitmap output = image.copy(Bitmap.Config.ARGB_8888, true);
            if (output == null) throw new IOException("Preview unavailable");
            try { renderer.draw(output, image, engine.detect(image), settings.loadAppearance()); return output; }
            catch (Exception e) { output.recycle(); throw e; }
        } finally { image.recycle(); }
    }
    private void launchJob(String id) {
        try { ContextCompat.startForegroundService(this, new Intent(this, ExportService.class).setAction(ExportService.ACTION_START).putExtra(ExportService.EXTRA_JOB, id)); }
        catch (RuntimeException rejected) { store.recover(); notice(getString(R.string.export_wait_running)); }
        status.setText(R.string.export_ready_snapshot); renderResults();
    }
    private void renderResults() {
        if (results == null || closed) return;
        List<ExportJobStore.Item> items = job == null ? Collections.emptyList() : store.items(job);
        boolean running = ExportService.isRunning() || items.stream().anyMatch(item -> item.state == ExportJobStore.State.PENDING || item.state == ExportJobStore.State.RUNNING);
        boolean editable = !preparing && !running;
        pick.setEnabled(editable); configure.setEnabled(editable); quality.setEnabled(editable);
        faster.setEnabled(editable); mute.setEnabled(editable); deletion.setEnabled(editable);
        start.setEnabled(editable && !selected.isEmpty()); previewButton.setEnabled(editable && !selected.isEmpty());
        resultCard.setVisibility(items.stream().anyMatch(item -> item.state != ExportJobStore.State.DRAFT) ? View.VISIBLE : View.GONE);
        cancel.setVisibility(running ? View.VISIBLE : View.GONE);
        if (job == null) { retry.setVisibility(View.GONE); delete.setVisibility(View.GONE); return; }
        StringBuilder fingerprint = new StringBuilder(job);
        boolean unfinished = false, deletable = false;
        for (ExportJobStore.Item item : items) {
            fingerprint.append(item.state).append(item.progress).append(item.message);
            unfinished |= item.state == ExportJobStore.State.FAILED || item.state == ExportJobStore.State.CANCELLED || item.state == ExportJobStore.State.INTERRUPTED;
            deletable |= item.state == ExportJobStore.State.SAVED && !"Original deleted".equals(item.message);
        }
        retry.setVisibility(unfinished && editable ? View.VISIBLE : View.GONE);
        delete.setVisibility(deletable && editable && store.options(job).deleteOriginals ? View.VISIBLE : View.GONE);
        if (!preparing && !items.isEmpty() && items.stream().noneMatch(item -> item.state == ExportJobStore.State.DRAFT)) {
            long saved = items.stream().filter(item -> item.state == ExportJobStore.State.SAVED).count();
            status.setText(getString(running ? R.string.export_batch_running : R.string.export_batch_finished, saved, items.size()));
        }
        if (fingerprint.toString().equals(resultsFingerprint)) return;
        resultsFingerprint = fingerprint.toString(); results.removeAllViews(); int number = 0;
        for (ExportJobStore.Item item : items) {
            text(results, getString(R.string.export_item_status, ++number, ExportAppearanceActivity.label(item.state.name().toLowerCase(Locale.ROOT)), item.progress), 15, false);
            if (!item.message.isEmpty() && !"Saved".equals(item.message)) text(results, item.message, 12, true);
            if (item.state == ExportJobStore.State.SAVED && item.output != null) {
                button(results, getString(R.string.export_open_copy), () -> open(Uri.parse(item.output), false));
                button(results, getString(R.string.export_share_copy), () -> open(Uri.parse(item.output), true));
            }
        }
    }
    private void open(Uri uri, boolean share) {
        String type = getContentResolver().getType(uri);
        Intent intent = share ? new Intent(Intent.ACTION_SEND).setType(type).putExtra(Intent.EXTRA_STREAM, uri)
                : new Intent(Intent.ACTION_VIEW).setDataAndType(uri, type);
        if (share) intent.setClipData(ClipData.newRawUri("Export", uri));
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try { startActivity(share ? Intent.createChooser(intent, getString(R.string.export_share_copy)) : intent); }
        catch (ActivityNotFoundException unavailable) { notice(getString(R.string.export_cannot_open)); }
    }
    private void deleteSavedOriginals() {
        if (job == null || ExportService.isRunning()) return;
        ControllerPinGate.require(this, () -> ThemedDialogs.builder(this).setTitle(R.string.export_delete_warning_title)
                .setMessage(R.string.export_delete_ready_help).setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.export_delete_saved, (d, w) -> requestDeletion()).show(), false);
    }
    private void markDeleted(ExportJobStore.Item item) { store.update(item.id, ExportJobStore.State.SAVED, Uri.parse(item.output), "Original deleted", 100); }
    private void requestDeletion() {
        if (!ControllerPinManager.isDomModeActive()) return;
        List<Uri> media = new ArrayList<>(); pendingDeleteIds.clear(); int deleted = 0;
        for (ExportJobStore.Item item : store.items(job)) {
            if (item.state != ExportJobStore.State.SAVED || item.output == null || "Original deleted".equals(item.message)) continue;
            try {
                try (ParcelFileDescriptor fd = getContentResolver().openFileDescriptor(Uri.parse(item.output), "r")) { if (fd == null || fd.getStatSize() == 0) continue; }
                Uri source = Uri.parse(item.source);
                Uri mediaUri = "media".equals(source.getAuthority()) ? source : Build.VERSION.SDK_INT >= 29 ? MediaStore.getMediaUri(this, source) : null;
                if (Build.VERSION.SDK_INT >= 30 && mediaUri != null) { media.add(mediaUri); pendingDeleteIds.add(item.id); }
                else if (DocumentsContract.isDocumentUri(this, source) && DocumentsContract.deleteDocument(getContentResolver(), source)) { markDeleted(item); deleted++; }
            } catch (Exception denied) { /* Failed deletion leaves the source and saved copy intact. */ }
        }
        if (!media.isEmpty() && Build.VERSION.SDK_INT >= 30) {
            try { deleteConsent.launch(new IntentSenderRequest.Builder(MediaStore.createDeleteRequest(getContentResolver(), media).getIntentSender()).build()); }
            catch (RuntimeException denied) { pendingDeleteIds.clear(); notice(getString(R.string.export_deleted_count, deleted)); }
        } else notice(getString(R.string.export_deleted_count, deleted));
        renderResults();
    }
    protected Uri saveBitmapCopy(Bitmap bitmap) throws IOException {
        String temporaryJob;
        try { temporaryJob = store.create(Collections.singletonList(Uri.EMPTY), ExportSettings.snapshot(this), ExportOptions.defaults()); }
        catch (org.json.JSONException error) { throw new IOException(error); }
        File file = new File(store.directory(temporaryJob), "legacy.jpg");
        try {
            try (OutputStream output = new FileOutputStream(file)) { if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output)) throw new IOException("Encoding failed"); }
            return ExportMedia.publish(this, store, store.items(temporaryJob).get(0), file, false);
        } finally { if (file.exists() && !file.delete()) file.deleteOnExit(); }
    }
    @Override protected void onResume() { super.onResume(); if (settingsSummary != null) refreshSummary(); main.removeCallbacks(refresh); main.post(refresh); }
    @Override protected void onPause() { main.removeCallbacks(refresh); super.onPause(); }
    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("job", job); state.putString("fingerprint", preparedFingerprint); state.putBoolean("delete_originals", deleteOriginals);
        ArrayList<String> uris = new ArrayList<>(); for (Uri uri : selected) uris.add(uri.toString()); state.putStringArrayList("selected", uris);
        long[] pending = new long[pendingDeleteIds.size()]; for (int i = 0; i < pending.length; i++) pending[i] = pendingDeleteIds.get(i);
        state.putLongArray("pending_delete", pending); super.onSaveInstanceState(state);
    }
    @Override protected void onDestroy() { closed = true; main.removeCallbacksAndMessages(null); worker.shutdown(); if (previewBitmap != null) previewBitmap.recycle(); if (store != null) store.close(); super.onDestroy(); }
}
