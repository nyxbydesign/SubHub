package com.subhub.app;

import android.Manifest;
import android.app.UiAutomation;
import android.content.Context;
import android.provider.Settings;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.rules.ExternalResource;
import static org.junit.Assert.*;

/** UI-only automation restores remembered Accessibility state after disconnecting its service. */
public final class UiAutomationStateRule extends ExternalResource {
    private String services, enabled;

    @Override protected void before() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        services = Settings.Secure.getString(context.getContentResolver(), "enabled_accessibility_services");
        enabled = Settings.Secure.getString(context.getContentResolver(), "accessibility_enabled");
        if ("1".equals(enabled) && services != null && services.contains("ScreenshotAccessibilityService")) {
            throw new AssertionError("Use an owned UI-only guest without active capture");
        }
        InstrumentationRegistry.getInstrumentation().getUiAutomation(UiAutomation.FLAG_DONT_USE_ACCESSIBILITY);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    @Override protected void after() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        UiAutomation automation = InstrumentationRegistry.getInstrumentation()
                .getUiAutomation(UiAutomation.FLAG_DONT_USE_ACCESSIBILITY);
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            automation.adoptShellPermissionIdentity(Manifest.permission.WRITE_SECURE_SETTINGS);
            try {
                assertTrue(Settings.Secure.putString(context.getContentResolver(), "enabled_accessibility_services", services));
                assertTrue(Settings.Secure.putString(context.getContentResolver(), "accessibility_enabled", enabled));
            } finally { automation.dropShellPermissionIdentity(); }
        } else {
            // Older platforms expose shell execution, but no permission-identity adoption API.
            try { restoreFromShell(automation, "enabled_accessibility_services", services); }
            finally { restoreFromShell(automation, "accessibility_enabled", enabled); }
        }
        assertEquals(services, Settings.Secure.getString(context.getContentResolver(), "enabled_accessibility_services"));
        assertEquals(enabled, Settings.Secure.getString(context.getContentResolver(), "accessibility_enabled"));
    }
    private static void restoreFromShell(UiAutomation automation, String key, String value) {
        boolean servicesKey = "enabled_accessibility_services".equals(key);
        if (!servicesKey && !"accessibility_enabled".equals(key))
            throw new AssertionError("Unexpected Accessibility setting");
        if (value != null && (servicesKey
                ? !value.matches("(?:[A-Za-z0-9_.$]+/[A-Za-z0-9_.$]+(?::[A-Za-z0-9_.$]+/[A-Za-z0-9_.$]+)*)?")
                : !value.matches("[01]")))
            throw new AssertionError("Accessibility snapshot cannot be safely restored");
        // API 26 tokenizes this command with Runtime.exec; quotes would become stored data.
        String command = "settings " + (value == null ? "delete" : "put") + " secure " + key
                + (value == null ? "" : " " + value);
        if (value != null && value.isEmpty()) {
            // A fixed, whitespace-free shell program preserves the empty final argument.
            command = "/system/bin/sh -c settings${IFS}put${IFS}secure${IFS}enabled_accessibility_services${IFS}''";
        }
        android.os.ParcelFileDescriptor descriptor = automation.executeShellCommand(command);
        assertNotNull("Accessibility restoration shell unavailable", descriptor);
        try (java.io.InputStream response = new android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor)) {
            byte[] buffer = new byte[256];
            int size = 0, count;
            while ((count = response.read(buffer)) != -1) {
                size += count;
                if (size > 8192) throw new AssertionError("Unexpected Accessibility restoration response");
            }
        } catch (java.io.IOException unavailable) {
            throw new AssertionError("Accessibility restoration did not complete", unavailable);
        }
    }
}
