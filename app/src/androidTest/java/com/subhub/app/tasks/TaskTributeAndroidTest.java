package com.subhub.app.tasks;

import android.content.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.appmode.AppModeManager;
import com.subhub.app.penance.*;
import com.subhub.app.security.SubFeaturePolicy;
import com.subhub.app.settings.SettingsRepository;
import org.junit.*;
import org.junit.runner.RunWith;
import java.util.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class TaskTributeAndroidTest {
    private Context context;
    private final List<String> stores = new ArrayList<>();
    private final Context base = InstrumentationRegistry.getInstrumentation().getTargetContext();
    @Before public void setup() {
        String prefix = "tasks-test-" + UUID.randomUUID() + "-";
        context = new ContextWrapper(base) {
            @Override public Context getApplicationContext() { return this; }
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                String selected = prefix + name; if (!stores.contains(selected)) stores.add(selected);
                return base.getSharedPreferences(selected, mode);
            }
        };
        context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME, 0).edit().putBoolean(AppModeManager.KEY_ARMED, true).commit();
    }
    @After public void cleanup() { for (String name : stores) base.deleteSharedPreferences(name); }
    private PenanceManager enabled(int cents, int cap) {
        PenanceManager manager = new PenanceManager(context);
        manager.configure(true, Map.of(PenanceInfraction.TASK_FAILURE, cents), cap, cap, 10, 10, 1);
        return manager;
    }
    @Test public void atomicReceiptSurvivesReopenAndHistoryChangesWithoutDoubleTrigger() {
        PenanceManager manager = enabled(100, 500); long now = System.currentTimeMillis();
        assertEquals(100, manager.recordTaskFailure("pair:task:2026-10-28", now));
        assertEquals(0, new PenanceManager(context).recordTaskFailure("pair:task:2026-10-28", now));
        assertEquals(1, manager.snapshot(now).getEvents().size());
        assertEquals(PenanceInfraction.TASK_FAILURE, manager.snapshot(now).getEvents().get(0).getInfraction());
        assertEquals(100, manager.snapshot(now).getEvents().get(0).getAmountCents());
        context.getSharedPreferences(PenanceManager.PREFS_NAME, 0).edit().putString("events_v1", "").commit();
        assertEquals(0, new PenanceManager(context).recordTaskFailure("pair:task:2026-10-28", now));
        assertTrue(manager.snapshot(now).getEvents().isEmpty());
    }
    @Test public void disabledRuleWalletOptoutTasksOptoutAndDisarmedStateAreFinalZeroCentReceipts() {
        long now = System.currentTimeMillis(); PenanceManager manager = new PenanceManager(context);
        assertEquals(0, manager.recordTaskFailure("rule-off", now));
        manager = enabled(100, 500);
        assertEquals(0, manager.recordTaskFailure("rule-off", now));
        context.getSharedPreferences(SubFeaturePolicy.STORE, 0).edit().putBoolean("wallet", false).commit();
        assertEquals(0, manager.recordTaskFailure("wallet-off", now));
        context.getSharedPreferences(SubFeaturePolicy.STORE, 0).edit().putBoolean("wallet", true).putBoolean("tasks", false).commit();
        assertEquals(0, manager.recordTaskFailure("tasks-off", now));
        context.getSharedPreferences(SubFeaturePolicy.STORE, 0).edit().putBoolean("tasks", true).commit();
        context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME, 0).edit().putBoolean(AppModeManager.KEY_ARMED, false).commit();
        assertEquals(0, manager.recordTaskFailure("disarmed", now));
        context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME, 0).edit().putBoolean(AppModeManager.KEY_ARMED, true).commit();
        for (String receipt : List.of("rule-off", "wallet-off", "tasks-off", "disarmed")) assertEquals(0, manager.recordTaskFailure(receipt, now));
        assertTrue(manager.snapshot(now).getEvents().isEmpty());
    }
    @Test public void spendingCapsAndOrdinaryCorrectionWindowApplyWithoutPaymentState() {
        PenanceManager manager = enabled(100, 150); long now = System.currentTimeMillis();
        assertEquals(100, manager.recordTaskFailure("first", now));
        assertEquals(50, manager.recordTaskFailure("second", now));
        assertEquals(0, manager.recordTaskFailure("capped", now));
        assertEquals(2, manager.snapshot(now).getEvents().size());
        for (PenanceEvent event : manager.snapshot(now).getEvents()) {
            assertTrue(event.isInMercy(now)); assertEquals(PenanceEvent.Status.OPEN, event.getStatus());
        }
        assertEquals(PenanceManager.CheckoutMode.NONE, manager.getActiveCheckoutMode());
        assertEquals("", manager.getActiveOrderId());
        assertEquals(0, manager.recordInfraction(PenanceInfraction.TASK_FAILURE, 1, now));
    }
    @Test public void failedPersistenceAcknowledgementMustRecoverBeforeReceiptReturns() {
        enabled(100, 500);
        SharedPreferences real = context.getSharedPreferences(PenanceManager.PREFS_NAME, 0);
        java.util.concurrent.atomic.AtomicInteger attempts = new java.util.concurrent.atomic.AtomicInteger();
        SharedPreferences uncertain = (SharedPreferences) java.lang.reflect.Proxy.newProxyInstance(
                SharedPreferences.class.getClassLoader(), new Class<?>[]{SharedPreferences.class}, (proxy, method, args) -> {
                    if (!"edit".equals(method.getName())) return method.invoke(real, args);
                    SharedPreferences.Editor editor = real.edit();
                    return java.lang.reflect.Proxy.newProxyInstance(SharedPreferences.Editor.class.getClassLoader(),
                            new Class<?>[]{SharedPreferences.Editor.class}, (editorProxy, operation, values) -> {
                                Object result = operation.invoke(editor, values);
                                if ("commit".equals(operation.getName())) return attempts.incrementAndGet() > 2 && (Boolean) result;
                                return result instanceof SharedPreferences.Editor ? editorProxy : result;
                            });
                });
        Context faultContext = new ContextWrapper(context) {
            @Override public Context getApplicationContext() { return this; }
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                return PenanceManager.PREFS_NAME.equals(name) ? uncertain : super.getSharedPreferences(name, mode);
            }
        };
        PenanceManager manager = new PenanceManager(faultContext); long now = System.currentTimeMillis();
        assertThrows(IllegalStateException.class, () -> manager.recordTaskFailure("uncertain", now));
        assertEquals(1, manager.snapshot(now).getEvents().size());
        assertThrows(IllegalStateException.class, () -> manager.recordTaskFailure("uncertain", now));
        assertEquals(0, manager.recordTaskFailure("uncertain", now));
        assertEquals(3, attempts.get()); assertEquals(1, manager.snapshot(now).getEvents().size());
    }
}
