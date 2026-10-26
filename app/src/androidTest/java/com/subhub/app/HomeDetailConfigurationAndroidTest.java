package com.subhub.app;

import static org.junit.Assert.*;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withTagValue;
import static com.subhub.app.NativeUiActions.revealAboveNavigation;
import static org.hamcrest.Matchers.is;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.appmode.AppModeManager;
import com.subhub.app.appmode.AppTimerManager;
import com.subhub.app.pack.*;
import com.subhub.app.people.ContractActivity;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.settings.SettingsRepository;
import com.subhub.app.settings.SharedPreferenceTestRestore;
import com.subhub.app.util.AsyncUiScope;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.*;

@androidx.test.filters.SdkSuppress(minSdkVersion = 29)
public final class HomeDetailConfigurationAndroidTest {
    @Rule public final UiAutomationStateRule automation = new UiAutomationStateRule();
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private SharedPreferences prefs, auth;
    private Map<String, ?> before, authBefore;
    private SubHubPackManager packs;
    private String id;
    @Before public void prepare() throws Exception {
        prefs = new SettingsRepository(context).preferences();
        auth = context.getSharedPreferences("subhub_controller_auth", Context.MODE_PRIVATE);
        before = new java.util.LinkedHashMap<>(prefs.getAll()); authBefore = new java.util.LinkedHashMap<>(auth.getAll());
        prefs.edit().putBoolean(AppModeManager.KEY_ARMED, false).remove("commitment_ends_at").commit();
        packs = new SubHubPackManager(context);
        assertNull("Disposable fixture must have no active personal pack", packs.activePackId());
        if (!ControllerPinManager.isConfigured(context)) assertTrue(ControllerPinManager.setPin(context, "2468"));
        String chrome = "com.android.chrome", settings = "com.android.settings";
        context.getPackageManager().getApplicationInfo(chrome, 0);
        context.getPackageManager().getApplicationInfo(settings, 0);
        AppModeManager apps = new AppModeManager(context);
        apps.setAllAppsEnabled(false);
        apps.saveIncludedPackages(Set.of(chrome, settings));
        AppTimerManager timers = new AppTimerManager(context);
        timers.saveSettings(true, 30, false, 120);
        timers.saveAllowances(Set.of(chrome, settings), Map.of(chrome, 35, settings, 75));
        assertAllowances();
        ControllerPinManager.enterSubMode();
    }
    @After public void restore() {
        ControllerPinManager.enterDomMode();
        if (id != null) { assertTrue(packs.deactivate()); packs.deleteLibrary(id); }
        SharedPreferenceTestRestore.restore(prefs, before); SharedPreferenceTestRestore.restore(auth, authBefore);
        ControllerPinManager.enterSubMode();
    }
    @Test public void limitsListsDistinctInstalledAppAllowancesAndEmptySelection() throws Exception {
        try (ActivityScenario<ContractActivity> scenario = launch()) {
            awaitContract(scenario, false);
            inspect("home-limits-allowances.png", text -> {
                assertTrue(text.contains("Chrome · 35 min"));
                assertTrue(text.contains("Settings · 75 min"));
            });
            assertAllowances();
            new AppModeManager(context).saveIncludedPackages(Set.of());
            scenario.recreate();
            awaitContract(scenario, false);
            inspect(null, text -> {
                assertTrue(text.contains(context.getString(R.string.arrangement_no_apps)));
                assertFalse(text.contains("Chrome · 35 min"));
                assertFalse(text.contains("Settings · 75 min"));
            });
            assertTrue(new AppModeManager(context).getIncludedPackages().isEmpty());
        }
    }
    @Test public void ritualsShowsActivePackAndConfiguredMethodsWithoutSecrets() throws Exception {
        ControllerPinManager.enterDomMode();
        SubHubPack pack = packs.createBlank(); id = pack.getId();
        pack.setMetadata("Native Rituals pack", "Synthetic holder", "", "2.3.0");
        pack.putAsset("assets/cover/rituals.png", coverArtwork());
        pack.setSection(SubHubPackSchema.CENSOR, PackSettingCatalog.defaults(SubHubPackSchema.CENSOR));
        packs.addToLibrary(pack); assertTrue(packs.activate(pack, Set.of(SubHubPackSchema.CENSOR)));
        auth.edit().putString("encrypted_secret", "SYNTHETIC_SECRET_DO_NOT_SHOW").commit();
        ControllerPinManager.enterSubMode();
        try (ActivityScenario<ContractActivity> scenario = launch()) {
            awaitContract(scenario, true);
            onView(withTagValue(is("contract_cover:local"))).perform(revealAboveNavigation());
            awaitCommittedFrame();
            assertCover(true);
            inspect("home-rituals-config.png", text -> {
                assertTrue(text.contains("Native Rituals pack · v2.3.0"));
                assertTrue(text.contains(context.getString(R.string.keyholder_status_both)));
                assertFalse(text.contains("SYNTHETIC_SECRET_DO_NOT_SHOW"));
            });
            assertAllowances();
            ControllerPinManager.enterDomMode();
            pack.removeAsset("assets/cover/rituals.png"); packs.addToLibrary(pack);
            ControllerPinManager.enterSubMode();
            scenario.recreate();
            awaitContract(scenario, false);
            assertCover(false);
            inspect(null, text -> assertTrue(text.contains("Native Rituals pack · v2.3.0")));
            ControllerPinManager.enterDomMode(); assertTrue(packs.deactivate());
            ControllerPinManager.enterSubMode();
            scenario.recreate();
            awaitContract(scenario, false);
            assertCover(false);
            inspect(null, text -> assertTrue(text.contains(context.getString(R.string.arrangement_none_selected))));
        }
    }
    private ActivityScenario<ContractActivity> launch() {
        return ActivityScenario.launch(ContractActivity.class);
    }
    private void assertAllowances() {
        assertEquals(Set.of("com.android.chrome", "com.android.settings"), new AppModeManager(context).getIncludedPackages());
        AppTimerManager timers = new AppTimerManager(context);
        assertEquals(35, timers.allowanceMinutes("com.android.chrome"));
        assertEquals(75, timers.allowanceMinutes("com.android.settings"));
    }
    private static void awaitContract(ActivityScenario<ContractActivity> scenario, boolean expectedCover) {
        long end = android.os.SystemClock.uptimeMillis() + 10000;
        AtomicBoolean ready = new AtomicBoolean();
        while (android.os.SystemClock.uptimeMillis() < end) {
            scenario.onActivity(activity -> {
                View body = activity.findViewById(R.id.role_page_body);
                View cover = body.findViewWithTag("contract_cover:local");
                AsyncUiScope data = AsyncUiScope.forPage(activity);
                ready.set(!data.isPending("contract") && !data.isPending("contract-cover")
                        && text(body).contains(activity.getString(R.string.atmosphere_title))
                        && body.isLaidOut() && body.getWidth() > 0 && body.getHeight() > 0
                        && cover != null && cover.getVisibility() == (expectedCover ? View.VISIBLE : View.GONE)
                        && (!expectedCover || cover.isLaidOut() && cover.getWidth() > 0 && cover.getHeight() > 0));
            });
            if (ready.get()) {
                InstrumentationRegistry.getInstrumentation().waitForIdleSync();
                awaitCommittedFrame();
                return;
            }
            android.os.SystemClock.sleep(25);
        }
        fail("Contract details and cover did not finish loading and layout");
    }
    private void inspect(String file, java.util.function.Consumer<String> check) {
        awaitCommittedFrame();
        onView(withId(R.id.role_page_body)).check((body, missing) -> {
            if (missing != null) throw missing;
            check.accept(text(body));
            assertFalse(text(body).contains("SYNTHETIC_SECRET_DO_NOT_SHOW"));
            checkTextFits(body);
            assertFalse(hasConfigurationInput(body));
            View navigation = body.getRootView().findViewById(R.id.nav_contract);
            assertTrue(navigation.isShown());
            assertTrue(navigation.isSelected());
        });
        if (file != null) {
            Bitmap bitmap = renderedContractScreenshot(file);
            try {
                if (file.equals("home-rituals-config.png")) {
                    java.util.concurrent.atomic.AtomicReference<Rect> coverBounds = new java.util.concurrent.atomic.AtomicReference<>();
                    onView(withTagValue(is("contract_cover:local"))).check((cover, missing) -> {
                        if (missing != null) throw missing;
                        int[] location = new int[2]; cover.getLocationOnScreen(location);
                        coverBounds.set(new Rect(location[0], location[1], location[0] + cover.getWidth(),
                                location[1] + cover.getHeight()));
                    });
                    Rect bounds = coverBounds.get();
                    assertTrue("Actual compositor displays the cover's center marker",
                            sameOpaqueColor(bitmap.getPixel(bounds.centerX(), bounds.centerY()), 0xFF4FD3C8));
                }
            } finally { bitmap.recycle(); }
        }
    }
    private static byte[] coverArtwork() {
        Bitmap image = Bitmap.createBitmap(96, 128, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(image); canvas.drawColor(0xFF693DA2);
        android.graphics.Paint paint = new android.graphics.Paint(); paint.setColor(0xFF4FD3C8);
        canvas.drawRect(20, 20, 76, 108, paint);
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, bytes)); image.recycle();
        return bytes.toByteArray();
    }
    private static void assertCover(boolean visible) {
        onView(withTagValue(is("contract_cover:local"))).check((view, missing) -> {
            if (missing != null) throw missing;
            android.widget.ImageView cover = (android.widget.ImageView) view;
            assertEquals(visible ? View.VISIBLE : View.GONE, cover.getVisibility());
            if (!visible) { assertNull(cover.getDrawable()); return; }
            assertTrue(cover.getDrawable() instanceof android.graphics.drawable.BitmapDrawable);
            Bitmap image = ((android.graphics.drawable.BitmapDrawable) cover.getDrawable()).getBitmap();
            assertTrue(image.getWidth() <= 512 && image.getHeight() <= 512);
            assertEquals(0xFF4FD3C8, image.getPixel(image.getWidth() / 2, image.getHeight() / 2));
            Rect text = new Rect(), artwork = new Rect();
            ViewGroup row = (ViewGroup) cover.getParent();
            assertTrue(row.getChildAt(0).getGlobalVisibleRect(text));
            assertTrue(cover.getGlobalVisibleRect(artwork));
            assertTrue("Cover sits to the right without overlapping the pack name", text.right < artwork.left);
            int[] position = new int[2]; cover.getLocationOnScreen(position);
            assertEquals(new Rect(position[0], position[1], position[0] + cover.getWidth(),
                    position[1] + cover.getHeight()), artwork);
            Rect navigation = new Rect();
            assertTrue(cover.getRootView().findViewById(R.id.bottom_navigation).getGlobalVisibleRect(navigation));
            assertTrue("Cover clears the floating navigation", artwork.bottom <= navigation.top);
        });
    }
    private Bitmap renderedContractScreenshot(String file) {
        java.util.concurrent.atomic.AtomicReference<Rect> bounds = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<Rect> coverBounds = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicInteger color = new java.util.concurrent.atomic.AtomicInteger();
        onView(withId(R.id.nav_contract_label)).check((label, missing) -> {
            if (missing != null) throw missing;
            int[] point = new int[2]; label.getLocationOnScreen(point);
            bounds.set(new Rect(point[0], point[1], point[0] + label.getWidth(), point[1] + label.getHeight()));
            color.set(((TextView) label).getCurrentTextColor());
            View cover = label.getRootView().findViewWithTag("contract_cover:local");
            if (cover.getVisibility() == View.VISIBLE) {
                cover.getLocationOnScreen(point);
                coverBounds.set(new Rect(point[0], point[1], point[0] + cover.getWidth(), point[1] + cover.getHeight()));
            }
        });
        long end = android.os.SystemClock.uptimeMillis() + 5000;
        boolean first = true;
        Bitmap last = null;
        while (android.os.SystemClock.uptimeMillis() < end) {
            if (last != null) { last.recycle(); last = null; }
            Bitmap frame = InstrumentationRegistry.getInstrumentation()
                    .getUiAutomation(android.app.UiAutomation.FLAG_DONT_USE_ACCESSIBILITY).takeScreenshot();
            assertNotNull("Native compositor screenshot available", frame);
            Rect label = new Rect(bounds.get());
            label.inset(label.width() / 3, label.height() / 4);
            int matched = 0, expected = color.get();
            if (label.left >= 0 && label.top >= 0 && label.right <= frame.getWidth() && label.bottom <= frame.getHeight()) {
                for (int y = label.top; y < label.bottom; y++) for (int x = label.left; x < label.right; x++) {
                    int pixel = frame.getPixel(x, y);
                    if (sameOpaqueColor(pixel, expected)) matched++;
                }
            }
            Rect artwork = coverBounds.get();
            boolean coverSettled = artwork == null || artwork.left >= 0 && artwork.top >= 0
                    && artwork.right <= frame.getWidth() && artwork.bottom <= frame.getHeight()
                    && sameOpaqueColor(frame.getPixel(artwork.centerX(), artwork.centerY()), 0xFF4FD3C8);
            if (matched > 40 && coverSettled) {
                savePixels(frame, file);
                return frame;
            }
            if (first) {
                savePixels(frame, file.replace(".png", "-pending.png"));
                first = false;
            }
            last = frame;
            // A hardware frame callback alone does not establish compositor visibility.
            awaitCommittedFrame();
        }
        if (last != null) { savePixels(last, file.replace(".png", "-failed.png")); last.recycle(); }
        throw new AssertionError("Contract label or cover marker absent from compositor at "
                + bounds.get() + " / " + coverBounds.get());
    }
    private void savePixels(Bitmap bitmap, String name) {
        try (FileOutputStream output = new FileOutputStream(new File(context.getExternalFilesDir(null), name))) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
        } catch (java.io.IOException failure) { throw new AssertionError(failure); }
    }
    private static boolean sameOpaqueColor(int actual, int expected) {
        // Allow two levels of 8-bit compositor rounding; fading remains a failed capture.
        return android.graphics.Color.alpha(actual) == 255
                && Math.abs(android.graphics.Color.red(actual) - android.graphics.Color.red(expected)) <= 2
                && Math.abs(android.graphics.Color.green(actual) - android.graphics.Color.green(expected)) <= 2
                && Math.abs(android.graphics.Color.blue(actual) - android.graphics.Color.blue(expected)) <= 2;
    }
    private static void awaitCommittedFrame() {
        java.util.concurrent.CountDownLatch frame = new java.util.concurrent.CountDownLatch(1);
        onView(withId(R.id.role_page_body)).check((body, missing) -> {
            if (missing != null) throw missing;
            View root = body.getRootView(); assertTrue(root.isHardwareAccelerated());
            root.getViewTreeObserver().registerFrameCommitCallback(frame::countDown); root.invalidate();
        });
        try { assertTrue("Contract committed a native frame", frame.await(5, java.util.concurrent.TimeUnit.SECONDS)); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
    }
    private static void checkTextFits(View view) {
        if (view.getVisibility() != View.VISIBLE) return;
        if (view instanceof TextView) {
            TextView label = (TextView) view;
            assertNotNull("Text has a laid-out paragraph", label.getLayout());
            assertTrue("Text height: " + label.getText(), label.getLayout().getHeight()
                    <= label.getHeight() - label.getCompoundPaddingTop() - label.getCompoundPaddingBottom());
            for (int line = 0; line < label.getLayout().getLineCount(); line++)
                assertTrue("Text width: " + label.getText(), label.getLayout().getLineMax(line)
                        <= label.getWidth() - label.getCompoundPaddingLeft() - label.getCompoundPaddingRight() + 1);
        }
        if (view instanceof ViewGroup) for (int index = 0; index < ((ViewGroup) view).getChildCount(); index++)
            checkTextFits(((ViewGroup) view).getChildAt(index));
    }
    private static boolean hasConfigurationInput(View view) {
        if (view instanceof android.widget.EditText || view instanceof android.widget.CompoundButton
                || view instanceof android.widget.Spinner) return true;
        if (view instanceof ViewGroup) for (int index = 0; index < ((ViewGroup) view).getChildCount(); index++)
            if (hasConfigurationInput(((ViewGroup) view).getChildAt(index))) return true;
        return false;
    }
    private static String text(View view) {
        StringBuilder result = new StringBuilder();
        if (view instanceof TextView) result.append(((TextView) view).getText()).append('\n');
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
            result.append(text(((ViewGroup) view).getChildAt(i)));
        return result.toString();
    }
}
