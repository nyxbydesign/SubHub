package com.subhub.app;

import static org.junit.Assert.*;

import android.app.Activity;
import android.app.Application;
import android.app.Instrumentation;
import android.os.Bundle;
import android.os.Looper;
import android.os.SystemClock;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.activity.ActivityEntry;
import com.subhub.app.activity.ActivityRepository;
import com.subhub.app.activity.ActivitySources;
import com.subhub.app.people.PersonId;
import com.subhub.app.relationships.RelationshipUi;
import com.subhub.app.security.KeyholderPresentationAndroidTest.IntegrationFixture;
import com.subhub.app.tasks.TasksActivity;
import com.subhub.app.util.AsyncUiScope;
import java.lang.ref.Reference;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.rules.ExternalResource;

/** Test-owned Activities drain before any shared presentation hooks or preferences are restored. */
public final class OwnedUiIntegrationFixture extends ExternalResource {
    private final boolean isolate;
    private final IntegrationFixture integration = new IntegrationFixture();
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final Set<Activity> activities = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<AsyncUiScope> scopes = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<ExecutorService> executors = Collections.newSetFromMap(new IdentityHashMap<>());
    private Application application;
    private ActivityRepository originalActivity;
    private RelationshipUi.Provider originalRelationships;
    private volatile Throwable captureFailure;
    private Throwable drainFailure;
    private boolean drained, registered;

    public OwnedUiIntegrationFixture() { this(true); }
    public OwnedUiIntegrationFixture(boolean isolate) { this.isolate = isolate; }

    private final Application.ActivityLifecycleCallbacks callbacks = new Application.ActivityLifecycleCallbacks() {
        @Override public void onActivityCreated(Activity activity, Bundle state) { capture(activity); }
        @Override public void onActivityStarted(Activity activity) { capture(activity); }
        @Override public void onActivityResumed(Activity activity) { capture(activity); }
        @Override public void onActivityPaused(Activity activity) { capture(activity); }
        @Override public void onActivityStopped(Activity activity) { capture(activity); }
        @Override public void onActivityDestroyed(Activity activity) { capture(activity); }
        @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { capture(activity); }
    };

    @Override protected void before() throws Throwable {
        if (isolate) {
            invokeIntegration("before");
            originalActivity = ActivitySources.repository();
            originalRelationships = RelationshipUi.provider();
            ActivitySources.install(new ActivityRepository() {
                @Override public boolean supports(PersonId person) { return false; }
                @Override public Page load(String before, int limit) { throw unexpected(); }
                @Override public Page locate(String key, int limit) { throw unexpected(); }
                @Override public List<ActivityEntry> recent(PersonId person, int limit) { throw unexpected(); }
                @Override public int unreadCount() { throw unexpected(); }
                @Override public void markRead(List<String> keys) { throw unexpected(); }
                private AssertionError unexpected() { return new AssertionError("Tasks fixture must not query the Activity feed"); }
            });
            RelationshipUi.install(RelationshipUi.unavailable());
        }
        application = (Application) instrumentation.getTargetContext().getApplicationContext();
        instrumentation.runOnMainSync(() -> {
            application.registerActivityLifecycleCallbacks(callbacks);
            registered = true;
        });
    }

    private void capture(Activity activity) {
        activities.add(activity);
        try {
            Field pages = AsyncUiScope.class.getDeclaredField("pages");
            pages.setAccessible(true);
            Object reference = ((Map<?, ?>) pages.get(null)).get(activity);
            if (reference instanceof Reference) {
                Object scope = ((Reference<?>) reference).get();
                if (scope != null) scopes.add((AsyncUiScope) scope);
            }
            // Diagnostics owns its export worker directly; do not touch application/shared pools.
            for (Field field : activity.getClass().getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers()) && ExecutorService.class.isAssignableFrom(field.getType())) {
                    field.setAccessible(true);
                    ExecutorService executor = (ExecutorService) field.get(activity);
                    if (executor != null) executors.add(executor);
                }
            }
            if (activity instanceof TasksActivity) {
                Field model = TasksActivity.class.getDeclaredField("model"); model.setAccessible(true);
                Object state = model.get(activity);
                if (state != null) {
                    Field worker = state.getClass().getDeclaredField("worker"); worker.setAccessible(true);
                    executors.add((ExecutorService) worker.get(state));
                }
            }
        } catch (ReflectiveOperationException failure) { captureFailure = failure; }
    }

    /** Call at the start of @After, before database/media cleanup or any shared provider restoration. */
    public void drainReaders() throws Exception {
        assertNotEquals("Reader drain must run off main", Looper.getMainLooper(), Looper.myLooper());
        if (drainFailure != null) throw new AssertionError("Earlier reader drain failed; retain isolated hooks", drainFailure);
        if (drained) return;
        try {
            instrumentation.runOnMainSync(() -> {
                for (Activity activity : new ArrayList<>(activities)) if (!activity.isDestroyed()) activity.finish();
            });
            long deadline = SystemClock.uptimeMillis() + 10_000;
            java.util.concurrent.atomic.AtomicBoolean destroyed = new java.util.concurrent.atomic.AtomicBoolean();
            do {
                instrumentation.waitForIdleSync();
                instrumentation.runOnMainSync(() -> destroyed.set(activities.stream().allMatch(Activity::isDestroyed)));
                if (!destroyed.get()) SystemClock.sleep(20);
            } while (!destroyed.get() && SystemClock.uptimeMillis() < deadline);
            assertTrue("Every owned Activity must be destroyed before restoring integration", destroyed.get());
            if (captureFailure != null) throw new AssertionError("Could not capture actual Activity readers", captureFailure);
            instrumentation.runOnMainSync(() -> {
                for (AsyncUiScope scope : scopes) for (String name : new String[]{"worker", "serialWorker"}) {
                    try {
                        Field field = AsyncUiScope.class.getDeclaredField(name); field.setAccessible(true);
                        ExecutorService executor = (ExecutorService) field.get(scope);
                        if (executor != null) executors.add(executor);
                    } catch (ReflectiveOperationException failure) { captureFailure = failure; }
                }
            });
            if (captureFailure != null) throw new AssertionError("Could not inspect captured readers", captureFailure);
            Set<ExecutorService> owned = Collections.newSetFromMap(new IdentityHashMap<>());
            owned.addAll(executors);
            for (ExecutorService executor : owned) {
                assertTrue("Destroyed Activity shuts down its actual reader", executor.isShutdown());
                assertTrue("Destroyed Activity drains its actual reader", executor.awaitTermination(
                        Math.max(1, deadline - SystemClock.uptimeMillis()), TimeUnit.MILLISECONDS));
            }
            instrumentation.waitForIdleSync();
            java.util.concurrent.atomic.AtomicBoolean quiet = new java.util.concurrent.atomic.AtomicBoolean();
            java.util.concurrent.atomic.AtomicBoolean unchanged = new java.util.concurrent.atomic.AtomicBoolean();
            instrumentation.runOnMainSync(() -> {
                quiet.set(activities.stream().allMatch(Activity::isDestroyed));
                unchanged.set(owned.equals(executors));
            });
            assertTrue("No Activity can appear after the reader drain", quiet.get());
            assertTrue("No new reader can appear after the drain", unchanged.get());
            drained = true;
        } catch (Exception | AssertionError failure) {
            drainFailure = failure;
            throw failure;
        } finally {
            if (registered) instrumentation.runOnMainSync(() -> {
                application.unregisterActivityLifecycleCallbacks(callbacks); registered = false;
            });
        }
    }

    @Override protected void after() {
        try {
            drainReaders();
            if (!isolate) return;
            // This gate also covers the original IntegrationFixture's unconditional finally blocks.
            try {
                RelationshipUi.install(originalRelationships);
                assertSame(originalRelationships, RelationshipUi.provider());
            }
            finally {
                try {
                    ActivitySources.install(originalActivity);
                    assertSame(originalActivity, ActivitySources.repository());
                }
                finally { invokeIntegration("after"); }
            }
        } catch (RuntimeException | Error failure) { throw failure; }
        catch (Throwable failure) { throw new AssertionError(failure); }
    }

    private void invokeIntegration(String name) throws Throwable {
        Method method = IntegrationFixture.class.getDeclaredMethod(name); method.setAccessible(true);
        try { method.invoke(integration); }
        catch (InvocationTargetException failure) { throw failure.getCause(); }
    }
}
