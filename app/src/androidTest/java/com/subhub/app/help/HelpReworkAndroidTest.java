package com.subhub.app.help;

import static androidx.test.espresso.intent.Intents.intended;
import static androidx.test.espresso.intent.Intents.intending;
import static androidx.test.espresso.intent.matcher.IntentMatchers.hasComponent;
import static androidx.test.espresso.intent.matcher.IntentMatchers.hasExtra;
import static org.hamcrest.Matchers.allOf;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.Instrumentation;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.espresso.intent.Intents;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.subhub.app.BuildConfig;
import com.subhub.app.R;
import com.subhub.app.diagnostics.DiagnosticsActivity;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.update.UpdatesActivity;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Actual Help interaction and routing contracts; no live recording or permission mutation. */
@RunWith(AndroidJUnit4.class)
public final class HelpReworkAndroidTest {
    @Before public void setUp() {
        ControllerPinManager.enterDomMode();
        Intents.init();
    }

    @After public void tearDown() {
        Intents.release();
        ControllerPinManager.enterSubMode();
    }

    @Test public void searchFindsAnswerTextAndRestoresTheOpenQuestion() {
        try (ActivityScenario<HelpActivity> scenario = ActivityScenario.launch(HelpActivity.class)) {
            scenario.onActivity(activity -> {
                LinearLayout apps = question(activity, "apps");
                apps.getChildAt(0).performClick();
                assertEquals(View.VISIBLE, apps.getChildAt(1).getVisibility());
                ((EditText) activity.findViewById(R.id.help_search)).setText("ALL APPS");
                assertEquals(View.VISIBLE, apps.getVisibility());
                assertEquals(View.GONE, question(activity, "limits").getVisibility());
            });
            scenario.recreate();
            scenario.onActivity(activity -> {
                assertEquals("ALL APPS", ((EditText) activity.findViewById(R.id.help_search))
                        .getText().toString());
                assertEquals(View.VISIBLE, question(activity, "apps").getChildAt(1).getVisibility());
                EditText search = activity.findViewById(R.id.help_search);
                search.setText("unmatched-question-1234");
                assertEquals(View.VISIBLE, activity.findViewById(R.id.help_no_results).getVisibility());
                LinearLayout questions = activity.findViewById(R.id.help_sections);
                for (int index = 0; index < questions.getChildCount(); index++)
                    assertEquals(View.GONE, questions.getChildAt(index).getVisibility());
                search.setText("");
                assertEquals(View.GONE, activity.findViewById(R.id.help_no_results).getVisibility());
                for (int index = 0; index < questions.getChildCount(); index++)
                    assertEquals(View.VISIBLE, questions.getChildAt(index).getVisibility());
            });
        }
    }

    @Test public void answersUseOneExpansionAndShowCurrentControlSemantics() {
        try (ActivityScenario<HelpActivity> scenario = ActivityScenario.launch(HelpActivity.class)) {
            scenario.onActivity(activity -> {
                LinearLayout censor = question(activity, "censor");
                LinearLayout limits = question(activity, "limits");
                censor.getChildAt(0).performClick();
                assertEquals(View.VISIBLE, censor.getChildAt(1).getVisibility());
                limits.getChildAt(0).performClick();
                assertEquals(View.GONE, censor.getChildAt(1).getVisibility());
                assertEquals(View.VISIBLE, limits.getChildAt(1).getVisibility());
                limits.getChildAt(0).performClick();
                assertEquals(View.GONE, limits.getChildAt(1).getVisibility());
                assertTrue(((TextView) question(activity, "wallet").getChildAt(1)).getText()
                        .toString().contains("no separate enable switch"));
                assertTrue(((TextView) censor.getChildAt(1)).getText().toString().contains("Choose Off"));
                assertEquals(activity.getString(R.string.help_rework_build,
                                BuildConfig.VERSION_NAME, BuildConfig.BUILD_DATE),
                        ((TextView) activity.findViewById(R.id.help_build)).getText().toString());
                assertTrue(((TextView) activity.findViewById(R.id.permission_status)).getText().length() > 0);
            });
        }
    }

    @Test public void subCanOpenReadOnlyToolsWithoutGainingDomAuthorization() {
        ControllerPinManager.enterSubMode();
        intending(hasComponent(DiagnosticsActivity.class.getName())).respondWith(
                new Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null));
        intending(hasComponent(PermissionSetupActivity.class.getName())).respondWith(
                new Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null));
        intending(hasComponent(UpdatesActivity.class.getName())).respondWith(
                new Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null));
        try (ActivityScenario<HelpActivity> scenario = ActivityScenario.launch(HelpActivity.class)) {
            scenario.onActivity(activity -> {
                activity.findViewById(R.id.help_diagnostics).performClick();
                activity.findViewById(R.id.help_censor_lab).performClick();
                activity.findViewById(R.id.button_accessibility).performClick();
                activity.findViewById(R.id.button_updates).performClick();
            });
            intended(allOf(hasComponent(DiagnosticsActivity.class.getName()),
                    hasExtra(DiagnosticsActivity.EXTRA_SHOW_CENSOR_LAB, false)));
            intended(allOf(hasComponent(DiagnosticsActivity.class.getName()),
                    hasExtra(DiagnosticsActivity.EXTRA_SHOW_CENSOR_LAB, true)));
            intended(hasComponent(PermissionSetupActivity.class.getName()));
            intended(hasComponent(UpdatesActivity.class.getName()));
            assertFalse(ControllerPinManager.isDomModeActive());
            scenario.recreate();
            scenario.onActivity(activity -> {
                assertTrue(activity.findViewById(R.id.button_language).isShown());
                assertEquals(.45f, activity.findViewById(R.id.button_language).getAlpha(), .01f);
                assertTrue(activity.findViewById(R.id.button_fix_permissions).isShown());
                assertNotNull(activity.findViewById(R.id.help_diagnostics));
                assertNotNull(activity.findViewById(R.id.help_censor_lab));
                assertTrue(activity.findViewById(R.id.button_accessibility).isShown());
            });
        }
    }

    private static LinearLayout question(HelpActivity activity, String key) {
        return activity.findViewById(R.id.help_sections)
                .findViewWithTag("help_question:help_rework_" + key + "_title");
    }
}
