package com.subhub.app.fullaccess;

import static org.junit.Assert.*;

import org.junit.Test;

public final class FullAccessSmsPrivacyTest {
    @Test public void smsKeepsTextSuppliedByTheSource() {
        String appointment = "Your verification appointment has moved to room 873241.";
        String note = "The password note is next to the router.";
        String directions = " Please meet at stop １７２４６８.\nI'll bring the forms. ";
        assertEquals(appointment, FullAccessSmsExport.messageText(appointment));
        assertEquals(note, FullAccessSmsExport.messageText(note));
        assertEquals(directions, FullAccessSmsExport.messageText(directions));
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

    @Test public void missingSmsBodyStaysEmptyAndTransportBoundDoesNotSplitSurrogatePairs() {
        assertEquals("", FullAccessSmsExport.messageText(null));
        assertEquals("", FullAccessSmsExport.messageText(""));
        String full = "x".repeat(998) + "🙂";
        assertEquals(full, FullAccessSmsExport.messageText(full));
        assertEquals("x".repeat(998) + "…", FullAccessSmsExport.messageText(full + "x"));
    }
}
