package com.subhub.app.remote;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.widget.EditText;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Test-only bridge for two disposable guests; never approves enrollment or logs a bearer. */
@RunWith(AndroidJUnit4.class)
public final class TwoGuestInvitationBridgeAndroidTest {
    @Test public void serveVisiblePairingPage() throws Exception {
        org.junit.Assume.assumeTrue("Explicit two-guest acceptance only", "true".equals(InstrumentationRegistry.getArguments().getString("two_guest_bridge")));
        Context app = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File dir = new File(app.getFilesDir(), "two_guest_bridge");
        if (!dir.exists() && !dir.mkdir()) throw new IllegalStateException("bridge directory");
        Intent launch = app.getPackageManager().getLaunchIntentForPackage(app.getPackageName());
        if (launch == null) throw new IllegalStateException("launch intent");
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        app.startActivity(launch);
        write(new File(dir, "ready"), "ready");
        long deadline = android.os.SystemClock.elapsedRealtime() + 900_000L;
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            if (new File(dir, "stop").exists()) return;
            File export = new File(dir, "export");
            File input = new File(dir, "import");
            if (export.exists()) {
                final Throwable[] error = new Throwable[1];
                InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                    try {
                        PairingActivity page = visible();
                        java.lang.reflect.Field field = PairingActivity.class.getDeclaredField("invitation");
                        field.setAccessible(true);
                        PairingInvite invite = (PairingInvite) field.get(page);
                        if (invite == null) throw new IllegalStateException("invitation unavailable");
                        write(new File(dir, "secret"), invite.code());
                    } catch (Throwable failure) { error[0] = failure; }
                });
                if (!export.delete()) throw new IllegalStateException("consume export");
                write(new File(dir, "export_result"), error[0] == null ? "ok" : "unavailable");
            }
            if (input.exists()) {
                final String code = new String(Files.readAllBytes(new File(dir, "secret").toPath()), StandardCharsets.UTF_8);
                final Throwable[] error = new Throwable[1];
                InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                    try {
                        PairingActivity page = visible();
                        java.lang.reflect.Field field = PairingActivity.class.getDeclaredField("link");
                        field.setAccessible(true);
                        EditText entry = (EditText) field.get(page);
                        if (entry == null) throw new IllegalStateException("input unavailable");
                        entry.setText(code);
                    } catch (Throwable failure) { error[0] = failure; }
                });
                if (!input.delete()) throw new IllegalStateException("consume import");
                write(new File(dir, "import_result"), error[0] == null ? "ok" : "unavailable");
            }
            Thread.sleep(250);
        }
        throw new AssertionError("bridge timed out");
    }
    private static PairingActivity visible() {
        for (Activity a : ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED))
            if (a instanceof PairingActivity && !a.isFinishing()) return (PairingActivity) a;
        throw new IllegalStateException("pairing page not resumed");
    }
    private static void write(File file, String value) throws java.io.IOException {
        Files.write(file.toPath(), value.getBytes(StandardCharsets.UTF_8));
    }
}
