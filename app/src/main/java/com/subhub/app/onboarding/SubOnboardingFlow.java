package com.subhub.app.onboarding;

import static com.subhub.app.onboarding.OnboardingPage.detail;
import static com.subhub.app.onboarding.OnboardingPage.setup;
import static com.subhub.app.onboarding.OnboardingPage.story;

import com.subhub.app.R;
import com.subhub.app.onboarding.OnboardingPage.Action;
import com.subhub.app.onboarding.OnboardingPage.Kind;
import com.subhub.app.onboarding.OnboardingPage.Scene;
import com.subhub.app.onboarding.OnboardingPage.Workspace;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Sub setup introduces shared control before optional local configuration and security. */
public final class SubOnboardingFlow {
    private SubOnboardingFlow() {}

    public static List<OnboardingPage> pages() {
        return Collections.unmodifiableList(Arrays.asList(
                story("sub_connect_dom", R.string.onboarding_sub_connect_title,
                        R.string.onboarding_sub_connect_body, Scene.CONNECT_DOM, Workspace.SUB,
                        detail(R.string.onboarding_sub_pair_steps_title,
                                R.string.onboarding_sub_pair_steps_body),
                        detail(R.string.onboarding_sub_shared_title,
                                R.string.onboarding_sub_shared_body),
                        detail(R.string.onboarding_sub_unpair_title,
                                R.string.onboarding_sub_unpair_body))
                        .action(R.string.onboarding_sub_pair_action, Action.PAIR_DOM),
                story("sub_tasks", R.string.onboarding_sub_tasks_title,
                        R.string.onboarding_sub_tasks_body, Scene.SUB_TASKS, Workspace.SUB,
                        detail(R.string.onboarding_sub_submit_title,
                                R.string.onboarding_sub_submit_body),
                        detail(R.string.onboarding_sub_review_title,
                                R.string.onboarding_sub_review_body)),
                setup("sub_apps", R.string.onboarding_sub_apps_title,
                        R.string.onboarding_sub_apps_body, Scene.LOCAL_FEATURES,
                        Workspace.SUB, Kind.APPS),
                story("sub_allowed_features", R.string.onboarding_sub_features_title,
                        R.string.onboarding_sub_features_body, Scene.LOCAL_FEATURES, Workspace.SUB,
                        detail(R.string.onboarding_sub_feature_control_title,
                                R.string.onboarding_sub_feature_control_body)),
                story("sub_hardcore", R.string.onboarding_sub_hardcore_title,
                        R.string.onboarding_sub_hardcore_body, Scene.HARDCORE, Workspace.SUB,
                        detail(R.string.onboarding_sub_hardcore_controls_title,
                                R.string.onboarding_sub_hardcore_controls_body),
                        detail(R.string.onboarding_sub_hardcore_choices_title,
                                R.string.onboarding_sub_hardcore_choices_body),
                        detail(R.string.onboarding_sub_hardcore_payments_title,
                                R.string.onboarding_sub_hardcore_payments_body))
                        .action(R.string.onboarding_sub_hardcore_action, Action.HARDCORE),
                setup("sub_security", R.string.onboarding_sub_security_title,
                        R.string.onboarding_sub_security_body, Scene.SECURITY,
                        Workspace.SUB, Kind.SECURITY)));
    }
}
