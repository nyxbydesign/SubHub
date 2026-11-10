package com.subhub.app.penance;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;
import android.app.*;
import android.content.*;
import android.os.*;
import android.view.*;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.R;
import com.subhub.app.appmode.AppModeManager;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.settings.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.json.*;
import org.junit.Test;

/** Emulator-only editor timing with synthetic encrypted credentials and no provider requests. */
public final class WalletNavigationLatencyAndroidTest {
    @Test public void measureRepeatedEditorOpens() throws Exception {
        assumeTrue(Boolean.parseBoolean(InstrumentationRegistry.getArguments().getString("measureWallet", "false")));
        assumeTrue(Build.MODEL.contains("sdk_gphone"));
        Instrumentation instrument = InstrumentationRegistry.getInstrumentation();
        Context context = instrument.getTargetContext();
        Map<SharedPreferences, Map<String, ?>> before = new LinkedHashMap<>();
        for (String name : new String[] {SettingsRepository.PREFERENCES_NAME, PenanceManager.PREFS_NAME,
                PayPalCredentialStore.PREFS_NAME}) {
            SharedPreferences prefs = context.getSharedPreferences(name, 0);
            before.put(prefs, prefs.getAll());
        }
        new AppModeManager(context).setArmed(false);
        new HardcoreAutoPayManager(context).disable();
        ControllerPinManager.setPin(context, "2468");
        ControllerPinManager.enterDomMode();
        JSONObject report = new JSONObject();
        try {
            for (boolean saved : new boolean[] {false, true}) {
                PayPalCredentialStore credentials = new PayPalCredentialStore(context);
                credentials.clear();
                if (saved) assertTrue(credentials.save(PayPalEnvironment.SANDBOX, "synthetic-ui-client", "synthetic-ui-secret"));
                new PenanceManager(context).configure(false, Map.of(), 1000, 5000, 10, 15);
                try (ActivityScenario<PenanceActivity> page = ActivityScenario.launch(PenanceActivity.class)) {
                    JSONArray opens = new JSONArray();
                    for (int round = 0; round < 6; round++) {
                        for (String editor : new String[] {"rules", "pause", "paypal", "corrections"}) {
                            instrument.waitForIdleSync();
                            CountDownLatch drawn = new CountDownLatch(1);
                            AtomicLong start = new AtomicLong(), firstDraw = new AtomicLong();
                            AtomicLong listener = new AtomicLong(), cpu = new AtomicLong();
                            AtomicReference<String> focus = new AtomicReference<>("none");
                            page.onActivity(a -> {
                                View root = a.getWindow().getDecorView();
                                ViewTreeObserver.OnDrawListener watch = new ViewTreeObserver.OnDrawListener() {
                                    @Override public void onDraw() {
                                        if (firstDraw.compareAndSet(0, SystemClock.elapsedRealtimeNanos())) {
                                            View focused = a.getCurrentFocus();
                                            if (focused != null) focus.set(focused.getClass().getSimpleName());
                                            root.post(() -> root.getViewTreeObserver().removeOnDrawListener(this));
                                            drawn.countDown();
                                        }
                                    }
                                };
                                root.getViewTreeObserver().addOnDrawListener(watch);
                                long initialCpu = Debug.threadCpuTimeNanos();
                                start.set(SystemClock.elapsedRealtimeNanos());
                                a.findViewById(android.R.id.content).findViewWithTag("wallet:" + editor).performClick();
                                listener.set(SystemClock.elapsedRealtimeNanos() - start.get());
                                cpu.set(Debug.threadCpuTimeNanos() - initialCpu);
                            });
                            assertTrue("Editor never drew", drawn.await(5, TimeUnit.SECONDS));
                            opens.put(new JSONObject().put("editor", editor).put("round", round)
                                    .put("listener_ms", listener.get() / 1000000.0)
                                    .put("listener_cpu_ms", cpu.get() / 1000000.0)
                                    .put("first_draw_ms", (firstDraw.get() - start.get()) / 1000000.0)
                                    .put("focus_class", focus.get()));
                            instrument.waitForIdleSync();
                            page.onActivity(a -> a.findViewById(R.id.button_back).performClick());
                        }
                    }
                    report.put(saved ? "synthetic_saved" : "disconnected", opens);
                }
            }
            String name = InstrumentationRegistry.getArguments().getString("walletReport", "wallet-navigation.json");
            assertTrue(name.matches("[a-zA-Z0-9_-]+\\.json"));
            try (Writer out = new OutputStreamWriter(context.openFileOutput(name, 0), StandardCharsets.UTF_8)) {
                out.write(report.toString(2));
            }
        } finally {
            for (Map.Entry<SharedPreferences, Map<String, ?>> item : before.entrySet())
                SharedPreferenceTestRestore.restore(item.getKey(), item.getValue());
            ControllerPinManager.enterSubMode();
        }
    }
}
