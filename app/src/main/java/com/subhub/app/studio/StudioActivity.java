package com.subhub.app.studio;

import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import com.subhub.app.R;
import com.subhub.app.databinding.ActivityStudioBinding;
import com.subhub.app.pack.PackManager;
import com.subhub.app.pack.SubHubPack;
import com.subhub.app.pack.SubHubPackManager;
import com.subhub.app.pack.SubHubPackSchema;
import com.subhub.app.profiles.ProfilesActivity;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.util.SubHubNavigation;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Always-available creator, library, importer, previewer, and share surface for arrangements. */
public final class StudioActivity extends AppCompatActivity {
    private static final long MAX_IMAGE_BYTES = 25L * 1024L * 1024L;
    private static final String[] SECTION_ORDER = {
            SubHubPackSchema.MODULES, SubHubPackSchema.CENSOR, SubHubPackSchema.LIMITS,
            SubHubPackSchema.WALLET, SubHubPackSchema.SUBLIMINAL, SubHubPackSchema.POPUP
    };
    private static final long[] DURATION_VALUES = {
            0L, 3_600_000L, 86_400_000L, 604_800_000L, 2_592_000_000L, -1L
    };

    private ActivityStudioBinding binding;
    private SubHubPackManager manager;
    private StudioPayPalTransfer payPalTransfer;
    private SubHubPack draft;
    private boolean suppressEvents;
    private String assetTarget = "censor";
    private final Map<String, CheckBox> includes = new LinkedHashMap<>();
    private final Map<String, CheckBox> locks = new LinkedHashMap<>();

    private final ActivityResultLauncher<String[]> importPicker = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(), this::importPack);
    private final ActivityResultLauncher<String[]> imagePicker = registerForActivityResult(
            new ActivityResultContracts.OpenMultipleDocuments(), this::addImages);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityStudioBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        manager = new SubHubPackManager(this);
        payPalTransfer = new StudioPayPalTransfer(this, manager);
        SubHubNavigation.bind(this, binding.getRoot(), SubHubNavigation.Screen.SETTINGS);
        binding.studioMode.setText(ControllerPinManager.isDomModeActive()
                ? R.string.studio_dom_space : R.string.studio_sub_space);
        setupTabs();
        setupEditor();
        binding.buttonImport.setOnClickListener(view -> importPicker.launch(
                new String[]{"application/zip", "application/octet-stream", "*/*"}));
        binding.buttonSavedProfiles.setOnClickListener(view ->
                startActivity(new Intent(this, ProfilesActivity.class)));
        binding.buttonBlank.setOnClickListener(view -> openDraft(manager.createBlank()));
        binding.buttonCapture.setOnClickListener(view -> openDraft(manager.captureCurrent()));
        renderLibrary();
        renderDrafts();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (binding != null) {
            SubHubNavigation.bind(this, binding.getRoot(), SubHubNavigation.Screen.SETTINGS);
            binding.studioMode.setText(ControllerPinManager.isDomModeActive()
                    ? R.string.studio_dom_space : R.string.studio_sub_space);
            applySpaceVisibility();
        }
    }

    @Override protected void onPause() {
        if (payPalTransfer != null) payPalTransfer.pause();
        super.onPause();
    }

    @Override protected void onDestroy() {
        if (payPalTransfer != null) payPalTransfer.destroy();
        super.onDestroy();
    }

    private void setupTabs() {
        binding.tabLibrary.setOnClickListener(view -> showPanel(binding.libraryPanel));
        binding.tabDrafts.setOnClickListener(view -> {
            renderDrafts();
            showPanel(binding.draftsPanel);
        });
        binding.tabCreate.setOnClickListener(view -> showPanel(binding.createPanel));
        updateTabSelection(binding.libraryPanel);
        applySpaceVisibility();
    }

    private void applySpaceVisibility() {
        boolean domSpace = ControllerPinManager.isDomModeActive();
        binding.tabDrafts.setVisibility(domSpace ? View.VISIBLE : View.GONE);
        binding.tabCreate.setVisibility(domSpace ? View.VISIBLE : View.GONE);
        binding.buttonSavedProfiles.setVisibility(domSpace ? View.VISIBLE : View.GONE);
        if (!domSpace) showPanel(binding.libraryPanel);
    }

    private void showPanel(View panel) {
        binding.libraryPanel.setVisibility(panel == binding.libraryPanel ? View.VISIBLE : View.GONE);
        binding.draftsPanel.setVisibility(panel == binding.draftsPanel ? View.VISIBLE : View.GONE);
        binding.createPanel.setVisibility(panel == binding.createPanel ? View.VISIBLE : View.GONE);
        binding.editorPanel.setVisibility(panel == binding.editorPanel ? View.VISIBLE : View.GONE);
        updateTabSelection(panel);
        binding.studioScroll.smoothScrollTo(0, 0);
    }

    private void updateTabSelection(View panel) {
        binding.tabLibrary.setSelected(panel == binding.libraryPanel);
        binding.tabDrafts.setSelected(panel == binding.draftsPanel);
        binding.tabCreate.setSelected(panel == binding.createPanel || panel == binding.editorPanel);
    }

    private void setupEditor() {
        String[] durations = {
                getString(R.string.studio_recommend_none), getString(R.string.studio_recommend_1h),
                getString(R.string.studio_recommend_24h), getString(R.string.studio_recommend_7d),
                getString(R.string.studio_recommend_30d),
                getString(R.string.studio_recommend_permanent)
        };
        binding.recommendDuration.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, durations));
        addMetadataWatcher(binding.packName);
        addMetadataWatcher(binding.packAuthor);
        addMetadataWatcher(binding.packDescription);
        addMetadataWatcher(binding.packVersion);
        binding.recommendHardcore.setOnCheckedChangeListener((button, checked) -> saveEditor());
        binding.recommendDuration.setOnItemSelectedListener(new SimpleItemSelectedListener(this::saveEditor));
        binding.buttonCensorAssets.setOnClickListener(view -> pickImages("censor"));
        binding.buttonPopupAssets.setOnClickListener(view -> pickImages("popup"));
        binding.buttonPublish.setOnClickListener(view -> publishDraft());
        binding.buttonExport.setOnClickListener(view -> share(draft));
        binding.buttonDeleteDraft.setOnClickListener(view -> deleteDraft());
        buildSectionRows();
    }

    private void buildSectionRows() {
        binding.sectionList.removeAllViews();
        includes.clear();
        locks.clear();
        for (String section : SECTION_ORDER) {
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(10), dp(8), dp(10), dp(8));
            LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cardParams.topMargin = dp(6);
            card.setLayoutParams(cardParams);
            card.setBackgroundResource(R.drawable.bg_sub_module_card);

            LinearLayout controls = new LinearLayout(this);
            controls.setGravity(Gravity.CENTER_VERTICAL);
            CheckBox include = new CheckBox(this);
            include.setText(sectionTitle(section));
            include.setTextColor(getColor(R.color.text_primary));
            include.setTextSize(12f);
            controls.addView(include, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            CheckBox lock = new CheckBox(this);
            lock.setText(R.string.studio_lock);
            lock.setTextColor(getColor(R.color.text_secondary));
            lock.setTextSize(10f);
            lock.setVisibility(ControllerPinManager.isDomModeActive() ? View.VISIBLE : View.GONE);
            controls.addView(lock);
            card.addView(controls);

            Button refresh = outlineButton(getString(R.string.studio_refresh));
            LinearLayout.LayoutParams refreshParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(42));
            refreshParams.topMargin = dp(4);
            card.addView(refresh, refreshParams);
            if (SubHubPackSchema.WALLET.equals(section)) {
                Button attach = outlineButton("Encrypt current PayPal into pack");
                attach.setOnClickListener(view -> payPalTransfer.attach(draft, this::saveEditor));
                card.addView(attach);
                Button remove = outlineButton("Remove encrypted PayPal");
                remove.setOnClickListener(view -> {
                    if (draft == null || !ControllerPinManager.isDomModeActive()) return;
                    payPalTransfer.pause();
                    try { draft.setEncryptedPayPal(null); saveEditor(); }
                    catch (java.security.GeneralSecurityException ignored) { }
                });
                card.addView(remove);
            }
            binding.sectionList.addView(card);
            includes.put(section, include);
            locks.put(section, lock);
            include.setOnCheckedChangeListener((button, checked) -> {
                if (suppressEvents || draft == null) return;
                if (!checked && SubHubPackSchema.WALLET.equals(section)) payPalTransfer.pause();
                draft.setSection(section, checked ? manager.captureSection(section) : null);
                lock.setEnabled(checked);
                if (!checked) { lock.setChecked(false); draft.setGroupLocked(section, false); }
                saveEditor();
            });
            lock.setOnCheckedChangeListener((button, checked) -> {
                if (!suppressEvents && draft != null && ControllerPinManager.isDomModeActive()) {
                    draft.setGroupLocked(section, checked && include.isChecked());
                    saveEditor();
                }
            });
            refresh.setOnClickListener(view -> {
                if (draft == null) return;
                draft.setSection(section, manager.captureSection(section));
                include.setChecked(true);
                saveEditor();
            });
        }
    }

    private void openDraft(SubHubPack value) {
        if (payPalTransfer != null) payPalTransfer.pause();
        draft = value;
        suppressEvents = true;
        binding.packName.setText(value.getName());
        binding.packAuthor.setText(value.getAuthor());
        binding.packDescription.setText(value.getDescription());
        binding.packVersion.setText(value.getPackVersion());
        for (String section : SECTION_ORDER) {
            boolean included = value.getIncludedSections().contains(section);
            includes.get(section).setChecked(included);
            includes.get(section).setEnabled(true);
            locks.get(section).setChecked(value.getLockGroups().contains(section));
            locks.get(section).setEnabled(included);
        }
        JSONObject recommendations = value.getRecommendations();
        binding.recommendHardcore.setChecked(recommendations.optBoolean("hardcoreSuggested", false));
        long duration = recommendations.optLong("serviceDurationMillis", 0L);
        int index = 0;
        for (int item = 0; item < DURATION_VALUES.length; item++) {
            if (DURATION_VALUES[item] == duration) index = item;
        }
        binding.recommendDuration.setSelection(index);
        suppressEvents = false;
        updatePreview();
        autosave();
        showPanel(binding.editorPanel);
    }

    private void saveEditor() {
        if (suppressEvents || draft == null) return;
        draft.setMetadata(binding.packName.getText().toString(),
                binding.packAuthor.getText().toString(), binding.packDescription.getText().toString(),
                binding.packVersion.getText().toString());
        JSONObject recommendations = new JSONObject();
        try {
            recommendations.put("hardcoreSuggested", binding.recommendHardcore.isChecked());
            recommendations.put("serviceDurationMillis",
                    DURATION_VALUES[binding.recommendDuration.getSelectedItemPosition()]);
        } catch (Exception ignored) {}
        draft.setRecommendations(recommendations);
        autosave();
        updatePreview();
    }

    private void autosave() {
        if (draft == null) return;
        try { manager.saveDraft(draft); }
        catch (IOException error) { toast(error.getMessage()); }
    }

    private void updatePreview() {
        if (draft == null) return;
        int censorAssets = 0;
        int popupAssets = 0;
        for (String path : draft.getAssets().keySet()) {
            if (path.startsWith("assets/censor/")) censorAssets++;
            if (path.startsWith("assets/popup/")) popupAssets++;
        }
        binding.assetSummary.setText(censorAssets == 0 && popupAssets == 0
                ? getString(R.string.studio_assets_empty)
                : getString(R.string.studio_assets_count, censorAssets, popupAssets));
        String creator = draft.getAuthor().isBlank() ? "Private arrangement"
                : "By " + draft.getAuthor();
        String locksText = draft.getLockGroups().isEmpty() ? "No Dom locks"
                : draft.getLockGroups().size() + " Dom lock group(s)";
        binding.previewText.setText(draft.getName() + " · v" + draft.getPackVersion() + "\n"
                + creator + "\n" + draft.getIncludedSections().size() + " feature section(s) · "
                + locksText + "\n" + (draft.hasEncryptedPayPal()
                ? "Encrypted PayPal attached · passphrase required · no payer authorization"
                : getString(R.string.studio_no_secrets)));
    }

    private void renderLibrary() {
        binding.libraryList.removeAllViews();
        List<SubHubPackManager.Record> records = manager.listLibrary();
        List<PackManager.PackInfo> legacy = new PackManager(this).listInstalled();
        binding.libraryEmpty.setVisibility(records.isEmpty() && legacy.isEmpty()
                ? View.VISIBLE : View.GONE);
        for (SubHubPackManager.Record record : records) addLibraryCard(record);
        for (PackManager.PackInfo record : legacy) addLegacyCard(record);
    }

    private void renderDrafts() {
        binding.draftsList.removeAllViews();
        List<SubHubPackManager.Record> records = manager.listDrafts();
        binding.draftsEmpty.setVisibility(records.isEmpty() ? View.VISIBLE : View.GONE);
        for (SubHubPackManager.Record record : records) {
            LinearLayout card = packCard(record.pack,
                    record.pack.getIncludedSections().size() + " feature section(s)");
            LinearLayout actions = actionRow();
            Button edit = outlineButton(getString(R.string.studio_edit));
            edit.setOnClickListener(view -> openDraft(record.pack));
            Button duplicate = outlineButton(getString(R.string.studio_duplicate));
            duplicate.setOnClickListener(view -> duplicatePack(record.pack));
            Button share = outlineButton(getString(R.string.studio_share));
            share.setOnClickListener(view -> share(record.pack));
            actions.addView(edit, weighted()); actions.addView(duplicate, weighted());
            actions.addView(share, weighted());
            card.addView(actions);
            binding.draftsList.addView(card);
        }
    }

    private void addLibraryCard(SubHubPackManager.Record record) {
        String detail = record.pack.getIncludedSections().size() + " feature section(s) · "
                + record.pack.getAssets().size() + " asset(s)";
        LinearLayout card = packCard(record.pack, detail);
        if (record.active) {
            TextView active = label(getString(R.string.studio_active), true);
            active.setTextColor(getColor(R.color.accent));
            card.addView(active);
        }
        LinearLayout actions = actionRow();
        Button apply = outlineButton(record.active
                ? getString(R.string.studio_deactivate) : getString(R.string.studio_apply));
        apply.setOnClickListener(view -> {
            if (!ControllerPinManager.isDomModeActive()) {
                toast(getString(R.string.studio_unlock_required));
            } else if (record.active) {
                if (!manager.deactivate()) toast("Could not deactivate. Turn off automatic settlement "
                        + "and finish or cancel any pending checkout first.");
                renderLibrary();
            } else review(record.pack);
        });
        Button share = outlineButton(getString(R.string.studio_share));
        share.setOnClickListener(view -> share(record.pack));
        Button duplicate = outlineButton(getString(R.string.studio_duplicate));
        duplicate.setOnClickListener(view -> duplicatePack(record.pack));
        Button delete = outlineButton(getString(R.string.studio_delete));
        delete.setOnClickListener(view -> new AlertDialog.Builder(this)
                .setTitle(R.string.studio_delete_title)
                .setMessage(record.pack.getName())
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.studio_delete, (dialog, which) -> {
                    boolean deleted = manager.deleteLibrary(record.pack.getId());
                    toast(getString(deleted ? R.string.studio_deleted
                            : R.string.studio_unlock_required));
                    renderLibrary();
                }).show());
        actions.addView(apply, weighted()); actions.addView(share, weighted());
        card.addView(actions);
        LinearLayout secondary = actionRow();
        secondary.addView(duplicate, weighted()); secondary.addView(delete, weighted());
        card.addView(secondary);
        binding.libraryList.addView(card);
    }

    private void addLegacyCard(PackManager.PackInfo record) {
        SubHubPack shell = new SubHubPack(record.getManifest().getPackId(),
                record.getManifest().getName(), record.getManifest().getAuthor(),
                record.getManifest().getDescription(), record.getManifest().getVersion(),
                1L, 1L, "0.1.0", Map.of(), Set.of(), new JSONObject(), Map.of());
        LinearLayout card = packCard(shell, getString(R.string.studio_legacy_badge));
        LinearLayout actions = actionRow();
        Button apply = outlineButton(record.getManifest().getPackId().equals(
                new PackManager(this).activePackId()) ? getString(R.string.studio_deactivate)
                : getString(R.string.studio_apply));
        apply.setOnClickListener(view -> {
            if (!ControllerPinManager.isDomModeActive()) {
                toast(getString(R.string.studio_unlock_required)); return;
            }
            PackManager legacy = new PackManager(this);
            if (record.getManifest().getPackId().equals(legacy.activePackId())) legacy.deactivate();
            else legacy.activate(record.getManifest().getPackId());
            renderLibrary();
        });
        actions.addView(apply, weighted());
        card.addView(actions);
        binding.libraryList.addView(card);
    }

    private void review(SubHubPack pack) {
        List<String> included = new ArrayList<>(pack.getIncludedSections());
        if (included.isEmpty()) { toast(getString(R.string.studio_no_sections)); return; }
        boolean[] selected = new boolean[included.size()];
        java.util.Arrays.fill(selected, true);
        CharSequence[] labels = new CharSequence[included.size()];
        for (int index = 0; index < labels.length; index++) labels[index] = sectionTitle(included.get(index));
        new AlertDialog.Builder(this).setTitle(R.string.studio_review_title)
                .setMultiChoiceItems(labels, selected, (dialog, which, checked) -> selected[which] = checked)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.studio_apply, (dialog, which) -> {
                    Set<String> sections = new LinkedHashSet<>();
                    for (int index = 0; index < included.size(); index++) {
                        if (selected[index]) sections.add(included.get(index));
                    }
                    showDiff(pack, sections);
                }).show();
    }

    private void showDiff(SubHubPack pack, Set<String> sections) {
        if (sections.isEmpty()) { toast(getString(R.string.studio_no_sections)); return; }
        String message = String.join("\n\n", manager.diff(pack, sections));
        new AlertDialog.Builder(this).setTitle(pack.getName()).setMessage(message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.studio_apply_confirm, (dialog, which) -> {
                    if (pack.hasEncryptedPayPal() && sections.contains(SubHubPackSchema.WALLET)) {
                        payPalTransfer.activate(pack, sections, this::renderLibrary);
                        return;
                    }
                    boolean applied = manager.activate(pack, sections);
                    toast(getString(applied ? R.string.studio_applied : R.string.studio_apply_failed));
                    renderLibrary();
                }).show();
    }

    private void duplicatePack(SubHubPack source) {
        if (source.hasEncryptedPayPal()) {
            new AlertDialog.Builder(this).setTitle("Duplicate without PayPal?")
                    .setMessage("The copy has a new identity. Its encrypted PayPal attachment is removed; "
                            + "attach and encrypt again if needed. The original is unchanged.")
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton("Duplicate", (d, w) -> {
                        openDraft(source.duplicate());
                    }).show();
        } else openDraft(source.duplicate());
    }

    private void publishDraft() {
        if (payPalTransfer.isWorking()) { toast("Wait for PayPal encryption to finish."); return; }
        saveEditor();
        if (draft == null || draft.getIncludedSections().isEmpty()) {
            toast(getString(R.string.studio_no_sections)); return;
        }
        try {
            manager.addToLibrary(draft);
            toast(getString(R.string.studio_library_added));
            renderLibrary();
            showPanel(binding.libraryPanel);
        } catch (IOException error) { toast(error.getMessage()); }
    }

    private void deleteDraft() {
        if (draft == null) return;
        payPalTransfer.pause();
        manager.deleteDraft(draft.getId());
        draft = null;
        renderDrafts();
        showPanel(binding.draftsPanel);
    }

    private void importPack(Uri uri) {
        if (uri == null) return;
        try {
            SubHubPack pack = manager.importPack(uri);
            toast(getString(pack.getId().equals(manager.activePackId())
                    ? R.string.studio_active_update_imported : R.string.studio_imported,
                    pack.getName()));
        } catch (IOException modernFailure) {
            try {
                PackManager.PackInfo legacy = new PackManager(this).importPack(uri);
                toast(getString(R.string.studio_legacy_imported, legacy.getManifest().getName()));
            } catch (IOException legacyFailure) {
                toast(getString(R.string.studio_import_failed, modernFailure.getMessage()));
            }
        }
        renderLibrary();
    }

    private void share(SubHubPack pack) {
        if (pack == null) return;
        if (payPalTransfer.isWorking()) { toast("Wait for PayPal encryption to finish."); return; }
        try {
            File file = manager.exportForShare(pack);
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".updates", file);
            Intent send = new Intent(Intent.ACTION_SEND).setType("application/zip")
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(send, getString(R.string.studio_share_chooser)));
        } catch (Exception error) { toast(error.getMessage()); }
    }

    private void pickImages(String target) {
        assetTarget = target;
        imagePicker.launch(new String[]{"image/png", "image/jpeg", "image/webp"});
    }

    private void addImages(List<Uri> uris) {
        if (draft == null || uris == null) return;
        int existing = 0;
        for (String path : draft.getAssets().keySet()) if (path.startsWith("assets/" + assetTarget + "/")) {
            existing++;
        }
        for (Uri uri : uris) {
            if (existing >= 64) break;
            try (InputStream input = getContentResolver().openInputStream(uri)) {
                if (input == null) continue;
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                long total = 0L;
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    total += read;
                    if (total > MAX_IMAGE_BYTES) throw new IOException("Image exceeds 25 MiB");
                    output.write(buffer, 0, read);
                }
                draft.putAsset(String.format(Locale.ROOT, "assets/%s/image-%02d.png",
                        assetTarget, existing++), output.toByteArray());
            } catch (IOException error) { toast(error.getMessage()); }
        }
        autosave();
        updatePreview();
    }

    private LinearLayout packCard(SubHubPack pack, String detail) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(14), dp(14), dp(14));
        card.setBackgroundResource(R.drawable.bg_card);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(10);
        card.setLayoutParams(params);
        card.addView(label(pack.getName(), true));
        String byline = pack.getAuthor().isBlank() ? "v" + pack.getPackVersion()
                : "by " + pack.getAuthor() + " · v" + pack.getPackVersion();
        card.addView(label(byline, false));
        if (!pack.getDescription().isBlank()) card.addView(label(pack.getDescription(), false));
        card.addView(label(detail, false));
        if (pack.hasEncryptedPayPal()) card.addView(label("Encrypted PayPal · passphrase required", false));
        return card;
    }

    private LinearLayout actionRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(8);
        row.setLayoutParams(params);
        return row;
    }

    private Button outlineButton(String text) {
        Button button = new Button(this, null, 0, R.style.Widget_SubHub_CompactOutlineButton);
        button.setText(text);
        button.setTextSize(10f);
        button.setMinWidth(0);
        button.setPadding(dp(4), 0, dp(4), 0);
        return button;
    }

    private LinearLayout.LayoutParams weighted() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(44), 1f);
        params.setMarginEnd(dp(4));
        return params;
    }

    private TextView label(String text, boolean title) {
        TextView value = new TextView(this);
        value.setText(text);
        value.setTextColor(getColor(title ? R.color.text_primary : R.color.text_secondary));
        value.setTextSize(title ? 15f : 11f);
        if (title) value.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        if (!title) value.setPadding(0, dp(3), 0, 0);
        return value;
    }

    private void addMetadataWatcher(TextView field) {
        field.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence value, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence value, int start, int before, int count) {
                saveEditor();
            }
            @Override public void afterTextChanged(Editable value) {}
        });
    }

    private String sectionTitle(String section) {
        switch (section) {
            case SubHubPackSchema.MODULES: return getString(R.string.studio_section_modules);
            case SubHubPackSchema.CENSOR: return getString(R.string.studio_section_censor);
            case SubHubPackSchema.LIMITS: return getString(R.string.studio_section_limits);
            case SubHubPackSchema.WALLET: return getString(R.string.studio_section_wallet);
            case SubHubPackSchema.SUBLIMINAL: return getString(R.string.studio_section_subliminal);
            case SubHubPackSchema.POPUP: return getString(R.string.studio_section_popup);
            default: return section;
        }
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void toast(String message) {
        Toast.makeText(this, message == null ? "Studio action failed" : message,
                Toast.LENGTH_LONG).show();
    }

    private static final class SimpleItemSelectedListener
            implements android.widget.AdapterView.OnItemSelectedListener {
        private final Runnable callback;
        SimpleItemSelectedListener(Runnable callback) { this.callback = callback; }
        @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view,
                int position, long id) { callback.run(); }
        @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
    }
}
