package com.subhub.app.onboarding;

import com.subhub.app.R;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static com.subhub.app.onboarding.OnboardingPage.detail;
import static com.subhub.app.onboarding.OnboardingPage.setup;
import static com.subhub.app.onboarding.OnboardingPage.story;

/** Controller setup and the journey from invitation to reviewing a Sub's work. */
public final class DomOnboardingFlow {
    private DomOnboardingFlow() { }

    public static List<OnboardingPage> pages() {
        return Collections.unmodifiableList(Arrays.asList(
                setup("dom_security",
                        R.string.dom_onboarding_security_title,
                        R.string.dom_onboarding_security_body,
                        OnboardingPage.Scene.SECURITY,
                        OnboardingPage.Workspace.DEVICE,
                        OnboardingPage.Kind.SECURITY),
                story("dom_add_sub",
                        R.string.dom_onboarding_pair_title,
                        R.string.dom_onboarding_pair_body,
                        OnboardingPage.Scene.CONNECT_SUB,
                        OnboardingPage.Workspace.DOM,
                        detail(R.string.dom_onboarding_invitation_title,
                                R.string.dom_onboarding_invitation_body),
                        detail(R.string.dom_onboarding_approval_title,
                                R.string.dom_onboarding_approval_body),
                        detail(R.string.dom_onboarding_profiles_title,
                                R.string.dom_onboarding_profiles_body))
                        .action(R.string.dom_onboarding_pair_action, OnboardingPage.Action.PAIR_SUB),
                story("dom_contracts",
                        R.string.dom_onboarding_contract_title,
                        R.string.dom_onboarding_contract_body,
                        OnboardingPage.Scene.CONTRACTS,
                        OnboardingPage.Workspace.DOM,
                        detail(R.string.dom_onboarding_rules_title,
                                R.string.dom_onboarding_rules_body),
                        detail(R.string.dom_onboarding_apps_title,
                                R.string.dom_onboarding_apps_body),
                        detail(R.string.dom_onboarding_sessions_title,
                                R.string.dom_onboarding_sessions_body)),
                story("dom_allowed_features",
                        R.string.dom_onboarding_consent_title,
                        R.string.dom_onboarding_consent_body,
                        OnboardingPage.Scene.LOCAL_FEATURES,
                        OnboardingPage.Workspace.DOM,
                        detail(R.string.dom_onboarding_opt_out_title,
                                R.string.dom_onboarding_opt_out_body),
                        detail(R.string.dom_onboarding_disabled_title,
                                R.string.dom_onboarding_disabled_body),
                        detail(R.string.dom_onboarding_revocation_title,
                                R.string.dom_onboarding_revocation_body)),
                story("dom_hardcore",
                        R.string.dom_onboarding_hardcore_title,
                        R.string.dom_onboarding_hardcore_body,
                        OnboardingPage.Scene.HARDCORE,
                        OnboardingPage.Workspace.DOM,
                        detail(R.string.dom_onboarding_hardcore_consent_title,
                                R.string.dom_onboarding_hardcore_consent_body),
                        detail(R.string.dom_onboarding_hardcore_override_title,
                                R.string.dom_onboarding_hardcore_override_body),
                        detail(R.string.dom_onboarding_hardcore_release_title,
                                R.string.dom_onboarding_hardcore_release_body)),
                story("dom_create_task",
                        R.string.dom_onboarding_create_title,
                        R.string.dom_onboarding_create_body,
                        OnboardingPage.Scene.CREATE_TASK,
                        OnboardingPage.Workspace.DOM,
                        detail(R.string.dom_onboarding_schedule_title,
                                R.string.dom_onboarding_schedule_body),
                        detail(R.string.dom_onboarding_attachment_title,
                                R.string.dom_onboarding_attachment_body),
                        detail(R.string.dom_onboarding_refusal_title,
                                R.string.dom_onboarding_refusal_body)),
                story("dom_review_task",
                        R.string.dom_onboarding_review_title,
                        R.string.dom_onboarding_review_body,
                        OnboardingPage.Scene.REVIEW_TASK,
                        OnboardingPage.Workspace.DOM,
                        detail(R.string.dom_onboarding_proof_title,
                                R.string.dom_onboarding_proof_body),
                        detail(R.string.dom_onboarding_outside_title,
                                R.string.dom_onboarding_outside_body),
                        detail(R.string.dom_onboarding_verdict_title,
                                R.string.dom_onboarding_verdict_body))));
    }
}
