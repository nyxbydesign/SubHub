package com.subhub.app.remote;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.matcher.ViewMatchers.withTagValue;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static com.subhub.app.NativeUiActions.revealAboveNavigation;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.*;

import android.Manifest;
import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.PermissionInfo;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.service.notification.StatusBarNotification;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.TextView;
import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.filters.SdkSuppress;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.R;
import com.subhub.app.UiAutomationStateRule;
import com.subhub.app.configuration.LegacyPackMigration;
import com.subhub.app.people.People;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.security.DeviceRolePreferences;
import com.subhub.app.security.KeyholderPresentationAndroidTest;
import com.subhub.app.security.SubFeaturePolicy;
import com.subhub.app.settings.SettingsRepository;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.RuleChain;

/** Actual Activity and OS service on an owned guest; no permission grants or ten-minute wall wait. */
@SdkSuppress(minSdkVersion = 31)
public final class PairingForegroundServiceAndroidTest {
    @Rule public final RuleChain rules = RuleChain
            .outerRule(new KeyholderPresentationAndroidTest.IntegrationFixture())
            .around(new UiAutomationStateRule());
    private Context target;
    private RemoteFeature original, fixture;
    private Field singleton, application;
    private Object originalApplication;
    private RemoteStore originalAuthority;
    private boolean published, authorityInstalled;
    private final List<Owned> owned = new ArrayList<>();
    private OffsetClock clock;
    private File evidence;
    private ActivityScenario<PairingActivity> page;
    private Map<String, ?> originalConsent;
    private boolean drawerLimitationRecorded;

    private final class Owned {
        final String prefix = "pairing-fgs-" + UUID.randomUUID() + "-";
        final String alias = "subhub.test." + prefix;
        String database;
        LinkingWitness witness;
        boolean ownsWrappingAlias;
        final Set<String> preferences = Collections.newSetFromMap(new ConcurrentHashMap<>());
        final File directory = new File(target.getCacheDir(), prefix + "files");
        final Context context = new ContextWrapper(target) {
            @Override public Context getApplicationContext() { return this; }
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                preferences.add(name); return target.getSharedPreferences(prefix + name, mode);
            }
            @Override public File getFilesDir() { return child("files"); }
            @Override public File getNoBackupFilesDir() { return child("no-backup"); }
            @Override public File getCacheDir() { return child("cache"); }
            private File child(String name) {
                File child = new File(directory, name);
                if (!child.isDirectory() && !child.mkdirs()) throw new IllegalStateException("Owned fixture directory unavailable");
                return child;
            }
        };
        RemoteStore store;
        RemoteFeature endpoint;
        Owned() { owned.add(this); }
        void open(OffsetClock source) throws Exception {
            assertTrue(LegacyPackMigration.recoverAndMigrate(context));
            assertTrue(LegacyPackMigration.isReady(context));
            assertTrue(DeviceRolePreferences.set(context, DeviceRolePreferences.Mode.BOTH));
            assertEquals(DeviceRolePreferences.Mode.BOTH, DeviceRolePreferences.get(context));
            assertTrue(ControllerPinManager.setPin(context, "624871"));
            assertTrue(ControllerPinManager.verify(context, "624871"));
            // Real native transport, with no relay dependency or substituted endpoint.
            assertTrue(context.getSharedPreferences("subhub_peer_transport", 0).edit()
                    .putStringSet("relay_urls", Collections.emptySet()).commit());
            store = FormalRelationshipCoordinator.createOwnedFixture(context);
            database = store.getDatabaseName();
            FormalRelationshipCoordinator.requireOwnedFixture(store);
            assertFalse(store.isApplicationDatabase());
            witness = LinkingWitness.attach(context, store);
            assertSame(witness, LinkingWitness.registered(store));
            assertEquals("verified", witness.readCurrent(store, () -> "verified"));
            assertTrue(FormalRelationshipCoordinator.application(context, store, witness)
                    .recoverySnapshot().snapshot.supported);
            KeyStore keys = KeyStore.getInstance("AndroidKeyStore"); keys.load(null);
            assertFalse(keys.containsAlias(alias)); ownsWrappingAlias = true;
            RemoteSecrets secrets = new RemoteSecrets(context, "secrets", alias);
            final Constructor<RemoteFeature> constructor;
            try {
                constructor = RemoteFeature.class.getDeclaredConstructor(Context.class, RemoteStore.class,
                        RemoteSecrets.class, PairingLifecycleClock.Source.class);
            } catch (NoSuchMethodException missing) {
                throw new AssertionError("Owned expiry fixture requires the pending package-private Feature clock constructor; no independent clock or wall-wait fallback", missing);
            }
            constructor.setAccessible(true); endpoint = constructor.newInstance(context, store, secrets, source);
            assertTrue("Migrate this Context before use; do not fake readiness", endpoint.recoveryReady());
            assertSame(witness, LinkingWitness.registered(endpoint.store));
        }
    }

    private static final class OffsetClock implements PairingLifecycleClock.Source {
        final PairingLifecycleClock.Source real;
        final AtomicLong offset = new AtomicLong();
        OffsetClock(Context context) { real = PairingLifecycleClock.source(context); }
        @Override public FormalRelationshipClock.Sample observe() { return shifted(real.observe()); }
        @Override public FormalRelationshipClock.Sample transition(FormalRelationshipClock.Sample observation) {
            return shifted(real.transition(new FormalRelationshipClock.Sample(
                    observation.wall - offset.get(), observation.elapsed - offset.get(), observation.boot)));
        }
        private FormalRelationshipClock.Sample shifted(FormalRelationshipClock.Sample realSample) {
            long amount = offset.get();
            return new FormalRelationshipClock.Sample(realSample.wall + amount, realSample.elapsed + amount, realSample.boot);
        }
    }

    @Before public void setup() throws Exception {
        target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        // Connect automation before any target Views and drain AccessibilityManager's connection state.
        InstrumentationRegistry.getInstrumentation().getUiAutomation();
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        assertNull("Use a guest without another pairing service owner", running());
        original = RemoteFeature.get(target);
        assertFalse("An established phone is not an owned pairing guest", original.controlsLocal());
        application = ControllerPinManager.class.getDeclaredField("application"); application.setAccessible(true);
        originalApplication = application.get(null);
        originalConsent = new LinkedHashMap<>(target.getSharedPreferences(SubFeaturePolicy.STORE, 0).getAll());
        singleton = RemoteFeature.class.getDeclaredField("instance"); singleton.setAccessible(true);
        synchronized (SubFeaturePolicy.LOCK) { originalAuthority = authorities().get(target.getApplicationContext()); }
        Owned primary = new Owned(); clock = new OffsetClock(primary.context); primary.open(clock); fixture = primary.endpoint;
        synchronized (RemoteFeature.class) {
            assertSame("Capture the original before replacement construction", original, singleton.get(null));
            singleton.set(null, fixture); published = true;
        }
        synchronized (SubFeaturePolicy.LOCK) {
            authorities().put(target.getApplicationContext(), primary.store); authorityInstalled = true;
        }
        People.installKeyholder(fixture.keyholder);
        People.installRemoteAuthority(fixture::controlsLocal);
        assertTrue(ControllerPinManager.setPin(target, "624871"));
        assertTrue(ControllerPinManager.verify(target, "624871"));
        assertTrue(DeviceRolePreferences.set(target, DeviceRolePreferences.Mode.BOTH));
        evidence = new File(target.getExternalFilesDir(null), "pairing-foreground-" + UUID.randomUUID());
        assertTrue(evidence.mkdirs());
    }

    @Test public void visibleUserStartRetainsOriginalOperationThenCancelReplacementActiveAndExpiryAreScoped() throws Throwable {
        try {
            metadata();
            launch(); tapCreate();
            RemoteFeature.PairingOperation first = current(); awaitRunning(first);
            assertNativeService(); assertCopyFits();
            PendingIntent staleFirst = cancelAction();
            long originalDeadline = first.deadlineWall;
            page.moveToState(Lifecycle.State.CREATED);
            await(() -> running() != null && running().foreground);
            RemoteFeature.PairingOperation background = status(first);
            assertEquals(first.id, background.id); assertEquals(originalDeadline, background.deadlineWall);
            page.moveToState(Lifecycle.State.RESUMED); page.recreate();
            assertEquals(first.id, current().id); assertEquals(originalDeadline, status(first).deadlineWall);
            tap("pairing_lifecycle_cancel");
            await(() -> !statusUnchecked(first).live);
            awaitStopped(); assertReleased();

            // A new visible Create is a new operation, never a reset of the old deadline.
            tapCreate(); RemoteFeature.PairingOperation replacement = current();
            assertNotEquals(first.id, replacement.id); awaitRunning(replacement);
            sendStale(staleFirst);
            assertTrue(status(replacement).live); awaitRunning(replacement);

            // Complete this same real operation through the counterpart's own native Feature workers.
            // The producer of the Android service remains the installed fixture singleton throughout.
            Owned counterpart = new Owned(); counterpart.open(clock);
            PairingInvite invite = fixture.submit(() -> fixture.restoreInvitation(replacement.generation)).get(10, TimeUnit.SECONDS);
            assertNotNull(invite);
            RemoteFeature.Enrollment enrollment = counterpart.endpoint.prepareEnrollment(invite.code()).get(10, TimeUnit.SECONDS);
            counterpart.endpoint.confirmEnrollment(enrollment).get(10, TimeUnit.SECONDS);
            RemoteFeature.PairingOperation accepting = counterpart.endpoint.currentPairingOperation(false).get(10, TimeUnit.SECONDS);
            assertNotNull(accepting);
            RemoteFeature.PairingStart start = counterpart.endpoint.preparePairingStart(accepting.id, accepting.generation, false).get(10, TimeUnit.SECONDS);
            try (PairingOperationLease remoteLease = counterpart.endpoint.acquirePairingLease(start.ticket()).get(10, TimeUnit.SECONDS)) {
                PendingIntent staleActive = cancelAction();
                await(() -> {
                    for (RemoteStore.Invitation candidate : fixture.store.invitations(System.currentTimeMillis()))
                        if (candidate.id.equals(invite.id) && candidate.candidate != null && !candidate.candidate.isEmpty()) return true;
                    return false;
                });
                fixture.approve(invite.id, enrollment.localIdentity, enrollment.fingerprint).get(10, TimeUnit.SECONDS);
                await(() -> {
                    RemoteStore.Peer peer = fixture.store.peer(enrollment.localIdentity, invite.id);
                    return peer != null && peer.state == RemoteStore.State.ACTIVE;
                });
                sendStale(staleActive);
                assertEquals(RemoteStore.State.ACTIVE, fixture.store.peer(enrollment.localIdentity, invite.id).state);
                assertFalse(fixture.cancelPairingOperation(replacement.id, replacement.generation, true, null).get(10, TimeUnit.SECONDS));
                assertSame(fixture, RemoteFeature.get(target));
            }
            awaitStopped();
            // Service ownership ends before committed ACTIVE work's existing 55-second drain.
            assertReleased(60_000);
            // The remote Sub activation can lock the process's local controller session.
            assertTrue(ControllerPinManager.verify(target, "624871"));
            page.close(); page = null; launch(); tapCreate();
            RemoteFeature.PairingOperation expiring = current(); awaitRunning(expiring);
            page.moveToState(Lifecycle.State.CREATED);
            clock.offset.addAndGet(PairingLifecycleClock.PUBLIC_MILLIS + 1);
            await(() -> !statusUnchecked(expiring).live);
            awaitStopped(); assertReleased();
            assertEquals("Expiry must never revoke the already ACTIVE relationship", RemoteStore.State.ACTIVE,
                    fixture.store.peer(enrollment.localIdentity, invite.id).state);
            assertEquals(expiring.deadlineWall, status(expiring).deadlineWall);
        } catch (Throwable failure) {
            try { retainFailure(); } catch (Throwable observerFailure) { failure.addSuppressed(observerFailure); }
            throw failure;
        } finally { if (page != null) { page.close(); page = null; } }
    }

    private void launch() {
        page = ActivityScenario.launch(new Intent(target, PairingActivity.class).putExtra("asDom", true));
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        page.onActivity(a -> assertTrue((a.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0));
    }
    private void tapCreate() throws Exception {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        onView(withText(R.string.remote_create_invite)).perform(revealAboveNavigation(), wholeTarget(), click());
        await(() -> currentUnchecked() != null);
    }
    private void tap(String tag) throws Exception {
        await(() -> hasTag(tag));
        onView(withTagValue(is(tag))).perform(revealAboveNavigation(), wholeTarget(), click());
    }
    private static androidx.test.espresso.ViewAction wholeTarget() {
        return new androidx.test.espresso.ViewAction() {
            @Override public org.hamcrest.Matcher<View> getConstraints() { return isDisplayed(); }
            @Override public String getDescription() { return "assert complete native tap bounds before input"; }
            @Override public void perform(androidx.test.espresso.UiController ui, View view) {
                ui.loopMainThreadUntilIdle();
                int[] origin = new int[2]; view.getLocationOnScreen(origin);
                Rect complete = new Rect(origin[0], origin[1], origin[0] + view.getWidth(), origin[1] + view.getHeight());
                Rect visible = new Rect(), window = new Rect();
                assertTrue(view.getGlobalVisibleRect(visible)); view.getWindowVisibleDisplayFrame(window);
                assertEquals("Clipped controls are not complete tap targets", complete, visible);
                assertTrue("Tap target must clear system insets", window.contains(complete));
            }
        };
    }
    private boolean hasTag(String tag) {
        boolean[] found = {false};
        page.onActivity(a -> found[0] = a.findViewById(android.R.id.content).findViewWithTag(tag) != null);
        return found[0];
    }
    private RemoteFeature.PairingOperation current() throws Exception { return fixture.currentPairingOperation(true).get(10, TimeUnit.SECONDS); }
    private RemoteFeature.PairingOperation currentUnchecked() {
        try { return current(); } catch (Exception e) { throw new AssertionError(e); }
    }
    private RemoteFeature.PairingOperation status(RemoteFeature.PairingOperation op) throws Exception {
        return fixture.pairingStatus(op.id, op.generation, op.asDom).get(10, TimeUnit.SECONDS);
    }
    private RemoteFeature.PairingOperation statusUnchecked(RemoteFeature.PairingOperation op) {
        try { return status(op); } catch (Exception e) { throw new AssertionError(e); }
    }
    private void awaitRunning(RemoteFeature.PairingOperation op) throws Exception {
        assertNotNull(op);
        await(() -> fixture.pairingServiceState(op.id, op.generation, op.asDom) == RemoteFeature.PairingServiceState.RUNNING);
        assertSame(fixture, RemoteFeature.get(target)); assertNativeService();
    }
    @SuppressWarnings("deprecation") private ActivityManager.RunningServiceInfo running() {
        for (ActivityManager.RunningServiceInfo info : target.getSystemService(ActivityManager.class).getRunningServices(100))
            if (info.service.equals(new ComponentName(target, PairingForegroundService.class))) return info;
        return null;
    }
    private void assertNativeService() throws Exception {
        assertNotNull(running()); assertTrue(running().foreground);
        String dump = shell("dumpsys activity services " + target.getPackageName() + "/" + PairingForegroundService.class.getName());
        java.util.regex.Matcher records = java.util.regex.Pattern.compile(
                "(?m)^[ \\t]*\\* ServiceRecord\\{[^\\r\\n]*\\}[ \\t]*\\r?$").matcher(dump);
        assertTrue("Exactly one OS service record must be exposed", records.find());
        String header = records.group(); int bodyStart = records.end();
        assertFalse("Multiple OS service records are ambiguous", records.find());
        ComponentName expected = new ComponentName(target, PairingForegroundService.class);
        java.util.regex.Matcher identity = java.util.regex.Pattern.compile("(?<!\\S)(?:"
                + java.util.regex.Pattern.quote(expected.flattenToShortString()) + "|"
                + java.util.regex.Pattern.quote(expected.flattenToString()) + ")(?=\\s|\\})").matcher(header);
        assertTrue("The selected OS record must be the exact pairing service", identity.find());
        assertFalse("The OS service identity must be unique", identity.find());
        String body = dump.substring(bodyStart);
        java.util.regex.Matcher foreground = java.util.regex.Pattern.compile("(?<!\\S)isForeground=([^\\s]+)").matcher(body);
        assertTrue("The OS service must expose its foreground state", foreground.find());
        assertEquals("true", foreground.group(1));
        assertFalse("Multiple foreground states are ambiguous", foreground.find());
        java.util.regex.Matcher type = java.util.regex.Pattern.compile(
                "(?<!\\S)(?:types|foregroundServiceType)=([^\\s]+)").matcher(body);
        assertTrue("Actual OS foreground type must be exposed", type.find());
        String value = type.group(1);
        assertFalse("Multiple foreground type values are ambiguous", type.find());
        assertTrue("The foreground type must be one bounded hexadecimal value", value.matches("0x[0-9a-fA-F]{1,8}"));
        long actualType = Long.parseLong(value.substring(2), 16);
        assertTrue("The OS foreground type must be nonzero", actualType != 0);
        assertEquals((long) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE, actualType);
    }
    private void metadata() throws Exception {
        PackageManager packages = target.getPackageManager();
        ServiceInfo service = packages.getServiceInfo(new ComponentName(target, PairingForegroundService.class), 0);
        assertFalse(service.exported); assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE, service.getForegroundServiceType());
        for (String permission : new String[] {Manifest.permission.FOREGROUND_SERVICE,
                "android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE", Manifest.permission.CHANGE_NETWORK_STATE}) {
            // Android 12/13 do not define the Android 14 type-specific normal permission yet.
            try { assertEquals(PermissionInfo.PROTECTION_NORMAL, packages.getPermissionInfo(permission, 0).getProtection()); }
            catch (PackageManager.NameNotFoundException absent) {
                assertEquals("android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE", permission);
                assertTrue(android.os.Build.VERSION.SDK_INT < 34); continue;
            }
            assertEquals(PackageManager.PERMISSION_GRANTED, packages.checkPermission(permission, target.getPackageName()));
        }
    }
    private PendingIntent cancelAction() throws Exception {
        NotificationManager manager = target.getSystemService(NotificationManager.class);
        NotificationChannel channel = manager.getNotificationChannel(PairingForegroundService.CHANNEL);
        assertNotNull(channel);
        if (PairingForegroundService.drawerAvailable(target)) {
            assertEquals(NotificationManager.IMPORTANCE_LOW, channel.getImportance());
            await(() -> {
                for (StatusBarNotification record : manager.getActiveNotifications()) {
                    Notification notification = record.getNotification();
                    if (PairingForegroundService.CHANNEL.equals(notification.getChannelId())
                            && notification.actions != null && notification.actions.length == 1) return true;
                }
                return false;
            });
        }
        for (StatusBarNotification record : manager.getActiveNotifications()) {
            Notification notification = record.getNotification();
            if (!PairingForegroundService.CHANNEL.equals(notification.getChannelId())) continue;
            assertTrue((notification.flags & Notification.FLAG_FOREGROUND_SERVICE) != 0);
            assertTrue((notification.flags & Notification.FLAG_ONGOING_EVENT) != 0);
            assertTrue((notification.flags & Notification.FLAG_ONLY_ALERT_ONCE) != 0);
            assertEquals(target.getString(R.string.remote_pairing), notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString());
            String text = notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString();
            assertFalse(text.contains("subhubapp:")); assertFalse(text.contains("pair?"));
            assertNotNull(notification.contentIntent); assertTrue(notification.contentIntent.isImmutable());
            if (notification.actions != null && notification.actions.length == 1) {
                PendingIntent cancel = notification.actions[0].actionIntent;
                assertTrue(cancel.isImmutable()); return cancel;
            }
        }
        assertFalse("An enabled drawer must expose the OS notification metadata", PairingForegroundService.drawerAvailable(target));
        await(() -> hasText(R.string.pairing_notifications_blocked));
        if (!drawerLimitationRecorded) {
            write("notification-limitation.txt", "Notification drawer blocked; retained notification metadata and PendingIntent delivery unavailable in this run.\n");
            drawerLimitationRecorded = true;
        }
        return null;
    }
    private boolean hasText(int resource) {
        boolean[] found = {false}; page.onActivity(a -> found[0] = contains(a.findViewById(android.R.id.content), a.getString(resource))); return found[0];
    }
    private static boolean contains(View view, String value) {
        if (view instanceof TextView && value.contentEquals(((TextView) view).getText())) return true;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
            if (contains(((ViewGroup) view).getChildAt(i), value)) return true;
        return false;
    }
    private void sendStale(PendingIntent intent) throws Exception {
        if (intent == null) return;
        try { intent.send(); } catch (PendingIntent.CanceledException retired) { /* Retired immutable action is also safe. */ }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        fixture.submit(() -> null).get(10, TimeUnit.SECONDS);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }
    private void awaitStopped() throws Exception { await(() -> running() == null); }
    private void assertReleased() throws Exception { assertReleased(15_000); }
    private void assertReleased(long timeoutMillis) throws Exception {
        Field leases = RemoteFeature.class.getDeclaredField("leases"); leases.setAccessible(true);
        Field endpoint = RemoteFeature.class.getDeclaredField("endpoint"); endpoint.setAccessible(true);
        await(() -> {
            try { return ((java.util.concurrent.atomic.AtomicInteger) leases.get(fixture)).get() == 0 && endpoint.get(fixture) == null; }
            catch (IllegalAccessException e) { throw new AssertionError(e); }
        }, timeoutMillis);
    }
    private void assertCopyFits() {
        page.onActivity(a -> {
            int width = a.findViewById(android.R.id.content).getWidth() - (int) (64 * a.getResources().getDisplayMetrics().density);
            assertTrue(width > 0);
            int[] copies = {R.string.pairing_channel, R.string.pairing_background, R.string.pairing_resume, R.string.pairing_starting,
                    R.string.pairing_running, R.string.pairing_notification_running, R.string.pairing_stopped,
                    R.string.pairing_unavailable, R.string.pairing_recovery, R.string.pairing_preserved,
                    R.string.pairing_notifications_blocked, R.string.pairing_notification_settings,
                    R.string.pairing_status_unavailable, R.string.pairing_no_operation};
            for (String language : new String[] {"en", "de"}) {
                android.content.res.Configuration config = new android.content.res.Configuration(a.getResources().getConfiguration());
                config.setLocale(Locale.forLanguageTag(language)); Context localized = a.createConfigurationContext(config);
                TextPaint paint = new TextPaint(); paint.setTextSize(16 * a.getResources().getDisplayMetrics().density
                        * Math.max(1.6f, a.getResources().getConfiguration().fontScale));
                for (int copy : copies) {
                    StaticLayout layout = StaticLayout.Builder.obtain(localized.getString(copy), 0, localized.getString(copy).length(), paint, width)
                            .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(true).build();
                    for (int line = 0; line < layout.getLineCount(); line++)
                        assertTrue("Lifecycle copy must wrap within its native region: " + language + "/" + copy, layout.getLineMax(line) <= width + 1);
                }
                String remaining = localized.getString(R.string.pairing_remaining, "10:00");
                StaticLayout layout = StaticLayout.Builder.obtain(remaining, 0, remaining.length(), paint, width).build();
                for (int line = 0; line < layout.getLineCount(); line++)
                    assertTrue("Remaining time must wrap within its native region: " + language, layout.getLineMax(line) <= width + 1);
            }
            View status = a.findViewById(android.R.id.content).findViewWithTag("pairing_lifecycle_status");
            assertNotNull(status); assertNativeTextFits((View) status.getParent());
        });
    }
    private static void assertNativeTextFits(View view) {
        if (view instanceof TextView) {
            TextView text = (TextView) view; Layout layout = text.getLayout(); assertNotNull(layout);
            int width = text.getWidth() - text.getCompoundPaddingLeft() - text.getCompoundPaddingRight();
            for (int line = 0; line < layout.getLineCount(); line++) {
                assertEquals(0, layout.getEllipsisCount(line)); assertTrue(layout.getLineMax(line) <= width + 1);
            }
            assertTrue(layout.getHeight() <= text.getHeight() - text.getCompoundPaddingTop() - text.getCompoundPaddingBottom());
        }
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
            assertNativeTextFits(((ViewGroup) view).getChildAt(i));
    }
    private static void await(BooleanSupplier condition) throws Exception {
        await(condition, 15_000);
    }
    private static void await(BooleanSupplier condition, long timeoutMillis) throws Exception {
        long end = SystemClock.uptimeMillis() + timeoutMillis;
        while (!condition.getAsBoolean() && SystemClock.uptimeMillis() < end) Thread.sleep(50);
        assertTrue("Bounded pairing observer did not converge", condition.getAsBoolean());
    }
    private static String shell(String command) throws Exception {
        ParcelFileDescriptor descriptor = InstrumentationRegistry.getInstrumentation().getUiAutomation().executeShellCommand(command);
        assertNotNull(descriptor);
        try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(descriptor); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] bytes = new byte[4096]; int count;
            while ((count = in.read(bytes)) != -1) { out.write(bytes, 0, count); assertTrue(out.size() <= 256 * 1024); }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
    private void retainFailure() throws Exception {
        if (evidence == null) return;
        write("services.txt", shell("dumpsys activity services " + target.getPackageName() + "/" + PairingForegroundService.class.getName()));
        if (page != null) page.onActivity(a -> {
            StringBuilder hierarchy = new StringBuilder(); hierarchy(a.findViewById(android.R.id.content), hierarchy);
            try { write("view-bounds.txt", hierarchy.toString()); } catch (Exception error) { throw new AssertionError(error); }
        });
        Bitmap pixels = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        if (pixels != null) try (FileOutputStream out = new FileOutputStream(new File(evidence, "failure.png"))) {
            // FLAG_SECURE remains enabled: these pixels may be redacted, never display-acceptance evidence.
            assertTrue(pixels.compress(Bitmap.CompressFormat.PNG, 100, out));
        } finally { if (pixels != null) pixels.recycle(); }
    }
    private static void hierarchy(View view, StringBuilder out) {
        Rect bounds = new Rect(); view.getGlobalVisibleRect(bounds);
        out.append(view.getClass().getName()).append(' ').append(view.getTag()).append(' ').append(bounds).append('\n');
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) hierarchy(((ViewGroup) view).getChildAt(i), out);
    }
    private void write(String name, String value) throws Exception {
        File file = new File(evidence, name); assertTrue("Do not overwrite retained evidence", file.createNewFile());
        try (FileOutputStream out = new FileOutputStream(file)) { out.write(value.getBytes(StandardCharsets.UTF_8)); }
    }
    @SuppressWarnings("unchecked") private static Map<Context, RemoteStore> authorities() throws Exception {
        Field field = SubFeaturePolicy.class.getDeclaredField("authorities"); field.setAccessible(true);
        return (Map<Context, RemoteStore>) field.get(null);
    }
    @After public void cleanup() throws Throwable {
        List<Throwable> failures = new ArrayList<>();
        boolean[] serviceStopped = {false};
        clean(failures, () -> { if (page != null) { page.close(); page = null; } });
        clean(failures, () -> {
            if (published) {
                target.stopService(new Intent(target, PairingForegroundService.class)); awaitStopped();
            }
            serviceStopped[0] = true;
        });
        clean(failures, () -> {
            if (published) synchronized (RemoteFeature.class) {
                assertSame(fixture, singleton.get(null)); singleton.set(null, original); published = false;
            }
        });
        // Mandatory restoration is independent of any earlier assertion or shutdown failure.
        clean(failures, () -> {
            if (authorityInstalled) synchronized (SubFeaturePolicy.LOCK) {
                if (originalAuthority == null) authorities().remove(target.getApplicationContext());
                else authorities().put(target.getApplicationContext(), originalAuthority);
                authorityInstalled = false;
            }
        });
        for (Owned entry : owned) {
            boolean[] closed = {false};
            boolean[] disposed = {false};
            clean(failures, () -> {
                assertTrue("Preserve owned recovery assets if the service has not stopped", serviceStopped[0]);
                if (entry.endpoint != null) entry.endpoint.shutdownForTest();
                else if (entry.store != null) { SubFeaturePolicy.unregisterHardcoreAuthority(entry.context, entry.store); entry.store.close(); }
                closed[0] = true;
            });
            clean(failures, () -> {
                if (entry.store != null) synchronized (SubFeaturePolicy.LOCK) {
                    SubFeaturePolicy.unregisterHardcoreAuthority(entry.context, entry.store);
                }
            });
            clean(failures, () -> {
                if (!closed[0]) return;
                if (entry.store != null) {
                    LinkingWitness attached = entry.witness != null ? entry.witness : LinkingWitness.attach(entry.context, entry.store);
                    attached.cleanupOwnedFixture(entry.store);
                    FormalRelationshipCoordinator.releaseOwnedFixture(entry.store);
                    assertTrue(target.deleteDatabase(entry.database));
                }
                disposed[0] = true;
            });
            clean(failures, () -> { if (disposed[0]) for (String name : entry.preferences) assertTrue(target.getSharedPreferences(entry.prefix + name, 0).edit().clear().commit()); });
            clean(failures, () -> { if (disposed[0] && entry.ownsWrappingAlias) { KeyStore keys = KeyStore.getInstance("AndroidKeyStore"); keys.load(null); keys.deleteEntry(entry.alias); } });
            clean(failures, () -> { if (disposed[0]) removeOwned(entry.directory, target.getCacheDir()); });
        }
        clean(failures, () -> { if (application != null) application.set(null, originalApplication); });
        clean(failures, () -> { if (originalConsent != null) assertEquals(originalConsent, target.getSharedPreferences(SubFeaturePolicy.STORE, 0).getAll()); });
        clean(failures, () -> {
            if (original != null) {
                assertSame(original, RemoteFeature.get(target));
                Field workers = RemoteFeature.class.getDeclaredField("sessions"); workers.setAccessible(true);
                assertFalse("Original endpoint must remain live", ((ThreadPoolExecutor) workers.get(original)).isShutdown());
            }
        });
        if (!failures.isEmpty()) {
            AssertionError failed = new AssertionError("Owned pairing fixture restoration incomplete; retain original evidence");
            for (Throwable failure : failures) failed.addSuppressed(failure); throw failed;
        }
    }
    private interface Cleanup { void run() throws Throwable; }
    private static void clean(List<Throwable> failures, Cleanup work) { try { work.run(); } catch (Throwable failure) { failures.add(failure); } }
    private static void removeOwned(File directory, File allowed) throws Exception {
        if (!directory.exists()) return;
        assertTrue(directory.getName().startsWith("pairing-fgs-"));
        assertEquals(allowed.getCanonicalFile(), directory.getCanonicalFile().getParentFile());
        removeLeaf(directory);
    }
    private static void removeLeaf(File file) throws Exception {
        assertFalse("Do not follow fixture symlinks", java.nio.file.Files.isSymbolicLink(file.toPath()));
        if (file.isDirectory()) { File[] children = file.listFiles(); assertNotNull(children); for (File child : children) removeLeaf(child); }
        assertTrue(file.delete());
    }
}
