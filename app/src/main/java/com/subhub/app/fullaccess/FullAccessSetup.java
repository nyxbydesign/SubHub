package com.subhub.app.fullaccess;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import com.subhub.app.R;
import com.subhub.app.people.FeatureConsentView;
import com.subhub.app.people.InternalSubData;
import com.subhub.app.people.PersonUi;
import com.subhub.app.people.ProfileStore;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.security.HardcoreModeManager;
import com.subhub.app.settings.SettingsRepository;
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
    private final FeatureConsentView features;
    private final BooleanSupplier allowed;
    private final DemoFullAccess demo;
    private final HardcoreModeManager hardcore;
    private final SharedPreferences hardcoreSettings;
    private final SharedPreferences.OnSharedPreferenceChangeListener hardcoreChange = (settings, key) -> {
        if (key == null || HardcoreModeManager.KEY_REQUESTED.equals(key)) refresh();
    };
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
    private boolean notificationsDisclosed;
    private CompoundButton featureToggle;
    private AlertDialog confirmation;

    public FullAccessSetup(AppCompatActivity host, LinearLayout parent, FeatureConsentView features,
            BooleanSupplier allowed, Bundle state) {
        this.host = host;
        this.app = host.getApplicationContext();
        this.allowed = allowed;
        this.features = features;
        this.demo = features == null ? new DemoFullAccess(app, InternalSubData.get(host).acknowledged().generation,
                ControllerPinManager.domWorkspaceRevision()) : null;
        hardcore = demo == null ? new HardcoreModeManager(app) : null;
        hardcoreSettings = demo == null ? app.getSharedPreferences(SettingsRepository.PREFERENCES_NAME, 0) : null;
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
            // A restored setup may have started before the updated Notifications disclosure.
            notificationsDisclosed = state.getBoolean("full_access_notifications_disclosed");
            flow = state.getLong("full_access_flow", -1);
            exportEpoch = state.getLong("full_access_export_epoch", -1);
            List<String> steps = state.getStringArrayList("full_access_steps");
            if (steps != null) remaining.addAll(steps);
        }
    }

    public void resume() {
        resumed = true;
        if (hardcoreSettings != null) hardcoreSettings.registerOnSharedPreferenceChangeListener(hardcoreChange);
        refresh();
        advance();
    }

    public void pause() {
        resumed = false;
        if (hardcoreSettings != null) hardcoreSettings.unregisterOnSharedPreferenceChangeListener(hardcoreChange);
    }

    public void saveState(Bundle state) {
        state.putString("full_access_pending", launched ? pending : null);
        state.putBoolean("full_access_notifications_disclosed", notificationsDisclosed);
        state.putLong("full_access_flow", flow);
        state.putLong("full_access_export_epoch", exportEpoch);
        var queued = new java.util.ArrayList<>(remaining);
        if (pending != null && !launched) queued.add(0, pending);
        state.putStringArrayList("full_access_steps", queued);
    }

    private void refresh() {
        if (!resumed || !allowed.getAsBoolean()) return;
        if (!showable()) {
            hide();
            return;
        }
        work.load("profile-full-access-state", () -> {
            if (demo != null) return new State(demo.current(), demo.available(), "", "", 0,
                    FullAccessFiles.setupState(app, true, 0), DemoFullAccess.notificationChoice(app));
            long epoch = FullAccessGrant.epoch(app);
            return new State(epoch >= 0, FullAccessGrant.eligible(app), FullAccessGrant.pairedDom(app),
                    FullAccessGrant.pairedDomKey(app), epoch, FullAccessFiles.setupState(app, false, epoch), FullAccessDataSources.notificationChoice(app));
        }, state -> {
            if (!resumed || !allowed.getAsBoolean()) return;
            if (showable()) render(state);
            else hide();
        }, failure -> { if (resumed) notice(R.string.full_access_unavailable); });
    }

    private boolean showable() { return demo != null || hardcore.isEnabled(); }

    private void hide() {
        work.cancel("profile-full-access-state");
        if (featureToggle != null) {
            features.removeView((View) featureToggle.getParent());
            featureToggle = null;
        }
        if (confirmation != null) confirmation.dismiss();
        panel.removeAllViews();
        remaining.clear();
        pending = null;
        launched = false;
        notificationsDisclosed = false;
        flow = -1;
        exportEpoch = -1;
    }

    private void render(State state) {
        panel.removeAllViews();
        CompoundButton toggle;
        if (features != null) {
            if (featureToggle == null)
                featureToggle = features.editable("blackmail", R.string.full_access_title, state.enabled);
            toggle = featureToggle;
        } else {
            toggle = (StateToggle) host.getLayoutInflater().inflate(R.layout.view_form_toggle, panel, false);
            toggle.setText(R.string.full_access_title);
            toggle.setTag("full_access_toggle");
            panel.addView(toggle, new LinearLayout.LayoutParams(-1, -2));
        }
        toggle.setOnCheckedChangeListener(null);
        toggle.setChecked(state.enabled);
        toggle.setEnabled((state.enabled || state.eligible) && pending == null && remaining.isEmpty()
                && !work.isPending("profile-full-access-switch"));
        toggle.setOnCheckedChangeListener((button, next) -> {
            toggle.setEnabled(false);
            if (next) confirm(state);
            else switchGrant(state, false);
        });
        if (!state.enabled && !state.eligible && demo == null)
            PersonUi.text(panel, host.getString(R.string.full_access_pair_needed), false);
        if (state.enabled && pending == null && remaining.isEmpty()) {
            boolean reviewNotifications = state.notifications != null && state.notifications.textSince() == 0
                    && FullAccessDataSources.hasNotificationAccess(app);
            boolean reviewFiles = state.files == FullAccessFiles.Setup.PAIRED_REVIEW;
            if (reviewNotifications || reviewFiles) {
                int title = reviewNotifications && reviewFiles ? R.string.blackmail_access_review
                        : reviewNotifications ? R.string.blackmail_notifications_review : R.string.blackmail_files_review;
                PersonUi.action(panel, title, () -> reviewAccess(state, title, reviewNotifications, reviewFiles), false);
            }
            if (state.files == FullAccessFiles.Setup.ANDROID_PERMISSION)
                PersonUi.action(panel, R.string.blackmail_files_android_permission, () -> finishAccessReview(state, false, true), false);
            if (state.files == FullAccessFiles.Setup.ANDROID_10)
                PersonUi.text(panel, host.getString(R.string.blackmail_files_android_10), false);
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

    private void reviewAccess(State state, int title, boolean notifications, boolean files) {
        if (!resumed || !showable() || !allowed.getAsBoolean() || confirmation != null
                || work.isPending("profile-full-access-write")) return;
        String disclosure = notifications ? (demo == null
                ? host.getString(R.string.blackmail_notifications_review_pair, state.paired)
                : host.getString(R.string.blackmail_notifications_review_demo)) : "";
        if (files) {
            if (notifications) disclosure += "\n\n";
            disclosure += host.getString(R.string.blackmail_files_review_pair, state.paired);
        }
        confirmation = new AlertDialog.Builder(host)
                .setTitle(title)
                .setMessage(disclosure)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(demo == null ? R.string.full_access_share : R.string.role_enabled,
                        (dialog, which) -> finishAccessReview(state, notifications, files))
                .create();
        confirmation.setOnDismissListener(dialog -> confirmation = null);
        confirmation.show();
    }

    private void finishAccessReview(State state, boolean notifications, boolean files) {
        if (!resumed || !showable() || !allowed.getAsBoolean() || pending != null || !remaining.isEmpty()) return;
        if (files) {
            flow = state.epoch;
            remaining.add("files");
        }
        if (!notifications) {
            advance();
            refresh();
            return;
        }
        work.loadSerial("profile-full-access-write", () -> current(state.epoch)
                && (demo == null ? FullAccessDataSources.renewNotifications(app, state.notifications)
                : demo.renewNotifications(state.notifications)), saved -> {
            if (!saved) notice(R.string.full_access_setup_failed);
            else FullAccessNotificationListener.reconcileIfConnected();
            advance();
            refresh();
        }, failure -> {
            notice(R.string.full_access_setup_failed);
            advance();
            refresh();
        });
    }

    private void confirm(State state) {
        if (!resumed || !allowed.getAsBoolean() || !showable() || !state.eligible) { refresh(); return; }
        confirmation = new AlertDialog.Builder(host)
                .setTitle(demo == null ? host.getString(R.string.full_access_grant_title, state.paired)
                        : host.getString(R.string.demo_full_access_enable_title))
                .setMessage(demo == null ? R.string.full_access_grant_disclosure : R.string.demo_full_access_enable_disclosure)
                .setNegativeButton(android.R.string.cancel, (dialog, which) -> refresh())
                .setPositiveButton(demo == null ? R.string.full_access_share : R.string.role_enabled,
                        (dialog, which) -> switchGrant(state, true))
                .setOnCancelListener(dialog -> refresh())
                .create();
        confirmation.setOnDismissListener(dialog -> confirmation = null);
        confirmation.show();
    }

    private void switchGrant(State state, boolean next) {
        if (!resumed || !allowed.getAsBoolean() || !showable()) { refresh(); return; }
        work.loadSerial("profile-full-access-switch", () -> {
            if (!allowed.getAsBoolean() || !showable()) return -1L;
            if (demo != null) return demo.setEnabled(next) ? 0L : -1L;
            if (!ProfileStore.mayEdit(app, false)) return -1L;
            if (!next) return FullAccessGrant.disable(app) ? 0L : -1L;
            return FullAccessGrant.enable(app, state.pairKey) ? FullAccessGrant.epoch(app) : -1L;
        }, epoch -> {
            if (epoch < 0) notice(R.string.full_access_setup_failed);
            if (next && epoch >= 0) {
                notificationsDisclosed = true;
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
        if (!resumed || !allowed.getAsBoolean() || !showable() || pending != null
                || work.isPending("profile-full-access-write") || remaining.isEmpty()) return;
        String step = remaining.removeFirst();
        pending = step;
        long expected = flow;
        work.load("profile-full-access-advance", () -> current(expected)
                && (demo == null || !"files".equals(step) || !FullAccessFiles.supportsSharedRoots()
                || FullAccessFiles.prepareDemoStorageRequest(app)), permitted -> {
            if (!permitted || !allowed.getAsBoolean() || !showable()) {
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
            boolean disclosed = notificationsDisclosed;
            saveScope(() -> demo == null ? FullAccessDataSources.enableScope(app, step, disclosed)
                    : !"notifications".equals(step) || demo.enableNotifications(disclosed));
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
        if (remaining.isEmpty()) {
            flow = -1;
            notificationsDisclosed = false;
        }
        advance();
        refresh();
    }

    private void notice(int message) {
        if (resumed) Toast.makeText(host, message, Toast.LENGTH_SHORT).show();
    }

    private record State(boolean enabled, boolean eligible, String paired, String pairKey,
            long epoch, FullAccessFiles.Setup files, FullAccessNotificationListener.Choice notifications) { }
}
