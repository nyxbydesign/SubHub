package com.subhub.app.capture;

import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.ActivityResultCallback;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.switchmaterial.SwitchMaterial;
import com.subhub.app.R;
import com.subhub.app.security.ControllerPinManager;

import java.util.ArrayList;
import java.util.List;

/** Shared private image editor, embedded alongside censor appearance controls. */
public final class CensorImageEditor implements AutoCloseable {
    private final AppCompatActivity activity;
    private final String artGroup;
    private final android.content.Context app;
    private final Button add;
    private final TextView status;
    private final LinearLayout list;
    private final com.subhub.app.util.AsyncUiScope uiData;
    private final String taskKey;
    private final android.content.SharedPreferences preferences;
    private final android.view.ViewTreeObserver.OnGlobalLayoutListener visibilityChanged =
            this::loadIfVisible;
    private long loadedRevision = Long.MIN_VALUE;
    private final List<Bitmap> thumbnails = new ArrayList<>();
    private boolean closed;
    private boolean importing;
    private final Picker sharedPicker;

    /** Registers before onStart even when the image editor appears later in an inline panel. */
    public static final class Picker {
        private static final String WORKSPACE = "artwork_picker_workspace";
        private final ActivityResultLauncher<String[]> launcher;
        private ActivityResultCallback<List<Uri>> receiver;
        private List<Uri> pending;
        private long workspace;

        public Picker(AppCompatActivity activity, String key, Bundle state) {
            workspace = state == null ? -1 : state.getLong(WORKSPACE, -1);
            launcher = activity.getActivityResultRegistry().register(key, activity,
                    new ActivityResultContracts.OpenMultipleDocuments(), result -> {
                        if (workspace != ControllerPinManager.domWorkspaceRevision()) return;
                        if (receiver == null) pending = result;
                        else receiver.onActivityResult(result);
                    });
        }

        public void saveState(Bundle state) { state.putLong(WORKSPACE, workspace); }

        private void bind(ActivityResultCallback<List<Uri>> receiver) {
            this.receiver = receiver;
            if (pending != null && workspace == ControllerPinManager.domWorkspaceRevision()) {
                receiver.onActivityResult(pending);
            }
            pending = null;
        }

        private void launch(String[] mimeTypes) {
            workspace = ControllerPinManager.domWorkspaceRevision();
            launcher.launch(mimeTypes);
        }
    }

    public CensorImageEditor(
            AppCompatActivity activity, Button add, TextView status, LinearLayout list) {
        this(activity, add, status, list, "censor");
    }
    public CensorImageEditor(AppCompatActivity activity, Button add, TextView status, LinearLayout list, String group) {
        this(activity, add, status, list, group, null);
    }

    public CensorImageEditor(AppCompatActivity activity, Button add, TextView status, LinearLayout list,
            String group, Picker sharedPicker) {
        artGroup = group;
        this.activity = activity;
        this.add = add;
        this.status = status;
        this.list = list;
        this.sharedPicker = sharedPicker;
        app = com.subhub.app.configuration.FeatureEditorSession.context(activity);
        uiData = com.subhub.app.util.AsyncUiScope.forPage(activity);
        taskKey = "censor-images:" + System.identityHashCode(this) + ":";
        preferences =
                com.subhub.app.configuration.FeatureEditorSession.context(activity)
                        .getSharedPreferences(
                                com.subhub.app.settings.SettingsRepository.PREFERENCES_NAME,
                                android.content.Context.MODE_PRIVATE);
        list.getViewTreeObserver().addOnGlobalLayoutListener(visibilityChanged);
        java.util.function.Consumer<String[]> launch;
        if (sharedPicker == null) {
            ActivityResultLauncher<String[]> picker = activity.registerForActivityResult(
                    new ActivityResultContracts.OpenMultipleDocuments(), this::importImages);
            launch = picker::launch;
        } else {
            sharedPicker.bind(this::importImages);
            launch = sharedPicker::launch;
        }
        add.setOnClickListener(
                view -> {
                    if (!importing && com.subhub.app.configuration.FeatureEditorSession.canEdit(activity, artGroup)) {
                        launch.accept(new String[] {"image/*"});
                    }
                });
        list.post(this::refresh);
    }

    private void importImages(List<Uri> uris) {
        if (closed
                || importing
                || uris == null
                || uris.isEmpty()
                || !com.subhub.app.configuration.FeatureEditorSession.canEdit(activity, artGroup)) return;
        importing = true;
        refresh();
        status.setText(R.string.custom_images_importing);
        android.content.Context context = app;
        List<Uri> selected = new ArrayList<>(uris);
        uiData.loadSerial(
                taskKey + "import",
                () ->
                        com.subhub.app.configuration.FeatureEditorSession.canEdit(activity, artGroup)
                                ? new CustomImageManager(context, artGroup).addImages(selected)
                                : 0,
                added -> {
                    importing = false;
                    status.setText(activity.getString(R.string.custom_images_added, added));
                    loadedRevision = Long.MIN_VALUE;
                    refresh();
                },
                failure -> {
                    importing = false;
                    status.setText(R.string.custom_images_unavailable);
                    refresh();
                });
    }

    public void refresh() {
        if (closed) return;
        boolean editing = com.subhub.app.configuration.FeatureEditorSession.canEdit(activity, artGroup) && !importing;
        add.setEnabled(editing);
        for (int i = 0; i < list.getChildCount(); i++) {
            android.view.View row = list.getChildAt(i);
            if (row instanceof android.view.ViewGroup) {
                android.view.ViewGroup controls = (android.view.ViewGroup) row;
                for (int j = 1; j < controls.getChildCount(); j++)
                    controls.getChildAt(j).setEnabled(editing);
            }
        }
        loadIfVisible();
    }

    private void loadIfVisible() {
        if (closed || !list.isShown() || importing || uiData.isPending(taskKey + "read")) return;
        long revision = preferences.getLong(CustomImageManager.REVISION_KEY, 0);
        if (revision == loadedRevision) return;
        android.content.Context context = app;
        uiData.load(
                taskKey + "read",
                () -> ImageData.load(new CustomImageManager(context, artGroup)),
                data -> {
                    if (preferences.getLong(CustomImageManager.REVISION_KEY, 0) != revision) {
                        data.close();
                        loadIfVisible();
                        return;
                    }
                    render(data);
                    loadedRevision = revision;
                },
                failure -> status.setText(R.string.custom_images_unavailable));
    }

    private void render(ImageData data) {
        list.removeAllViews();
        releaseThumbnails();
        thumbnails.addAll(data.bitmaps);
        data.bitmaps.clear();
        boolean editing = com.subhub.app.configuration.FeatureEditorSession.canEdit(activity, artGroup) && !importing;
        if (data.entries.isEmpty()) {
            TextView empty = new TextView(activity);
            empty.setText(R.string.custom_images_empty);
            empty.setTextColor(activity.getColor(R.color.text_secondary));
            com.subhub.app.util.UiIdentity.textSize(empty, R.dimen.ui_text_label);
            empty.setPadding(0, dp(8), 0, dp(8));
            list.addView(empty);
        }
        for (int index = 0; index < data.entries.size(); index++) {
            CustomImageManager.Entry entry = data.entries.get(index);
            LinearLayout row = new LinearLayout(activity);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(4), 0, dp(4));
            row.setTag("censor-image:" + entry.getId());
            ImageView preview = new ImageView(activity);
            preview.setScaleType(ImageView.ScaleType.CENTER_CROP);
            preview.setImportantForAccessibility(ImageView.IMPORTANT_FOR_ACCESSIBILITY_NO);
            preview.setImageBitmap(thumbnails.get(index));
            row.addView(preview, new LinearLayout.LayoutParams(dp(56), dp(56)));
            SwitchMaterial enabled = new com.subhub.app.util.StateToggle(activity);
            enabled.setText(R.string.custom_images_enabled);
            enabled.setChecked(entry.isEnabled());
            enabled.setEnabled(editing);
            enabled.setMinimumHeight(dp(48));
            enabled.setOnCheckedChangeListener(
                    (button, checked) -> {
                        if (com.subhub.app.configuration.FeatureEditorSession.canEdit(activity, artGroup) && !importing) {
                            android.content.Context context = app;
                            String id = entry.getId();
                            uiData.loadSerial(
                                    taskKey + "enabled:" + id,
                                    () -> {
                                        if (com.subhub.app.configuration.FeatureEditorSession.canEdit(activity, artGroup))
                                            new CustomImageManager(context, artGroup).setEnabled(id, checked);
                                        return null;
                                    },
                                    ignored -> {
                                        loadedRevision =
                                                preferences.getLong(
                                                        CustomImageManager.REVISION_KEY, 0);
                                    },
                                    failure -> {
                                        loadedRevision = Long.MIN_VALUE;
                                        refresh();
                                    });
                        } else {
                            loadedRevision = Long.MIN_VALUE;
                            refresh();
                        }
                    });
            row.addView(
                    enabled,
                    new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            Button delete =
                    new Button(activity, null, 0, R.style.Widget_SubHub_CompactOutlineButton);
            delete.setText(R.string.delete);
            delete.setEnabled(editing);
            delete.setOnClickListener(
                    view -> {
                        if (!com.subhub.app.configuration.FeatureEditorSession.canEdit(activity, artGroup) || importing) return;
                        importing = true;
                        refresh();
                        android.content.Context context = app;
                        String id = entry.getId();
                        uiData.loadSerial(
                                taskKey + "delete",
                                () -> {
                                    if (com.subhub.app.configuration.FeatureEditorSession.canEdit(activity, artGroup))
                                        new CustomImageManager(context, artGroup).delete(id);
                                    return null;
                                },
                                ignored -> {
                                    importing = false;
                                    loadedRevision = Long.MIN_VALUE;
                                    refresh();
                                },
                                failure -> {
                                    importing = false;
                                    status.setText(R.string.custom_images_unavailable);
                                    refresh();
                                });
                    });
            row.addView(delete, new LinearLayout.LayoutParams(-2, -2));
            list.addView(row);
        }
    }

    private static final class ImageData implements AutoCloseable {
        final List<CustomImageManager.Entry> entries;
        final List<Bitmap> bitmaps = new ArrayList<>();

        ImageData(List<CustomImageManager.Entry> entries) {
            this.entries = entries;
        }

        static ImageData load(CustomImageManager manager) throws InterruptedException {
            ImageData data = new ImageData(manager.listEntries());
            try {
                for (CustomImageManager.Entry entry : data.entries) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                    data.bitmaps.add(manager.thumbnail(entry.getId(), 128));
                }
                return data;
            } catch (RuntimeException | InterruptedException failure) {
                data.close();
                throw failure;
            }
        }

        @Override
        public void close() {
            for (Bitmap bitmap : bitmaps)
                if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
            bitmaps.clear();
        }
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    private void releaseThumbnails() {
        for (Bitmap bitmap : thumbnails)
            if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
        thumbnails.clear();
    }

    @Override
    public void close() {
        closed = true;
        if (sharedPicker != null) sharedPicker.receiver = null;
        uiData.cancel(taskKey + "read");
        if (list.getViewTreeObserver().isAlive())
            list.getViewTreeObserver().removeOnGlobalLayoutListener(visibilityChanged);
        list.removeAllViews();
        releaseThumbnails();
    }
}
