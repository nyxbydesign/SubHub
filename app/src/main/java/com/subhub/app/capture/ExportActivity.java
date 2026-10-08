package com.subhub.app.capture;

import com.subhub.app.util.PrimaryHeader;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.view.View;
import android.widget.CompoundButton;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.subhub.app.R;
import com.subhub.app.databinding.ActivityExportBinding;
import com.subhub.app.detection.DetectionEngine;
import com.subhub.app.security.ControllerEditMode;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.settings.CensorAppearance;
import com.subhub.app.settings.SettingsRepository;
import com.subhub.app.stats.StatsRepository;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Explicit, cancellable gallery export flow using Android's document and media stores. */
public final class ExportActivity extends AppCompatActivity {
    private static final int MAX_DECODE_DIMENSION = 4096;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private ActivityExportBinding binding;
    private ActivityResultLauncher<String[]> picker;
    private boolean suppressDeleteListener;
    private ControllerEditMode editMode;
    private boolean busy;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityExportBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        PrimaryHeader.bindSecondary(binding.getRoot(), R.string.export_title, true);
        picker = registerForActivityResult(
                new ActivityResultContracts.OpenMultipleDocuments(), this::startExport);

        PrimaryHeader.backButton(binding.getRoot()).setOnClickListener(view -> finish());
        binding.buttonPickImages.setOnClickListener(
                view -> picker.launch(new String[]{"image/*"}));
        binding.buttonCancelExport.setOnClickListener(view -> cancelled.set(true));
        binding.switchDeleteOriginals.setOnCheckedChangeListener(this::onDeleteOriginalsChanged);
        editMode = ControllerEditMode.bind(this, PrimaryHeader.editLockButton(binding.getRoot()), editing ->
                binding.switchDeleteOriginals.setEnabled(editing && !busy));
        refreshSummary();
    }

    @Override protected void onResume() {
        super.onResume();
        if (editMode != null) editMode.refresh();
    }

    private void refreshSummary() {
        CensorAppearance appearance = new SettingsRepository(this).loadAppearance();
        binding.exportSettingsSummary.setText(getString(
                R.string.export_summary_line,
                appearance.getType().getPreferenceValue().replace('_', ' '),
                appearance.isReverseMode() ? getString(R.string.export_reverse_on)
                        : getString(R.string.export_reverse_off),
                appearance.getBorderEffect().preferenceValue()));
    }

    private void onDeleteOriginalsChanged(CompoundButton button, boolean checked) {
        if (suppressDeleteListener || !checked) return;
        suppressDeleteListener = true;
        button.setChecked(false);
        suppressDeleteListener = false;
        com.subhub.app.util.ThemedDialogs.builder(this)
                .setTitle(R.string.export_delete_warning_title)
                .setMessage(R.string.export_delete_warning_body)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.export_delete_warning_enable,
                        (dialog, which) -> {
                            suppressDeleteListener = true;
                            button.setChecked(true);
                            suppressDeleteListener = false;
                        })
                .show();
    }

    private void startExport(List<Uri> uris) {
        if (uris == null || uris.isEmpty()) {
            binding.exportStatus.setText(R.string.export_no_images);
            return;
        }
        boolean deleteOriginals = binding.switchDeleteOriginals.isChecked();
        cancelled.set(false);
        setBusy(true, uris.size());
        worker.execute(() -> runExport(uris, deleteOriginals));
    }

    private void runExport(List<Uri> uris, boolean deleteOriginals) {
        int saved = 0;
        int skipped = 0;
        int deleted = 0;
        try (DetectionEngine engine = new DetectionEngine(
                     this, new SettingsRepository(this).loadDetectorConfig());
             CensorRenderer renderer = new CensorRenderer(this)) {
            engine.initialize();
            for (int index = 0; index < uris.size() && !cancelled.get(); index++) {
                int current = index + 1;
                main.post(() -> updateProgress(current, uris.size()));
                Uri sourceUri = uris.get(index);
                Bitmap source = null;
                Bitmap output = null;
                try {
                    source = decode(sourceUri);
                    CensorRenderer.RenderResult result = renderer.renderWithDetection(source, engine);
                    output = result.getBitmap();
                    saveToGallery(output, current);
                    saved++;
                    if (deleteOriginals && deleteOriginal(sourceUri)) deleted++;
                } catch (Exception error) {
                    skipped++;
                } finally {
                    if (output != null && !output.isRecycled()) output.recycle();
                    if (source != null && !source.isRecycled()) source.recycle();
                }
                if (cancelled.get()) break;
            }
        } catch (Exception initializationError) {
            skipped += Math.max(0, uris.size() - saved - skipped);
        }
        int finalSaved = saved;
        int finalSkipped = skipped;
        int finalDeleted = deleted;
        boolean wasCancelled = cancelled.get();
        if (saved > 0) new StatsRepository(this).addExportedImages(saved);
        main.post(() -> finishExport(finalSaved, finalSkipped, finalDeleted, wasCancelled));
    }

    private Bitmap decode(Uri uri) throws IOException {
        ContentResolver resolver = getContentResolver();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            ImageDecoder.Source source = ImageDecoder.createSource(resolver, uri);
            return ImageDecoder.decodeBitmap(source, (decoder, info, ignored) -> {
                decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                int width = info.getSize().getWidth();
                int height = info.getSize().getHeight();
                int largest = Math.max(width, height);
                if (largest > MAX_DECODE_DIMENSION) {
                    float scale = (float) MAX_DECODE_DIMENSION / largest;
                    decoder.setTargetSize(Math.max(1, Math.round(width * scale)),
                            Math.max(1, Math.round(height * scale)));
                }
            });
        }
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream input = resolver.openInputStream(uri)) {
            if (input == null) throw new IOException("Selected image is unavailable");
            BitmapFactory.decodeStream(input, null, bounds);
        }
        int sample = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight) / sample > MAX_DECODE_DIMENSION) {
            sample *= 2;
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        try (InputStream input = resolver.openInputStream(uri)) {
            if (input == null) throw new IOException("Selected image is unavailable");
            Bitmap bitmap = BitmapFactory.decodeStream(input, null, options);
            if (bitmap == null) throw new IOException("Selected content is not a supported image");
            return bitmap;
        }
    }

    Uri saveToGallery(Bitmap bitmap, int sequence) throws IOException {
        ContentValues values = new ContentValues();
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT)
                .format(new Date());
        values.put(MediaStore.Images.Media.DISPLAY_NAME,
                "SubHub_" + timestamp + '_' + sequence + ".jpg");
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        values.put(MediaStore.Images.Media.RELATIVE_PATH,
                Environment.DIRECTORY_PICTURES + "/SubHub/Exports");
        values.put(MediaStore.Images.Media.IS_PENDING, 1);
        Uri destination = getContentResolver().insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        if (destination == null) throw new IOException("Gallery refused the export");
        boolean complete = false;
        try (OutputStream output = getContentResolver().openOutputStream(destination, "w")) {
            if (output == null || !bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output)) {
                throw new IOException("Could not encode exported image");
            }
            complete = true;
        } finally {
            if (complete) {
                ContentValues ready = new ContentValues();
                ready.put(MediaStore.Images.Media.IS_PENDING, 0);
                getContentResolver().update(destination, ready, null, null);
            } else {
                getContentResolver().delete(destination, null, null);
            }
        }
        return destination;
    }

    private boolean deleteOriginal(Uri uri) {
        try {
            if (DocumentsContract.isDocumentUri(this, uri)) {
                return DocumentsContract.deleteDocument(getContentResolver(), uri);
            }
            return getContentResolver().delete(uri, null, null) > 0;
        } catch (Exception denied) {
            return false;
        }
    }

    private void setBusy(boolean busy, int total) {
        this.busy = busy;
        binding.buttonPickImages.setEnabled(!busy);
        binding.switchDeleteOriginals.setEnabled(
                ControllerPinManager.isSessionUnlocked() && !busy);
        binding.buttonCancelExport.setVisibility(busy ? View.VISIBLE : View.GONE);
        binding.exportProgressContainer.setVisibility(busy ? View.VISIBLE : View.GONE);
        if (busy) {
            binding.exportProgress.setMax(total);
            binding.exportProgress.setProgress(0);
            binding.exportStatus.setText(getString(R.string.export_processing, 0, total));
        }
    }

    private void updateProgress(int current, int total) {
        if (binding == null) return;
        binding.exportProgress.setProgress(current - 1);
        binding.exportStatus.setText(getString(R.string.export_processing, current, total));
    }

    private void finishExport(int saved, int skipped, int deleted, boolean wasCancelled) {
        if (binding == null) return;
        setBusy(false, 0);
        binding.exportProgressContainer.setVisibility(View.VISIBLE);
        binding.exportProgress.setMax(Math.max(1, saved + skipped));
        binding.exportProgress.setProgress(saved + skipped);
        if (wasCancelled) {
            binding.exportStatus.setText(getString(R.string.export_cancelled, saved));
        } else if (binding.switchDeleteOriginals.isChecked()) {
            binding.exportStatus.setText(getString(
                    R.string.export_done_delete, saved, skipped, deleted));
        } else {
            binding.exportStatus.setText(getString(R.string.export_done, saved, skipped));
        }
        binding.exportLocation.setVisibility(saved > 0 ? View.VISIBLE : View.GONE);
    }

    @Override
    protected void onDestroy() {
        cancelled.set(true);
        worker.shutdownNow();
        binding = null;
        super.onDestroy();
    }
}
