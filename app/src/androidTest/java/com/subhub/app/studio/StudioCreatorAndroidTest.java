package com.subhub.app.studio;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.closeSoftKeyboard;
import static androidx.test.espresso.action.ViewActions.replaceText;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withTagValue;
import static com.subhub.app.NativeUiActions.revealAboveNavigation;

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.*;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.SystemClock;
import android.view.View;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.subhub.app.R;
import com.subhub.app.appmode.AppModeManager;
import com.subhub.app.pack.PackSettingCatalog;
import com.subhub.app.pack.SubHubPack;
import com.subhub.app.pack.SubHubPackManager;
import com.subhub.app.pack.SubHubPackSchema;
import com.subhub.app.penance.PenanceManager;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.settings.SettingsRepository;
import com.subhub.app.util.StateToggle;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Own native draft/editor/export/import/apply flow; never calls a payment provider. */
@RunWith(AndroidJUnit4.class)
public final class StudioCreatorAndroidTest {
    @Test public void tributeToggleAndAmountShareACompactDraftOnlyRow() {
        AtomicReference<JSONObject> saved = new AtomicReference<>();
        Map<String, ?> before = walletPrefs().getAll();
        try (ActivityScenario<StudioActivity> scenario = ActivityScenario.launch(StudioActivity.class)) {
            scenario.onActivity(activity -> PackSectionEditor.show(activity, "wallet", "Tribute rules",
                    PackSettingCatalog.defaults("wallet"), saved::set));
            onView(withTagValue(is((Object) "rule_new_detection_cents")))
                    .inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog())
                    .check((view, missing) -> {
                        if (missing != null) throw missing;
                        View checkbox = view.getRootView().findViewWithTag("rule_new_detection_enabled");
                        assertTrue(checkbox instanceof StateToggle);
                        View amountRow = (View) view.getParent();
                        View toggleRow = (View) checkbox.getParent();
                        assertEquals(toggleRow.getParent(), amountRow.getParent());
                        assertTrue(amountRow.getParent() instanceof com.subhub.app.util.CompactFieldLayout);
                        assertEquals(toggleRow.getTop(), amountRow.getTop());
                        assertTrue(toggleRow.getRight() <= amountRow.getLeft());
                    })
                    .perform(scrollTo(), replaceText("2.25"), closeSoftKeyboard());
            onView(withTagValue(is((Object) "rule_new_detection_enabled")))
                    .inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog())
                    .perform(scrollTo(), click());
            onView(withId(android.R.id.button1)).inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).perform(click());
            assertNotNull(saved.get());
            assertEquals(225, saved.get().optInt("rule_new_detection_cents"));
            assertFalse(saved.get().optBoolean("rule_new_detection_enabled"));
            assertEquals(before, walletPrefs().getAll());
        }
    }
    private Context context;
    private SubHubPackManager manager;
    private Map<String, ?> mainBefore;
    private Map<String, ?> walletBefore;
    private String createdId;

    @Before public void setup() {
        context = ApplicationProvider.getApplicationContext();
        ControllerPinManager.enterDomMode();
        manager = new SubHubPackManager(context);
        assertTrue(manager.deactivate());
        mainBefore = mainPrefs().getAll();
        walletBefore = walletPrefs().getAll();
    }

    @After public void cleanup() {
        ControllerPinManager.enterDomMode();
        if (createdId != null) {
            if (createdId.equals(manager.activePackId())) assertTrue(manager.deactivate());
            manager.deleteLibrary(createdId);
            manager.deleteDraft(createdId);
        }
        restore(mainPrefs(), mainBefore);
        restore(walletPrefs(), walletBefore);
        ControllerPinManager.enterSubMode();
    }

    @Test public void subCreatesNativeDraftThenExportsImportsAndDomAppliesWithoutEnteringService() throws Exception {
        mainPrefs().edit().putBoolean(AppModeManager.KEY_ARMED, false)
                .putInt(SettingsRepository.KEY_CENSOR_INTENSITY, 15).commit();
        ControllerPinManager.enterSubMode();
        try (ActivityScenario<StudioActivity> scenario = ActivityScenario.launch(StudioActivity.class)) {
            onView(withId(R.id.tab_create)).perform(click());
            onView(withId(R.id.button_blank)).perform(revealAboveNavigation(), click());
            onView(withId(R.id.pack_name)).perform(revealAboveNavigation(), replaceText("Native creator round-trip"), closeSoftKeyboard());
            scenario.onActivity(activity -> createdId = draft(activity).getId());
            onView(withId(R.id.editor_next)).perform(revealAboveNavigation(), click());
            onView(withTagValue(is((Object) "pack_configure:censor"))).perform(revealAboveNavigation(), click());
            // The Appearance group opens separately; edits are native input controls on a draft.
            onView(withTagValue(is((Object) ("pack_group:censor:" + R.string.pack_group_appearance)))).perform(scrollTo(), click());
            onView(withTagValue(is((Object) "censor_intensity"))).perform(scrollTo(), replaceText("67"), closeSoftKeyboard());
            onView(withId(android.R.id.button1)).perform(click());
            assertEquals(15, mainPrefs().getInt(SettingsRepository.KEY_CENSOR_INTENSITY, -1));
            AtomicReference<SubHubPack> edited = new AtomicReference<>();
            scenario.onActivity(activity -> edited.set(draft(activity).snapshot()));
            assertEquals(67, edited.get().getSection("censor").getInt("censor_intensity"));
            File exported = manager.exportForShare(edited.get());
            scenario.onActivity(activity -> invokeImport(activity, Uri.fromFile(exported)));
            awaitIdle(scenario);
            assertEquals(67, manager.findLibrary(createdId).getSection("censor").getInt("censor_intensity"));
            ControllerPinManager.enterDomMode();
            onView(withId(R.id.tab_library)).perform(revealAboveNavigation(), click());
            awaitCard(scenario, "pack_apply:" + createdId);
            onView(withTagValue(is((Object) ("pack_apply:" + createdId)))).perform(revealAboveNavigation(), click());
            awaitIdle(scenario);
            onView(withId(android.R.id.button1)).inRoot(org.hamcrest.Matchers.allOf(
                    androidx.test.espresso.matcher.RootMatchers.isDialog(),
                    androidx.test.espresso.matcher.RootMatchers.withDecorView(
                            androidx.test.espresso.matcher.ViewMatchers.hasDescendant(
                                    androidx.test.espresso.matcher.ViewMatchers.withText(R.string.studio_apply)))))
                    .perform(click()); // Section review.
            onView(withId(android.R.id.button1)).inRoot(org.hamcrest.Matchers.allOf(
                    androidx.test.espresso.matcher.RootMatchers.isDialog(),
                    androidx.test.espresso.matcher.RootMatchers.withDecorView(
                            androidx.test.espresso.matcher.ViewMatchers.hasDescendant(
                                    androidx.test.espresso.matcher.ViewMatchers.withText(R.string.studio_apply_confirm)))))
                    .perform(click()); // Final diff confirmation.
            awaitIdle(scenario);
            assertEquals(createdId, new SubHubPackManager(context).activePackId());
            assertEquals(67, new SettingsRepository(context).preferences()
                    .getInt(SettingsRepository.KEY_CENSOR_INTENSITY, -1));
            assertFalse(new AppModeManager(context).isArmed());
            assertTrue(manager.deactivate());
            assertEquals(15, mainPrefs().getInt(SettingsRepository.KEY_CENSOR_INTENSITY, -1));
            assertFalse(new AppModeManager(context).isArmed());
        }
    }

    @Test public void everyCatalogFieldAppliesWithItsRuntimeTypeAndPreservesLocalState() throws Exception {
        SubHubPack pack = manager.createBlank();
        createdId = pack.getId();
        for (String section : SubHubPackSchema.SECTIONS) pack.setSection(section, PackSettingCatalog.defaults(section));
        mainPrefs().edit().putBoolean(AppModeManager.KEY_ARMED, true)
                .putStringSet(AppModeManager.KEY_INCLUDED_PACKAGES, Set.of("synthetic.local.app"))
                .putBoolean(com.subhub.app.popup.PopupStormSettings.K_ACK, true).commit();
        walletPrefs().edit().putString("wallet_currency", "USD").commit();
        File exported = manager.exportForShare(pack);
        SubHubPack imported = manager.importPack(Uri.fromFile(exported));
        assertTrue(manager.activate(imported, SubHubPackSchema.SECTIONS));
        for (String section : SubHubPackSchema.SECTIONS) {
            Map<String, ?> actual = ("wallet".equals(section) ? walletPrefs() : mainPrefs()).getAll();
            for (PackSettingCatalog.Field field : PackSettingCatalog.fields(section)) {
                Object value = actual.get(field.key);
                assertNotNull(field.key, value);
                Class<?> type = switch (field.kind) {
                    case BOOLEAN -> Boolean.class;
                    case INTEGER, MONEY -> Integer.class;
                    case LONG -> Long.class;
                    case DECIMAL, RATIO -> Float.class;
                    case SELECTION -> Set.class;
                    default -> String.class;
                };
                assertTrue(field.key + " should be " + type.getSimpleName(), type.isInstance(value));
            }
        }
        assertTrue(new AppModeManager(context).isArmed());
        assertEquals(Set.of("synthetic.local.app"), new AppModeManager(context).getIncludedPackages());
        assertEquals("USD", walletPrefs().getString("wallet_currency", ""));
        assertTrue(mainPrefs().getBoolean(com.subhub.app.popup.PopupStormSettings.K_ACK, false));
        assertFalse(context.getSharedPreferences("subhub_pack_state_v1", Context.MODE_PRIVATE).contains("active_lock_groups"));
        assertTrue(new SubHubPackManager(context).deactivate());
        assertTrue(new AppModeManager(context).isArmed());
    }

    @Test public void rotationRestoresDraftStepAndExcludedSectionEditsWithoutChangingLiveSettings() {
        try (ActivityScenario<StudioActivity> scenario = ActivityScenario.launch(StudioActivity.class)) {
            scenario.onActivity(activity -> {
                activity.findViewById(R.id.tab_create).performClick();
                activity.findViewById(R.id.button_blank).performClick();
                createdId = draft(activity).getId();
                ((TextView) activity.findViewById(R.id.pack_name)).setText("Rotation draft");
                activity.findViewById(R.id.editor_next).performClick();
                StateToggle include = activity.findViewById(R.id.section_list).findViewWithTag("pack_include:censor");
                assertFalse(include.isChecked());
                include.performClick(); // CompoundButton toggles even without an
                                                 // OnClickListener.
                        assertTrue(include.isChecked());
                activity.findViewById(R.id.editor_next).performClick();
            });
            scenario.recreate();
            awaitIdle(scenario);
            scenario.onActivity(activity -> {
                assertEquals("Rotation draft", draft(activity).getName());
                assertEquals(createdId, draft(activity).getId());
                assertTrue(activity.findViewById(R.id.images_step).isShown());
                assertFalse(activity.findViewById(R.id.details_step).isShown());
                assertTrue(draft(activity).getIncludedSections().contains("censor"));
            });
            assertEquals(mainBefore.get(SettingsRepository.KEY_CENSOR_INTENSITY),
                    mainPrefs().getAll().get(SettingsRepository.KEY_CENSOR_INTENSITY));
        }
    }

    @Test public void gradientPickerEditsOnlyTheDraftAndSavesBothEndpoints() throws Exception {
        int liveBefore = new com.subhub.app.settings.SettingsRepository(context).loadAppearance().getGradientStart();
        JSONObject values = PackSettingCatalog.defaults("censor");
        values.put("border_effect", "gradient");
        values.put("border_gradient_start", "#0000FF");
        values.put("border_gradient_end", "#00FF00");
        java.util.concurrent.atomic.AtomicReference<JSONObject> result = new java.util.concurrent.atomic.AtomicReference<>();
        try (ActivityScenario<StudioActivity> scenario = ActivityScenario.launch(StudioActivity.class)) {
            scenario.onActivity(activity -> PackSectionEditor.show(activity, "censor", "Censor", values, result::set));
            onView(withTagValue(is((Object) ("pack_group:censor:" + R.string.pack_group_border))))
                    .inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).perform(scrollTo(), click());
            onView(withTagValue(is((Object) "border_gradient_start")))
                    .inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).perform(scrollTo(), click());
            org.hamcrest.Matcher<androidx.test.espresso.Root> picker = org.hamcrest.Matchers.allOf(
                    androidx.test.espresso.matcher.RootMatchers.isDialog(),
                    androidx.test.espresso.matcher.RootMatchers.withDecorView(
                            androidx.test.espresso.matcher.ViewMatchers.hasDescendant(withTagValue(is((Object) "color_wheel")))));
            onView(withTagValue(is((Object) "color_wheel"))).inRoot(picker)
                    .perform(com.subhub.app.ColorPickerDialogTest.redEdge());
            onView(withId(android.R.id.button1)).inRoot(picker).perform(click());
            assertEquals(liveBefore, new com.subhub.app.settings.SettingsRepository(context).loadAppearance().getGradientStart());
            onView(withId(android.R.id.button1)).inRoot(org.hamcrest.Matchers.allOf(
                    androidx.test.espresso.matcher.RootMatchers.isDialog(),
                    androidx.test.espresso.matcher.RootMatchers.withDecorView(
                            androidx.test.espresso.matcher.ViewMatchers.hasDescendant(withTagValue(is((Object) "border_gradient_start"))))))
                    .perform(click());
            assertNotNull(result.get());
            assertEquals("#FFFF0000", result.get().getString("border_gradient_start"));
            assertEquals("#00FF00", result.get().getString("border_gradient_end"));
        }
    }

    @Test public void all115TransferableSettingsHaveNativeDraftControls() {
        try (ActivityScenario<StudioActivity> scenario = ActivityScenario.launch(StudioActivity.class)) {
            int count = 0;
            for (String section : SubHubPackSchema.SECTIONS) {
                AtomicBoolean saved = new AtomicBoolean();
                scenario.onActivity(activity -> PackSectionEditor.show(activity, section, section,
                        PackSettingCatalog.defaults(section), values -> {
                            assertEquals(PackSettingCatalog.fields(section).size(), values.length());
                            saved.set(true);
                        }));
                for (PackSettingCatalog.Field field : PackSettingCatalog.fields(section)) {
                    onView(withTagValue(is((Object) field.key)))
                            .inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).check((view, error) -> {
                        if (error != null) throw error;
                        assertNotNull(view);
                        assertNotNull(view.getTag());
                        if (field.kind == PackSettingCatalog.Kind.BOOLEAN) {
                            assertTrue(field.key, view instanceof StateToggle);
                            StateToggle toggle = (StateToggle) view;
                            assertNull(toggle.getThumbDrawable());
                            assertNull(toggle.getTrackDrawable());
                            assertEquals(context.getString(R.string.control_state_on), toggle.getTextOn());
                            assertEquals(context.getString(R.string.control_state_off), toggle.getTextOff());
                            assertEquals(PackSettingCatalog.defaults(section).optBoolean(field.key), toggle.isChecked());
                        }
                    });
                    count++;
                }
                onView(withId(android.R.id.button1)).inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).perform(click());
                assertTrue(section + " should save as a draft section", saved.get());
            }
            assertEquals(115, count);
        }
    }

    @Test public void categoryPickerShowsOneAssChoiceAndSavesBothInternalClasses() throws Exception {
        JSONObject values = PackSettingCatalog.defaults("censor");
        values.put("enabled_categories", new org.json.JSONArray(java.util.List.of("anus")));
        AtomicReference<JSONObject> saved = new AtomicReference<>();
        try (ActivityScenario<StudioActivity> scenario = ActivityScenario.launch(StudioActivity.class)) {
            scenario.onActivity(activity -> PackSectionEditor.show(activity, "censor", "Censor", values, saved::set));
            onView(withTagValue(is((Object) "enabled_categories")))
                    .inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).perform(scrollTo(), click());
            org.hamcrest.Matcher<androidx.test.espresso.Root> picker = org.hamcrest.Matchers.allOf(
                    androidx.test.espresso.matcher.RootMatchers.isDialog(),
                    androidx.test.espresso.matcher.RootMatchers.withDecorView(
                            androidx.test.espresso.matcher.ViewMatchers.hasDescendant(
                                    androidx.test.espresso.matcher.ViewMatchers.withText("Ass"))));
            onView(androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom(android.widget.ListView.class))
                    .inRoot(picker).check((view, missing) -> {
                if (missing != null) throw missing;
                android.widget.ListView choices = (android.widget.ListView) view;
                int assCount = 0;
                for (int index = 0; index < choices.getAdapter().getCount(); index++) {
                    String name = String.valueOf(choices.getAdapter().getItem(index));
                    assertFalse(name.contains("Buttocks"));
                    assertFalse(name.contains("Anus"));
                    if ("Ass".equals(name)) {
                        assCount++;
                        assertTrue(choices.isItemChecked(index));
                    }
                }
                assertEquals(1, assCount);
            });
            onView(androidx.test.espresso.matcher.ViewMatchers.withText("Ass")).inRoot(picker).perform(click());
            onView(androidx.test.espresso.matcher.ViewMatchers.withText("Ass")).inRoot(picker).perform(click());
            onView(withId(android.R.id.button1)).inRoot(picker).perform(click());
            onView(withTagValue(is((Object) "enabled_categories")))
                    .inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog())
                    .check((view, missing) -> {
                        if (missing != null) throw missing;
                        assertEquals(context.getResources().getQuantityString(R.plurals.pack_editor_selection_count, 1, 1),
                                ((TextView) view).getText().toString());
                    });
            onView(withId(android.R.id.button1)).inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).perform(click());
            assertNotNull(saved.get());
            org.json.JSONArray categories = saved.get().getJSONArray("enabled_categories");
            Set<String> raw = new java.util.LinkedHashSet<>();
            for (int index = 0; index < categories.length(); index++) raw.add(categories.getString(index));
            assertEquals(Set.of("buttocks", "anus"), raw);
        }
    }

    @Test public void nativeImageImportAndRemovalRoundTripThroughTheArchive() throws Exception {
        File imageFile = new File(context.getCacheDir(), "studio-synthetic-image.png");
        android.graphics.Bitmap image = android.graphics.Bitmap.createBitmap(4, 4, android.graphics.Bitmap.Config.ARGB_8888);
        image.eraseColor(android.graphics.Color.BLUE);
        try (java.io.FileOutputStream output = new java.io.FileOutputStream(imageFile)) {
            assertTrue(image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output));
        } finally { image.recycle(); }
        try (ActivityScenario<StudioActivity> scenario = ActivityScenario.launch(StudioActivity.class)) {
            scenario.onActivity(activity -> {
                activity.findViewById(R.id.tab_create).performClick();
                activity.findViewById(R.id.button_blank).performClick();
                createdId = draft(activity).getId();
                try {
                    Method method = StudioActivity.class.getDeclaredMethod("addImages", java.util.List.class);
                    method.setAccessible(true);
                    method.invoke(activity, java.util.List.of(Uri.fromFile(imageFile)));
                } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
            });
            awaitIdle(scenario);
            AtomicReference<SubHubPack> edited = new AtomicReference<>();
            scenario.onActivity(activity -> edited.set(draft(activity).snapshot()));
            assertEquals(1, edited.get().getAssetPaths().size());
            String path = edited.get().getAssetPaths().iterator().next();
            assertTrue(path.startsWith("assets/censor/"));
            SubHubPack imported = manager.importPack(Uri.fromFile(manager.exportForShare(edited.get())));
            assertArrayEquals(edited.get().getAsset(path), imported.getAsset(path));
            long deadline = SystemClock.uptimeMillis() + 15000;
            AtomicBoolean ready = new AtomicBoolean();
            while (!ready.get() && SystemClock.uptimeMillis() < deadline) {
                scenario.onActivity(activity -> ready.set(activity.findViewById(R.id.asset_list)
                        .findViewWithTag("pack_remove_asset:" + path) != null));
                if (!ready.get()) SystemClock.sleep(25);
            }
            assertTrue("Image thumbnail/removal control should appear", ready.get());
            onView(withId(R.id.editor_next)).perform(revealAboveNavigation(), click());
            onView(withId(R.id.editor_next)).perform(revealAboveNavigation(), click());
            onView(withTagValue(is((Object) ("pack_remove_asset:" + path)))).perform(revealAboveNavigation(), click());
            scenario.onActivity(activity -> {
                assertTrue(draft(activity).getAssetPaths().isEmpty());
            });
        } finally { assertTrue(imageFile.delete()); }
    }

    private static SubHubPack draft(StudioActivity activity) {
        try { Field field = StudioActivity.class.getDeclaredField("draft"); field.setAccessible(true); return (SubHubPack) field.get(activity); }
        catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    private static void invokeImport(StudioActivity activity, Uri uri) {
        try { Method method = StudioActivity.class.getDeclaredMethod("importPack", Uri.class); method.setAccessible(true); method.invoke(activity, uri); }
        catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    private static void awaitIdle(ActivityScenario<StudioActivity> scenario) {
        AtomicBoolean busy = new AtomicBoolean(true);
        long deadline = SystemClock.uptimeMillis() + 15000;
        while (busy.get() && SystemClock.uptimeMillis() < deadline) {
            scenario.onActivity(activity -> {
                try { Field field = StudioActivity.class.getDeclaredField("actionBusy"); field.setAccessible(true); busy.set(field.getBoolean(activity)); }
                catch (ReflectiveOperationException error) { throw new AssertionError(error); }
            });
            if (busy.get()) SystemClock.sleep(25);
        }
        assertFalse("Studio storage action timed out", busy.get());
    }
    private static void awaitCard(ActivityScenario<StudioActivity> scenario, String tag) {
        AtomicBoolean found = new AtomicBoolean();
        long deadline = SystemClock.uptimeMillis() + 15000;
        while (!found.get() && SystemClock.uptimeMillis() < deadline) {
            scenario.onActivity(activity -> found.set(activity.findViewById(R.id.library_list).findViewWithTag(tag) != null));
            if (!found.get()) SystemClock.sleep(25);
        }
        assertTrue("Imported pack card missing", found.get());
    }
    private SharedPreferences mainPrefs() { return context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME, Context.MODE_PRIVATE); }
    private SharedPreferences walletPrefs() { return context.getSharedPreferences(PenanceManager.PREFS_NAME, Context.MODE_PRIVATE); }
    private static void restore(SharedPreferences preferences, Map<String, ?> values) {
        SharedPreferences.Editor edit = preferences.edit().clear();
        for (Map.Entry<String, ?> item : values.entrySet()) {
            Object value = item.getValue(); String key = item.getKey();
            if (value instanceof Boolean) edit.putBoolean(key, (Boolean) value);
            else if (value instanceof Integer) edit.putInt(key, (Integer) value);
            else if (value instanceof Long) edit.putLong(key, (Long) value);
            else if (value instanceof Float) edit.putFloat(key, (Float) value);
            else if (value instanceof String) edit.putString(key, (String) value);
            else if (value instanceof Set<?>) edit.putStringSet(key, new java.util.LinkedHashSet<>((Set<String>) value));
        }
        assertTrue(edit.commit());
    }
}
