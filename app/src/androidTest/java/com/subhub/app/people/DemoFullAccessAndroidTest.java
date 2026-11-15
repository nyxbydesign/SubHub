package com.subhub.app.people;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static androidx.test.espresso.matcher.ViewMatchers.withTagValue;
import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.*;

import android.Manifest;
import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Build;
import android.os.Environment;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ListView;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.filters.SdkSuppress;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;
import com.subhub.app.OwnedUiIntegrationFixture;
import com.subhub.app.R;
import com.subhub.app.capture.MediaProjectionLeaseRegistry;
import com.subhub.app.fullaccess.DemoFullAccessActivity;
import com.subhub.app.fullaccess.FullAccessDataPresentationAndroidTest;
import com.subhub.app.onboarding.OnboardingState;
import com.subhub.app.remote.DummyPreviewEndpointFixture;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.security.DeviceRolePreferences;
import com.subhub.app.settings.SettingsRepository;
import com.subhub.app.settings.SharedPreferenceTestRestore;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.junit.Rule;
import org.junit.Test;

/** Exercises the Demo route and real Android consent on an owned synthetic guest. */
public final class DemoFullAccessAndroidTest {
    @Rule public final OwnedUiIntegrationFixture integration = new OwnedUiIntegrationFixture(false);
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final Context context = instrumentation.getTargetContext();

    @Test public void demoUsesDedicatedPhoneDataPagesAndInvalidatesOnLock() throws Exception {
        UiDevice device = UiDevice.getInstance(instrumentation);
        withViewer(viewer -> {
            assertLanding(viewer);
            if ("true".equals(InstrumentationRegistry.getArguments().getString("blackmail_preview")))
                renderSyntheticLanding(viewer);
            List<ApplicationInfo> installed = new ArrayList<>(context.getPackageManager().getInstalledApplications(0));
            installed.removeIf(app -> (app.flags & (ApplicationInfo.FLAG_SYSTEM
                    | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0);
            installed.sort(Comparator.comparing(app -> app.packageName));
            assertFalse(installed.isEmpty());
            String realApp = installed.get(0).loadLabel(context.getPackageManager()).toString();
            assertFalse(onMain(() -> contains(viewer.findViewById(android.R.id.content), realApp)));

            tapEntry("apps");
            await(() -> onMain(() -> {
                View apps = visible(viewer, "blackmail_page:apps");
                return apps != null && contains(apps, realApp);
            }));
            assertFalse(onMain(() -> visible(viewer, "blackmail_entry:contacts") != null));
            assertTrue(device.pressBack());
            assertLanding(viewer);
            assertFalse(onMain(() -> contains(viewer.findViewById(android.R.id.content), realApp)));

            tapEntry("files");
            await(() -> onMain(() -> visible(viewer, "blackmail_page:files") != null));
            if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
                String status = context.getString(R.string.blackmail_demo_files_permission_needed);
                await(() -> onMain(() -> {
                    View files = visible(viewer, "blackmail_page:files");
                    return files != null && contains(files, status);
                }));
            }
            assertFalse(onMain(() -> contains(viewer.findViewById(android.R.id.content),
                    context.getString(R.string.full_access_browse_files))));
            assertTrue(device.pressBack());
            assertLanding(viewer);
        });
    }

    @Test @SdkSuppress(minSdkVersion = 35, maxSdkVersion = 35)
    public void demoScreenEntryShowsAndroidConsentWithoutAnotherTap() throws Exception {
        assertEquals("Run only on the owned guest", "true",
                InstrumentationRegistry.getArguments().getString("blackmail_owned_guest"));
        assertEquals(PackageManager.PERMISSION_GRANTED,
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS));
        UiDevice device = UiDevice.getInstance(instrumentation);
        withViewer(viewer -> {
            assertLanding(viewer);
            assertNull(MediaProjectionLeaseRegistry.owner());
            tapEntry("screen");
            UiObject2 cancel = device.wait(Until.findObject(By.pkg("com.android.systemui").text("Cancel")), 10_000);
            assertNotNull("A single Screen tap opens Android's MediaProjection consent dialog", cancel);
            assertNull(MediaProjectionLeaseRegistry.owner());
            cancel.click();
            await(() -> onMain(() -> viewer.hasWindowFocus() && visible(viewer, "blackmail_page:screen") != null));
            assertNull(MediaProjectionLeaseRegistry.owner());
            instrumentation.runOnMainSync(viewer::recreate);
            await(() -> onMain(viewer::isDestroyed));
            DemoFullAccessActivity restored = awaitResumed(DemoFullAccessActivity.class);
            await(() -> onMain(() -> restored.hasWindowFocus() && visible(restored, "blackmail_page:screen") != null));
            assertNull(device.findObject(By.pkg("com.android.systemui").text("Cancel")));
            assertTrue(device.pressBack());
            assertLanding(restored);
            assertNull(MediaProjectionLeaseRegistry.owner());
        });
    }

    @Test public void demoSmsThreadLoadsOlderMessagesAutomatically() throws Exception {
        assertEquals("true", InstrumentationRegistry.getArguments().getString("blackmail_owned_guest"));
        assertEquals(PackageManager.PERMISSION_DENIED, context.checkSelfPermission(Manifest.permission.READ_SMS));
        UiDevice device = UiDevice.getInstance(instrumentation);
        withViewer(viewer -> {
            String[] messages = new String[40];
            for (int index = 0; index < messages.length; index++)
                messages[index] = "Synthetic transcript message " + index;
            String generation = InternalSubData.peek(context).acknowledged().generation;
            try (AutoCloseable fixture = FullAccessDataPresentationAndroidTest.seedDemoTranscript(
                    context, generation, messages)) {
                tapEntry("sms");
                await(() -> onMain(() -> visible(viewer, "blackmail_conversation") != null));
                onView(withTagValue(is("blackmail_conversation"))).perform(click());
                await(() -> onMain(() -> {
                    View list = visible(viewer, "blackmail_continuous:thread");
                    return list != null && contains(list, messages[messages.length - 1]);
                }));
                AtomicReference<ListView> mounted = new AtomicReference<>();
                instrumentation.runOnMainSync(() -> mounted.set(viewer.findViewById(android.R.id.content)
                        .findViewWithTag("blackmail_continuous:thread")));
                ListView thread = mounted.get();
                for (int attempt = 0; attempt < 5 && count(thread) < messages.length; attempt++) {
                    int previous = count(thread);
                    instrumentation.runOnMainSync(() -> thread.setSelection(0));
                    await(() -> count(thread) > previous);
                }
                assertTrue(count(thread) >= messages.length);
                instrumentation.runOnMainSync(() -> thread.setSelection(0));
                await(() -> onMain(() -> contains(thread, messages[0])));
                assertTrue(onMain(() -> thread.getChildCount() < messages.length));
                assertFalse(onMain(() -> contains(viewer.findViewById(android.R.id.content),
                        context.getString(R.string.full_access_more))));
                assertTrue(device.pressBack());
                await(() -> onMain(() -> visible(viewer, "blackmail_conversation") != null));
                assertFalse(onMain(() -> visible(viewer, "blackmail_continuous:thread") != null));
            } catch (Exception failure) {
                throw new AssertionError(failure);
            }
        });
    }

    private int count(ListView list) {
        AtomicInteger count = new AtomicInteger();
        instrumentation.runOnMainSync(() -> count.set(list.getCount()));
        return count.get();
    }

    private void withViewer(Consumer<DemoFullAccessActivity> exercise) throws Exception {
        instrumentation.getUiAutomation();
        String choices = "demo_full_access_choices";
        String[] stores = {choices, SettingsRepository.PREFERENCES_NAME, "subhub_controller_auth",
                "subhub_onboarding", "subhub_home"};
        Map<String, Map<String, ?>> previous = new LinkedHashMap<>();
        for (String name : stores) previous.put(name, new LinkedHashMap<>(context.getSharedPreferences(name, 0).getAll()));
        boolean wasDom = ControllerPinManager.isDomModeActive();
        try {
            assertTrue(ControllerPinManager.setPin(context, "2468"));
            assertTrue(ControllerPinManager.verify(context, "2468"));
            assertTrue(DeviceRolePreferences.set(context, DeviceRolePreferences.Mode.BOTH));
            OnboardingState.complete(context);
            try (InternalSubUiFixture demo = new InternalSubUiFixture(context);
                 DummyPreviewEndpointFixture endpoint = new DummyPreviewEndpointFixture(context)) {
                assertTrue(DummySubPreview.toggle(context));
                String generation = demo.data.acknowledged().generation;
                assertTrue(context.getSharedPreferences(choices, 0).edit().clear()
                        .putString("enabled_generation", generation).commit());
                Map<String, List<String>> relationshipBefore = endpoint.snapshot();
                try (ActivityScenario<PeopleActivity> scenario = ActivityScenario.launch(PeopleActivity.class)) {
                    scenario.onActivity(DummySubPreview::open);
                    PersonActivity profile = awaitResumed(PersonActivity.class);
                    instrumentation.waitForIdleSync();
                    await(() -> onMain(() -> profile.findViewById(android.R.id.content)
                            .findViewWithTag("person_tab:full_access") != null));
                    instrumentation.runOnMainSync(() -> {
                        View row = profile.findViewById(android.R.id.content).findViewWithTag("person_tab:full_access");
                        assertNotNull(row);
                        assertTrue(row.performClick());
                    });
                    DemoFullAccessActivity viewer = awaitResumed(DemoFullAccessActivity.class);
                    exercise.accept(viewer);
                    assertEquals("Viewing phone data must not write to the peer endpoint", relationshipBefore, endpoint.snapshot());
                    // The explicit workspace change itself updates linking authority.
                    DemoFullAccessActivity current = awaitResumed(DemoFullAccessActivity.class);
                    instrumentation.runOnMainSync(ControllerPinManager::enterSubMode);
                    await(() -> onMain(current::isFinishing));
                    instrumentation.runOnMainSync(profile::finish);
                }
                integration.drainReaders();
            }
        } finally {
            for (String name : stores)
                SharedPreferenceTestRestore.restore(context.getSharedPreferences(name, 0), previous.get(name));
            if (wasDom) ControllerPinManager.enterDomMode(); else ControllerPinManager.enterSubMode();
        }
    }

    private void assertLanding(Activity activity) {
        await(() -> onMain(() -> {
            View first = visible(activity, "blackmail_entry:contacts");
            return first != null && first.getWidth() > 0;
        }));
        instrumentation.runOnMainSync(() -> {
            assertEquals(0, activity.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE);
            List<String> scopes = List.of("contacts", "sms", "notifications", "apps", "files", "screen");
            int previousTop = -1;
            for (int index = 0; index < scopes.size(); index += 2) {
                String first = scopes.get(index), second = scopes.get(index + 1);
                View left = visible(activity, "blackmail_entry:" + first);
                View right = visible(activity, "blackmail_entry:" + second);
                assertNotNull(first, left);
                assertNotNull(second, right);
                assertSame(left.getParent(), right.getParent());
                assertTrue(Math.abs(left.getWidth() - right.getWidth()) <= 1);
                assertEquals(left.getHeight(), right.getHeight());
                assertNotEquals(left.getLeft(), right.getLeft());
                assertCenteredIcon(left);
                assertCenteredIcon(right);
                View row = (View) left.getParent();
                assertTrue(row.getTop() > previousTop);
                previousTop = row.getTop();
                assertNull(first, visible(activity, "blackmail_page:" + first));
                assertNull(second, visible(activity, "blackmail_page:" + second));
            }
            assertNull(MediaProjectionLeaseRegistry.owner());
        });
    }

    private static void assertCenteredIcon(View tile) {
        View glyph = tile.findViewWithTag("blackmail_entry_icon");
        assertNotNull(glyph);
        View badge = (View) glyph.getParent();
        assertTrue(Math.abs(glyph.getLeft() + glyph.getRight() - badge.getWidth()) <= 1);
        assertTrue(Math.abs(glyph.getTop() + glyph.getBottom() - badge.getHeight()) <= 1);
    }

    private static void tapEntry(String scope) {
        onView(withTagValue(is("blackmail_entry:" + scope))).perform(scrollTo(), click());
    }

    private void renderSyntheticLanding(Activity activity) {
        AtomicReference<Bitmap> image = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> {
            View page = activity.findViewById(R.id.role_page_scroll);
            Bitmap bitmap = Bitmap.createBitmap(page.getWidth(), page.getHeight(), Bitmap.Config.ARGB_8888);
            page.draw(new Canvas(bitmap));
            image.set(bitmap);
        });
        Bitmap bitmap = image.get();
        File output = new File(context.getExternalCacheDir(), "blackmail-landing-synthetic.png");
        try (FileOutputStream stream = new FileOutputStream(output)) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream));
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        } finally {
            bitmap.recycle();
        }
    }

    private static View visible(Activity activity, String tag) {
        View found = activity.findViewById(android.R.id.content).findViewWithTag(tag);
        return found != null && found.isShown() ? found : null;
    }

    private <T extends Activity> T awaitResumed(Class<T> kind) {
        AtomicReference<T> found = new AtomicReference<>();
        await(() -> onMain(() -> {
            for (Activity candidate : ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)) {
                if (kind.isInstance(candidate)) {
                    found.set(kind.cast(candidate));
                    return true;
                }
            }
            return false;
        }));
        return found.get();
    }

    private boolean onMain(BooleanSupplier condition) {
        java.util.concurrent.atomic.AtomicBoolean result = new java.util.concurrent.atomic.AtomicBoolean();
        instrumentation.runOnMainSync(() -> result.set(condition.getAsBoolean()));
        return result.get();
    }

    private static boolean contains(View view, String text) {
        if (view instanceof TextView label && text.contentEquals(label.getText())) return true;
        if (view instanceof ViewGroup group) for (int index = 0; index < group.getChildCount(); index++)
            if (contains(group.getChildAt(index), text)) return true;
        return false;
    }

    private static void await(BooleanSupplier condition) {
        long deadline = SystemClock.elapsedRealtime() + 10_000;
        do {
            if (condition.getAsBoolean()) return;
            SystemClock.sleep(40);
        } while (SystemClock.elapsedRealtime() < deadline);
        fail("Demo Full Access state did not appear");
    }
}
