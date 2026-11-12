package com.subhub.app.fullaccess;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.provider.Settings;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import com.subhub.app.R;
import com.subhub.app.people.PersonId;
import com.subhub.app.people.PersonUi;
import com.subhub.app.people.ProfileStore;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.security.DeviceRolePreferences;
import com.subhub.app.security.HardcoreModeManager;
import com.subhub.app.util.AsyncUiScope;
import com.subhub.app.util.PreferencePage;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Objects;

/** Local consent setup and the read-only paired view have separate entry points. */
public final class FullAccessActivity extends PreferencePage {
    private static final String PEER = "full_access_peer";
    private static final List<String> DATA_SCOPES = List.of("contacts", "notifications", "sms", "apps");

    private boolean setup;
    private boolean resumed;
    private AsyncUiScope work;
    private LinearLayout controls;
    private FullAccessViewer viewer;
    private ActivityResultLauncher<String> contactsPermission, smsPermission;
    private ActivityResultLauncher<Intent> settingsRoute, folderRoute;
    private ActivityResultLauncher<String[]> smsExport;
    private final ArrayDeque<String> guided = new ArrayDeque<>();
    private String expectedGrant, pendingScope, displayedGrant;
    private boolean routeDispatched;
    private String chosenTab;
    private AlertDialog confirmation;

    public static void openSetup(Activity activity) {
        if (ProfileStore.mayEdit(activity, false))
            activity.startActivity(new Intent(activity, FullAccessActivity.class));
    }

    public static void openPeer(Activity activity, PersonId peer) {
        if (peer != null && peer.value().matches("remote_[0-9a-f]{64}_[A-Za-z0-9_-]{1,128}")
                && viewerAllowed(activity))
            activity.startActivity(new Intent(activity, FullAccessActivity.class).putExtra(PEER, peer.value()));
    }

    private static boolean viewerAllowed(Context context) {
        return DeviceRolePreferences.supportsDom(context) && ControllerPinManager.isDomWorkspaceUnlocked();
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setup = !getIntent().hasExtra(PEER);
        if (!allowed()) { finish(); return; }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        page(R.string.full_access_title);
        work = AsyncUiScope.forPage(this);
        controls = PersonUi.column(this);
        page.addView(controls, new LinearLayout.LayoutParams(-1, -2));
        if (setup) {
            if (state != null) {
                expectedGrant = state.getString("grant");
                pendingScope = state.getString("permission_scope");
                routeDispatched = pendingScope != null;
                List<String> queue = state.getStringArrayList("permission_queue");
                if (queue != null) guided.addAll(queue);
            }
            registerSetupRoutes();
        } else {
            String peer = getIntent().getStringExtra(PEER);
            if (peer == null || !peer.matches("remote_[0-9a-f]{64}_[A-Za-z0-9_-]{1,128}")) {
                finish();
                return;
            }
            chosenTab = state == null ? null : state.getString("tab");
            viewer = new FullAccessViewer(this, controls, new PersonId(peer), chosenTab, this::viewerCurrent);
        }
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        if (!allowed()) { finish(); return; }
        if (setup && controls != null) {
            refreshSetup();
            continueSetup();
        }
        if (viewer != null) viewer.resume();
    }

    @Override protected void onPause() {
        resumed = false;
        if (viewer != null) viewer.pause();
        if (work != null) {
            work.cancel("full-access-setup-state");
            if (!routeDispatched) {
                work.cancel("full-access-setup-authorize");
                finishPermission();
            }
        }
        if (confirmation != null) confirmation.dismiss();
        super.onPause();
    }

    @Override protected void onDestroy() {
        if (viewer != null) viewer.close();
        super.onDestroy();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("grant", expectedGrant);
        state.putString("permission_scope", pendingScope);
        state.putStringArrayList("permission_queue", new java.util.ArrayList<>(guided));
        if (viewer != null) state.putString("tab", viewer.selected());
        super.onSaveInstanceState(state);
    }

    private boolean allowed() {
        return setup ? ProfileStore.mayEdit(this, false) : viewerAllowed(this);
    }

    private boolean viewerCurrent() {
        return !setup && resumed && !isFinishing() && !isDestroyed() && viewerAllowed(this);
    }

    private void registerSetupRoutes() {
        contactsPermission = registerForActivityResult(new ActivityResultContracts.RequestPermission(),
                granted -> permissionReturned("contacts", granted));
        smsPermission = registerForActivityResult(new ActivityResultContracts.RequestPermission(),
                granted -> permissionReturned("sms_access", granted));
        settingsRoute = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), ignored -> {
            String scope = pendingScope;
            if (scope == null) return;
            boolean available = switch (scope) {
                case "notifications" -> "ok".equals(FullAccessDataSources.permissionState(this, scope));
                case "apps" -> true; // The installed-app list is available without Usage Access.
                case "files" -> true;
                default -> false;
            };
            permissionReturned(scope, available);
        });
        folderRoute = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (!"folder".equals(pendingScope)) return;
            String key = finishPermission();
            Intent selected = result.getResultCode() == RESULT_OK ? result.getData() : null;
            if (selected != null)
                importSelection(app -> sameGrant(app, key) && FullAccessFiles.retainFolder(app, selected));
            else updateSetupAfterResult();
        });
        smsExport = registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
            if (!"sms".equals(pendingScope)) return;
            String key = finishPermission();
            if (uri != null)
                importSelection(app -> sameGrant(app, key) && FullAccessDataSources.importSmsExport(app, uri));
            else updateSetupAfterResult();
        });
    }

    private void refreshSetup() {
        if (!resumed || !allowed() || controls == null) return;
        Context app = getApplicationContext();
        work.load("full-access-setup-state", () -> {
            String pairKey = FullAccessGrant.pairedDomKey(app);
            String paired = FullAccessGrant.pairedDom(app);
            long epoch = FullAccessGrant.epoch(app);
            boolean eligible = FullAccessGrant.eligible(app)
                    && Objects.equals(pairKey, FullAccessGrant.pairedDomKey(app));
            List<ScopeState> scopes = new java.util.ArrayList<>();
            for (String scope : DATA_SCOPES) scopes.add(new ScopeState(scope,
                    FullAccessDataSources.permissionState(app, scope),
                    FullAccessDataSources.isScopeEnabled(app, scope)));
            return new SetupState(paired, pairKey, epoch >= 0, Long.toString(epoch), eligible, new HardcoreModeManager(app).isEnabled(),
                    scopes, FullAccessFiles.available(app), FullAccessDataSources.hasUsageAccess(app));
        }, state -> {
            if (resumed && allowed()) {
                renderSetup(state);
                continueSetup();
            }
        }, failure -> {
            if (!resumed || !allowed()) return;
            controls.removeAllViews();
            PersonUi.text(controls, getString(R.string.full_access_unavailable), false);
            PersonUi.action(controls, R.string.role_retry, this::refreshSetup, false);
        });
    }

    private void renderSetup(SetupState state) {
        displayedGrant = state.enabled ? state.grant : null;
        controls.removeAllViews();
        LinearLayout grant = PersonUi.card(controls);
        if (!state.enabled && !state.eligible)
            PersonUi.text(grant, getString(state.hardcore ? R.string.full_access_pair_needed
                    : R.string.full_access_hardcore_needed), false);
        ViewToggle.add(this, grant, R.string.full_access_title, state.enabled, state.enabled || state.eligible,
                enabled -> {
                    if (enabled) confirmGrant(state);
                    else revoke();
                });
        if (!state.enabled) return;

        LinearLayout guide = PersonUi.card(controls);
        PersonUi.action(guide, R.string.full_access_guide, () -> startGuidedSetup(state), true)
                .setTag("full_access_guide");
        for (ScopeState scope : state.scopes) renderDataSetup(scope, state.usage);
        LinearLayout files = PersonUi.card(controls);
        PersonUi.text(files, getString(R.string.full_access_files), true);
        PersonUi.text(files, getString(state.files ? R.string.full_access_scope_on
                : R.string.full_access_permission_needed), false);
        if (!state.files && FullAccessFiles.broadStorageIntent(this) != null)
            PersonUi.action(files, R.string.full_access_grant_storage, () -> openPermission("files"), false);
        PersonUi.action(files, R.string.full_access_add_folder, () -> openPermission("folder"), false);
        LinearLayout screen = PersonUi.card(controls);
        PersonUi.text(screen, getString(R.string.full_access_screen), true);
        PersonUi.text(screen, getString(R.string.full_access_screen_setup_note), false);
    }

    private void renderDataSetup(ScopeState scope, boolean usage) {
        LinearLayout row = PersonUi.card(controls);
        int label = FullAccessViewer.label(scope.name);
        PersonUi.text(row, getString(label), true);
        PersonUi.text(row, getString(scope.enabled ? R.string.full_access_scope_on
                : R.string.full_access_scope_off), false);
        if (scope.enabled) {
            TextView stop = PersonUi.action(row, R.string.full_access_stop_sharing,
                    () -> updateDataScope(scope.name, false), false);
            stop.setText(getString(R.string.full_access_disable_scope, getString(label)));
        }
        if ("notifications".equals(scope.name))
            PersonUi.text(row, getString(R.string.full_access_notification_setup_note), false);
        if ("sms".equals(scope.name)) {
            if (!FullAccessDataSources.hasSmsAccess(this))
                PersonUi.text(row, getString(R.string.full_access_sms_export_note), false);
            if (!scope.enabled && (FullAccessDataSources.hasSmsAccess(this)
                    || FullAccessDataSources.canRequestSmsPermission(this)))
                PersonUi.action(row, R.string.full_access_grant_sms, () -> openPermission("sms_access"), false)
                        .setTag("full_access_permission:sms_access");
            PersonUi.action(row, R.string.full_access_import_sms, () -> openPermission("sms"), false)
                    .setTag("full_access_permission:sms");
        } else if (!scope.enabled) {
            int permissionLabel = switch (scope.name) {
                case "contacts" -> R.string.full_access_grant_contacts;
                case "notifications" -> R.string.full_access_grant_notifications;
                default -> R.string.full_access_share;
            };
            TextView action = PersonUi.action(row, permissionLabel, () -> {
                if ("ok".equals(scope.permission) || "apps".equals(scope.name)) updateDataScope(scope.name, true);
                else openPermission(scope.name);
            }, false);
            if ("ok".equals(scope.permission) || "apps".equals(scope.name))
                action.setText(getString(R.string.full_access_enable_scope, getString(label)));
            action.setTag("full_access_permission:" + scope.name);
        } else if ("permission_required".equals(scope.permission)) {
            int permission = "contacts".equals(scope.name) ? R.string.full_access_grant_contacts
                    : R.string.full_access_grant_notifications;
            PersonUi.action(row, permission, () -> openPermission(scope.name), false);
        }
        if ("apps".equals(scope.name) && !usage)
            PersonUi.action(row, R.string.full_access_grant_usage, () -> openPermission("apps"), false);
    }

    private void confirmGrant(SetupState shown) {
        if (!resumed || !allowed() || !shown.eligible || shown.pairKey == null || shown.pairKey.isEmpty()) {
            refreshSetup();
            return;
        }
        confirmation = new AlertDialog.Builder(this)
                .setTitle(getString(R.string.full_access_grant_title, shown.paired))
                .setMessage(R.string.full_access_grant_disclosure)
                .setNegativeButton(android.R.string.cancel, (dialog, which) -> refreshSetup())
                .setPositiveButton(R.string.full_access_share, (dialog, which) ->
                        mutate(app -> FullAccessGrant.enable(app, shown.pairKey)))
                .setOnCancelListener(dialog -> refreshSetup())
                .show();
    }

    private void startGuidedSetup(SetupState shown) {
        if (!resumed || !allowed() || !shown.enabled || pendingScope != null) return;
        List<String> steps = new java.util.ArrayList<>();
        List<CharSequence> labels = new java.util.ArrayList<>();
        for (ScopeState scope : shown.scopes) {
            if ("sms".equals(scope.name)) {
                if (!scope.enabled && (FullAccessDataSources.hasSmsAccess(this)
                        || FullAccessDataSources.canRequestSmsPermission(this))) {
                    steps.add("sms_access");
                    labels.add(getText(R.string.full_access_sms));
                }
            } else if ("apps".equals(scope.name) ? !shown.usage || !scope.enabled : !scope.enabled
                    || !"ok".equals(scope.permission)) {
                steps.add(scope.name);
                labels.add(getText(FullAccessViewer.label(scope.name)));
            }
        }
        if (!shown.files) {
            steps.add(FullAccessFiles.broadStorageIntent(this) == null ? "folder" : "files");
            labels.add(getText(R.string.full_access_files));
        }
        if (steps.isEmpty()) { notice(getString(R.string.full_access_scope_on)); return; }
        confirmation = showPermissionChoices(this, steps, labels, choices -> {
            guided.clear();
            guided.addAll(choices);
            continueSetup();
        });
    }

    /** Presents the selected setup routes. Only the owning page can authorize and dispatch them. */
    static AlertDialog showPermissionChoices(Context context, List<String> steps, List<CharSequence> labels,
            java.util.function.Consumer<List<String>> confirmed) {
        if (steps.size() != labels.size()) throw new IllegalArgumentException("Permission labels do not match");
        boolean[] selected = new boolean[steps.size()];
        return new AlertDialog.Builder(context).setTitle(R.string.full_access_guide)
                .setMultiChoiceItems(labels.toArray(new CharSequence[0]), selected,
                        (dialog, which, checked) -> selected[which] = checked)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    List<String> requested = new java.util.ArrayList<>();
                    for (int i = 0; i < steps.size(); i++) if (selected[i]) requested.add(steps.get(i));
                    confirmed.accept(requested);
                }).show();
    }

    private void continueSetup() {
        if (!resumed || pendingScope != null || guided.isEmpty() || displayedGrant == null
                || work == null || work.isPending("full-access-setup-write")
                || work.isPending("full-access-setup-import") || work.isPending("full-access-setup-revoke")) return;
        String scope = guided.removeFirst();
        if (!openPermission(scope)) guided.clear();
    }

    private boolean openPermission(String scope) {
        if (!resumed || !allowed() || pendingScope != null || displayedGrant == null
                || work.isPending("full-access-setup-revoke") || work.isPending("full-access-setup-write")
                || work.isPending("full-access-setup-import")) return false;
        Context app = getApplicationContext();
        String grant = displayedGrant;
        pendingScope = scope;
        expectedGrant = grant;
        work.load("full-access-setup-authorize", () -> sameGrant(app, grant), active -> {
            if (!resumed || !allowed() || !active || !scope.equals(pendingScope)
                    || !grant.equals(expectedGrant)) {
                finishPermission();
                guided.clear();
                refreshSetup();
                return;
            }
            launchPermission(scope);
        }, failure -> {
            finishPermission();
            guided.clear();
            refreshSetup();
        });
        return true;
    }

    private void launchPermission(String scope) {
        Context app = getApplicationContext();
        routeDispatched = true;
        try {
            switch (scope) {
                case "contacts" -> {
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS)
                            == PackageManager.PERMISSION_GRANTED) permissionReturned(scope, true);
                    else contactsPermission.launch(Manifest.permission.READ_CONTACTS);
                }
                case "notifications" -> {
                    if ("ok".equals(FullAccessDataSources.permissionState(app, scope))) permissionReturned(scope, true);
                    else settingsRoute.launch(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
                }
                case "apps" -> {
                    if (FullAccessDataSources.hasUsageAccess(app)) permissionReturned(scope, true);
                    else settingsRoute.launch(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
                }
                case "sms_access" -> {
                    if (FullAccessDataSources.hasSmsAccess(app)) permissionReturned(scope, true);
                    else if (FullAccessDataSources.canRequestSmsPermission(app)) smsPermission.launch(Manifest.permission.READ_SMS);
                    else { finishPermission(); updateSetupAfterResult(); }
                }
                case "sms" -> smsExport.launch(new String[]{"text/plain"});
                case "files" -> {
                    Intent target = FullAccessFiles.broadStorageIntent(this);
                    if (target == null) { finishPermission(); openPermission("folder"); return; }
                    settingsRoute.launch(target);
                }
                case "folder" -> folderRoute.launch(FullAccessFiles.selectFolderIntent());
                default -> { finishPermission(); updateSetupAfterResult(); }
            }
        } catch (RuntimeException unavailable) {
            finishPermission();
            notice(getString(R.string.full_access_permission_needed));
            updateSetupAfterResult();
        }
    }

    private void permissionReturned(String scope, boolean granted) {
        if (!Objects.equals(scope, pendingScope)) return;
        String key = finishPermission();
        String dataScope = "sms_access".equals(scope) ? "sms" : scope;
        if (granted && DATA_SCOPES.contains(dataScope))
            mutate(app -> sameGrant(app, key) && FullAccessDataSources.enableScope(app, dataScope));
        else updateSetupAfterResult();
    }

    private String finishPermission() {
        String key = expectedGrant;
        pendingScope = null;
        expectedGrant = null;
        routeDispatched = false;
        return key;
    }

    private void updateSetupAfterResult() {
        if (resumed) {
            refreshSetup();
            controls.post(this::continueSetup);
        }
    }

    private static boolean sameGrant(Context app, String key) {
        return key != null && ProfileStore.mayEdit(app, false) && FullAccessGrant.isLocallyEnabled(app)
                && key.equals(String.valueOf(FullAccessGrant.epoch(app)));
    }

    private void updateDataScope(String scope, boolean enable) {
        String key = displayedGrant;
        mutate(context -> {
            if (!sameGrant(context, key)) return false;
            if (enable) return FullAccessDataSources.enableScope(context, scope);
            FullAccessDataSources.disableScope(context, scope);
            return !FullAccessDataSources.isScopeEnabled(context, scope);
        });
    }

    private void importSelection(SetupWrite write) {
        if (work.isPending("full-access-setup-import")) return;
        Context app = getApplicationContext();
        work.load("full-access-setup-import", () -> write.run(app), success -> {
            if (!success && resumed) notice(getString(R.string.full_access_setup_failed));
            updateSetupAfterResult();
        }, failure -> {
            if (resumed) notice(getString(R.string.full_access_setup_failed));
            updateSetupAfterResult();
        });
    }

    private void revoke() {
        guided.clear();
        work.cancel("full-access-setup-authorize");
        if (!routeDispatched) finishPermission();
        Context app = getApplicationContext();
        work.loadSerial("full-access-setup-revoke", () -> {
            if (!ProfileStore.mayEdit(app, false)) return false;
            return FullAccessGrant.disable(app);
        }, success -> {
            if (!success && resumed) notice(getString(R.string.full_access_setup_failed));
            refreshSetup();
        }, failure -> {
            if (resumed) notice(getString(R.string.full_access_setup_failed));
            refreshSetup();
        });
    }

    private void mutate(SetupWrite write) {
        if (work == null || !allowed() || work.isPending("full-access-setup-write")
                || work.isPending("full-access-setup-revoke")) return;
        Context app = getApplicationContext();
        work.loadSerial("full-access-setup-write", () -> ProfileStore.mayEdit(app, false) && write.run(app),
                success -> {
                    if (!success && resumed) notice(getString(R.string.full_access_setup_failed));
                    updateSetupAfterResult();
                }, failure -> {
                    if (resumed) notice(getString(R.string.full_access_setup_failed));
                    updateSetupAfterResult();
                });
    }

    @FunctionalInterface private interface SetupWrite { boolean run(Context app) throws Exception; }
    private record ScopeState(String name, String permission, boolean enabled) {}
    private record SetupState(String paired, String pairKey, boolean enabled, String grant, boolean eligible, boolean hardcore,
            List<ScopeState> scopes, boolean files, boolean usage) {}

    private static final class ViewToggle {
        private static void add(FullAccessActivity activity, LinearLayout parent, int label, boolean checked,
                boolean enabled, java.util.function.Consumer<Boolean> changed) {
            com.subhub.app.util.StateToggle toggle = (com.subhub.app.util.StateToggle) activity.getLayoutInflater()
                    .inflate(R.layout.view_form_toggle, parent, false);
            toggle.setText(label);
            toggle.setTag("full_access_toggle");
            toggle.setChecked(checked);
            toggle.setEnabled(enabled);
            toggle.setOnCheckedChangeListener((button, next) -> {
                toggle.setEnabled(false);
                changed.accept(next);
            });
            parent.addView(toggle, new LinearLayout.LayoutParams(-1, -2));
        }
    }
}
