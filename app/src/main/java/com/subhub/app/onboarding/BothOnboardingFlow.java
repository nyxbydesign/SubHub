package com.subhub.app.onboarding;

import com.subhub.app.R;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;

import static com.subhub.app.onboarding.OnboardingPage.Kind;
import static com.subhub.app.onboarding.OnboardingPage.Scene;
import static com.subhub.app.onboarding.OnboardingPage.Workspace;

/** Sub setup followed by Dom lessons; page workspaces describe the lesson's context. */
public final class BothOnboardingFlow {
    private BothOnboardingFlow() {}

    public static List<OnboardingPage> pages() {
        List<OnboardingPage> pages = new ArrayList<>();
        EnumSet<Kind> setupSeen = EnumSet.noneOf(Kind.class);
        EnumSet<Scene> sharedStories = EnumSet.noneOf(Scene.class);
        append(pages, SubOnboardingFlow.pages(), setupSeen, sharedStories);

        pages.add(OnboardingPage.story(
                "both_dom_handoff",
                R.string.onboarding_both_handoff_title,
                R.string.onboarding_both_handoff_description,
                Scene.SWITCH_ROLES,
                Workspace.DOM,
                OnboardingPage.detail(R.string.onboarding_both_target_title,
                        R.string.onboarding_both_target_body),
                OnboardingPage.detail(R.string.onboarding_both_switch_title,
                        R.string.onboarding_both_switch_body),
                OnboardingPage.detail(R.string.onboarding_both_access_title,
                        R.string.onboarding_both_access_body)));

        append(pages, DomOnboardingFlow.pages(), setupSeen, sharedStories);
        return Collections.unmodifiableList(pages);
    }

    private static void append(List<OnboardingPage> target, List<OnboardingPage> source,
            EnumSet<Kind> setupSeen, EnumSet<Scene> sharedStories) {
        for (OnboardingPage page : source) {
            // Pairing is taught once in Sub; the Dom handoff reuses that relationship.
            // Device-wide permissions follow the complete journey in the activity.
            if (page.scene == Scene.CONNECT_SUB || page.kind == Kind.PERMISSIONS || page.scene == Scene.PERMISSIONS
                    || page.scene == Scene.SWITCH_ROLES) continue;

            // Security has both instructional and setup forms; either covers the shared lesson.
            Kind sharedKind = page.scene == Scene.SECURITY ? Kind.SECURITY : page.kind;
            if (sharedKind != Kind.STORY && !setupSeen.add(sharedKind)) continue;
            // Setup controls can share the LOCAL_FEATURES hero; only story pages repeat it.
            if (page.kind == Kind.STORY
                    && (page.scene == Scene.LOCAL_FEATURES || page.scene == Scene.HARDCORE)
                    && !sharedStories.add(page.scene)) continue;

            target.add(page);
        }
    }
}
