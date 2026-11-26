package com.subhub.app.people;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withTagValue;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.*;

import android.Manifest;
import android.app.Activity;
import android.app.Instrumentation;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Build;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
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
import com.subhub.app.NativeUiActions;
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
import java.util.function.Predicate;
import java.util.regex.Pattern;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.RuleChain;

/** Exercises the Demo route and real Android consent on an owned synthetic guest. */
public final class DemoFullAccessAndroidTest {
    private final OwnedUiIntegrationFixture integration = new OwnedUiIntegrationFixture(false);
    @Rule public final RuleChain fixtures = RuleChain
            .outerRule(new com.subhub.app.UiAutomationStateRule())
            .around(integration);
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

    @Test @SdkSuppress(minSdkVersion = 35, maxSdkVersion = 35)
    public void demoScreenApprovalSurvivesViewerRecreation() throws Exception {
        assertOwnedGuest();
        assertEquals(PackageManager.PERMISSION_GRANTED,
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS));
        UiDevice device = UiDevice.getInstance(instrumentation);
        String systemUi = "com.android.systemui";
        withViewer(viewer -> {
            assertNull(MediaProjectionLeaseRegistry.owner());
            tapEntry("screen");
            assertNotNull(device.wait(Until.findObject(By.pkg(systemUi).text("Cancel")), 10_000));
            instrumentation.runOnMainSync(viewer::recreate);
            await(() -> onMain(viewer::isDestroyed));
            UiObject2 spinner = device.findObject(By.res(systemUi, "screen_share_mode_spinner"));
            if (spinner == null) spinner = device.findObject(By.res(systemUi, "screen_share_mode_options"));
            assertNotNull("Android consent stays open across recreation", spinner);
            spinner.click();
            UiObject2 entire = device.wait(Until.findObject(By.pkg(systemUi)
                    .text(Pattern.compile("(?i)(?:share )?entire screen"))), 8_000);
            assertNotNull(entire);
            entire.click();
            UiObject2 approve = device.wait(Until.findObject(By.pkg(systemUi)
                    .text(Pattern.compile("Start|Start now|Start recording|Start sharing|Share"))), 8_000);
            assertNotNull(approve);
            approve.click();
            DemoFullAccessActivity restored = awaitResumed(DemoFullAccessActivity.class);
            assertNotSame(viewer, restored);
            await(() -> MediaProjectionLeaseRegistry.owner() == MediaProjectionLeaseRegistry.Owner.FULL_ACCESS);
            assertTrue(device.pressBack());
            assertLanding(restored);
            await(() -> MediaProjectionLeaseRegistry.owner() == null);
        });
    }

    @Test @SdkSuppress(minSdkVersion = 35, maxSdkVersion = 35)
    public void demoLateNotificationPermissionRequiresProfileReviewAndRetainsSms() throws Exception {
        assertOwnedGuest();
        var manager = context.getSystemService(NotificationManager.class);
        var listener = new ComponentName(context, "com.subhub.app.fullaccess.FullAccessNotificationListener");
        assertNotNull(manager);
        boolean wasAllowed = manager.isNotificationListenerAccessGranted(listener);
        UiDevice device = UiDevice.getInstance(instrumentation);
        try {
            setNotificationListener(listener, false);
            await(() -> !manager.isNotificationListenerAccessGranted(listener));
            withViewer(viewer -> {
                String generation = InternalSubData.peek(context).acknowledged().generation;
                SharedPreferences choices = context.getSharedPreferences("demo_full_access_choices", 0);
                try (AutoCloseable transcript = FullAccessDataPresentationAndroidTest.seedDemoTranscript(
                        context, generation, new String[]{"Synthetic consent fixture message"})) {
                    long smsSince = choices.getLong("sms_since", 0);
                    assertTrue(smsSince > 0);
                    tapEntry("notifications");
                    awaitViewerText(viewer, R.string.demo_full_access_permission_missing);
                    setNotificationListener(listener, true);
                    await(() -> manager.isNotificationListenerAccessGranted(listener));
                    assertTrue(device.pressBack());
                    assertLanding(viewer);
                    tapEntry("notifications");
                    awaitViewerText(viewer, R.string.demo_full_access_notifications_setup_required);
                    assertFalse("Android permission alone does not start collection", choices.contains("notifications_since"));

                    instrumentation.runOnMainSync(() -> viewer.startActivity(
                            DummySubPreview.childIntent(viewer, ProfileEditorActivity.class)));
                    ProfileEditorActivity profile = awaitResumed(ProfileEditorActivity.class);
                    awaitViewerText(profile, R.string.blackmail_notifications_review);
                    onView(withText(R.string.blackmail_notifications_review))
                            .perform(NativeUiActions.revealAboveNavigation(), click());
                    assertFalse("Opening the review does not start collection", choices.contains("notifications_since"));
                    long confirmedAfter = System.currentTimeMillis();
                    onView(withId(android.R.id.button1)).perform(click());
                    await(() -> choices.getLong("notifications_since", 0) >= confirmedAfter);
                    assertEquals(generation, choices.getString("notifications_generation", ""));
                    assertEquals(smsSince, choices.getLong("sms_since", 0));
                    instrumentation.runOnMainSync(profile::finish);
                    assertSame(viewer, awaitResumed(DemoFullAccessActivity.class));
                    await(() -> onMain(viewer::hasWindowFocus));
                    assertTrue(device.pressBack());
                    assertLanding(viewer);
                    tapEntry("sms");
                    await(() -> onMain(() -> visible(viewer, "blackmail_conversation") != null));
                } catch (Exception failure) {
                    throw new AssertionError(failure);
                }
            });
        } finally {
            setNotificationListener(listener, wasAllowed);
            await(() -> manager.isNotificationListenerAccessGranted(listener) == wasAllowed);
        }
    }

    @Test public void demoEnableRetriesAnUnacknowledgedPreferenceWrite() throws Exception {
        assertOwnedGuest();
        withViewer(viewer -> {
            try {
                var field = DemoFullAccessActivity.class.getDeclaredField("source");
                field.setAccessible(true);
                Object source = field.get(viewer);
                var setEnabled = source.getClass().getDeclaredMethod("setEnabled", boolean.class, Predicate.class);
                setEnabled.setAccessible(true);
                var current = source.getClass().getDeclaredMethod("current");
                current.setAccessible(true);
                Predicate<SharedPreferences.Editor> acknowledge = SharedPreferences.Editor::commit;
                assertTrue((Boolean) setEnabled.invoke(source, false, acknowledge));
                Predicate<SharedPreferences.Editor> memoryOnly = editor -> {
                    editor.apply(); // Publish values to memory while withholding a durability acknowledgement.
                    return false;
                };
                assertFalse((Boolean) setEnabled.invoke(source, true, memoryOnly));
                String generation = InternalSubData.peek(context).acknowledged().generation;
                assertEquals(generation, context.getSharedPreferences("demo_full_access_choices", 0)
                        .getString("enabled_generation", ""));
                assertFalse((Boolean) current.invoke(source));
                AtomicInteger writes = new AtomicInteger();
                Predicate<SharedPreferences.Editor> retry = editor -> {
                    writes.incrementAndGet();
                    return editor.commit();
                };
                assertTrue((Boolean) setEnabled.invoke(source, true, retry));
                assertEquals(1, writes.get());
                assertTrue((Boolean) current.invoke(source));
            } catch (ReflectiveOperationException failure) {
                throw new AssertionError(failure);
            }
        });
    }

    private void awaitViewerText(Activity activity, int id) {
        String text = context.getString(id);
        await(() -> onMain(() -> contains(activity.findViewById(android.R.id.content), text)));
    }

    private static void assertOwnedGuest() {
        assertEquals("Run only on the owned guest", "true",
                InstrumentationRegistry.getArguments().getString("blackmail_owned_guest"));
    }

    private void setNotificationListener(ComponentName listener, boolean enable) throws IOException {
        String operation = enable ? "allow_listener" : "disallow_listener";
        try (var output = new ParcelFileDescriptor.AutoCloseInputStream(instrumentation.getUiAutomation()
                .executeShellCommand("cmd notification " + operation + " " + listener.flattenToString()))) {
            byte[] buffer = new byte[1024];
            while (output.read(buffer) != -1) {
                // An independent NotificationManager read checks whether the operation took effect.
            }
        }
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
