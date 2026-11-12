package com.subhub.app.remote;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.matcher.RootMatchers.isDialog;
import static androidx.test.espresso.matcher.ViewMatchers.withTagValue;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.sqlite.SQLiteDatabase;
import android.os.SystemClock;
import android.provider.ContactsContract;

import androidx.core.content.FileProvider;

import androidx.test.core.app.ActivityScenario;
import androidx.test.filters.SdkSuppress;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.BySelector;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;

import com.subhub.app.R;
import com.subhub.app.people.ProfileEditorActivity;
import com.subhub.app.fullaccess.FullAccessGrant;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.security.DeviceRolePreferences;
import com.subhub.app.security.HardcoreModeManager;
import com.subhub.app.security.KeyholderPresentationAndroidTest;
import com.subhub.app.settings.SettingsRepository;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

/** Uses real Android permission dialogs and a single synthetic contact on a disposable guest. */
@SdkSuppress(minSdkVersion = 35, maxSdkVersion = 35)
public final class FullAccessContactsAndroidTest {
    private static final long WAIT = 12_000;
    private static final String CONTACT = "Full Access Test Contact";
    private static final String NUMBER = "2025550144";

    @Rule public final KeyholderPresentationAndroidTest.IntegrationFixture integration =
            new KeyholderPresentationAndroidTest.IntegrationFixture();
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final Context context = instrumentation.getTargetContext();

    @Test public void localGrantSharesOneSyntheticContactAndRevokeClosesTheLiveChannel() throws Exception {
        assertEquals("This fixture writes only to the reserved, read-only synthetic guest overlay",
                "true", InstrumentationRegistry.getArguments().getString("full_access_owned_guest"));
        HardcoreModeManager hardcore = new HardcoreModeManager(context);
        assertFalse(hardcore.isAdminActive());
        assertFalse(hardcore.isRequested());
        assertFalse(FullAccessGrant.isLocallyEnabled(context));
        int initialSms = context.checkSelfPermission(Manifest.permission.READ_SMS);
        assertEquals(PackageManager.PERMISSION_DENIED, context.checkSelfPermission(Manifest.permission.READ_CONTACTS));
        assertTrue(context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME, 0).edit()
                .putString(DeviceRolePreferences.KEY_MODE, DeviceRolePreferences.Mode.BOTH.name()).commit());
        ControllerPinManager.enterSubMode();
        UiDevice device = UiDevice.getInstance(instrumentation);

        try (SyntheticPair pair = new SyntheticPair(context)) {
            try (ActivityScenario<ProfileEditorActivity> setup = ActivityScenario.launch(ProfileEditorActivity.class)) {
                await("Focused SubHub setup before Device Admin", () -> {
                    AtomicBoolean focused = new AtomicBoolean();
                    setup.onActivity(activity -> focused.set(activity.hasWindowFocus()));
                    return focused.get();
                });
                setup.onActivity(activity -> {
                    hardcore.beginActivation();
                    activity.startActivity(hardcore.activationIntent());
                });
                UiObject2 approval = device.wait(Until.findObject(
                        By.res("com.android.settings", "action_button").enabled(true)), WAIT);
                try (FileOutputStream witness = new FileOutputStream(
                        new File(context.getCacheDir(), "full-access-admin-witness.xml"))) {
                    device.dumpWindowHierarchy(witness);
                }
                assertNotNull("Android Device Admin approval in " + device.getCurrentPackageName(), approval);
                var bounds = approval.getVisibleBounds();
                assertFalse("Android Device Admin approval is visible", bounds.isEmpty());
                assertTrue("Android Device Admin approval tap", device.click(bounds.centerX(), bounds.centerY()));
                await("Android Device Admin approval completed", hardcore::isAdminActive);
                assertTrue("Hardcore Mode is active", hardcore.finishActivation() && hardcore.isEnabled());
                awaitToggle(setup, false);
                assertEquals(PackageManager.PERMISSION_DENIED, context.checkSelfPermission(Manifest.permission.READ_CONTACTS));
                assertEquals(initialSms, context.checkSelfPermission(Manifest.permission.READ_SMS));
                try (FullAccessChannel before = pair.open("contacts")) {
                    assertEquals("disabled", before.query(page()).getString("status"));
                }
                onView(withTagValue(is("sub_feature:blackmail"))).perform(androidx.test.espresso.action.ViewActions.scrollTo(), click());
                onView(withText(R.string.full_access_share)).inRoot(isDialog()).perform(click());
                control(device, By.res(Pattern.compile(".*permissioncontroller:id/permission_allow_button")),
                        "Android Contacts permission Allow").click();
                await("Contacts permission granted through Android UI", () ->
                        context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED);
                await("Contacts source explicitly enabled", () ->
                        com.subhub.app.fullaccess.FullAccessDataSources.isScopeEnabled(context, "contacts"));
                awaitRemainingPermissions(device, setup);
                assertTrue(FullAccessGrant.isLocallyEnabled(context));
                assertEquals(initialSms, context.checkSelfPermission(Manifest.permission.READ_SMS));
                addSyntheticContact(device, setup);
            }

            verifySmsExportWithoutAndroidSmsPermission(pair);
            try (FullAccessChannel live = pair.open("contacts")) {
                JSONObject response = live.query(page());
                assertEquals("ok", response.getString("status"));
                JSONArray contacts = response.getJSONArray("items");
                assertEquals("Only the fixture contact should exist in the disposable guest", 1, contacts.length());
                JSONObject row = contacts.getJSONObject(0);
                assertEquals(CONTACT, row.getString("title"));
                assertTrue(row.getString("detail").replaceAll("[^0-9]", "").contains(NUMBER));

                assertTrue(FullAccessGrant.disable(context));
                assertFalse(FullAccessGrant.isLocallyEnabled(context));
                assertThrows(IOException.class, () -> live.query(page()));
            }
            try (FullAccessChannel after = pair.open("contacts")) {
                assertEquals("disabled", after.query(page()).getString("status"));
            }
            try (ActivityScenario<ProfileEditorActivity> setup = ActivityScenario.launch(ProfileEditorActivity.class)) {
                awaitToggle(setup, false);
                hardcore.disable();
                await("Disabling Hardcore removes Blackmail from the open Features grid", () -> {
                    AtomicBoolean hidden = new AtomicBoolean();
                    setup.onActivity(activity -> hidden.set(activity.findViewById(android.R.id.content)
                            .findViewWithTag("sub_feature:blackmail") == null));
                    return hidden.get();
                });
            }
            assertEquals(initialSms, context.checkSelfPermission(Manifest.permission.READ_SMS));
        } finally {
            FullAccessGrant.disable(context);
            if (hardcore.isAdminActive()) hardcore.disable();
        }
    }

    private void verifySmsExportWithoutAndroidSmsPermission(SyntheticPair pair) throws Exception {
        String sample = "The verification appointment moved to room 873241.";
        String later = "Meet me outside stop １７２４６８.";
        File folder = new File(context.getFilesDir(), "full-access-downloads");
        assertTrue(folder.isDirectory() || folder.mkdir());
        File transcript = File.createTempFile("full-access-fixture-", ".txt", folder);
        try {
            try (FileOutputStream output = new FileOutputStream(transcript)) {
                output.write((sample + "\n" + later).getBytes(StandardCharsets.UTF_8));
            }
            assertTrue(com.subhub.app.fullaccess.FullAccessDataSources.importSmsExport(context,
                    FileProvider.getUriForFile(context, context.getPackageName() + ".fullaccess.files", transcript)));
            try (FullAccessChannel sms = pair.open("sms")) {
                JSONObject page = sms.query(page());
                assertEquals("ok", page.getString("status"));
                assertEquals(sample, page.getJSONArray("items").getJSONObject(0).getString("title"));
                JSONObject conversations = sms.query(new JSONObject().put("op", "conversations"));
                assertEquals("ok", conversations.getString("status"));
                JSONArray rows = conversations.getJSONArray("items");
                assertEquals(1, rows.length());
                JSONObject summary = rows.getJSONObject(0);
                assertEquals(later, summary.getString("detail"));
                JSONObject thread = sms.query(new JSONObject().put("op", "thread").put("node", summary.getString("id")));
                assertEquals("ok", thread.getString("status"));
                JSONArray messages = thread.getJSONArray("items");
                assertEquals(2, messages.length());
                assertEquals(later, messages.getJSONObject(0).getString("detail"));
                assertEquals(sample, messages.getJSONObject(1).getString("detail"));
            }
            assertEquals(PackageManager.PERMISSION_DENIED, context.checkSelfPermission(Manifest.permission.READ_SMS));
        } finally {
            assertTrue(transcript.delete());
        }
    }

    private void awaitRemainingPermissions(UiDevice device, ActivityScenario<ProfileEditorActivity> scenario) {
        await("Inline switch after remaining Android prompts are dismissed", () -> {
            String pkg = device.getCurrentPackageName();
            if (pkg != null && !context.getPackageName().equals(pkg)) {
                UiObject2 deny = device.findObject(By.res(Pattern.compile(".*permissioncontroller:id/permission_deny_button")));
                if (deny != null) deny.click();
                else if (pkg.equals("com.android.settings") || pkg.endsWith(".documentsui")) device.pressBack();
                return false;
            }
            AtomicBoolean ready = new AtomicBoolean();
            scenario.onActivity(activity -> {
                android.widget.CompoundButton toggle = activity.findViewById(android.R.id.content)
                        .findViewWithTag("sub_feature:blackmail");
                ready.set(activity.hasWindowFocus() && toggle != null && toggle.isEnabled() && toggle.isChecked());
            });
            return ready.get();
        });
    }

    private void addSyntheticContact(UiDevice device, ActivityScenario<ProfileEditorActivity> setup) {
        Intent insert = new Intent(ContactsContract.Intents.Insert.ACTION)
                .setType(ContactsContract.RawContacts.CONTENT_TYPE)
                .putExtra(ContactsContract.Intents.Insert.NAME, CONTACT)
                .putExtra(ContactsContract.Intents.Insert.PHONE, NUMBER);
        setup.onActivity(activity -> activity.startActivity(insert));
        UiObject2 local = device.wait(Until.findObject(By.text(Pattern.compile("(?i)save locally|keep contacts on this device"))), 2_000);
        if (local != null) local.click();
        control(device, By.text(Pattern.compile("(?i)save|done")), "Android Contacts save action").click();
        await("Android Contacts persisted the fixture", () -> {
            try (var rows = context.getContentResolver().query(ContactsContract.Contacts.CONTENT_URI,
                    new String[]{ContactsContract.Contacts._ID}, null, null, null)) {
                return rows != null && rows.getCount() == 1;
            }
        });
    }

    private static JSONObject page() throws Exception {
        return new JSONObject().put("op", "page");
    }

    private static void awaitToggle(ActivityScenario<ProfileEditorActivity> scenario, boolean checked) {
        await("Visible Full Access setup state", () -> {
            AtomicBoolean ready = new AtomicBoolean();
            scenario.onActivity(activity -> {
                android.widget.CompoundButton toggle = activity.findViewById(android.R.id.content)
                        .findViewWithTag("sub_feature:blackmail");
                ready.set(activity.hasWindowFocus() && toggle != null && toggle.isEnabled()
                        && toggle.isChecked() == checked
                        && toggle.getParent().getParent() == activity.findViewById(android.R.id.content)
                                .findViewWithTag("feature_consent_grid"));
            });
            return ready.get();
        });
    }

    private static void await(String description, BooleanSupplier condition) {
        long deadline = SystemClock.elapsedRealtime() + WAIT;
        do {
            if (condition.getAsBoolean()) return;
            SystemClock.sleep(30);
        } while (SystemClock.elapsedRealtime() < deadline);
        assertTrue(description, condition.getAsBoolean());
    }

    private static UiObject2 control(UiDevice device, BySelector selector, String description) {
        UiObject2 action = device.wait(Until.findObject(selector), WAIT);
        assertNotNull(description + " in " + device.getCurrentPackageName(), action);
        return action;
    }

    private static final class SyntheticPair implements AutoCloseable {
        private final Context context;
        private final RemoteStore store;
        private final RemoteFeature original, receiver;
        private final PeerTransport dom, sub;
        private final ExecutorService worker = Executors.newSingleThreadExecutor();
        private final Future<?> accept;
        private final Field singleton;
        private final String generation = UUID.randomUUID().toString();

        SyntheticPair(Context context) throws Exception {
            this.context = context;
            original = RemoteFeature.get(context);
            assertTrue("Never replace a live relationship for this fixture", original.store.peers(false).isEmpty());
            store = FormalRelationshipCoordinator.createOwnedFixture(context);
            receiver = new RemoteFeature(context, store, new RemoteSecrets(context),
                    PairingLifecycleClock.source(context), new RemoteFeature.BackgroundScheduling() {
                        public void synchronize(Context selected, boolean needed) { }
                        public void kick(Context selected) { }
                    });
            dom = IrohPeerTransport.open(context, seed(), new String[0], 10_000);
            sub = IrohPeerTransport.open(context, seed(), new String[0], 10_000);
            SQLiteDatabase database = store.getWritableDatabase();
            database.beginTransaction();
            try {
                database.execSQL("INSERT INTO peers(peer,generation,name,local_role,state) VALUES(?,?,?,'SUB','ACTIVE')",
                        new Object[] {dom.localIdentity(), generation, "Synthetic Dom"});
                FormalRelationshipStore.mapPeer(database, dom.localIdentity(), generation, true);
                database.setTransactionSuccessful();
            } finally {
                database.endTransaction();
            }
            field("endpoint").set(receiver, sub);
            ((AtomicInteger) field("leases").get(receiver)).incrementAndGet();
            singleton = field("instance");
            synchronized (RemoteFeature.class) {
                assertSame(original, singleton.get(null));
                singleton.set(null, receiver);
            }
            Method listen = RemoteFeature.class.getDeclaredMethod("accept", PeerTransport.class);
            listen.setAccessible(true);
            accept = worker.submit(() -> listen.invoke(receiver, sub));
        }

        FullAccessChannel open(String scope) throws Exception {
            PeerTransport.Connection connection = dom.connect(sub.localIdentity(), sub.inviteRoute(), 10_000);
            try {
                connection.setReadTimeoutMillis(WAIT);
                RemoteProtocol.write(connection.output(), FullAccessChannel.frame(FullAccessChannel.OPEN)
                        .put("generation", generation).put("scope", scope));
                JSONObject response = RemoteProtocol.read(connection.input());
                FullAccessChannel.require(response, FullAccessChannel.OPENED);
                return new FullAccessChannel(connection, scope, response, () -> true, () -> {});
            } catch (Exception failure) {
                connection.close();
                throw failure;
            }
        }

        @Override public void close() throws Exception {
            try {
                FullAccessGrant.disable(context);
                receiver.shutdownForTest();
                accept.get(WAIT, TimeUnit.MILLISECONDS);
            } finally {
                try { dom.close(); }
                finally {
                    worker.shutdownNow();
                    synchronized (RemoteFeature.class) {
                        assertSame(receiver, singleton.get(null));
                        singleton.set(null, original);
                    }
                    FormalRelationshipCoordinator.releaseOwnedFixture(store);
                    context.deleteDatabase(store.getDatabaseName());
                }
            }
        }

        private static Field field(String name) throws Exception {
            Field field = RemoteFeature.class.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        }

        private static byte[] seed() {
            byte[] seed = new byte[32];
            new SecureRandom().nextBytes(seed);
            return seed;
        }
    }
}
