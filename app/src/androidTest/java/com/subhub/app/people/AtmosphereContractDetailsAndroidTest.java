package com.subhub.app.people;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.matcher.ViewMatchers.withTagValue;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.*;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.text.Spanned;
import android.text.style.StyleSpan;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.NativeUiActions;
import com.subhub.app.R;
import com.subhub.app.UiAutomationStateRule;
import com.subhub.app.configuration.ConfigurationSchema;
import com.subhub.app.onboarding.OnboardingState;
import com.subhub.app.popup.PopupStormSettings;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.security.DeviceRolePreferences;
import com.subhub.app.security.KeyholderPresentationAndroidTest;
import com.subhub.app.security.SubFeaturePolicy;
import com.subhub.app.settings.FeatureModuleManager;
import com.subhub.app.settings.SettingsRepository;
import com.subhub.app.settings.SharedPreferenceTestRestore;
import com.subhub.app.subliminal.SubliminalSettingsRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public final class AtmosphereContractDetailsAndroidTest {
    @Rule public final KeyholderPresentationAndroidTest.IntegrationFixture integration =
            new KeyholderPresentationAndroidTest.IntegrationFixture();
    @Rule public final UiAutomationStateRule automation = new UiAutomationStateRule();
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private Map<String, ?> originalConsent;
    private Map<String, ?> originalGag;
    private SharedPreferences preferences;

    @Before public void prepare() {
        originalConsent = new LinkedHashMap<>(context.getSharedPreferences(SubFeaturePolicy.STORE, 0).getAll());
        SharedPreferences gag = context.getSharedPreferences("atmosphere_gag", 0);
        originalGag = new LinkedHashMap<>(gag.getAll());
        assertTrue(gag.edit().putBoolean("enabled", true).commit());
        assertTrue(context.getSharedPreferences(SubFeaturePolicy.STORE, 0).edit()
                .putBoolean("subliminal", true).putBoolean("popup", true).commit());
        preferences = context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME, 0);
        assertTrue(preferences.edit()
                .putBoolean(FeatureModuleManager.KEY_SUBLIMINAL_ENABLED, true)
                .putStringSet(SubliminalSettingsRepository.KEY_PACKS, Set.of("obedience", "focus", "custom"))
                .putString(SubliminalSettingsRepository.KEY_PRESET, "strict")
                .putBoolean(SubliminalSettingsRepository.KEY_ADVANCED, false)
                .putString(SubliminalSettingsRepository.KEY_CUSTOM, "Private fixture phrase never shown")
                .putBoolean(PopupStormSettings.K_ENABLED, true)
                .putString(PopupStormSettings.K_POSITION_MODE, "center")
                .putString(PopupStormSettings.K_DETECTION_MODE, "avoid")
                .putBoolean(PopupStormSettings.K_BURST_ENABLED, true)
                .putBoolean(PopupStormSettings.K_BOUNCING, true)
                .putBoolean(PopupStormSettings.K_RANDOM_ROT, false)
                .putInt(PopupStormSettings.K_DENIAL_CHANCE, 40)
                .putString(PopupStormSettings.K_DENIAL_STYLE, "blur").commit());
    }

    @After public void restore() {
        SharedPreferenceTestRestore.restore(context.getSharedPreferences(SubFeaturePolicy.STORE, 0), originalConsent);
        SharedPreferenceTestRestore.restore(context.getSharedPreferences("atmosphere_gag", 0), originalGag);
    }

    @Test public void localSummaryListsSelectedCategoriesAndQualitativePopupDetails() {
        String details = AtmosphereContractDetails.local(context).details;
        assertEquals(expected(), details);
        assertEquals(details, new LocalContractDetails(context).forSection("atmosphere"));
        assertFalse(details.matches("(?s).*\\d.*"));
        assertFalse(details.contains("Private fixture"));
        assertFalse(details.contains("spawn"));
        assertTrue(AtmosphereContractDetails.local(context).summary.endsWith("\nGag Unknown"));
    }

    @Test public void disabledEmptyAndCustomSettingsRemainExplicitWithoutInventedSelections() {
        assertTrue(preferences.edit().putBoolean(FeatureModuleManager.KEY_SUBLIMINAL_ENABLED, false)
                .putStringSet(SubliminalSettingsRepository.KEY_PACKS, Set.of())
                .putBoolean(SubliminalSettingsRepository.KEY_ADVANCED, true)
                .putBoolean(PopupStormSettings.K_ENABLED, false).commit());
        String details = AtmosphereContractDetails.local(context).details;
        assertTrue(details.startsWith("Whispers\nOff\nCategories: None Selected\nIntensity: Custom"));
        assertTrue(details.contains("Popup Storm\nOff"));
        assertFalse(details.contains("Strict"));
        assertTrue(context.getSharedPreferences(SubFeaturePolicy.STORE, 0).edit()
                .putBoolean("subliminal", false).putBoolean("popup", false).commit());
        assertTrue(AtmosphereContractDetails.local(context).summary.contains("Whispers Disabled by Sub"));
        assertTrue(AtmosphereContractDetails.local(context).summary.contains("Popup Storm Disabled by Sub"));
    }

    @Test public void remoteSummaryMatchesLocalButReadsOnlyItsSuppliedSnapshot() throws Exception {
        JSONObject sections = sections();
        assertTrue(preferences.edit().putStringSet(SubliminalSettingsRepository.KEY_PACKS, Set.of("beta"))
                .putString(PopupStormSettings.K_POSITION_MODE, "random").commit());
        assertEquals(expectedAtmosphere() + "\n\nGag\nUnknown", AtmosphereContractDetails.remote(context, sections,
                Map.of("subliminal", true, "popup", true), PersonSnapshot.Freshness.CURRENT).details);
        sections.put("gag", com.subhub.app.remote.GagContractFields.defaults().put("enabled", true));
        assertTrue(AtmosphereContractDetails.remote(context, sections, Map.of(), PersonSnapshot.Freshness.CURRENT)
                .summary.endsWith("\nGag Configured on"));
        sections.getJSONObject("subliminal").put(SubliminalSettingsRepository.KEY_PACKS, new JSONArray(List.of("findom")));
        assertTrue(AtmosphereContractDetails.remote(context, sections, Map.of(), PersonSnapshot.Freshness.UNKNOWN)
                .details.contains("Categories: Findom"));
    }

    @Test public void remoteConsentIsNeverInferredFromMissingOrStaleObservations() throws Exception {
        JSONObject sections = sections();
        AtmosphereContractDetails blocked = AtmosphereContractDetails.remote(context, sections,
                Map.of("subliminal", false, "popup", true), PersonSnapshot.Freshness.CURRENT);
        assertEquals("Whispers Disabled by Sub\nPopup Storm On\nGag Unknown", blocked.summary);
        for (PersonSnapshot.Freshness freshness : PersonSnapshot.Freshness.values()) {
            AtmosphereContractDetails missing = AtmosphereContractDetails.remote(context, sections, Map.of(), freshness);
            assertEquals("Whispers Configured on\nPopup Storm Configured on\nGag Unknown", missing.summary);
            assertTrue(missing.details.contains("Consent: Unknown"));
        }
        AtmosphereContractDetails stale = AtmosphereContractDetails.remote(context, sections,
                Map.of("subliminal", false, "popup", true), PersonSnapshot.Freshness.STALE);
        assertEquals("Whispers Configured on\nPopup Storm Configured on\nGag Unknown", stale.summary);
        assertTrue(stale.details.contains("Consent: Last observed disabled by Sub"));
        assertTrue(stale.details.contains("Consent: Last observed allowed"));
    }

    @Test public void subContractExpandsWithExistingBoldStyleAndRestoresAfterRecreation() throws Exception {
        readyUi(false);
        try (ActivityScenario<ContractActivity> page = ActivityScenario.launch(ContractActivity.class)) {
            await(page, a -> tagged(a, "contract_details:atmosphere") != null);
            onView(withTagValue(is("contract_section:atmosphere"))).perform(NativeUiActions.revealAboveNavigation(), click());
            await(page, a -> detailsReady(tagged(a, "contract_details:atmosphere")));
            page.onActivity(a -> assertDetails(tagged(a, "contract_details:atmosphere")));
            page.recreate();
            await(page, a -> detailsReady(tagged(a, "contract_details:atmosphere")));
            page.onActivity(a -> assertDetails(tagged(a, "contract_details:atmosphere")));
            onView(withTagValue(is("contract_section:atmosphere"))).perform(NativeUiActions.revealAboveNavigation(), click());
            page.onActivity(a -> assertEquals(View.GONE, tagged(a, "contract_details:atmosphere").getVisibility()));
        }
    }

    @Test public void domContractShowsDetailsWhileKeepingItsExistingEditRoute() throws Exception {
        readyUi(true);
        Intent intent = new Intent(context, PersonActivity.class).putExtra(PersonRoutes.EXTRA_PERSON_ID, PersonId.LOCAL.value())
                .putExtra("person_section", "contracts");
        try (ActivityScenario<PersonActivity> page = ActivityScenario.launch(intent)) {
            await(page, a -> tagged(a, "person_section:atmosphere") != null);
            page.onActivity(a -> {
                View header = tagged(a, "person_section:atmosphere");
                assertTrue(header.isClickable());
                assertTrue(text((View) header.getParent()).contains(expected()));
            });
            page.recreate();
            await(page, a -> tagged(a, "person_section:atmosphere") != null);
            page.onActivity(a -> assertTrue(text(tagged(a, "person_contract_features")).contains(expected())));
        }
    }

    @Test public void unknownContractHidesDetailsWhileStaleContractKeepsLabeledSavedValues() throws Exception {
        for (boolean dom : new boolean[]{false, true}) {
            for (PersonSnapshot.Freshness freshness : List.of(PersonSnapshot.Freshness.STALE, PersonSnapshot.Freshness.UNKNOWN)) {
                readyUi(dom);
                People.installLocalProjection(p -> new PersonSnapshot(p.id, p.name, p.device, p.contractName,
                        p.serviceLabel, p.connection, p.freshness, p.lastSyncedAt, p.capabilities, p.sections,
                        p.allowances, p.currency, p.protectedSeconds, p.activityEvents, p.tributeCents, p.sessions,
                        p.serviceActive, new PersonSnapshot.SyncState(freshness, p.sync.stats, p.sync.runtime, "", ""))
                        .withContractDom(ProfileData.empty("Dom"), null));
                Intent intent = new Intent(context, dom ? PersonActivity.class : ContractActivity.class)
                        .putExtra(PersonRoutes.EXTRA_PERSON_ID, PersonId.LOCAL.value()).putExtra("person_section", "contracts");
                try (ActivityScenario<android.app.Activity> page = ActivityScenario.launch(intent)) {
                    String headerTag = dom ? "person_section:atmosphere" : "contract_section:atmosphere";
                    await(page, a -> tagged(a, headerTag) != null);
                    page.onActivity(a -> {
                        String copy = text(tagged(a, "person_contract_features"));
                        if (freshness == PersonSnapshot.Freshness.UNKNOWN) {
                            assertFalse(copy.contains("Categories:"));
                            assertFalse(copy.contains("Placement:"));
                            assertFalse(tagged(a, headerTag).isClickable());
                        } else {
                            assertTrue(copy.contains(expected()));
                            assertTrue(text(tagged(a, "person_contract_scope")).contains(context.getString(R.string.role_stale_data)));
                        }
                    });
                }
            }
        }
    }

    private JSONObject sections() throws Exception {
        JSONObject result = new JSONObject();
        for (String section : List.of("modules", "subliminal", "popup"))
            result.put(section, ConfigurationSchema.captureSection(context, section));
        return result;
    }

    private String expected() {
        return expectedAtmosphere() + "\n\nGag\nUnknown";
    }

    private String expectedAtmosphere() {
        return "Whispers\nOn\nCategories: Custom, Focus, Obedience\nIntensity: Strict\n\n"
                + "Popup Storm\nOn\nPlacement: Center · Avoid detections\nEffects: Bursts, Bouncing, Tease (Blur)";
    }

    private void readyUi(boolean dom) {
        assertTrue(ControllerPinManager.setPin(context, "2468"));
        assertTrue(ControllerPinManager.verify(context, "2468"));
        assertTrue(DeviceRolePreferences.set(context, DeviceRolePreferences.Mode.BOTH));
        OnboardingState.complete(context);
        if (dom) ControllerPinManager.enterDomMode(); else ControllerPinManager.enterSubMode();
        // Contract identity remains an unpaired presentation; no synthetic transport or session action.
        People.installLocalProjection(p -> p.withContractDom(ProfileData.empty("Dom"), null));
    }

    private static boolean detailsReady(View value) {
        if (!(value instanceof ViewGroup) || !value.isShown()) return false;
        ViewGroup group = (ViewGroup) value;
        if (group.getChildCount() != 3) return false;
        for (int index = 0; index < group.getChildCount(); index++) {
            View child = group.getChildAt(index);
            if (!(child instanceof TextView) || ((TextView) child).getLayout() == null
                    || child.getWidth() <= 0 || child.isLayoutRequested()) return false;
        }
        return true;
    }

    private void assertDetails(View value) {
        ViewGroup group = (ViewGroup) value;
        assertEquals(3, group.getChildCount());
        assertEquals(expected(), text(group));
        for (int index = 0; index < group.getChildCount(); index++) {
            TextView row = (TextView) group.getChildAt(index);
            assertTrue(row.getText() instanceof Spanned);
            Spanned copy = (Spanned) row.getText();
            StyleSpan[] spans = copy.getSpans(0, copy.length(), StyleSpan.class);
            assertEquals(1, spans.length);
            assertEquals(0, copy.getSpanStart(spans[0]));
            assertEquals(copy.toString().indexOf('\n'), copy.getSpanEnd(spans[0]));
            assertFalse(row.isClickable());
            assertNotNull(row.getLayout());
            for (int line = 0; line < row.getLineCount(); line++) {
                assertEquals(0, row.getLayout().getEllipsisCount(line));
                assertTrue(row.getLayout().getLineMax(line) <= row.getWidth() - row.getCompoundPaddingLeft()
                        - row.getCompoundPaddingRight() + 1);
            }
        }
    }

    private static View tagged(android.app.Activity activity, String tag) {
        return activity.findViewById(android.R.id.content).findViewWithTag(tag);
    }
    private static String text(View value) {
        if (value instanceof TextView) return ((TextView) value).getText().toString();
        if (!(value instanceof ViewGroup)) return "";
        java.util.ArrayList<String> copy = new java.util.ArrayList<>();
        ViewGroup group = (ViewGroup) value;
        for (int index = 0; index < group.getChildCount(); index++) {
            String child = text(group.getChildAt(index));
            if (!child.isEmpty()) copy.add(child);
        }
        return String.join("\n\n", copy);
    }
    private static <T extends android.app.Activity> void await(ActivityScenario<T> page, Predicate<T> ready) {
        long until = SystemClock.elapsedRealtime() + 5000;
        AtomicBoolean value = new AtomicBoolean();
        do {
            page.onActivity(a -> value.set(ready.test(a)));
            if (value.get()) return;
            SystemClock.sleep(25);
        } while (SystemClock.elapsedRealtime() < until);
        fail("Contract content was not ready");
    }
}
