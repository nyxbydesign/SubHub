package com.subhub.app.security;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.WindowManager;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.R;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.io.FileOutputStream;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.*;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class KeyholderPresentationAndroidTest {
    @Test public void handoverUsesExistingThemedControlsAndCancellationDoesNotPair() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        context.getSharedPreferences("subhub_controller_auth", 0).edit().clear().commit();
        ControllerPinManager.enterDomMode();
        try (ActivityScenario<AuthenticatorActivity> scenario = ActivityScenario.launch(AuthenticatorActivity.class)) {
            scenario.onActivity(activity -> {
                assertTrue((activity.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0);
                assertNotNull(activity.findViewById(R.id.primary_header));
                // Render only the unpaired overview, which contains no pairing key or credential.
                android.view.View root = activity.getWindow().getDecorView();
                root.post(() -> {
                    Bitmap image = Bitmap.createBitmap(root.getWidth(), root.getHeight(), Bitmap.Config.ARGB_8888);
                    root.draw(new Canvas(image));
                    try (FileOutputStream out = new FileOutputStream(new File(context.getFilesDir(), "keyholder-overview.png"))) {
                        image.compress(Bitmap.CompressFormat.PNG, 100, out);
                    } catch (Exception error) { throw new AssertionError(error); }
                    finally { image.recycle(); }
                });
            });
            onView(withId(R.id.keyholder_remote_header)).perform(scrollTo(), click());
            onView(withId(R.id.keyholder_pin_header)).perform(scrollTo());
            scenario.onActivity(activity -> {
                android.view.View root = activity.getWindow().getDecorView();
                Bitmap image = Bitmap.createBitmap(root.getWidth(), root.getHeight(), Bitmap.Config.ARGB_8888);
                root.draw(new Canvas(image));
                try (FileOutputStream out = new FileOutputStream(new File(context.getFilesDir(), "keyholder-remote-overview.png"))) {
                    image.compress(Bitmap.CompressFormat.PNG, 100, out);
                } catch (Exception error) { throw new AssertionError(error); }
                finally { image.recycle(); }
            });
            onView(withId(R.id.keyholder_pair_button)).perform(scrollTo(), click());
            onView(withId(R.id.authenticator_confirm_button)).perform(scrollTo());
            assertFalse(new ControllerAuthenticator(context).isPaired());
            onView(withText(android.R.string.cancel)).perform(scrollTo(), click());
            assertFalse(new ControllerAuthenticator(context).isPaired());
        } finally { ControllerPinManager.enterSubMode(); }
    }
}
