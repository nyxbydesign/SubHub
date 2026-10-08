package com.subhub.app.privacy;

import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.*;
import com.subhub.app.MainActivity;
import com.subhub.app.security.ControllerPinManager;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static org.junit.Assume.*;

@RunWith(AndroidJUnit4.class)
public class DeviceCredentialAndroidTest {
    @Test public void deviceCredentialUnlocksAppAndReturningRequiresItAgain() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assumeTrue("Synthetic credentials are restricted to the dedicated emulator", android.os.Build.MODEL.contains("sdk_gphone"));
        assumeFalse("Never replace an existing device credential", context.getSystemService(KeyguardManager.class).isDeviceSecure());
        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        PrivacyManager privacy = new PrivacyManager(context);
        String fixturePin = "246813";
        assertTrue(device.executeShellCommand("locksettings set-pin " + fixturePin).contains("set to"));
        try {
            ControllerPinManager.enterSubMode();
            privacy.setAppLock(true);
            Intent launch = new Intent(context, MainActivity.class).setAction(Intent.ACTION_MAIN)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            context.startActivity(launch);
            UiObject2 input = device.wait(Until.findObject(By.clazz("android.widget.EditText")), 8000);
            assertNotNull("Android device credential prompt", input);
            input.setText(fixturePin); device.pressEnter();
            assertTrue("Authenticated Home becomes visible", device.wait(Until.hasObject(By.res("com.subhub.app", "nav_home")), 8000));
            assertFalse("Device authentication never grants Dom", ControllerPinManager.isDomModeActive());
            device.waitForIdle();
            Thread.sleep(400);
            device.pressHome();
            Thread.sleep(800);
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> assertTrue("Backgrounding must relock", PrivacyLifecycle.locked()));
            context.startActivity(launch);
            assertNotNull("Returning from background requires authentication again", device.wait(Until.findObject(By.clazz("android.widget.EditText")), 8000));
            device.pressBack();
            device.waitForIdle();
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> assertTrue("Cancellation stays locked", PrivacyLifecycle.locked()));
            assertFalse(device.hasObject(By.res("com.subhub.app", "nav_home")));
        } finally {
            try { privacy.setAppLock(false); }
            finally { device.executeShellCommand("locksettings clear --old " + fixturePin); }
        }
    }
}
