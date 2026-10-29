package com.subhub.app.onboarding;

import com.subhub.app.R;
import com.subhub.app.security.DeviceRolePreferences;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Stable page identities keep a resumed draft independent of presentation order. */
public final class OnboardingFlow {
    public static final int VERSION = 4;
    public static final int STABLE_IDS_VERSION = 3;
    public static final String ROLE = "role";
    private OnboardingFlow() { }

    public static List<OnboardingPage> pages(DeviceRolePreferences.Mode role) {
        List<OnboardingPage> pages = new ArrayList<>(role == DeviceRolePreferences.Mode.SUB_ONLY
                ? SubOnboardingFlow.pages() : role == DeviceRolePreferences.Mode.DOM_ONLY
                ? DomOnboardingFlow.pages() : BothOnboardingFlow.pages());
        pages.add(OnboardingPage.setup("device_permissions", R.string.tour_permissions,
                R.string.onboarding_permissions_body, OnboardingPage.Scene.PERMISSIONS,
                OnboardingPage.Workspace.DEVICE, OnboardingPage.Kind.PERMISSIONS));
        Set<String> ids = new HashSet<>();
        for (OnboardingPage page : pages)
            if (!ids.add(page.id)) throw new IllegalStateException("Duplicate setup page identity");
        return Collections.unmodifiableList(pages);
    }

    public static int index(List<OnboardingPage> pages, String id) {
        for (int i = 0; i < pages.size(); i++) if (pages.get(i).id.equals(id)) return i + 1;
        return 0;
    }

    public static String pageId(List<OnboardingPage> pages, int step) {
        return step > 0 && step <= pages.size() ? pages.get(step - 1).id : ROLE;
    }

    public static String resumePage(List<OnboardingPage> pages, String id) {
        if (index(pages, id) > 0 || ROLE.equals(id)) return id;
        // Finish the Sub lessons before reaching the moved Dom settings later in Both.
        if ("sub_appearance".equals(id) || "sub_wallet".equals(id))
            return index(pages, "sub_apps") > 0 ? "sub_apps" : ROLE;
        if ("dom_hardcore".equals(id))
            return index(pages, "dom_create_task") > 0 ? "dom_create_task" : ROLE;
        if ("dom_add_sub".equals(id))
            return index(pages, "dom_wallet") > 0 ? "dom_wallet" : ROLE;
        return ROLE;
    }

    public static String legacyPage(List<OnboardingPage> pages, int version, int step) {
        if (version < 2) {
            int[] migrated = {0, 3, 1, 4, 5, 5};
            step = migrated[Math.max(0, Math.min(5, step))];
        }
        if (step <= 0) return ROLE;
        OnboardingPage.Kind[] kinds = {OnboardingPage.Kind.STORY, OnboardingPage.Kind.APPEARANCE,
                OnboardingPage.Kind.WALLET, OnboardingPage.Kind.APPS,
                OnboardingPage.Kind.SECURITY, OnboardingPage.Kind.PERMISSIONS};
        OnboardingPage.Kind kind = kinds[Math.min(5, step)];
        if (kind == OnboardingPage.Kind.APPEARANCE || kind == OnboardingPage.Kind.WALLET)
            return index(pages, "sub_apps") > 0 ? "sub_apps" : pages.get(0).id;
        for (OnboardingPage page : pages) if (page.kind == kind) return page.id;
        return pages.isEmpty() ? ROLE : pages.get(0).id;
    }
}
