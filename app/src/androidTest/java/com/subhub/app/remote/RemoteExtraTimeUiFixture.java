package com.subhub.app.remote;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.appmode.AppModeManager;
import com.subhub.app.appmode.AppTimerManager;
import com.subhub.app.appmode.RuntimeSessionIdentity;
import com.subhub.app.notifications.NotificationPreferences;
import com.subhub.app.people.People;
import com.subhub.app.people.PersonId;
import com.subhub.app.penance.PenanceManager;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.security.DeviceRolePreferences;
import com.subhub.app.security.KeyholderProvider;
import com.subhub.app.security.SubFeaturePolicy;
import com.subhub.app.settings.SettingsRepository;
import com.subhub.app.settings.SharedPreferenceTestRestore;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Controlled paired-domain fixture for real Activities on an owned clean guest.
 * Both screen roles use the real application Context and typed-snapshotted preferences;
 * their journals and authority databases retain distinct peer/generation scopes.
 * Transport qualification belongs to ExtraTimeWireAndroidTest.
 */
public final class RemoteExtraTimeUiFixture implements AutoCloseable {
    private static final String PIN = "2468";
    public PersonId subPerson;
    public String firstPackage, secondPackage;
    private final Context context;
    private final String prefix = "extra-time-ui-" + UUID.randomUUID();
    private final Map<String, Map<String, ?>> preferences = new LinkedHashMap<>();
    private final Map<Field, Object> peopleHooks = new LinkedHashMap<>();
    private final Map<Field, Object> controllerState = new LinkedHashMap<>();
    private final List<String> databases = new ArrayList<>();
    private final List<String> secretPreferences = new ArrayList<>();
    private final List<String> secretAliases = new ArrayList<>();
    private final List<RemoteFeature> owned = new ArrayList<>();
    private final List<RemoteStore> ownedStores = new ArrayList<>();
    private RemoteFeature original, dom, sub, selected;
    private RemoteStore registeredOriginal;
    private RemoteActivityRecorder.Sink originalRecorder;
    private Field singleton;
    private String domIdentity, subIdentity, generation;
    private Map<String, String> originalAcknowledgements;
    private boolean closed;

    private RemoteExtraTimeUiFixture(Context context) { this.context = context.getApplicationContext(); }

    public static RemoteExtraTimeUiFixture setupSub(Context context) throws Exception {
        RemoteExtraTimeUiFixture fixture = new RemoteExtraTimeUiFixture(context);
        try { fixture.setup(); return fixture; }
        catch (Exception | Error failure) {
            try { fixture.close(); } catch (Exception | Error cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    private void setup() throws Exception {
        assertEquals("Explicitly owned UI fixture only", "true",
                InstrumentationRegistry.getArguments().getString("extra_time_ui_fixture"));
        assertTrue("Never seed a physical device", android.os.Build.HARDWARE.contains("ranchu")
                || android.os.Build.HARDWARE.contains("goldfish"));
        assertFalse("Use only an owned inactive guest", new AppModeManager(context).isArmed());
        assertFalse(new AppModeManager(context).isAccessibilityEnabled());
        assertFalse(new com.subhub.app.security.HardcoreModeManager(context).isRequested());
        assertFalse(new com.subhub.app.security.HardcoreModeManager(context).isAdminActive());
        assertFalse(new com.subhub.app.penance.HardcoreAutoPayManager(context).isConfigured());
        original = RemoteFeature.get(context);
        assertTrue("Complete application migration before UI fixtures", original.recoveryReady());
        singleton = RemoteFeature.class.getDeclaredField("instance"); singleton.setAccessible(true);
        synchronized (RemoteFeature.class) { assertSame(original, singleton.get(null)); }
        for (String name : new String[]{SettingsRepository.PREFERENCES_NAME, SubFeaturePolicy.STORE,
                PenanceManager.PREFS_NAME, ExtraTimeStore.PREFS, "subhub_app_timer_usage",
                com.subhub.app.stats.StatsRepository.PREFS_NAME, BackgroundSyncPreferences.STORE,
                NotificationPreferences.STORE, "subhub_pack_state_v1", "subhub_home", "subhub_onboarding",
                "subhub_controller_auth", "subhub_hardcore_auto_pay"})
            preferences.put(name, new LinkedHashMap<>(context.getSharedPreferences(name, 0).getAll()));
        for (Field field : People.class.getDeclaredFields())
            if (Modifier.isStatic(field.getModifiers()) && !Modifier.isFinal(field.getModifiers())) {
                field.setAccessible(true); peopleHooks.put(field, field.get(null));
            }
        for (String name : new String[]{"domModeActive", "application", "localWorkspace", "workspaceRevision"}) {
            Field field = ControllerPinManager.class.getDeclaredField(name); field.setAccessible(true);
            controllerState.put(field, field.get(null));
        }
        Field recorder = RemoteActivityRecorder.class.getDeclaredField("installed"); recorder.setAccessible(true);
        originalRecorder = (RemoteActivityRecorder.Sink) recorder.get(null);
        registeredOriginal = registeredAuthority();
        assertSame(original.store, registeredOriginal);
        originalAcknowledgements = acknowledgements(original.store);
        People.installKeyholder(new KeyholderProvider() {
            public State state() { return State.NONE; }
            public String assignedDomName() { return ""; }
            public com.subhub.app.security.ControllerAuthenticator.Result verifyCode(String code) {
                return com.subhub.app.security.ControllerAuthenticator.Result.UNAVAILABLE;
            }
        });
        People.installRemoteAuthority(() -> false);
        People.installRemoteRepository(null);
        People.installLocalProjection(java.util.function.UnaryOperator.identity());
        assertTrue(context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME, 0).edit()
                .putString(DeviceRolePreferences.KEY_MODE, DeviceRolePreferences.Mode.BOTH.name()).commit());
        ControllerPinManager.initialize(context);
        assertTrue("Synthetic controller setup must be acknowledged", ControllerPinManager.setPin(context, PIN));
        chooseInstalledApps();
        seedSettings();
        domIdentity = RemoteProtocol.sha256((prefix + ":dom").getBytes(StandardCharsets.UTF_8));
        subIdentity = RemoteProtocol.sha256((prefix + ":sub").getBytes(StandardCharsets.UTF_8));
        RemoteStore ds = store("dom"), ss = store("sub");
        long now = System.currentTimeMillis();
        PairingInvite invite = PairingInvite.create(domIdentity, "fixture", now); generation = invite.id;
        ds.saveInvite(invite); ds.bindInvite(generation, subIdentity, invite.tokenHash(), now + 1);
        ss.acceptInvite(invite, "Morgan"); ds.approveInvite(generation, subIdentity, "Alex", now + 1);
        ds.activate(subIdentity, generation); ss.activate(domIdentity, generation);
        ds.putMetadata(subIdentity, generation, "extra_time_version", "1");
        ss.putMetadata(domIdentity, generation, "extra_time_version", "1");
        dom = feature(ds, domIdentity, "dom"); sub = feature(ss, subIdentity, "sub");
        subPerson = RemotePersonRepository.id(ds.peer(subIdentity, generation));
        switchSub();
        ExtraTimeRequests.Snapshot snapshot = subRequests().snapshot(System.currentTimeMillis());
        assertTrue(snapshot.canRequest); assertEquals(2, snapshot.apps.size()); assertEquals(100, snapshot.tributeCents);
        for (ExtraTimeRequests.App app : snapshot.apps) assertTrue(app.exhausted);
    }

    private void chooseInstalledApps() {
        List<String> selected = new ArrayList<>();
        for (String candidate : new String[]{"com.android.deskclock", "com.google.android.deskclock",
                "com.android.calendar", "com.google.android.calendar", "com.android.chrome",
                "com.google.android.calculator", "com.android.calculator2", "com.android.contacts"}) {
            try {
                ApplicationInfo info = context.getPackageManager().getApplicationInfo(candidate, 0);
                if (info.enabled && context.getPackageManager().getLaunchIntentForPackage(candidate) != null)
                    selected.add(candidate);
            } catch (PackageManager.NameNotFoundException unavailable) { /* Select another actually installed app. */ }
            if (selected.size() == 2) break;
        }
        assertEquals("Install two meaningful launcher apps for this screenshot fixture", 2, selected.size());
        firstPackage = selected.get(0); secondPackage = selected.get(1);
    }

    private void seedSettings() {
        Set<String> packages = new LinkedHashSet<>(List.of(firstPackage, secondPackage));
        assertTrue(context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME, 0).edit()
                .putBoolean(AppModeManager.KEY_ARMED, true).putBoolean(AppModeManager.KEY_ALL_APPS, false)
                .putStringSet(AppModeManager.KEY_INCLUDED_PACKAGES, packages)
                .putBoolean(AppTimerManager.KEY_PER_APP_ENABLED, true).putBoolean(AppTimerManager.KEY_TOTAL_ENABLED, false)
                .putInt(AppTimerManager.KEY_PER_APP_MINUTES, 5).putInt(AppTimerManager.KEY_TOTAL_MINUTES, 120)
                .putInt("app_timer_allowance_minutes:" + firstPackage, 5)
                .putInt("app_timer_allowance_minutes:" + secondPackage, 10).commit());
        assertTrue(context.getSharedPreferences(SubFeaturePolicy.STORE, 0).edit().putBoolean("limits", true)
                .putBoolean("wallet", true).putBoolean("censor", false).commit());
        assertTrue(context.getSharedPreferences(PenanceManager.PREFS_NAME, 0).edit().clear()
                .putBoolean(PenanceManager.KEY_RULE_PARTICIPATION_MIGRATED, true)
                .putBoolean("rule_new_detection_enabled", false).putBoolean("rule_extra_time_request_enabled", true)
                .putInt("rule_extra_time_request_cents", 100).putInt("daily_cap_cents", 1000)
                .putInt("weekly_cap_cents", 5000).putInt("mercy_minutes", 10).commit());
        assertTrue(context.getSharedPreferences(ExtraTimeStore.PREFS, 0).edit().clear().commit());
        assertTrue(context.getSharedPreferences("subhub_app_timer_usage", 0).edit().clear()
                .putString("day", LocalDate.now().toString()).putString("history_epoch", prefix)
                .putString("history_zone", java.time.ZoneId.systemDefault().getId())
                .putLong("package_used_ms:" + firstPackage, 5 * 60_000L)
                .putLong("package_used_ms:" + secondPackage, 10 * 60_000L)
                .putLong("total_used_ms", 15 * 60_000L).commit());
        // Fixture decisions remain visible in-app without posting OS notifications or scheduling work.
        assertTrue(context.getSharedPreferences(NotificationPreferences.STORE, 0).edit()
                .putBoolean(NotificationPreferences.key(NotificationPreferences.Role.DOM, NotificationPreferences.Event.EXTRA_TIME_REQUESTS), false)
                .putBoolean(NotificationPreferences.key(NotificationPreferences.Role.SUB, NotificationPreferences.Event.EXTRA_TIME_REQUESTS), false).commit());
        assertTrue(context.getSharedPreferences(BackgroundSyncPreferences.STORE, 0).edit().putBoolean("enabled", false).commit());
    }

    private RemoteStore store(String side) {
        String name = prefix + "-" + side + ".db"; databases.add(name);
        RemoteStore value = new RemoteStore(context, name); ownedStores.add(value); return value;
    }

    private RemoteFeature feature(RemoteStore store, String identity, String side) throws Exception {
        String secretPreference = prefix + "-" + side + "-secrets", alias = prefix + "." + side;
        secretPreferences.add(secretPreference); secretAliases.add(alias);
        RemoteFeature value = new RemoteFeature(context, store, new RemoteSecrets(context, secretPreference, alias),
                PairingLifecycleClock.source(context), new RemoteFeature.BackgroundScheduling() {
                    public void synchronize(Context selected, boolean needed) { }
                    public void kick(Context selected) { }
                });
        owned.add(value);
        Field leases = RemoteFeature.class.getDeclaredField("leases"); leases.setAccessible(true);
        assertEquals(1, ((AtomicInteger) leases.get(value)).incrementAndGet());
        Field endpoint = RemoteFeature.class.getDeclaredField("endpoint"); endpoint.setAccessible(true);
        endpoint.set(value, new PeerTransport() {
            public String localIdentity() { return identity; }
            public String inviteRoute() throws IOException { throw new IOException("No transport in paired-domain UI fixture"); }
            public Connection connect(String peer, String route, long timeout) throws IOException { throw new IOException("No network in UI fixture"); }
            public Connection accept(long timeout) throws IOException { throw new IOException("No network in UI fixture"); }
            public void close() { }
        });
        assertTrue(value.recoveryReady());
        return value;
    }

    public void switchDom() throws Exception {
        select(dom);
        ControllerPinManager.enterSubMode();
        assertTrue("Authenticate the local Dom workspace", ControllerPinManager.verifyLocalDomWorkspace(context, PIN));
        assertTrue(ControllerPinManager.isDomWorkspaceUnlocked());
    }

    public void switchSub() throws Exception {
        select(sub); ControllerPinManager.enterSubMode();
        assertFalse(ControllerPinManager.isDomWorkspaceUnlocked());
    }

    private void select(RemoteFeature value) throws Exception {
        assertNotNull(value);
        synchronized (RemoteFeature.class) {
            Object before = singleton.get(null);
            assertTrue(before == original || owned.contains(before));
            singleton.set(null, value); selected = value;
        }
        synchronized (SubFeaturePolicy.LOCK) {
            RemoteStore current = registeredAuthority();
            assertTrue(current == registeredOriginal || current == dom.store || current == sub.store);
            SubFeaturePolicy.unregisterHardcoreAuthority(context, current);
            SubFeaturePolicy.registerHardcoreAuthority(context, value.store);
            assertSame(value.store, registeredAuthority());
        }
        RemotePersonRepository repository = new RemotePersonRepository(context, value);
        People.installRemoteRepository(repository); People.installRemoteAuthority(value::controlsLocal);
        People.installLocalProjection(repository::localProjection);
        People.installKeyholder(RemoteUi.keyholderProvider(value));
        RemoteActivityRecorder.install(value.activity);
    }

    /** The screen for the corresponding side must be closed before switching roles. */
    public ExtraTimeRequests subRequests() throws Exception {
        assertSame(sub, selected); return new ExtraTimeRequests(context, PersonId.LOCAL);
    }

    public ExtraTimeRequests domRequests() throws Exception {
        assertSame(dom, selected); return new ExtraTimeRequests(context, subPerson);
    }

    /** Production serialization/validation and durable domain application, deliberately without transport. */
    public void transfer() throws Exception {
        ExtraTimePeer.Endpoint domEndpoint = ExtraTimeRequests.endpoint(context, dom.store, domIdentity,
                dom.store.peer(subIdentity, generation));
        ExtraTimePeer.Endpoint subEndpoint = ExtraTimeRequests.endpoint(context, sub.store, subIdentity,
                sub.store.peer(domIdentity, generation));
        assertTrue(domEndpoint.enabled()); assertTrue(subEndpoint.enabled());
        subEndpoint.receive(domEndpoint.snapshot());
        domEndpoint.receive(subEndpoint.snapshot());
    }

    @Override public void close() throws Exception {
        if (closed) return; closed = true;
        List<Throwable> failures = new ArrayList<>();
        clean(failures, () -> {
            if (singleton != null) synchronized (RemoteFeature.class) {
                Object current = singleton.get(null);
                assertTrue(current == original || owned.contains(current)); singleton.set(null, original);
            }
        });
        clean(failures, () -> {
            if (registeredOriginal != null) synchronized (SubFeaturePolicy.LOCK) {
                RemoteStore current = registeredAuthority();
                if (current != registeredOriginal) {
                    assertTrue(owned.stream().anyMatch(feature -> feature.store == current));
                    SubFeaturePolicy.unregisterHardcoreAuthority(context, current);
                    SubFeaturePolicy.registerHardcoreAuthority(context, registeredOriginal);
                }
                assertSame(registeredOriginal, registeredAuthority());
            }
        });
        clean(failures, () -> {
            RemoteActivityRecorder.install(originalRecorder);
            for (Map.Entry<Field, Object> entry : peopleHooks.entrySet()) entry.getKey().set(null, entry.getValue());
        });
        Set<RemoteStore> closedStores = new java.util.HashSet<>();
        for (RemoteFeature feature : owned) clean(failures, () -> {
            feature.shutdownForTest(); closedStores.add(feature.store);
        });
        for (RemoteStore store : ownedStores) if (owned.stream().noneMatch(feature -> feature.store == store))
            clean(failures, () -> { store.close(); closedStores.add(store); });
        // A shutdown failure retains its owned files for recovery instead of deleting a live store.
        if (closedStores.containsAll(ownedStores)) {
            for (String name : databases) clean(failures, () -> { if (context.getDatabasePath(name).exists()) assertTrue(context.deleteDatabase(name)); });
            for (String name : secretPreferences) clean(failures, () -> context.deleteSharedPreferences(name));
            for (String alias : secretAliases) clean(failures, () -> {
                java.security.KeyStore keys = java.security.KeyStore.getInstance("AndroidKeyStore"); keys.load(null);
                if (keys.containsAlias(alias)) keys.deleteEntry(alias);
            });
        }
        for (Map.Entry<String, Map<String, ?>> entry : preferences.entrySet()) clean(failures, () ->
                SharedPreferenceTestRestore.restore(context.getSharedPreferences(entry.getKey(), 0), entry.getValue()));
        clean(failures, () -> InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> { }));
        clean(failures, () -> {
            synchronized (RuntimeSessionIdentity.LOCK) {
                for (Map.Entry<Field, Object> entry : controllerState.entrySet()) entry.getKey().set(null, entry.getValue());
            }
            for (Map.Entry<String, Map<String, ?>> entry : preferences.entrySet())
                assertEquals(entry.getKey(), entry.getValue(), context.getSharedPreferences(entry.getKey(), 0).getAll());
            if (original != null) {
                assertSame(original, RemoteFeature.get(context)); assertSame(original.store, registeredAuthority());
                assertEquals(originalAcknowledgements, acknowledgements(original.store));
                Field sessions = RemoteFeature.class.getDeclaredField("sessions"); sessions.setAccessible(true);
                assertFalse(((java.util.concurrent.ThreadPoolExecutor) sessions.get(original)).isShutdown());
            }
        });
        if (!failures.isEmpty()) {
            Throwable failure = failures.get(0);
            for (int i = 1; i < failures.size(); i++) failure.addSuppressed(failures.get(i));
            if (failure instanceof Exception) throw (Exception) failure;
            if (failure instanceof Error) throw (Error) failure;
            throw new AssertionError(failure);
        }
    }

    private RemoteStore registeredAuthority() throws Exception {
        Field field = SubFeaturePolicy.class.getDeclaredField("authorities"); field.setAccessible(true);
        return (RemoteStore) ((Map<?, ?>) field.get(null)).get(context);
    }

    private static Map<String, String> acknowledgements(RemoteStore store) {
        Map<String, String> result = new TreeMap<>();
        try (android.database.Cursor rows = store.getReadableDatabase().query("remote_metadata",
                new String[]{"peer", "generation", "value"}, "name=?", new String[]{"hardcore_start_review_ack"}, null, null, null)) {
            while (rows.moveToNext()) result.put(rows.getString(0) + ":" + rows.getString(1), rows.getString(2));
        }
        return result;
    }

    private interface Cleanup { void run() throws Throwable; }
    private static void clean(List<Throwable> failures, Cleanup step) {
        try { step.run(); } catch (Throwable failure) { failures.add(failure); }
    }
}
