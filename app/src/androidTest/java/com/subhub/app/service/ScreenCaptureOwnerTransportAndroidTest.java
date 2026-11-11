package com.subhub.app.service;

import static org.junit.Assert.*;
import android.content.Context;
import android.content.Intent;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.UUID;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Intent construction only. Never starts a service or changes any physical session. */
@RunWith(AndroidJUnit4.class)
public final class ScreenCaptureOwnerTransportAndroidTest {
    @Test public void ownerTransportContainsOnlyCanonicalOpaqueToken() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String token = UUID.randomUUID().toString(); Intent intent = ScreenCaptureService.ownerStartIntent(context, token);
        assertEquals("com.subhub.app.action.ATTACH_RUNTIME_OWNER", intent.getAction());
        assertEquals(ScreenCaptureService.class.getName(), intent.getComponent().getClassName());
        assertEquals(context.getPackageName(), intent.getComponent().getPackageName());
        assertEquals(1, intent.getExtras().size()); assertEquals(token, intent.getStringExtra("runtime_owner_token"));
        assertFalse(intent.hasExtra("projection_result_code")); assertFalse(intent.hasExtra("projection_result_data"));
    }
    @Test public void invalidOwnerTransportNeverBecomesLegacyStart() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        for (String token : new String[]{null, "", "unknown", "1-1-1-1-1", " " + UUID.randomUUID()})
            assertThrows(IllegalArgumentException.class, () -> ScreenCaptureService.ownerStartIntent(context, token));
    }
    @Test public void laterCallerIntentMutationCannotAlterAnotherTransport() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext(); String token = UUID.randomUUID().toString();
        Intent first = ScreenCaptureService.ownerStartIntent(context, token), second = ScreenCaptureService.ownerStartIntent(context, token);
        first.putExtra("runtime_owner_token", UUID.randomUUID().toString()); first.setAction("untrusted");
        assertEquals(token, second.getStringExtra("runtime_owner_token"));
        assertEquals("com.subhub.app.action.ATTACH_RUNTIME_OWNER", second.getAction());
    }
}
