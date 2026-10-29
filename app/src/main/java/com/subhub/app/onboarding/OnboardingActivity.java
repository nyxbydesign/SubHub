package com.subhub.app.onboarding;

import android.Manifest;
import android.content.*;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import com.subhub.app.MainActivity;
import com.subhub.app.R;
import com.subhub.app.appmode.AppModeManager;
import com.subhub.app.people.People;
import com.subhub.app.security.*;
import com.subhub.app.settings.*;
import com.subhub.app.util.*;
import java.util.List;

/** Role-specific setup resumes stable page IDs; replay preserves saved role and feature choices. */
public final class OnboardingActivity extends PreferencePage {
    public static final String REPLAY = "replay";
    private int step;
    private boolean replay, roleChosen, censor, limits, wallet, appearanceChanged, appearanceAuthorized;
    private CensorAppearance.Type style;
    private DeviceRolePreferences.Mode selectedRole;
    private List<OnboardingPage> flow;
    private LinearLayout body;
    private boolean resumeRefresh;
    private SetupWalletRulesView walletRules;
    private Bundle walletDraft;
    private com.subhub.app.settings.AppSelectionPanel appSelection;
    private final ActivityResultLauncher<String> notifications = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(), ignored -> render(true));

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        replay = getIntent().getBooleanExtra(REPLAY, false);
        FeatureModuleManager modules = new FeatureModuleManager(this);
        censor = modules.isCensorEnabled(); limits = modules.isLimitsEnabled(); wallet = modules.isWalletEnabled();
        style = new SettingsRepository(this).loadAppearance().getType();
        selectedRole = DeviceRolePreferences.get(this);
        flow = OnboardingFlow.pages(selectedRole);
        SharedPreferences draft = getSharedPreferences("subhub_onboarding", 0);
        if (!replay && state == null && OnboardingState.inProgress(this)) {
            int version = draft.getInt("draft_flow_version", 1);
            String id = version < OnboardingFlow.VERSION
                    ? OnboardingFlow.legacyPage(flow, version, draft.getInt("draft_step", 0))
                    : draft.getString("draft_page", OnboardingFlow.ROLE);
            roleChosen = version < OnboardingFlow.VERSION ? !OnboardingFlow.ROLE.equals(id)
                    : draft.getBoolean("draft_role_chosen", false);
            if (version >= OnboardingFlow.VERSION && !selectedRole.name().equals(draft.getString("draft_role", ""))) {
                id = OnboardingFlow.ROLE; roleChosen = false;
            }
            step = roleChosen ? OnboardingFlow.index(flow, id) : 0;
            walletDraft = SetupWalletRulesView.readDraft(draft);
            censor = draft.getBoolean("draft_censor", censor);
            style = CensorAppearance.Type.fromPreference(draft.getString("draft_style", style.getPreferenceValue()));
            appearanceChanged = draft.getBoolean("draft_appearance_changed", false);
            appearanceAuthorized = draft.getBoolean("draft_appearance_authorized", version < OnboardingFlow.VERSION
                    && appearanceChanged && localSetupAllowed());
        }
        if (!replay && !OnboardingState.completed(this)) OnboardingState.begin(this);
        if (state != null) {
            String role = state.getString("role", selectedRole.name());
            try { if (replay) selectedRole = DeviceRolePreferences.Mode.valueOf(role); }
            catch (IllegalArgumentException invalid) { selectedRole = DeviceRolePreferences.get(this); }
            flow = OnboardingFlow.pages(selectedRole);
            roleChosen = state.getBoolean("role_chosen") && (replay || selectedRole.name().equals(role));
            step = roleChosen ? OnboardingFlow.index(flow, state.getString("page", OnboardingFlow.ROLE)) : 0;
            censor = state.getBoolean("censor", censor);
            style = CensorAppearance.Type.fromPreference(state.getString("style", style.getPreferenceValue()));
            appearanceChanged = state.getBoolean("appearance_changed"); walletDraft = state.getBundle("wallet_rules");
            appearanceAuthorized = state.getBoolean("appearance_authorized");
        }
        getOnBackPressedDispatcher().addCallback(this, new androidx.activity.OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { back(); }
        });
        render();
    }

    @Override protected void onResume() {
        super.onResume();
        if (resumeRefresh && body != null) {
            if (!replay && selectedRole != DeviceRolePreferences.get(this)) {
                selectedRole = DeviceRolePreferences.get(this); flow = OnboardingFlow.pages(selectedRole);
                step = 0; roleChosen = false;
            }
            render(true);
        }
        if (appSelection != null) appSelection.reload();
        resumeRefresh = false;
    }
    @Override protected void onPause() { saveDraft(); resumeRefresh = true; super.onPause(); }
    @Override protected void onSaveInstanceState(Bundle state) {
        captureWalletDraft();
        state.putString("role", selectedRole.name()); state.putBoolean("role_chosen", roleChosen);
        state.putString("page", OnboardingFlow.pageId(flow, step));
        state.putBoolean("censor", censor); state.putString("style", style.getPreferenceValue());
        state.putBoolean("appearance_changed", appearanceChanged); state.putBundle("wallet_rules", walletDraft);
        state.putBoolean("appearance_authorized", appearanceAuthorized);
        super.onSaveInstanceState(state);
    }

    private void back() {
        captureWalletDraft();
        if (step > 0) { step--; render(); }
        else finish();
    }

    private void render() { render(false); }
    private void render(boolean preserveScroll) {
        if (isFinishing() || isDestroyed()) return;
        ScrollView prior = findViewById(R.id.tour_scroll);
        int scroll = preserveScroll && prior != null ? prior.getScrollY() : 0;
        step = Math.max(0, Math.min(flow.size(), step));
        saveDraft(); walletRules = null; appSelection = null;
        setContentView(R.layout.activity_onboarding); page = findViewById(R.id.tour_content); body = page;
        PrimaryHeader.bindSecondary(findViewById(android.R.id.content), R.string.tour_title, false);
        findViewById(R.id.header_role_badge).setVisibility(View.GONE);
        PrimaryHeader.backButton(findViewById(android.R.id.content)).setOnClickListener(v -> back());
        LinearLayout progress = findViewById(R.id.tour_steps);
        TextView count = findViewById(R.id.tour_progress);
        ((View) progress.getParent()).setVisibility(step == 0 ? View.GONE : View.VISIBLE);
        if (step > 0) {
            count.setText(getString(R.string.tour_progress, step, flow.size()));
            for (int i = 0; i < flow.size(); i++) {
                View segment = new View(this);
                segment.setBackgroundResource(i < step ? R.drawable.bg_primary_button : R.drawable.bg_outline_button);
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(4), 1);
                params.setMargins(dp(2), 0, dp(2), 0); progress.addView(segment, params);
            }
        }
        if (step == 0) chooseRole(); else showPage(flow.get(step - 1));
        Button next = findViewById(R.id.tour_next);
        next.setBackgroundResource(R.drawable.bg_primary_button); next.setTextColor(getColor(R.color.text_primary));
        next.setText(step == flow.size() ? R.string.tour_finish : R.string.tour_next);
        next.setEnabled(step > 0 || roleChosen);
        next.setOnClickListener(v -> next());
        View skip = findViewById(R.id.tour_skip); skip.setVisibility(step == 0 ? View.GONE : View.VISIBLE);
        skip.setOnClickListener(v -> finishSetup());
        InputFocusPolicy.openActivityForReading(this);
        ScrollView current = findViewById(R.id.tour_scroll);
        current.post(() -> { if (!isFinishing()) current.scrollTo(0, scroll); });
    }

    private void chooseRole() {
        body.setTag("onboarding_page:role");
        TextView title = OnboardingPageUi.text(body, getString(R.string.onboarding_choose_role), 28, false);
        title.setId(R.id.tour_step_title); title.setTypeface(null, android.graphics.Typeface.BOLD);
        androidx.core.view.ViewCompat.setAccessibilityHeading(title, true);
        LinearLayout.LayoutParams heading = (LinearLayout.LayoutParams) title.getLayoutParams();
        heading.bottomMargin = dp(20); title.setLayoutParams(heading);
        body.addView(new OnboardingRolePickerView(this, roleChosen ? selectedRole : null, this::requestRole),
                new LinearLayout.LayoutParams(-1, -2));
    }

    private void requestRole(DeviceRolePreferences.Mode role) {
        if (replay || role == DeviceRolePreferences.get(this)) { choose(role); return; }
        if (!localRoleAuthority()) { blockedRoleNotice(); return; }
        if (role == DeviceRolePreferences.Mode.DOM_ONLY && DeviceRolePreferences.hasSubProtection(this)) {
            notice(getString(R.string.device_role_end_session)); return;
        }
        Runnable apply = () -> {
            if (localRoleAuthority() && DeviceRolePreferences.set(this, role)) choose(role);
            else notice(getString(R.string.device_role_change_unavailable));
        };
        if (OnboardingState.isPristine(this)) apply.run();
        else if (!ControllerPinManager.hasCredentials(this))
            ControllerPinGate.setupLocalPin(this, () -> render(true));
        else ControllerPinGate.authenticate(this, apply, false);
    }

    private void choose(DeviceRolePreferences.Mode role) {
        captureWalletDraft(); selectedRole = role; roleChosen = true;
        flow = OnboardingFlow.pages(role); step = 0; render();
    }

    private boolean localRoleAuthority() {
        try { return People.keyholder().state() == KeyholderProvider.State.NONE && People.isLocalConfigurationAllowed(); }
        catch (RuntimeException unavailable) { return false; }
    }

    private void blockedRoleNotice() {
        int message = R.string.device_role_managed;
        try {
            KeyholderProvider.State state = People.keyholder().state();
            if (state == KeyholderProvider.State.UNAVAILABLE) message = R.string.device_role_unavailable;
            else if (state == KeyholderProvider.State.PENDING) message = R.string.keyholder_pairing_in_progress;
        } catch (RuntimeException unavailable) { message = R.string.device_role_unavailable; }
        notice(getString(message));
    }

    private void showPage(OnboardingPage spec) {
        OnboardingPageUi.render(body, spec);
        switch (spec.kind) {
            case APPEARANCE: appearance(); break;
            case WALLET: walletRules(); break;
            case APPS: features(); break;
            case SECURITY: security(); break;
            case PERMISSIONS: permissions(); break;
            default: break;
        }
        if (spec.action != OnboardingPage.Action.NONE)
            button(body, getString(spec.actionLabel), () -> performAction(spec.action))
                    .setTag("onboarding_action:" + spec.id);
    }

    private void next() {
        if (step == 0 && !roleChosen) return;
        if (walletRules != null && !walletRules.applyRules()) return;
        captureWalletDraft();
        if (step == flow.size()) finishSetup(); else { step++; render(); }
    }

    private void performAction(OnboardingPage.Action action) {
        saveDraft();
        if (action == OnboardingPage.Action.HARDCORE) {
            if (!DeviceRolePreferences.supportsSub(this) || DeviceRolePreferences.isDomWorkspace(this)) {
                notice(getString(R.string.onboarding_hardcore_sub_only)); return;
            }
            startActivity(new Intent(this, GlobalSettingsActivity.class).putExtra("settings_group", "permissions"));
        } else {
            boolean dom = action == OnboardingPage.Action.PAIR_SUB;
            if (dom && !DeviceRolePreferences.supportsDom(this) || !dom && !DeviceRolePreferences.supportsSub(this)) {
                notice(getString(R.string.onboarding_replay_action)); return;
            }
            if (dom && !localRoleAuthority()) { blockedRoleNotice(); return; }
            Runnable open = () -> {
                if (dom && !localRoleAuthority()) { blockedRoleNotice(); return; }
                if (dom && !DeviceRolePreferences.supportsDom(this) || !dom && !DeviceRolePreferences.supportsSub(this)) return;
                if (People.connectionUi() != null) People.connectionUi().openPairing(this, dom);
                else notice(getString(R.string.role_pairing_unavailable));
            };
            if (dom && !ControllerPinManager.hasCredentials(this) && !ControllerPinManager.allowsUnkeyedAccess(this)) {
                for (int i = 0; i < flow.size(); i++) if (flow.get(i).kind == OnboardingPage.Kind.SECURITY) { step = i + 1; break; }
                render(); notice(getString(R.string.device_role_pin_setup_required)); return;
            }
            if (dom) ControllerPinGate.unlock(this, open, false); else open.run();
        }
    }

    private boolean domOnly() { return selectedRole == DeviceRolePreferences.Mode.DOM_ONLY; }

    private void security() {
        LinearLayout local = card(body); local.setId(R.id.keyholder_setup_local);
        if (!localRoleAuthority()) {
            text(local, getString(R.string.keyholder_remote_control), 17, false);
            text(local, getString(R.string.keyholder_remote_help), 14, true); return;
        }
        boolean pin = ControllerPinManager.isConfigured(this);
        text(local, getString(pin ? R.string.onboarding_security_ready : R.string.onboarding_security_body), 15, false);
        button(local, getString(pin ? R.string.keyholder_pin_change : R.string.controller_pin_set),
                () -> ControllerPinGate.setupLocalPin(this, () -> render(true)))
                .setId(R.id.keyholder_pin_change_button);
    }

    private void features() {
        LinearLayout apps = card(body);
        apps.setTag("setup-app-card");
        apps.setLayoutParams(new LinearLayout.LayoutParams(-1, 0, 1));
        appSelection = new com.subhub.app.settings.AppSelectionPanel(this);
        apps.addView(appSelection, new LinearLayout.LayoutParams(-1, -1));
        appSelection.fillAvailableHeight();
        appSelection.bind(this,
                this::editableSetup,
                this::saveDraft);
        appSelection.load();
        ScrollView scroll = findViewById(R.id.tour_scroll);
        apps.post(() -> {
            if (apps.getParent() != body || !apps.isAttachedToWindow()) return;
            View list = apps.findViewById(R.id.app_list);
            LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) apps.getLayoutParams();
            int available = scroll.getHeight() - apps.getTop() - body.getPaddingBottom() - params.bottomMargin;
            int controls = appSelection.getHeight() - list.getHeight() + apps.getPaddingTop() + apps.getPaddingBottom();
            // On short screens the page scrolls rather than reducing app choices to a single row.
            params.height = Math.max(available, controls + dp(168));
            params.weight = 0;
            apps.setLayoutParams(params);
        });
    }

    private void walletRules() {
        walletRules = new SetupWalletRulesView(this, walletDraft,
                this::editableSetup);
        body.addView(walletRules, new LinearLayout.LayoutParams(-1, -2));
        ScrollView scroll = findViewById(R.id.tour_scroll);
        scroll.post(() -> {
            if (walletRules != null && walletRules.getParent() == body) {
                walletRules.fitGraphic(scroll.getHeight() - body.getHeight());
            }
        });
    }

    private void appearance() {
        preview(body, style, dp(200));
        CensorAppearance.Type[] types = {
            CensorAppearance.Type.BOX,
            CensorAppearance.Type.PIXELATE,
            CensorAppearance.Type.BLUR,
            CensorAppearance.Type.STATIC
        };
        int[] labels = {
            R.string.style_box, R.string.style_pixelate, R.string.style_blur, R.string.style_static
        };
        SelectionGrid choices = new SelectionGrid(this, null);
        body.addView(choices, new LinearLayout.LayoutParams(-1, -2));
        for (int i = 0; i < types.length; i++) {
            final CensorAppearance.Type chosen = types[i];
            LinearLayout item = card(choices);
            item.setPadding(dp(10), dp(10), dp(10), dp(10));
            LinearLayout.LayoutParams itemParams =
                    (LinearLayout.LayoutParams) item.getLayoutParams();
            itemParams.setMargins(dp(4), dp(4), dp(4), dp(8));
            item.setLayoutParams(itemParams);
            item.setBackgroundResource(R.drawable.bg_choice_card);
            item.setSelected(style == chosen);
            preview(item, chosen, dp(86));
            Button pick =
                    button(
                            item,
                            getString(labels[i]),
                            () -> {
                                style = chosen;
                                appearanceChanged = true;
                                appearanceAuthorized = editableSetup();
                                render();
                            });
            pick.setSelected(style == chosen);
            pick.setTag("setup-style:" + chosen.getPreferenceValue());
            pick.setTextColor(
                    getColor(style == chosen ? R.color.text_primary : R.color.accent_hot));
            pick.setBackgroundResource(
                    style == chosen ? R.drawable.bg_primary_button : R.drawable.bg_outline_button);
        }
    }

    private void permissions() {
        boolean runtime = DeviceRolePreferences.supportsSub(this) && !domOnly() && (censor || limits);
        if (runtime)
            permission(
                    R.string.tour_accessibility_title,
                    R.string.tour_accessibility_help,
                    new AppModeManager(this).isAccessibilityEnabled(),
                    () -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        if (DeviceRolePreferences.supportsSub(this) && !domOnly() && censor)
            permission(
                    R.string.tour_overlay_title,
                    R.string.tour_overlay_help,
                    Settings.canDrawOverlays(this),
                    () ->
                            startActivity(
                                    new Intent(
                                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                            Uri.parse("package:" + getPackageName()))));
        if (Build.VERSION.SDK_INT >= 33 && (domOnly() || runtime || wallet))
            permission(
                    R.string.tour_notifications_title,
                    R.string.tour_notifications_help,
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                            == PackageManager.PERMISSION_GRANTED,
                    () -> notifications.launch(Manifest.permission.POST_NOTIFICATIONS));
        if (runtime)
            button(body, getString(R.string.permission_setup_open), () -> startActivity(
                    new Intent(this, com.subhub.app.help.PermissionSetupActivity.class)));
        if (Build.VERSION.SDK_INT < 33 && domOnly() || !domOnly() && !runtime && !wallet)
            text(body, getString(R.string.tour_no_permissions), 15, true);
    }

    private void permission(int name, int reason, boolean ready, Runnable open) {
        LinearLayout c = card(body);
        text(c, getString(name), 17, false).setTypeface(null, android.graphics.Typeface.BOLD);
        text(c, getString(reason), 14, true);
        button(c, getString(ready ? R.string.tour_allowed : R.string.tour_allow), open)
                .setEnabled(!ready);
        c.getChildAt(c.getChildCount() - 1).setTag("onboarding_permission:" + getResources().getResourceEntryName(name));
    }

    private void preview(LinearLayout parent, CensorAppearance.Type type, int height) {
        CensorPreviewView preview = new CensorPreviewView(this, null);
        // Keep the woman in the main preview, and use close-up effects in the
        // small choices so blur/pixelation remain legible at thumbnail size.
        preview.setPhonePreview(parent == body);
        preview.setTag(type.getPreferenceValue());
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, height);
        p.bottomMargin = dp(14);
        parent.addView(preview, p);
    }

    private void saveDraft() {
        captureWalletDraft();
        if (!replay && OnboardingState.inProgress(this)) getSharedPreferences("subhub_onboarding", 0).edit()
                .putInt("draft_step", step).putInt("draft_flow_version", OnboardingFlow.VERSION)
                .putString("draft_page", OnboardingFlow.pageId(flow, step))
                .putString("draft_role", selectedRole.name()).putBoolean("draft_role_chosen", roleChosen)
                .putString("draft_wallet_rules", SetupWalletRulesView.encodeDraft(walletDraft))
                .putBoolean("draft_censor", censor).putBoolean("draft_limits", limits).putBoolean("draft_wallet", wallet)
                .putString("draft_style", style.getPreferenceValue()).putBoolean("draft_appearance_changed", appearanceChanged)
                .putBoolean("draft_appearance_authorized", appearanceAuthorized).apply();
    }
    private void captureWalletDraft() { if (walletRules != null) walletDraft = walletRules.snapshot(); }
    private boolean editableSetup() {
        return !replay && localSetupAllowed() && (ControllerPinManager.isDomModeActive()
                || !ControllerPinManager.hasCredentials(this));
    }
    private boolean localSetupAllowed() {
        try { return DeviceRolePreferences.supportsSub(this)
                && People.keyholder().state() == KeyholderProvider.State.NONE && People.isLocalConfigurationAllowed(); }
        catch (RuntimeException unavailable) { return false; }
    }
    private void finishSetup() {
        if (!replay && DeviceRolePreferences.get(this) == DeviceRolePreferences.Mode.DOM_ONLY
                && !ControllerPinManager.isConfigured(this)) {
            for (int i = 0; i < flow.size(); i++) if (flow.get(i).kind == OnboardingPage.Kind.SECURITY) { step = i + 1; break; }
            render(); notice(getString(R.string.device_role_pin_setup_required)); return;
        }
        if (!replay) {
            if (appearanceChanged && appearanceAuthorized && localSetupAllowed()) {
                SettingsRepository settings = new SettingsRepository(this); CensorAppearance old = settings.loadAppearance();
                settings.saveAppearance(style, old.getIntensity(), old.isShowBorder(), old.isShowText());
            }
            if (!ControllerPinManager.hasCredentials(this)) ControllerPinManager.useWithoutKeyholder(this);
            OnboardingState.complete(this); ControllerPinManager.enterSubMode();
        }
        startActivity(new Intent(this, MainActivity.class).setAction(Intent.ACTION_MAIN).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP));
        finish();
    }
}
