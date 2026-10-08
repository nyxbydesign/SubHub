package com.subhub.app.studio;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.replaceText;
import static androidx.test.espresso.action.ViewActions.closeSoftKeyboard;
import static androidx.test.espresso.assertion.ViewAssertions.doesNotExist;
import static androidx.test.espresso.matcher.ViewMatchers.withHint;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static androidx.test.espresso.matcher.ViewMatchers.withParent;
import static androidx.test.espresso.matcher.ViewMatchers.hasDescendant;
import static androidx.test.espresso.matcher.ViewMatchers.isNotChecked;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static org.hamcrest.Matchers.allOf;
import static org.junit.Assert.*;

import android.content.Context;
import android.view.View;
import android.view.WindowManager;
import android.graphics.Rect;
import android.widget.ScrollView;

import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.subhub.app.R;
import com.subhub.app.pack.SubHubPackManager;
import com.subhub.app.pack.SubHubPack;
import com.subhub.app.pack.SubHubPackSchema;
import com.subhub.app.security.ControllerPinManager;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class StudioPayPalTransferAndroidTest {
    private static final String HINT = "Passphrase (12–256 characters)";

    @Before public void setup() {
        Context context = ApplicationProvider.getApplicationContext();
        if (!ControllerPinManager.isConfigured(context)) ControllerPinManager.setPin(context, "2468");
        ControllerPinManager.enterDomMode();
    }

    @Test public void secretEntryDisablesStateAutofillAndScreenshots() {
        try (ActivityScenario<StudioActivity> scenario = ActivityScenario.launch(StudioActivity.class)) {
            openEntry();
            onView(withHint(HINT)).check((view, error) -> {
                if (error != null) throw error;
                assertFalse(view.isSaveEnabled());
                assertEquals(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS,
                        view.getImportantForAutofill());
                WindowManager.LayoutParams params = (WindowManager.LayoutParams)
                        view.getRootView().getLayoutParams();
                assertTrue((params.flags & WindowManager.LayoutParams.FLAG_SECURE) != 0);
            });
            onView(withText(android.R.string.cancel)).perform(click());
            onView(withHint(HINT)).check(doesNotExist());
        }
    }

    @Test public void leavingActivityDismissesSecretEntryWithoutRestoringIt() {
        try (ActivityScenario<StudioActivity> scenario = ActivityScenario.launch(StudioActivity.class)) {
            openEntry();
            scenario.moveToState(Lifecycle.State.CREATED);
            scenario.moveToState(Lifecycle.State.RESUMED);
            onView(withHint(HINT)).check(doesNotExist());
        }
    }

    @Test public void subModeCannotOpenCredentialExport() {
        try (ActivityScenario<StudioActivity> scenario = ActivityScenario.launch(StudioActivity.class)) {
            scenario.onActivity(activity -> {
                ControllerPinManager.enterSubMode();
                SubHubPackManager manager = new SubHubPackManager(activity);
                StudioPayPalTransfer transfer = new StudioPayPalTransfer(activity, manager);
                transfer.attach(manager.captureCurrent(), () -> fail("Sub export must not run"));
                transfer.destroy();
            });
            onView(withHint(HINT)).check(doesNotExist());
        } finally { ControllerPinManager.enterDomMode(); }
    }

    private void openEntry() {
        onView(withId(R.id.tab_create)).perform(click());
        onView(withId(R.id.button_capture)).perform(revealAboveNavigation(), click());
        onView(withText("Encrypt current PayPal into pack")).perform(revealAboveNavigation(), click());
    }

    // Espresso's stock scrollTo sees through the floating navigation. Center the actual
    // control in the usable viewport and verify it is not covered before injecting a tap.
    private androidx.test.espresso.ViewAction revealAboveNavigation() {
        return new androidx.test.espresso.ViewAction() {
            @Override public org.hamcrest.Matcher<View> getConstraints() {
                return androidx.test.espresso.matcher.ViewMatchers.isDescendantOfA(
                        org.hamcrest.Matchers.instanceOf(ScrollView.class));
            }
            @Override public String getDescription() { return "reveal control above floating navigation"; }
            @Override public void perform(androidx.test.espresso.UiController ui, View view) {
                android.view.ViewParent parent = view.getParent();
                while (!(parent instanceof ScrollView)) parent = parent.getParent();
                ScrollView scroll = (ScrollView) parent;
                Rect target = new Rect();
                view.getDrawingRect(target);
                scroll.offsetDescendantRectToMyCoords(view, target);
                scroll.scrollTo(0, Math.max(0, target.top - scroll.getHeight() / 3));
                ui.loopMainThreadUntilIdle();
                Rect visible = new Rect();
                assertTrue(view.getGlobalVisibleRect(visible));
                View navigation = view.getRootView().findViewById(R.id.bottom_navigation);
                Rect nav = new Rect();
                if (navigation != null && navigation.getGlobalVisibleRect(nav)) {
                    assertTrue("Tap target must be above navigation", visible.bottom <= nav.top);
                }
            }
        };
    }

    @Test public void sectionReviewLetsRecipientDeselectWallet() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        SubHubPackManager manager = new SubHubPackManager(context);
        SubHubPack pack = manager.createBlank();
        pack.setMetadata("Synthetic section selection", "", "", "1.0.0");
        pack.setSection(SubHubPackSchema.WALLET, new org.json.JSONObject());
        pack.setSection(SubHubPackSchema.CENSOR, new org.json.JSONObject());
        manager.addToLibrary(pack);
        try (ActivityScenario<StudioActivity> scenario = ActivityScenario.launch(StudioActivity.class)) {
            onView(allOf(withText(R.string.studio_apply), withParent(withParent(
                    hasDescendant(withText("Synthetic section selection")))))).perform(revealAboveNavigation(), click());
            onView(withText(R.string.studio_section_wallet)).perform(click()).check(matches(isNotChecked()));
            onView(withText(android.R.string.cancel)).perform(click());
        } finally { manager.deleteLibrary(pack.getId()); }
    }

    @Test public void attachFinishesBeforeExportCanLeaveStudio() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        com.subhub.app.penance.PayPalCredentialStore store =
                new com.subhub.app.penance.PayPalCredentialStore(context);
        assertTrue(store.save(com.subhub.app.penance.PayPalEnvironment.SANDBOX,
                "synthetic-ui-merchant", "synthetic-ui-secret"));
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        try (ActivityScenario<StudioActivity> scenario = ActivityScenario.launch(StudioActivity.class)) {
            // Hold the real worker queue so fast devices cannot finish between the two taps.
            scenario.onActivity(activity -> {
                try {
                    java.lang.reflect.Field transferField = StudioActivity.class.getDeclaredField("payPalTransfer");
                    transferField.setAccessible(true);
                    java.lang.reflect.Field workerField = StudioPayPalTransfer.class.getDeclaredField("worker");
                    workerField.setAccessible(true);
                    java.util.concurrent.ExecutorService worker = (java.util.concurrent.ExecutorService)
                            workerField.get(transferField.get(activity));
                    worker.execute(() -> {
                        try { release.await(60, java.util.concurrent.TimeUnit.SECONDS); }
                        catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                    });
                } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
            });
            openEntry();
            onView(withHint(HINT)).perform(replaceText("synthetic UI passphrase"));
            onView(withHint("Confirm passphrase")).perform(replaceText("synthetic UI passphrase"),
                    closeSoftKeyboard());
            onView(withId(android.R.id.button1)).perform(click());
            onView(withId(R.id.button_export)).perform(revealAboveNavigation(), click());
            // Export must not launch a chooser and cancel the pending encryption.
            assertEquals(Lifecycle.State.RESUMED, scenario.getState());
            release.countDown();
            java.util.concurrent.atomic.AtomicBoolean attached = new java.util.concurrent.atomic.AtomicBoolean();
            long deadline = android.os.SystemClock.uptimeMillis() + 60_000;
            do {
                scenario.onActivity(activity -> attached.set(((android.widget.TextView)
                        activity.findViewById(R.id.preview_text)).getText().toString()
                        .contains("Encrypted PayPal attached")));
                if (!attached.get()) android.os.SystemClock.sleep(100);
            } while (!attached.get() && android.os.SystemClock.uptimeMillis() < deadline);
            assertTrue("Encrypted draft should finish without leaving Studio", attached.get());
            SubHubPack stored = new SubHubPackManager(context).listDrafts().get(0).pack;
            assertTrue(stored.hasEncryptedPayPal());
            try (com.subhub.app.pack.PackPayPalCipher.Payload payload =
                    com.subhub.app.pack.PackPayPalCipher.decrypt(stored.getId(), stored.getOriginDeviceId(),
                            stored.getEncryptedPayPal(), "synthetic UI passphrase".toCharArray())) {
                assertEquals("synthetic-ui-secret", payload.secret());
            }
        } finally { release.countDown(); store.clear(); }
    }
}
