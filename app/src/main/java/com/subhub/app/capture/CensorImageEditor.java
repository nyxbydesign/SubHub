package com.subhub.app.capture;

import android.graphics.Bitmap;
import android.net.Uri;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.switchmaterial.SwitchMaterial;
import com.subhub.app.R;
import com.subhub.app.security.ControllerPinManager;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Shared private image editor, embedded alongside censor appearance controls. */
public final class CensorImageEditor implements AutoCloseable {
    private final AppCompatActivity activity;
    private final CustomImageManager manager;
    private final Button add;
    private final TextView status;
    private final LinearLayout list;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final List<Bitmap> thumbnails = new ArrayList<>();
    private boolean closed;
    private boolean importing;

    public CensorImageEditor(AppCompatActivity activity, Button add, TextView status,
            LinearLayout list) {
        this.activity = activity;
        this.add = add;
        this.status = status;
        this.list = list;
        manager = new CustomImageManager(activity);
        ActivityResultLauncher<String[]> picker = activity.registerForActivityResult(
                new ActivityResultContracts.OpenMultipleDocuments(), this::importImages);
        add.setOnClickListener(view -> {
            if (!importing && ControllerPinManager.isSessionUnlocked()) {
                picker.launch(new String[]{"image/*"});
            }
        });
        refresh();
    }

    private void importImages(List<Uri> uris) {
        if (closed || importing || uris == null || uris.isEmpty()
                || !ControllerPinManager.isSessionUnlocked()) return;
        importing = true;
        refresh();
        status.setText(R.string.custom_images_importing);
        worker.execute(() -> {
            int added = ControllerPinManager.isSessionUnlocked() ? manager.addImages(uris) : 0;
            activity.runOnUiThread(() -> {
                if (closed) return;
                importing = false;
                status.setText(activity.getString(R.string.custom_images_added, added));
                refresh();
            });
        });
    }

    public void refresh() {
        if (closed) return;
        list.removeAllViews();
        releaseThumbnails();
        boolean editing = ControllerPinManager.isSessionUnlocked() && !importing;
        add.setEnabled(editing);
        List<CustomImageManager.Entry> entries = manager.listEntries();
        if (entries.isEmpty()) {
            TextView empty = new TextView(activity);
            empty.setText(R.string.custom_images_empty);
            empty.setTextColor(activity.getColor(R.color.text_secondary));
            empty.setTextSize(12);
            empty.setPadding(0, dp(8), 0, dp(8));
            list.addView(empty);
        }
        for (CustomImageManager.Entry entry : entries) {
            LinearLayout row = new LinearLayout(activity);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(4), 0, dp(4));
            row.setTag("censor-image:" + entry.getId());
            ImageView preview = new ImageView(activity);
            preview.setScaleType(ImageView.ScaleType.CENTER_CROP);
            preview.setImportantForAccessibility(ImageView.IMPORTANT_FOR_ACCESSIBILITY_NO);
            Bitmap bitmap = manager.thumbnail(entry.getId(), 128);
            if (bitmap != null) {
                thumbnails.add(bitmap);
                preview.setImageBitmap(bitmap);
            }
            row.addView(preview, new LinearLayout.LayoutParams(dp(56), dp(56)));
            SwitchMaterial enabled = new com.subhub.app.util.StateToggle(activity);
            enabled.setText(R.string.custom_images_enabled);
            enabled.setTextColor(activity.getColor(R.color.text_primary));
            enabled.setTextSize(12);
            enabled.setChecked(entry.isEnabled());
            enabled.setEnabled(editing);
            enabled.setMinimumHeight(dp(48));
            enabled.setOnCheckedChangeListener((button, checked) -> {
                if (ControllerPinManager.isSessionUnlocked() && !importing) {
                    manager.setEnabled(entry.getId(), checked);
                } else refresh();
            });
            row.addView(enabled, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            Button delete = new Button(activity, null, 0, R.style.Widget_SubHub_CompactOutlineButton);
            delete.setText(R.string.delete);
            delete.setTextSize(12);
            delete.setEnabled(editing);
            delete.setOnClickListener(view -> {
                if (!ControllerPinManager.isSessionUnlocked() || importing) return;
                manager.delete(entry.getId());
                refresh();
            });
            row.addView(delete, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            list.addView(row);
        }
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    private void releaseThumbnails() {
        for (Bitmap bitmap : thumbnails) if (!bitmap.isRecycled()) bitmap.recycle();
        thumbnails.clear();
    }

    @Override public void close() {
        closed = true;
        worker.shutdownNow();
        list.removeAllViews();
        releaseThumbnails();
    }
}
