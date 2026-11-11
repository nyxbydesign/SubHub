package com.subhub.app.service;

import static org.junit.Assert.*;
import android.app.Activity;
import android.app.Service;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Exercises transport construction and dispatch without starting an Android service or capture. */
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

    @Test public void ownerAttachLeavesInitializedDeviceShellUnchanged() throws Exception {
        onMain(() -> {
            CommandContext context = new CommandContext(targetContext());
            ScreenCaptureService service = commandShell(context);
            field("deviceInitialized").setBoolean(service, true);
            CaptureRunOwnership.Token incumbent = new CaptureRunOwnership.Token(
                    (String) field("instanceIdentity").get(service), UUID.randomUUID().toString(), 1,
                    new CaptureRunOwnership.Components("", "", ""));
            field("captureOwner").set(service, incumbent);

            Intent attach = ScreenCaptureService.ownerStartIntent(targetContext(), UUID.randomUUID().toString());
            assertEquals(Service.START_NOT_STICKY, service.onStartCommand(attach, 0, 2));

            assertSame(incumbent, field("captureOwner").get(service));
            assertFalse(incumbent.isClosed());
            assertFalse(field("managedOwnerRoute").getBoolean(service));
            assertNull(field("runtimeConnection").get(service));
            return null;
        });
    }

    @Test public void rejectedOwnerAttachKeepsShellOutOfLegacyStart() throws Exception {
        onMain(() -> {
            CommandContext context = new CommandContext(targetContext());
            ScreenCaptureService service = commandShell(context);
            context.allowGatewayLookup = true;
            Intent rejected = ScreenCaptureService.ownerStartIntent(targetContext(), UUID.randomUUID().toString());
            rejected.removeExtra("runtime_owner_token");
            assertEquals(Service.START_NOT_STICKY, service.onStartCommand(rejected, 0, 1));
            context.allowGatewayLookup = false;
            assertTrue(field("managedOwnerRoute").getBoolean(service));
            assertNull(field("runtimeConnection").get(service));

            Intent legacy = ScreenCaptureService.startIntent(targetContext(), Activity.RESULT_OK, new Intent());
            assertEquals(Service.START_NOT_STICKY, service.onStartCommand(legacy, 0, 2));
            assertTrue(field("managedOwnerRoute").getBoolean(service));
            assertFalse(field("deviceInitialized").getBoolean(service));
            assertNull(field("captureOwner").get(service));
            return null;
        });
    }

    private static final class CommandContext extends ContextWrapper {
        boolean allowGatewayLookup;
        CommandContext(Context base) { super(base); }
        @Override public Context getApplicationContext() {
            assertTrue("Command unexpectedly looked up the runtime gateway", allowGatewayLookup);
            return super.getApplicationContext();
        }
    }

    private static ScreenCaptureService commandShell(Context base) throws Exception {
        ScreenCaptureService service = new ScreenCaptureService();
        Method attach = ContextWrapper.class.getDeclaredMethod("attachBaseContext", Context.class);
        attach.setAccessible(true);
        // Use Android Intent and Context handling, without AMS or native capture resources.
        attach.invoke(service, base);
        return service;
    }

    private static Field field(String name) throws ReflectiveOperationException {
        Field field = ScreenCaptureService.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static Context targetContext() {
        return InstrumentationRegistry.getInstrumentation().getTargetContext();
    }

    private static void onMain(Callable<Void> test) throws Exception {
        FutureTask<Void> task = new FutureTask<>(test);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(task);
        task.get();
    }
}
