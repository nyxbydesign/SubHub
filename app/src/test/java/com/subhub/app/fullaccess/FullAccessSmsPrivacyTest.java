package com.subhub.app.fullaccess;

import static org.junit.Assert.*;

import org.junit.Test;

public final class FullAccessSmsPrivacyTest {
    @Test public void likelyCredentialMessagesNeverBecomeRows() {
        assertEquals("", FullAccessSmsExport.safeText("Your verification code is 873241."));
        assertEquals("", FullAccessSmsExport.safeText("Password reset request: https://example.invalid/reset"));
    }

    @Test public void publicNotificationPreviewsDropCredentialsAndBoundText() {
        assertEquals(new FullAccessNotificationListener.Preview("", ""),
                FullAccessNotificationListener.publicPreview("Sign-in", "A new device"));
        assertEquals(new FullAccessNotificationListener.Preview("", ""),
                FullAccessNotificationListener.publicPreview("Account", "７４６１９４"));
        assertEquals(new FullAccessNotificationListener.Preview("Calendar", "Lunch is ready"),
                FullAccessNotificationListener.publicPreview("Calendar", "Lunch is ready"));
        assertEquals(480, FullAccessNotificationListener.publicPreview(null, "x".repeat(481)).text().length());
    }

    @Test public void shortCodesAreMaskedWithoutHidingOrdinaryConversation() {
        assertEquals("Call me when you arrive.", FullAccessSmsExport.safeText("Call me when you arrive."));
        assertEquals("Use •••• on arrival.", FullAccessSmsExport.safeText("Use 172468 on arrival."));
    }
}
