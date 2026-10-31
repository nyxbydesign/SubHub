package com.subhub.app.util;

import static org.junit.Assert.*;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.R;
import com.subhub.app.capture.export.ExportMedia;
import com.subhub.app.capture.export.ExportJobStore;
import com.subhub.app.capture.export.ExportOptions;
import com.subhub.app.penance.PayPalCredentialStore;
import com.subhub.app.penance.PayPalEnvironment;
import com.subhub.app.settings.SharedPreferenceTestRestore;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import org.junit.After;
import org.junit.Test;

public final class InputBoundsAndroidTest {
    @org.junit.Rule public final com.subhub.app.security.KeyholderPresentationAndroidTest.IntegrationFixture integration =
            new com.subhub.app.security.KeyholderPresentationAndroidTest.IntegrationFixture();
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private static Uri fixture(String name) { return Uri.parse("content://com.subhub.app.test.input-fixture/" + name); }

    @After public void cleanup() {
        for (String name : new String[]{"photo", "largevideo"}) context.getContentResolver().delete(fixture(name), null, null);
    }

    @Test public void themedInputsBoundPasteAndPreserveUnicodeAtTheBoundary() {
        com.subhub.app.onboarding.OnboardingState.complete(context);
        try (androidx.test.core.app.ActivityScenario<com.subhub.app.help.HelpActivity> page =
                androidx.test.core.app.ActivityScenario.launch(com.subhub.app.help.HelpActivity.class)) {
        page.onActivity(activity -> {
            Context themed = new ContextThemeWrapper(context, R.style.Theme_SubHub);
            ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData previous = clipboard.getPrimaryClip();
            try {
            for (EditText input : new EditText[]{new EditText(themed), new androidx.appcompat.widget.AppCompatEditText(themed)}) {
                input.setText("x".repeat(4095));
                input.append("\ud83d\ude00");
                assertEquals(4095, input.length());
                clipboard.setPrimaryClip(ClipData.newPlainText("Synthetic input", "yz"));
                input.setSelection(input.length());
                input.onTextContextMenuItem(android.R.id.paste);
                assertEquals(4096, input.length());
                input.append("z");
                assertEquals(4096, input.length());
                input.getText().delete(4090, 4096);
                input.append("a");
                assertEquals(4091, input.length());
            }
            } finally {
                if (previous != null) clipboard.setPrimaryClip(previous);
                else if (android.os.Build.VERSION.SDK_INT >= 28) clipboard.clearPrimaryClip();
            }
        });
        }
    }

    @Test public void legacyCredentialsRemainExactAndRejectedImportsDoNotReplaceThem() throws Exception {
        PayPalCredentialStore store = new PayPalCredentialStore(context);
        android.content.SharedPreferences preferences = context.getSharedPreferences(PayPalCredentialStore.PREFS_NAME, 0);
        java.util.Map<String, ?> before = new LinkedHashMap<>(preferences.getAll());
        try {
            assertTrue(store.save(PayPalEnvironment.SANDBOX, "synthetic-id", "synthetic-secret"));
            java.lang.reflect.Method encrypt = PayPalCredentialStore.class.getDeclaredMethod("encrypt", String.class);
            encrypt.setAccessible(true);
            String legacy = "s".repeat(4097);
            preferences.edit().putString("client_id", (String) encrypt.invoke(store, legacy)).commit();
            assertEquals(legacy, store.load().clientId());
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                EditText input = new EditText(new ContextThemeWrapper(context, R.style.Theme_SubHub));
                InputBounds.restore(input, store.load().clientId(), InputBounds.CREDENTIAL);
                assertEquals(legacy, input.getText().toString());
                input.setId(R.id.paypal_client_id);
                input.setFreezesText(true);
                android.util.SparseArray<android.os.Parcelable> saved = new android.util.SparseArray<>();
                input.saveHierarchyState(saved);
                EditText recreated = new EditText(new ContextThemeWrapper(context, R.style.Theme_SubHub));
                recreated.setId(R.id.paypal_client_id);
                InputBounds.restore(recreated, legacy, InputBounds.CREDENTIAL);
                recreated.restoreHierarchyState(saved);
                assertEquals(legacy, recreated.getText().toString());
                input.append("new");
                assertEquals(legacy, input.getText().toString());
                input.getText().delete(4090, input.length());
                input.append("ok");
                assertEquals(4092, input.length());
            });
            assertFalse(store.saveImported(PayPalEnvironment.LIVE, "new".repeat(1400), "new-secret"));
            assertFalse(store.save(PayPalEnvironment.LIVE, "new".repeat(1400), "new-secret"));
            assertFalse(store.save(PayPalEnvironment.LIVE, legacy, "synthetic-secret"));
            assertEquals(legacy, store.load().clientId());
            assertEquals(PayPalEnvironment.SANDBOX, store.load().environment());
            assertTrue(store.save(PayPalEnvironment.SANDBOX, legacy, "synthetic-secret"));
            assertEquals(legacy, store.load().clientId());
        } finally { SharedPreferenceTestRestore.restore(preferences, before); }
    }

    @Test public void domainOverridesAndNumericStylesReplaceTheCommonCeiling() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context themed = new ContextThemeWrapper(context, R.style.Theme_SubHub);
            LinearLayout parent = new LinearLayout(themed);
            View wallet = LayoutInflater.from(themed).inflate(R.layout.activity_penance, parent, false);
            EditText amount = wallet.findViewById(R.id.rule_detection_amount);
            amount.setText("1".repeat(20));
            assertEquals(16, amount.length());
            EditText batch = wallet.findViewById(R.id.detection_batch);
            batch.setText("1".repeat(20));
            assertEquals(10, batch.length());
            EditText input = new EditText(themed);
            InputBounds.limit(input, InputBounds.PAIRING);
            input.setText("x".repeat(8193));
            assertEquals(8192, input.length());
            InputBounds.limit(input, InputBounds.SUBLIMINAL_PHRASES);
            input.setText("x".repeat(24200));
            assertEquals(24199, input.length());
        });
    }

    @Test public void subliminalSaveAndRemoteCatalogShareTheSameFullLengthWithoutSplittingEmoji() throws Exception {
        com.subhub.app.subliminal.SubliminalSettingsRepository repository =
                new com.subhub.app.subliminal.SubliminalSettingsRepository(context);
        String line = "x".repeat(120);
        String full = String.join("\n", java.util.Collections.nCopies(200, line));
        repository.saveCustomPhrases(full);
        assertEquals(24199, repository.load().getCustomPhrases().length());
        com.subhub.app.configuration.ConfigurationSettingCatalog.Field field =
                com.subhub.app.configuration.ConfigurationSettingCatalog.field("subliminal", "subliminal_custom_phrases");
        assertEquals(full, field.normalize(full));
        try { field.normalize(full + "x"); fail("Oversized text accepted"); }
        catch (IllegalArgumentException expected) { }
        repository.saveCustomPhrases("x".repeat(119) + "\ud83d\ude00");
        assertEquals("x".repeat(119), repository.load().getCustomPhrases());
    }

    @Test public void imagesCountActualBytesIncludingUnknownSizePipesAndRemoveTemporaryCopies() throws Exception {
        java.util.Set<String> before = cacheSources();
        for (String source : new String[]{"photo", "pipephoto"}) {
            Bitmap bitmap = ExportMedia.image(context, fixture(source), 32);
            try { assertTrue(bitmap.getWidth() <= 32); assertTrue(bitmap.getHeight() <= 32); }
            finally { bitmap.recycle(); }
        }
        try { ExportMedia.image(context, fixture("pipeoversize"), 32); fail("Oversized source accepted"); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("limit")); }
        assertEquals(before, cacheSources());
    }

    @Test public void videoChecksTheActualDescriptorAndQueueRejectsExcessSourcesBeforeWriting() throws Exception {
        java.util.Set<String> before = cacheSources();
        try (MediaInput ignored = MediaInput.video(context, fixture("pipephoto"))) {
            assertEquals(before.size() + 1, cacheSources().size());
        }
        assertEquals(before, cacheSources());
        try (MediaInput ignored = MediaInput.video(context, fixture("largevideo"))) { fail("Oversized video accepted"); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("1 GiB")); }
        assertEquals(before, cacheSources());
        try (ExportJobStore jobs = new ExportJobStore(context)) {
            String latest = jobs.latest();
            try {
                jobs.create(java.util.Collections.nCopies(101, fixture("photo")), new org.json.JSONObject(), ExportOptions.defaults());
                fail("Oversized queue accepted");
            } catch (IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("100")); }
            assertEquals(latest, jobs.latest());
        }
    }

    private java.util.Set<String> cacheSources() {
        String[] files = context.getCacheDir().list((directory, name) -> name.startsWith("media-input-"));
        assertNotNull(files);
        return new HashSet<>(Arrays.asList(files));
    }
}
