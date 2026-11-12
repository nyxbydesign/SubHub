package com.subhub.app.fullaccess;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.LinearLayout;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import com.subhub.app.R;
import com.subhub.app.people.InternalSubData;
import com.subhub.app.people.PersonUi;
import com.subhub.app.people.ProfileStore;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.security.HardcoreModeManager;
import com.subhub.app.util.AsyncUiScope;
import com.subhub.app.util.StateToggle;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.BooleanSupplier;

/** Inline profile switch. Only an explicit enable starts the ordered Android permission flow. */
public final class FullAccessSetup {
    private static final List<String> STEPS = List.of("contacts", "notifications", "sms", "apps", "files", "screen");
    private final AppCompatActivity host;
    private final Context app;
    private final LinearLayout panel;
    private final BooleanSupplier allowed;
    private final DemoFullAccess demo;
    private final AsyncUiScope work;
    private final ActivityResultLauncher<String> permission;
    private final ActivityResultLauncher<Intent> settings;
    private final ActivityResultLauncher<String[]> export;
    private final ArrayDeque<String> remaining = new ArrayDeque<>();
    private boolean resumed;
    private String pending;
    private long flow = -1;
    private long exportEpoch = -1;
    private boolean launched;

    public FullAccessSetup(AppCompatActivity host, LinearLayout parent, boolean demo, BooleanSupplier allowed, Bundle state) {
        this.host = host;
        this.app = host.getApplicationContext();
        this.allowed = allowed;
        this.demo = demo ? new DemoFullAccess(app, InternalSubData.get(host).acknowledged().generation,
                ControllerPinManager.domWorkspaceRevision()) : null;
        panel = PersonUi.column(host);
        parent.addView(panel, new LinearLayout.LayoutParams(-1, -2));
        work = AsyncUiScope.forPage(host);
        permission = host.registerForActivityResult(new ActivityResultContracts.RequestPermission(), this::returned);
        settings = host.registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), ignored -> {
            String step = pending;
            returned(switch (step == null ? "" : step) {
                case "files" -> FullAccessFiles.deviceStorageGranted(app);
                case "notifications" -> FullAccessDataSources.hasNotificationAccess(app);
                default -> true;
            });
        });
        export = host.registerForActivityResult(new ActivityResultContracts.OpenDocument(), selected -> {
            long expected = exportEpoch;
            exportEpoch = -1;
            if (selected == null) return;
            work.loadSerial("profile-full-access-write", () -> current(expected)
                    && (this.demo == null ? FullAccessDataSources.importSmsExport(app, selected)
                    : this.demo.importSms(selected)), saved -> {
                if (!saved) notice(R.string.full_access_setup_failed);
                refresh();
            }, failure -> notice(R.string.full_access_setup_failed));
        });
        if (state != null) {
            pending = state.getString("full_access_pending");
            launched = pending != null;
            flow = state.getLong("full_access_flow", -1);
            exportEpoch = state.getLong("full_access_export_epoch", -1);
            List<String> steps = state.getStringArrayList("full_access_steps");
            if (steps != null) remaining.addAll(steps);
        }
    }

    public void resume() {
        resumed = true;
        refresh();
        advance();
    }

    public void pause() { resumed = false; }

    public void saveState(Bundle state) {
        state.putString("full_access_pending", launched ? pending : null);
        state.putLong("full_access_flow", flow);
        state.putLong("full_access_export_epoch", exportEpoch);
        var queued = new java.util.ArrayList<>(remaining);
        if (pending != null && !launched) queued.add(0, pending);
        state.putStringArrayList("full_access_steps", queued);
    }

    private void refresh() {
        if (!resumed || !allowed.getAsBoolean()) return;
        work.load("profile-full-access-state", () -> {
            if (demo != null) return new State(demo.current(), demo.available(), false, "", "", 0,
                    FullAccessFiles.sharedRootsAvailable(app, true, 0));
            long epoch = FullAccessGrant.epoch(app);
            return new State(epoch >= 0, FullAccessGrant.eligible(app),
                    new HardcoreModeManager(app).isEnabled(), FullAccessGrant.pairedDom(app),
                    FullAccessGrant.pairedDomKey(app), epoch, FullAccessFiles.sharedRootsAvailable(app, false, epoch));
        }, state -> {
            if (resumed && allowed.getAsBoolean()) render(state);
        }, failure -> { if (resumed) notice(R.string.full_access_unavailable); });
    }

    private void render(State state) {
        panel.removeAllViews();
        StateToggle toggle = (StateToggle) host.getLayoutInflater().inflate(R.layout.view_form_toggle, panel, false);
        toggle.setText(R.string.full_access_title);
        toggle.setTag("full_access_toggle");
        toggle.setChecked(state.enabled);
        toggle.setEnabled((state.enabled || state.eligible) && pending == null && remaining.isEmpty()
                && !work.isPending("profile-full-access-switch"));
        toggle.setOnCheckedChangeListener((button, next) -> {
            toggle.setEnabled(false);
            if (next) confirm(state);
            else switchGrant(state, false);
        });
        panel.addView(toggle, new LinearLayout.LayoutParams(-1, -2));
        if (!state.enabled && !state.eligible && demo == null)
            PersonUi.text(panel, host.getString(state.hardcore ? R.string.full_access_pair_needed
                    : R.string.full_access_hardcore_needed), false);
        if (state.enabled && pending == null && remaining.isEmpty() && !state.sharedRoots) {
            if (FullAccessFiles.supportsSharedRoots()) {
                PersonUi.action(panel, R.string.blackmail_files_enable, () -> {
                    if (!resumed || !allowed.getAsBoolean() || pending != null || !remaining.isEmpty()) return;
                    flow = state.epoch;
                    remaining.add("files");
                    advance();
                    refresh();
                }, false);
            } else {
                PersonUi.text(panel, host.getString(R.string.blackmail_files_android_10), false);
            }
        }
        if (state.enabled && pending == null && remaining.isEmpty() && !FullAccessDataSources.hasSmsAccess(app)) {
            PersonUi.action(panel, demo == null ? R.string.full_access_import_sms : R.string.demo_full_access_sms_import,
                    () -> {
                        if (!resumed || !allowed.getAsBoolean() || exportEpoch >= 0) return;
                        exportEpoch = state.epoch;
                        try { export.launch(new String[]{"text/plain"}); }
                        catch (RuntimeException unavailable) {
                            exportEpoch = -1;
                            notice(R.string.full_access_unavailable);
                        }
                    }, false);
        }
    }

    private void confirm(State state) {
        if (!resumed || !allowed.getAsBoolean() || !state.eligible) { refresh(); return; }
        new AlertDialog.Builder(host)
                .setTitle(demo == null ? host.getString(R.string.full_access_grant_title, state.paired)
                        : host.getString(R.string.demo_full_access_enable_title))
                .setMessage(demo == null ? R.string.full_access_grant_disclosure : R.string.demo_full_access_enable_disclosure)
                .setNegativeButton(android.R.string.cancel, (dialog, which) -> refresh())
                .setPositiveButton(demo == null ? R.string.full_access_share : R.string.role_enabled,
                        (dialog, which) -> switchGrant(state, true))
                .setOnCancelListener(dialog -> refresh())
                .show();
    }

    private void switchGrant(State state, boolean next) {
        if (!resumed || !allowed.getAsBoolean()) return;
        work.loadSerial("profile-full-access-switch", () -> {
            if (!allowed.getAsBoolean()) return -1L;
            if (demo != null) return demo.setEnabled(next) ? 0L : -1L;
            if (!ProfileStore.mayEdit(app, false)) return -1L;
            if (!next) return FullAccessGrant.disable(app) ? 0L : -1L;
            return FullAccessGrant.enable(app, state.pairKey) ? FullAccessGrant.epoch(app) : -1L;
        }, epoch -> {
            if (epoch < 0) notice(R.string.full_access_setup_failed);
            if (next && epoch >= 0) {
                flow = epoch;
                remaining.clear();
                remaining.addAll(STEPS);
                advance();
            }
            refresh();
        }, failure -> {
            notice(R.string.full_access_setup_failed);
            refresh();
        });
    }

    private void advance() {
        if (!resumed || !allowed.getAsBoolean() || pending != null
                || work.isPending("profile-full-access-write") || remaining.isEmpty()) return;
        String step = remaining.removeFirst();
        pending = step;
        long expected = flow;
        work.load("profile-full-access-advance", () -> current(expected)
                && (demo == null || !"files".equals(step) || !FullAccessFiles.supportsSharedRoots()
                || FullAccessFiles.prepareDemoStorageRequest(app)), permitted -> {
            if (!permitted || !allowed.getAsBoolean()) {
                pending = null;
                remaining.clear();
                refresh();
            } else if (resumed) launch(step);
            else {
                pending = null;
                remaining.addFirst(step);
            }
        }, failure -> {
            pending = null;
            remaining.clear();
            refresh();
        });
    }

    private void launch(String step) {
        try {
            launched = true;
            switch (step) {
                case "contacts" -> {
                    if (granted(Manifest.permission.READ_CONTACTS)) returned(true);
                    else permission.launch(Manifest.permission.READ_CONTACTS);
                }
                case "notifications" -> {
                    if (FullAccessDataSources.hasNotificationAccess(app)) returned(true);
                    else settings.launch(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
                }
                case "sms" -> {
                    if (FullAccessDataSources.hasSmsAccess(app)) returned(true);
                    else if (app.getPackageManager().hasSystemFeature(PackageManager.FEATURE_TELEPHONY)
                            && FullAccessDataSources.canRequestSmsPermission(app))
                        permission.launch(Manifest.permission.READ_SMS);
                    else returned(false);
                }
                case "apps" -> {
                    if (Build.VERSION.SDK_INT < 28 || FullAccessDataSources.hasUsageAccess(app)) returned(true);
                    else settings.launch(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
                }
                case "files" -> launchStoragePermission();
                case "screen" -> {
                    if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS))
                        permission.launch(Manifest.permission.POST_NOTIFICATIONS);
                    else if (!FullAccessScreen.screenAlertsAvailable(app))
                        settings.launch(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(Settings.EXTRA_APP_PACKAGE, app.getPackageName()));
                    else returned(true);
                }
                default -> returned(false);
            }
        } catch (RuntimeException unavailable) {
            returned(false);
        }
    }

    private boolean granted(String permission) {
        return app.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    private void launchStoragePermission() {
        if (FullAccessFiles.deviceStorageGranted(app)) {
            returned(true);
        } else if (Build.VERSION.SDK_INT >= 30) {
            try { settings.launch(FullAccessFiles.broadStorageIntent(app)); }
            catch (ActivityNotFoundException unavailable) {
                settings.launch(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            }
        } else if (Build.VERSION.SDK_INT <= 28) {
            permission.launch(Manifest.permission.READ_EXTERNAL_STORAGE);
        } else {
            returned(false);
        }
    }

    private void returned(boolean granted) {
        String step = pending;
        if (step == null) return;
        pending = null;
        launched = false;
        if (granted && "files".equals(step) && demo == null) {
            long expected = flow;
            saveScope(() -> FullAccessFiles.approvePairedSharedRoots(app, expected));
        } else if ("apps".equals(step) || granted && ("contacts".equals(step) || "sms".equals(step) || "notifications".equals(step))) {
            saveScope(() -> demo == null ? FullAccessDataSources.enableScope(app, step)
                    : !"notifications".equals(step) || demo.enableNotifications());
        } else finishedStep();
    }

    private void saveScope(Callable<Boolean> action) {
        long expected = flow;
        work.loadSerial("profile-full-access-write", () -> current(expected) && action.call(),
                ignored -> finishedStep(), failure -> finishedStep());
    }

    private boolean current(long expected) {
        return expected >= 0 && allowed.getAsBoolean()
                && (demo == null ? ProfileStore.mayEdit(app, false) && expected == FullAccessGrant.epoch(app) : demo.current());
    }

    private void finishedStep() {
        if (remaining.isEmpty()) flow = -1;
        advance();
        refresh();
    }

    private void notice(int message) {
        if (resumed) Toast.makeText(host, message, Toast.LENGTH_SHORT).show();
    }

    private record State(boolean enabled, boolean eligible, boolean hardcore, String paired, String pairKey,
            long epoch, boolean sharedRoots) { }
}
