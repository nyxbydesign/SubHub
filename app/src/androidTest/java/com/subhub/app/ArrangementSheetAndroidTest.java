package com.subhub.app;

import static org.junit.Assert.*;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.subhub.app.settings.SettingsRepository;
import java.io.File;
import java.io.FileOutputStream;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class ArrangementSheetAndroidTest {
    @org.junit.Rule public final UiAutomationStateRule automation = new UiAutomationStateRule();

    @Test public void walletBackReturnsToOverviewAndArtworkCanBeReviewed() {
        boolean originalDom = com.subhub.app.security.ControllerPinManager.isDomModeActive();
        com.subhub.app.security.ControllerPinManager.enterDomMode();
        try (ActivityScenario<com.subhub.app.penance.PenanceActivity> scenario =
                ActivityScenario.launch(com.subhub.app.penance.PenanceActivity.class)) {
            scenario.onActivity(activity -> {
                try {
                    java.lang.reflect.Method open = activity.getClass().getDeclaredMethod("openWalletEditor", String.class);
                    open.setAccessible(true);
                    java.lang.reflect.Field section = activity.getClass().getDeclaredField("expandedWalletSection");
                    section.setAccessible(true);
                    open.invoke(activity, "rules");
                    assertEquals("rules", section.get(activity));
                    activity.findViewById(R.id.button_back).performClick();
                    assertEquals("", section.get(activity));
                    activity.showSectionHome();
                    assertEquals("", section.get(activity));
                } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
                com.subhub.app.onboarding.SetupFeaturePreviewView artwork =
                        new com.subhub.app.onboarding.SetupFeaturePreviewView(activity);
                artwork.measure(View.MeasureSpec.makeMeasureSpec(984, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
                artwork.layout(0, 0, artwork.getMeasuredWidth(), artwork.getMeasuredHeight());
                save(artwork, "setup-artwork");
            });
        } finally {
            if (!originalDom) com.subhub.app.security.ControllerPinManager.enterSubMode();
        }
    }
    @Test public void contractDetailsAndSavedCensorPreviewRemainReadableAndReadOnly() throws Exception {
        android.content.Context context = ApplicationProvider.getApplicationContext();
        SettingsRepository settings = new SettingsRepository(context);
        java.util.Map<String, ?> before = settings.preferences().getAll();
        java.util.Set<String> apps = java.util.Set.of("com.android.chrome", "com.android.settings");
        for (String app : apps) context.getPackageManager().getApplicationInfo(app, 0);
        com.subhub.app.appmode.AppModeManager assigned = new com.subhub.app.appmode.AppModeManager(context);
        assigned.setAllAppsEnabled(false);
        assigned.saveIncludedPackages(apps);
        com.subhub.app.appmode.AppTimerManager timers = new com.subhub.app.appmode.AppTimerManager(context);
        timers.saveSettings(true, 30, true, 60);
        timers.saveAllowances(apps, java.util.Map.of("com.android.chrome", 15, "com.android.settings", 40));
        java.util.Map<String, ?> configured = settings.preferences().getAll();
        com.subhub.app.security.ControllerPinManager.enterSubMode();
        try (ActivityScenario<com.subhub.app.people.ContractActivity> scenario =
                ActivityScenario.launch(com.subhub.app.people.ContractActivity.class)) {
            long until = android.os.SystemClock.uptimeMillis() + 5000;
            java.util.concurrent.atomic.AtomicBoolean rendered = new java.util.concurrent.atomic.AtomicBoolean();
            while (!rendered.get() && android.os.SystemClock.uptimeMillis() < until) {
                scenario.onActivity(activity -> rendered.set(text(activity.findViewById(R.id.role_page_body))
                        .contains(activity.getString(R.string.atmosphere_title))));
                if (!rendered.get()) android.os.SystemClock.sleep(25);
            }
            assertTrue("Contract did not render", rendered.get());
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> {
                ViewGroup content = activity.findViewById(R.id.role_page_body);
                String copy = text(content);
                com.subhub.app.people.PersonSnapshot person = new com.subhub.app.people.LocalPersonRepository(activity)
                        .load(com.subhub.app.people.PersonId.LOCAL);
                for (com.subhub.app.people.PersonSnapshot.Section section : person.sections) {
                    assertTrue(copy.contains(section.title));
                    assertTrue(copy.contains(section.details));
                }
                assertEquals(apps, assigned.getIncludedPackages());
                assertEquals(15, timers.allowanceMinutes("com.android.chrome"));
                assertEquals(40, timers.allowanceMinutes("com.android.settings"));
                assertTrue(content.getWidth() > 0);
                checkText(content);
                int[] origin = new int[2];
                content.getLocationOnScreen(origin);
                assertTrue("Contract remains below the top inset", origin[1] > 0);
                save(content, "contract-details");
                com.subhub.app.settings.CensorPreviewView preview =
                        new com.subhub.app.settings.CensorPreviewView(activity, null);
                preview.setSquarePreview(true);
                preview.setAppearance(settings.loadAppearance());
                int size = View.MeasureSpec.makeMeasureSpec(content.getWidth(), View.MeasureSpec.EXACTLY);
                preview.measure(size, size);
                preview.layout(0, 0, preview.getMeasuredWidth(), preview.getMeasuredHeight());
                assertEquals(settings.loadAppearance().getType().getPreferenceValue(), preview.getTag());
                assertEquals(preview.getWidth(), preview.getHeight());
                save(preview, "saved-censor-preview");
            });
            assertEquals("Read-only contract retains saved settings", configured, settings.preferences().getAll());
        } finally {
            com.subhub.app.settings.SharedPreferenceTestRestore.restore(settings.preferences(), before);
        }
    }

    private static String text(View view) {
        String copy = view instanceof TextView ? ((TextView) view).getText().toString() : "";
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) copy += "\n" + text(group.getChildAt(index));
        }
        return copy;
    }

    private static void checkText(View view) {
        if (view instanceof TextView) {
            TextView text = (TextView) view;
            if (text.getLayout() != null) {
                assertTrue("Text height: " + text.getText(), text.getLayout().getHeight()
                        <= text.getHeight() - text.getCompoundPaddingTop() - text.getCompoundPaddingBottom());
                for (int line = 0; line < text.getLayout().getLineCount(); line++) {
                    assertTrue("Text width: " + text.getText(), text.getLayout().getLineMax(line)
                            <= text.getWidth() - text.getCompoundPaddingLeft() - text.getCompoundPaddingRight() + 1);
                }
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) checkText(group.getChildAt(i));
        }
    }

    private static void save(View view, String name) {
        Bitmap bitmap = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
        view.draw(new Canvas(bitmap));
        File directory = new File(view.getContext().getFilesDir(), "arrangement-review");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        String profile = view.getResources().getConfiguration().screenWidthDp + "-"
                + view.getResources().getConfiguration().fontScale;
        try (FileOutputStream output = new FileOutputStream(new File(directory, profile + "-" + name + ".png"))) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
        } catch (Exception failure) { throw new AssertionError(failure); }
        finally { bitmap.recycle(); }
    }
}
