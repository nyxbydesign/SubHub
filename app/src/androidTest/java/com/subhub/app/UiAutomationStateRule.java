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
        automation.adoptShellPermissionIdentity(Manifest.permission.WRITE_SECURE_SETTINGS);
        try {
            assertTrue(Settings.Secure.putString(context.getContentResolver(), "enabled_accessibility_services", services));
            assertTrue(Settings.Secure.putString(context.getContentResolver(), "accessibility_enabled", enabled));
        } finally { automation.dropShellPermissionIdentity(); }
        assertEquals(services, Settings.Secure.getString(context.getContentResolver(), "enabled_accessibility_services"));
        assertEquals(enabled, Settings.Secure.getString(context.getContentResolver(), "accessibility_enabled"));
    }
}
