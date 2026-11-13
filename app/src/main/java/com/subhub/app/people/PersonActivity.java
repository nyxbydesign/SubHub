package com.subhub.app.people;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.lifecycle.ViewModelProvider;
import com.subhub.app.R;
import com.subhub.app.activity.ActivityPreview;
import com.subhub.app.appmode.DeviceStartResultRegistry;
import com.subhub.app.appmode.DeviceStartViewModel;
import com.subhub.app.commitment.ServiceDurationView;
import com.subhub.app.configuration.FeatureEditorSession;
import com.subhub.app.runtime.AppRuntimeGateway;
import com.subhub.app.runtime.RuntimeStartAttempt;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.stats.StatsActivity;
import com.subhub.app.util.SubHubNavigation;

/** A selected person's contract, statistics and connection are always identity-bound. */
public final class PersonActivity extends RolePage {
    private PersonId personId;
    private boolean internal;
    private long renderedInternalRevision = -1;
    private InternalPresentationStamp renderedInternalStamp;
    private String section = "profile";
    private String expandedDemoContractSection = "";
    private final android.os.Handler clock = new android.os.Handler(android.os.Looper.getMainLooper());
    private String renderedSection;
    private final java.util.Map<String, LinearLayout> panels = new java.util.LinkedHashMap<>();
    private final java.util.Map<String, java.util.List<Object>> signatures = new java.util.HashMap<>();
    private final java.util.Map<String, Integer> positions = new java.util.HashMap<>();
    private boolean observing;
    private AutoCloseable appIconObserver, presenceObserver;
    private long appIconEpoch;
    private com.subhub.app.relationships.RelationshipUi.Provider appIconProvider;
    private com.subhub.app.security.KeyholderProvider appIconAuthority;
    private TextView currentCountdown;
    private DeviceStartViewModel demoStart;
    private DeviceStartResultRegistry demoResults;
    private TextView demoStartAction, demoStartStatus, demoServiceLabel;
    private boolean demoUiResumed, demoStatusPending, demoEndPending;
    private long nextDemoObservation;
    private AppRuntimeGateway.InternalStatus demoRuntime;
    private final DeviceStartViewModel.Ui demoUi = new DeviceStartViewModel.Ui() {
        @Override public boolean available() {
            return demoUiResumed && !isFinishing() && !isDestroyed() && demoAuthorized();
        }
        @Override public boolean granted(RuntimeStartAttempt.Permission permission) {
            return demoPermissionGranted(permission);
        }
        @Override public Runnable prepare(DeviceStartViewModel.PromptRoute route) {
            DeviceStartResultRegistry.Prepared prepared = demoResults.prepare(route);
            Intent intent = switch (route.permission) {
                case NOTIFICATIONS -> null;
                case ACCESSIBILITY -> new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS);
                case OVERLAY -> new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.fromParts("package", getPackageName(), null));
                case PROJECTION -> java.util.Objects.requireNonNull(
                        getSystemService(android.media.projection.MediaProjectionManager.class)).createScreenCaptureIntent();
            };
            return () -> prepared.launch(intent);
        }
        @Override public void changed() { updateDemoStartAction(); }
        @Override public void completed(AppRuntimeGateway.StartState state) {
            if (state == AppRuntimeGateway.StartState.ACTIVE) {
                demoRuntime = null;
                observeDemoRuntime(true);
                updateDemoStartAction();
                refreshInternal(true);
            } else if (demoStartStatus != null) {
                demoStartStatus.setText(R.string.internal_sub_session_unavailable);
                demoStartStatus.setVisibility(View.VISIBLE);
            }
        }
    };
    private final Runnable observe = new Runnable() {
        @Override public void run() {
            if (!observing) return;
            observePerson();
            if (observing) clock.postDelayed(this, internal ? 250 : 2000);
        }
    };

    @Override protected void onResume() {
        super.onResume();
        if (!guardTarget()) return;
        observing = true;
        observeRemoteChanges();
        clock.postDelayed(observe, internal ? 250 : 2000);
    }

    @Override protected void onPause() {
        demoUiResumed = false;
        if (demoStart != null) demoStart.detach(demoUi);
        updateDemoStartAction();
        observing = false;
        stopRemoteChanges();
        clock.removeCallbacks(observe);
        if (data != null) { data.cancel("person"); data.cancel("person-observe"); }
        super.onPause();
    }

    @Override protected void onPostResume() {
        super.onPostResume();
        if (demoStart == null || !guardTarget()) return;
        demoUiResumed = true;
        demoStart.attach(demoUi);
        observeDemoRuntime(true);
        updateDemoStartAction();
    }

    @Override protected void onDestroy() {
        if (demoStart != null) demoStart.detach(demoUi);
        if (demoResults != null) demoResults.close();
        stopRemoteChanges();
        super.onDestroy();
    }

    private boolean demoAuthorized() {
        if (!internal || !DummySubPreview.ID.equals(personId) || !DummySubPreview.isAllowed(this)) return false;
        InternalSubData data = InternalSubData.peek(this);
        return data != null && data.ownerDataReady();
    }

    private AppRuntimeGateway demoGateway() {
        return demoAuthorized() ? AppRuntimeGateway.current(this) : null;
    }

    private boolean demoPermissionGranted(RuntimeStartAttempt.Permission permission) {
        switch (permission) {
            case NOTIFICATIONS:
                return android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU
                        || androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
                        == android.content.pm.PackageManager.PERMISSION_GRANTED;
            case OVERLAY: return android.provider.Settings.canDrawOverlays(this);
            case PROJECTION: return false;
            case ACCESSIBILITY:
                var manager = getSystemService(android.view.accessibility.AccessibilityManager.class);
                if (manager == null) return false;
                for (var service : manager.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)) {
                    var resolved = service.getResolveInfo();
                    if (resolved != null && resolved.serviceInfo != null
                            && getPackageName().equals(resolved.serviceInfo.packageName)
                            && com.subhub.app.service.ScreenshotAccessibilityService.class.getName().equals(resolved.serviceInfo.name)) return true;
                }
                return false;
            default: throw new IllegalArgumentException("Unknown Start permission");
        }
    }

    private void updateDemoStartAction() {
        if (demoStartAction == null) return;
        boolean active = demoRuntime != null && demoRuntime.phase == AppRuntimeGateway.InternalPhase.ACTIVE;
        boolean idle = demoRuntime != null && demoRuntime.phase == AppRuntimeGateway.InternalPhase.IDLE;
        boolean canOpen = idle || demoRuntime == null && demoGateway() == null;
        demoStartAction.setText(active ? R.string.role_end_session : R.string.role_start_session);
        demoStartAction.setEnabled(demoStart != null && demoUi.available() && !demoEndPending && !demoStart.busy()
                && (active || canOpen && FeatureEditorSession.canEdit(this, "duration")));
        if (demoServiceLabel != null)
            demoServiceLabel.setText(active ? R.string.status_active : canOpen ? R.string.status_inactive : R.string.role_unknown_data);
    }

    private AppRuntimeGateway.InternalStartSpec demoSpecification() {
        var record = InternalSubData.get(this).acknowledged();
        return new AppRuntimeGateway.InternalStartSpec(record.targetId, record.generation,
                record.configurationRevision, ControllerPinManager.domWorkspaceRevision());
    }

    private boolean stillObservingDemo(AppRuntimeGateway gateway, AppRuntimeGateway.InternalStartSpec expected) {
        return demoGateway() == gateway && sameDemoProfile(expected);
    }

    private boolean sameDemoProfile(AppRuntimeGateway.InternalStartSpec expected) {
        if (!demoUi.available()) return false;
        var actual = demoSpecification();
        return expected.target.equals(actual.target) && expected.generation.equals(actual.generation)
                && expected.configurationRevision == actual.configurationRevision
                && expected.controllerRevision == actual.controllerRevision;
    }

    private void observeDemoRuntime(boolean immediate) {
        if (!demoUi.available() || demoStatusPending) return;
        long now = android.os.SystemClock.elapsedRealtime();
        if (!immediate && now < nextDemoObservation) return;
        nextDemoObservation = now + 1000;
        AppRuntimeGateway gateway = demoGateway();
        if (gateway == null) {
            demoRuntime = null;
            updateDemoStartAction();
            return;
        }
        var expected = demoSpecification();
        demoStatusPending = true;
        gateway.internalStatus(expected).whenComplete((observed, failure) -> runOnUiThread(() -> {
            demoStatusPending = false;
            if (!stillObservingDemo(gateway, expected)) return;
            demoRuntime = failure == null ? observed : null;
            updateDemoStartAction();
        }));
    }

    private void beginDemoStart() {
        if (!demoUi.available() || !guardTarget() || demoStart.busy() || demoEndPending) return;
        AppRuntimeGateway gateway = demoGateway();
        if (gateway != null && demoRuntime != null && demoRuntime.phase == AppRuntimeGateway.InternalPhase.ACTIVE) {
            var expected = demoSpecification();
            var owner = demoRuntime.owner;
            demoEndPending = true;
            updateDemoStartAction();
            gateway.endInternal(expected, owner).whenComplete((ended, failure) -> runOnUiThread(() -> {
                demoEndPending = false;
                if (!sameDemoProfile(expected)) return;
                if ((failure != null || !Boolean.TRUE.equals(ended)) && demoStartStatus != null) {
                    demoStartStatus.setText(R.string.internal_sub_session_unavailable);
                    demoStartStatus.setVisibility(View.VISIBLE);
                }
                demoRuntime = null;
                observeDemoRuntime(true);
                updateDemoStartAction();
            }));
            return;
        }
        if (gateway != null && (demoRuntime == null || demoRuntime.phase != AppRuntimeGateway.InternalPhase.IDLE)) return;
        android.content.Context physical = getApplicationContext();
        long controllerRevision = ControllerPinManager.domWorkspaceRevision();
        if (demoStartStatus != null) demoStartStatus.setVisibility(View.GONE);
        demoStart.beginInternal(physical, () -> FeatureEditorSession.acknowledgeInternalDuration(this).thenApply(saved -> {
            if (!DummySubPreview.current(physical, saved.generation, controllerRevision))
                throw new SecurityException("Demo Start authority changed");
            return new AppRuntimeGateway.InternalStartSpec(saved.targetId, saved.generation,
                    saved.configurationRevision, controllerRevision);
        }));
    }

    private void observeRemoteChanges() {
        if (internal || personId == null || !observing || body == null || !body.isAttachedToWindow()) return;
        com.subhub.app.relationships.RelationshipUi.Provider provider = com.subhub.app.relationships.RelationshipUi.provider();
        com.subhub.app.security.KeyholderProvider authority = People.keyholder();
        if (presenceObserver != null && provider == appIconProvider && authority == appIconAuthority) return;
        stopRemoteChanges(); appIconProvider = provider; appIconAuthority = authority;
        long request = ++appIconEpoch;
        PersonId expected = personId;
        presenceObserver = com.subhub.app.remote.RemoteUi.observePresence(() -> {
            if (canRefreshRemote(request, expected, provider, authority)) observePerson();
        });
        if (!expected.isLocal()) appIconObserver = com.subhub.app.remote.RemoteUi.observeAppIcons(() -> {
            if (canRefreshRemote(request, expected, provider, authority)) refresh();
        });
    }

    private boolean canRefreshRemote(long request, PersonId expected,
            com.subhub.app.relationships.RelationshipUi.Provider provider,
            com.subhub.app.security.KeyholderProvider authority) {
        return request == appIconEpoch && observing && expected.equals(personId) && !isFinishing() && !isDestroyed()
                && getLifecycle().getCurrentState().isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
                && provider == com.subhub.app.relationships.RelationshipUi.provider() && authority == People.keyholder()
                && body.isAttachedToWindow() && mayRead(this, expected);
    }

    private void stopRemoteChanges() {
        ++appIconEpoch;
        for (AutoCloseable observer : new AutoCloseable[]{appIconObserver, presenceObserver})
            if (observer != null) try { observer.close(); } catch (Exception ignored) { /* The epoch fences callbacks. */ }
        appIconObserver = null;
        presenceObserver = null;
    }

    private void observePerson() {
        if (!guardTarget()) return;
        if (internal) { observeDemoRuntime(false); refreshInternal(false); return; }
        observeRemoteChanges();
        if (currentCountdown != null && personId != null && personId.isLocal()
                && com.subhub.app.commitment.CommitmentManager.isActive(this)) {
            currentCountdown.setText(com.subhub.app.commitment.CommitmentManager.countdownLabel(this));
        }
        if (data.isPending("person") || data.isPending("person-observe")) return;
        PersonId requested = personId;
        android.content.Context app = getApplicationContext();
        PersonRepository repository = People.repository(app);
        com.subhub.app.relationships.RelationshipUi.Provider provider = com.subhub.app.relationships.RelationshipUi.provider();
        com.subhub.app.security.KeyholderProvider authority = People.keyholder();
        data.load("person-observe", () -> {
            if (!mayRead(app, requested)) throw new SecurityException("Profile unavailable");
            return repository.load(requested);
        }, person -> {
            if (!guardTarget() || !observing || !requested.equals(personId)
                    || provider != com.subhub.app.relationships.RelationshipUi.provider() || authority != People.keyholder()) return;
            if (!requested.equals(person.id)) { renderedSection = null; unavailable(); return; }
            com.subhub.app.appmode.ExtraTimeEntry.refresh(body, requested);
            render(person);
        }, failure -> {
            if (guardTarget() && observing && provider == com.subhub.app.relationships.RelationshipUi.provider()
                    && authority == People.keyholder()) { renderedSection = null; unavailable(); }
        });
    }

    private boolean guardTarget() {
        if (internal ? !DummySubPreview.isAllowed(this) || !DummySubPreview.ID.equals(personId)
                : DummySubPreview.isRequested(getIntent()) || !mayRead(this, personId)) {
            observing = false;
            stopRemoteChanges();
            clock.removeCallbacks(observe);
            if (data != null) { data.cancel("person"); data.cancel("person-observe"); }
            currentCountdown = null;
            if (demoStart != null) demoStart.cancel();
            if (body != null) body.removeAllViews();
            finish();
            return false;
        }
        return true;
    }

    private static boolean mayRead(android.content.Context context, PersonId target) {
        return target != null && (target.isLocal()
                ? ControllerPinManager.isDomModeActive() && com.subhub.app.security.DeviceRolePreferences.supportsSub(context)
                : ControllerPinManager.isDomWorkspaceUnlocked() && com.subhub.app.security.DeviceRolePreferences.supportsDom(context));
    }



    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        internal = DummySubPreview.isRequested(getIntent());
        if (internal) {
            demoStart = new ViewModelProvider(this).get(DeviceStartViewModel.class);
            demoResults = new DeviceStartResultRegistry(getActivityResultRegistry(), this::demoPermissionGranted);
            demoResults.restore(state == null ? null : state.getBundle("demo_start_result_routes"), demoStart.pendingRoute());
        }
        if (internal && (!DummySubPreview.isAllowed(this)
                || !FeatureEditorSession.attach(this, "duration", state))) { demoStart.cancel(); finish(); return; }
        try { personId = PersonRoutes.requireId(getIntent()); }
        catch (IllegalArgumentException invalid) { finish(); return; }
        section = state == null ? getIntent().getStringExtra("person_section")
                : state.getString("person_section", "profile");
        if (section == null || !java.util.List.of("profile", "contracts", "stats", "connection").contains(section)) section = "profile";
        if (state != null && internal) expandedDemoContractSection = state.getString("demo_contract_expanded", "");
        if (state != null) for (String key : java.util.List.of("profile", "contracts", "connection"))
            positions.put(key, state.getInt("person_position:" + key));
        page(R.string.role_profile, SubHubNavigation.Screen.PEOPLE, true);
        body.addOnAttachStateChangeListener(new android.view.View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(android.view.View view) { observeRemoteChanges(); }
            @Override public void onViewDetachedFromWindow(android.view.View view) { stopRemoteChanges(); }
        });
        secondaryBack(this::backToProfile);
        getOnBackPressedDispatcher().addCallback(this, new androidx.activity.OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { backToProfile(); }
        });
    }

    @Override protected void onRestoreInstanceState(Bundle state) {
        // Raw internal fields are restored only for the same current retained draft.
        if (!internal || FeatureEditorSession.restoreInternalWidgets(this, state)) super.onRestoreInstanceState(state);
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        FeatureEditorSession.saveState(this, state);
        if (demoResults != null) state.putBundle("demo_start_result_routes", demoResults.saveMetadata());
        state.putString("person_section", section);
        if (internal) state.putString("demo_contract_expanded", expandedDemoContractSection);
        for (java.util.Map.Entry<String, Integer> position : positions.entrySet())
            state.putInt("person_position:" + position.getKey(), position.getValue());
        super.onSaveInstanceState(state);
    }

    @SuppressWarnings("deprecation")
    @Override protected void onActivityResult(int requestCode, int resultCode, Intent result) {
        try { super.onActivityResult(requestCode, resultCode, result); }
        finally { if (demoResults != null) demoResults.onFrameworkResultReturned(); }
    }

    @Override public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] results) {
        try { super.onRequestPermissionsResult(requestCode, permissions, results); }
        finally { if (demoResults != null) demoResults.onFrameworkResultReturned(); }
    }

    @Override protected void refresh() {
        if (!guardTarget()) return;
        if (internal) { refreshInternal(true); return; }
        refreshLocalDuration();
        observeRemoteChanges();
        data.cancel("person-observe");
        data.cancel("contract-cover");
        loading();
        PersonId requested = personId;
        android.content.Context app = getApplicationContext();
        PersonRepository repository = People.repository(app);
        com.subhub.app.relationships.RelationshipUi.Provider provider = com.subhub.app.relationships.RelationshipUi.provider();
        com.subhub.app.security.KeyholderProvider authority = People.keyholder();
        data.load("person", () -> {
            if (!mayRead(app, requested)) throw new SecurityException("Profile unavailable");
            return repository.load(requested);
        }, person -> {
            if (!guardTarget() || !requested.equals(personId)
                    || provider != com.subhub.app.relationships.RelationshipUi.provider() || authority != People.keyholder()) return;
            if (!requested.equals(person.id)) { renderedSection = null; unavailable(); return; }
            render(person);
        }, failure -> { if (guardTarget() && provider == com.subhub.app.relationships.RelationshipUi.provider()
                && authority == People.keyholder()) { renderedSection = null; unavailable(); } });
    }

    private void refreshInternal(boolean force) {
        if (!guardTarget()) return;
        InternalSubData backend=InternalSubData.get(this);
        com.subhub.app.internal.InternalSubRecord record=backend.acknowledged();
        long ownerRevision=backend.presentationRevisionHint();
        if(!force&&renderedInternalStamp!=null&&renderedInternalStamp.current(record,ownerRevision,ControllerPinManager.domWorkspaceRevision())){refreshLocalDuration();return;}
        PersonSnapshot cached=backend.peekPresentation();
        if(cached!=null){
            InternalPresentationStamp stamp=backend.presentationStamp(cached);
            if(stamp!=null){renderedInternalRevision=record.revision;renderedInternalStamp=stamp;render(cached);return;}
        }
        if(data.isPending("person"))return;
        if(renderedInternalRevision<0)loading();
        android.content.Context app=getApplicationContext();
        long controller=ControllerPinManager.domWorkspaceRevision();
        data.load("person",()->{
            if(!DummySubPreview.current(app,record.generation,controller))throw new SecurityException("Internal profile changed");
            InternalSubData.loadIfProvisioned(app);
            return backend.loadPresentation(app,record);
        },person->{
            if(!guardTarget()||!DummySubPreview.current(app,record.generation,controller))return;
            if(backend.acknowledged().revision!=record.revision||backend.peekPresentation()!=person){refreshInternal(true);return;}
            InternalPresentationStamp stamp=backend.presentationStamp(person);
            if(stamp==null){refreshInternal(true);return;}
            renderedInternalRevision=record.revision;renderedInternalStamp=stamp;render(person);
        },failure->{
            if(guardTarget()&&DummySubPreview.current(app,record.generation,controller)
                    &&backend.acknowledged().revision==record.revision){renderedInternalRevision=-1;renderedInternalStamp=null;renderedSection=null;unavailable();}
        });
    }

    private void render(PersonSnapshot person) {
        if (!guardTarget() || !personId.equals(person.id)) return;
        if ("stats".equals(section)) {
            section = "profile";
            render(person);
            openStats(person);
            return;
        }
        renderPage(() -> {
            if (!section.equals(renderedSection)) {
                body.removeAllViews(); panels.clear(); signatures.clear(); currentCountdown = null;
                renderedSection = section;
            }
            if ("profile".equals(section)) {
                title(R.string.role_profile);
                reconcile("identity", java.util.Arrays.asList(person.profile), parent -> {
                    ProfileViews.identity(parent, person, !internal);
                    if (internal) {
                        boolean writable = InternalSubData.get(this).ownerDataReady();
                        TextView edit = PersonUi.action(parent, R.string.internal_sub_edit_profile, () -> {
                            if (guardTarget() && InternalSubData.get(this).ownerDataReady())
                                startActivity(DummySubPreview.childIntent(this, ProfileEditorActivity.class));
                        }, false);
                        edit.setTag("internal_profile_edit"); edit.setEnabled(writable);
                        if (!writable) PersonUi.text(parent, getString(R.string.internal_sub_owner_data_unavailable), false);
                    }
                });
                ProfileViews.updateIdentityConnection(panels.get("identity"), person);
                java.util.List<Object> session = new java.util.ArrayList<>(java.util.Arrays.asList(
                        person.id, person.serviceActive, person.sessionIdentity, person.sync.runtime,
                        person.capabilities, internal ? null : person.sync.desiredRevision, internal ? null : person.sync.appliedRevision,
                        internal ? null : People.connectionUi(), internal ? null : People.keyholder(),
                        com.subhub.app.security.ControllerPinManager.domWorkspaceRevision()));
                if (person.id.isLocal()) session.add(com.subhub.app.commitment.CommitmentManager.isActive(this));
                reconcile("session", session, parent -> session(parent, person));
                refreshLocalDuration();
                TextView label = panels.get("session").findViewWithTag("person_service_label");
                if (label != null) label.setText(!person.id.isLocal() && person.sync.runtime == PersonSnapshot.Freshness.UNKNOWN
                        ? getString(R.string.role_unknown_data) : person.serviceLabel);
                reconcile("destinations", java.util.Arrays.asList(person.capabilities), parent -> {
                    LinearLayout destinations = PersonUi.card(parent);
                    destinations.setTag("person_destinations");
                    destination(destinations, person, "contracts", R.drawable.ic_nav_contract, R.string.role_contract);
                    PersonUi.row(destinations, R.drawable.ic_nav_tasks, getString(R.string.tasks_title), "", () -> {
                        if (guardTarget()) startActivity(internal ? DummySubPreview.childIntent(this, com.subhub.app.tasks.TasksActivity.class)
                                : new Intent(this, com.subhub.app.tasks.TasksActivity.class)
                                .putExtra(PersonRoutes.EXTRA_PERSON_ID, person.id.value()));
                    }).setTag("person_tab:tasks");
                    if (person.can(PersonSnapshot.Capability.VIEW_STATS))
                        destination(destinations, person, "stats", R.drawable.ic_nav_stats, R.string.role_stats);
                    if (internal || person.can(PersonSnapshot.Capability.VIEW_BLACKMAIL)) {
                        PersonUi.row(destinations, R.drawable.ic_ux_lock, getString(R.string.full_access_title), "", () -> {
                            if (!guardTarget()) return;
                            if (internal) startActivity(DummySubPreview.childIntent(this,
                                    com.subhub.app.fullaccess.DemoFullAccessActivity.class));
                            else com.subhub.app.fullaccess.FullAccessActivity.openPeer(this, person.id);
                        }).setTag("person_tab:full_access");
                    }
                    destination(destinations, person, "connection", R.drawable.ic_nav_people, R.string.role_connection);
                });
                if (!internal) reconcile("activity", java.util.List.of(person.id), parent ->
                        ActivityPreview.add(parent, person.id, 3, data, "person-recent-activity"));
            } else if ("connection".equals(section)) {
                title(R.string.role_connection);
                String observed = person.lastSyncedAt > 0 ? getString(R.string.role_last_synced,
                        android.text.format.DateUtils.getRelativeTimeSpanString(person.lastSyncedAt,
                                System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS))
                        : getString(R.string.role_never_synced);
                reconcile("connection", java.util.Arrays.asList(person.name, person.device,
                        PersonUi.connection(this, person), observed, person.capabilities, internal ? null : People.connectionUi()), parent -> {
                    PersonUi.text(parent, person.name, true);
                    PersonUi.text(parent, ProfileViews.targetDiscriminator(this, person), false)
                            .setTag("person_target:" + person.id.value());
                    connection(parent, person);
                });
            } else {
                title(R.string.role_contract);
                java.util.List<Object> contract = PersonPresentation.contract(person);
                java.util.Collections.addAll(contract, internal ? null : People.connectionUi(), internal ? null : People.keyholder(),
                        internal ? null : com.subhub.app.relationships.RelationshipUi.provider(),
                        ControllerPinManager.domWorkspaceRevision());
                reconcile("contracts", contract, parent -> {
                    if (internal) {
                        LinearLayout identity = OverviewUi.card(parent);
                        identity.setTag("person_contract_identity");
                        OverviewUi.identity(identity, person.profile, false, "demo_contract_identity");
                        new ContractActivity.ContractSections(parent, person, null, () -> getString(R.string.role_unknown_data),
                                () -> expandedDemoContractSection, value -> expandedDemoContractSection = value,
                                person.can(PersonSnapshot.Capability.EDIT_CONTRACT) && FeatureEditorSession.canEdit(this, "apps")
                                        ? key -> editSection(person, key) : null);
                        return;
                    }
                    PersonUi.text(parent, person.name, true);
                    ProfileViews.allowedFeatures(parent, person);
                    contracts(parent, person);
                });
                // Image bytes can arrive after the descriptor without changing the content signature.
                for (PersonSnapshot.Section value : person.sections) for (PersonSnapshot.App app : value.apps) {
                    android.widget.ImageView icon = body.findViewWithTag("contract_app_icon:" + app.packageName);
                    if (icon != null && app.appIcon != null)
                        com.subhub.app.remote.RemoteUi.bindAppIcon(icon, app.appIcon, R.drawable.ic_settings_apps);
                }
            }
        });
    }

    private void refreshLocalDuration() {
        if (personId == null || (!personId.isLocal() && !internal) || body == null) return;
        ServiceDurationView duration = body.findViewWithTag("person_duration:" + personId.value());
        if (duration != null) duration.reloadIfChanged();
    }

    private void reconcile(String key, java.util.List<Object> signature,
            java.util.function.Consumer<LinearLayout> render) {
        LinearLayout panel = panels.get(key);
        if (panel == null) {
            panel = PersonUi.column(this); panels.put(key, panel);
            body.addView(panel, new LinearLayout.LayoutParams(-1, -2));
        }
        if (signature.equals(signatures.get(key))) return;
        panel.removeAllViews(); render.accept(panel); signatures.put(key, signature);
    }

    private void selectSection(String next) {
        com.subhub.app.util.PageScrollView scroll = findViewById(R.id.role_page_scroll);
        positions.put(section, scroll.position());
        section = next;
        resetPagePosition();
        body.removeAllViews(); panels.clear(); signatures.clear(); renderedSection = null;
        // Restoring a subpage is an explicit navigation action, not an old refresh callback.
        restorePagePosition(positions.getOrDefault(next, 0));
        refresh();
    }

    private void destination(LinearLayout parent, PersonSnapshot person, String key, int icon, int label) {
        PersonUi.row(parent, icon, getString(label), "", () -> {
            if (!guardTarget()) return;
            if ("stats".equals(key)) openStats(person);
            else selectSection(key);
        }).setTag("person_tab:" + key);
    }

    private void backToProfile() {
        if (!guardTarget()) { finish(); return; }
        if ("profile".equals(section)) finish();
        else selectSection("profile");
    }

    private void session(LinearLayout body, PersonSnapshot person) {
        if (!person.id.isLocal()) {
            LinearLayout session = PersonUi.card(body);
            PersonUi.text(session, getString(R.string.role_session), true);
            TextView serviceLabel = PersonUi.text(session, person.sync.runtime == PersonSnapshot.Freshness.UNKNOWN
                    ? getString(R.string.role_unknown_data) : person.serviceLabel, false);
            serviceLabel.setTag("person_service_label");
            if (person.sync.runtime == PersonSnapshot.Freshness.STALE) freshness(session, person.sync.runtime);
            if (internal) {
                demoServiceLabel = serviceLabel;
                demoStartAction = PersonUi.action(session, R.string.role_start_session, this::beginDemoStart, true);
                demoStartAction.setId(R.id.person_session_action);
                demoStartAction.setTag("person_session:" + person.id.value());
                PersonUi.text(session, getString(R.string.role_service_duration), true);
                ServiceDurationView duration = new ServiceDurationView(this, null,
                        FeatureEditorSession.context(this), () -> guardTarget() && FeatureEditorSession.canEdit(this, "duration"));
                duration.setId(R.id.person_duration_control);
                duration.setTag("person_duration:" + person.id.value());
                duration.setHeadingVisible(false);
                duration.setEditable(FeatureEditorSession.canEdit(this, "duration"));
                duration.setSaveEnabled(false);
                session.addView(duration, new LinearLayout.LayoutParams(-1, -2));
                FeatureEditorSession.bind(this, findViewById(android.R.id.content), "duration", duration::validateSelection);
                demoStartStatus = PersonUi.text(session, getString(R.string.internal_sub_session_unavailable), false);
                demoStartStatus.setVisibility(demoAuthorized() ? View.GONE : View.VISIBLE);
                updateDemoStartAction();
                PersonUi.row(session, R.drawable.ic_nav_limits, getString(R.string.role_service_duration),
                        getString(R.string.dummy_sub_defaults), () -> {
                            if (guardTarget()) startActivity(FeatureEditorSession.child(this, ServiceDurationActivity.class));
                        }).setTag("person_duration");
                return;
            }
            ConnectionUi remote = People.connectionUi();
            if (remote == null) return;
            remote.bindSessionDuration(this, session, person);
            if (remote.bindSessionControls(this, session, person)) return;
            if (person.serviceActive && person.sync.runtime == PersonSnapshot.Freshness.CURRENT
                    && person.can(PersonSnapshot.Capability.END_SESSION) && !person.sessionIdentity.isEmpty()) {
                TextView end = PersonUi.action(session, R.string.remote_session_stop, () -> {
                    if (!guardTarget() || remote != People.connectionUi()) return;
                    remote.endSession(this, person.id, person.sessionIdentity);
                }, false);
                end.setId(R.id.person_session_action);
                end.setTag("person_session:" + person.id.value());
            }
            return;
        }
        if (person.id.isLocal()) {
            LinearLayout session = PersonUi.card(body);
            PersonUi.text(session, getString(R.string.role_session), true);
            PersonUi.text(session, person.serviceLabel, false).setTag("person_service_label");
            boolean active = LocalSessionControls.isActive(this);
            if (com.subhub.app.commitment.CommitmentManager.isActive(this)) {
                currentCountdown = PersonUi.text(session,
                        com.subhub.app.commitment.CommitmentManager.countdownLabel(this), true);
                currentCountdown.setTag("person_current_countdown");
            }
            TextView manageSession = PersonUi.action(session,
                    active ? R.string.role_end_session : R.string.role_start_session,
                    () -> {
                        if (!guardTarget()) return;
                        if (active) {
                            long controller = ControllerPinManager.domWorkspaceRevision();
                            LocalSessionControls.endAsync(this, person.id).whenComplete((ended, failure) -> runOnUiThread(() -> {
                                if (failure == null && Boolean.TRUE.equals(ended) && !isFinishing() && !isDestroyed()
                                        && PersonId.LOCAL.equals(personId) && controller == ControllerPinManager.domWorkspaceRevision()
                                        && guardTarget()) refresh();
                            }));
                        } else PersonRoutes.openLocalSession(this, person.id);
                    }, !active);
            manageSession.setId(R.id.person_session_action);
            manageSession.setTag("person_session:local");
            if (PersonRoutes.canEditLocal(this, person.id)) {
                PersonUi.text(session, getString(active ? R.string.profile_next_session_duration
                        : R.string.role_service_duration), true);
                ServiceDurationView duration = new ServiceDurationView(this, null);
                duration.setHeadingVisible(false);
                duration.setTag("person_duration:local");
                duration.setEditable(true);
                session.addView(duration, new LinearLayout.LayoutParams(-1, -2));
            }
        }
    }

    private void contracts(LinearLayout body, PersonSnapshot person) {
        if (!person.can(PersonSnapshot.Capability.VIEW_CONTRACT)) {
            PersonUi.text(body, getString(R.string.role_contract_unavailable), false);
            return;
        }

        boolean editable = person.can(PersonSnapshot.Capability.EDIT_CONTRACT)
                && (internal ? FeatureEditorSession.canEdit(this, "apps")
                        : person.id.isLocal() ? PersonRoutes.canEditLocal(this, person.id)
                        : ControllerPinManager.isDomWorkspaceUnlocked() && People.connectionUi() != null);
        LinearLayout scope = PersonUi.card(body);
        scope.setTag("person_contract_scope");
        PersonUi.text(scope, getString(R.string.profile_contract_scope), true);
        if (person.sync.hasPendingConfiguration()) PersonUi.text(scope,
                getString(R.string.role_changes_pending), false);
        freshness(scope, person.sync.contract);
        PersonSnapshot.Section apps = contractSection(person, "apps");
        TextView appsTitle = PersonUi.text(scope, apps == null ? getString(R.string.person_apps_title) : apps.title, true);
        if (!editable && !internal) appsTitle.setTag("person_apps");
        PersonUi.text(scope, apps == null ? getString(R.string.role_unknown_data) : apps.summary, false);
        if (apps != null && (!apps.details.isEmpty() || !apps.apps.isEmpty())) ContractAppList.add(scope, apps, internal || person.id.isLocal());
        if (internal && !editable) PersonUi.row(scope, R.drawable.ic_settings_apps, getString(R.string.dummy_sub_apps),
                getString(R.string.dummy_sub_defaults), () -> editSection(person, "apps")).setTag("person_apps");
        if (editable) PersonUi.action(scope, R.string.person_add_more_apps,
                () -> editSection(person, "apps"), false).setTag("person_apps");
        LinearLayout features = null;
        for (PersonSnapshot.Section value : person.sections) {
            if ("apps".equals(value.key) || "duration".equals(value.key)) continue;
            if (features == null) {
                features = PersonUi.card(body);
                features.setTag("person_contract_features");
                PersonUi.text(features, getString(R.string.profile_contract_features), true);
            }
            contractSection(features, person, value, editable);
        }
    }

    private PersonSnapshot.Section contractSection(PersonSnapshot person, String key) {
        for (PersonSnapshot.Section value : person.sections) if (key.equals(value.key)) return value;
        return null;
    }

    private void contractSection(LinearLayout parent, PersonSnapshot person,
            PersonSnapshot.Section value, boolean editable) {
        int icon = "apps".equals(value.key) ? R.drawable.ic_settings_apps
                : "duration".equals(value.key) ? R.drawable.ic_nav_limits : PersonUi.icon(value.key);
        boolean allowed = internal || (person.id.isLocal()
                ? !com.subhub.app.security.SubFeaturePolicy.FEATURES.contains(value.key) || com.subhub.app.security.SubFeaturePolicy.allowed(this, value.key)
                : People.connectionUi() != null && People.connectionUi().canEditSection(person.id, value.key));
        boolean atmosphere = "atmosphere".equals(value.key);
        boolean known = person.sync.contract != PersonSnapshot.Freshness.UNKNOWN;
        LinearLayout row = PersonUi.row(parent, icon, value.title,
                atmosphere ? (known ? value.summary : getString(R.string.role_unknown_data))
                        : allowed ? value.summary : getString(R.string.sub_feature_disabled),
                (internal || editable) && allowed && (!atmosphere || known) ? () -> editSection(person, value.key) : null);
        row.setTag("apps".equals(value.key) ? "person_apps"
                : "duration".equals(value.key) && editable ? "person_duration" : "person_section:" + value.key);
        if (atmosphere && known) {
            for (String block : value.details.split("\\n\\s*\\n")) {
                android.text.SpannableString copy = new android.text.SpannableString(block);
                int firstLine = block.indexOf('\n');
                if (firstLine > 0) copy.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                        0, firstLine, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                PersonUi.text(parent, copy, false);
            }
        } else if (!atmosphere && !editable && !value.details.isEmpty()) PersonUi.text(parent, value.details, false);
    }

    private void editSection(PersonSnapshot person, String section) {
        if (!guardTarget() || !personId.equals(person.id)) return;
        if (internal) { startActivity(FeatureEditorSession.child(this, PersonRoutes.sectionActivity(section))); return; }
        if (person.id.isLocal()) {
            PersonRoutes.editSection(this, person.id, section);
            return;
        }
        ConnectionUi ui = People.connectionUi();
        if (!person.id.isLocal() && person.can(PersonSnapshot.Capability.EDIT_CONTRACT)
                && ControllerPinManager.isDomWorkspaceUnlocked() && ui != null)
            ui.editSection(this, person.id, section);
    }

    private void openStats(PersonSnapshot person) {
        if (!guardTarget() || !personId.equals(person.id) || !person.can(PersonSnapshot.Capability.VIEW_STATS)) return;
        if (internal) startActivity(DummySubPreview.childIntent(this, StatsActivity.class));
        else if (person.id.isLocal()) startActivity(new Intent(this, StatsActivity.class)
                .putExtra(PersonRoutes.EXTRA_PERSON_ID, person.id.value()));
        else {
            ConnectionUi ui = People.connectionUi();
            if (ui != null) ui.openStats(this, person.id);
        }
    }

    private void connection(LinearLayout body, PersonSnapshot person) {
        LinearLayout card = PersonUi.card(body);
        PersonUi.text(card, PersonUi.connection(this, person), true);
        PersonUi.row(card, R.drawable.ic_nav_people, getString(R.string.role_device), person.device, null);
        if (internal) PersonUi.text(card, getString(R.string.dummy_sub_remove_help), false);
        if (!internal && !person.id.isLocal()) {
            PersonUi.text(card, person.lastSyncedAt > 0 ? getString(R.string.role_last_synced,
                    android.text.format.DateUtils.getRelativeTimeSpanString(person.lastSyncedAt,
                            System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS))
                    : getString(R.string.role_never_synced), false);
        }
        if (person.id.isLocal() && People.connectionUi() != null) PersonUi.action(body, R.string.role_manage_connection,
                () -> {
                    ConnectionUi ui = People.connectionUi();
                    if (guardTarget() && ui != null) ui.openConnection(this, person.id);
                }, false);
        else if (!person.id.isLocal() && person.can(PersonSnapshot.Capability.MANAGE_CONNECTION) && People.connectionUi() != null) {
            PersonUi.action(body, R.string.role_manage_connection, () -> {
                ConnectionUi ui = People.connectionUi();
                if (guardTarget() && ui != null) ui.openConnection(this, person.id);
            }, false);
        }
    }

    private void freshness(LinearLayout parent, PersonSnapshot.Freshness freshness) {
        if (freshness != PersonSnapshot.Freshness.CURRENT) PersonUi.text(parent,
                getString(freshness == PersonSnapshot.Freshness.STALE
                        ? R.string.role_stale_data : R.string.role_unknown_data), false);
    }
}
