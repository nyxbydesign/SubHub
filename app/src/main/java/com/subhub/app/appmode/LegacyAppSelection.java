package com.subhub.app.appmode;

import java.util.LinkedHashSet;
import java.util.Set;

/** One-time conversion only; retired feature assignments never participate in live policy. */
public final class LegacyAppSelection {
    private LegacyAppSelection() {}

    public static boolean wasAllApps(
            String mode, boolean explicit, Set<String> censor, boolean legacy) {
        return legacy
                && !"selected".equals(mode)
                && (explicit || AppModePolicy.sanitizePackages(censor).isEmpty());
    }

    public static Set<String> merge(
            boolean all,
            Set<String> censor,
            Set<String> limits,
            Set<String> whispers,
            Set<String> installed) {
        Set<String> result = new LinkedHashSet<>();
        result.addAll(AppModePolicy.sanitizePackages(censor));
        result.addAll(AppModePolicy.sanitizePackages(limits));
        result.addAll(AppModePolicy.sanitizePackages(whispers));
        if (all) result.addAll(AppModePolicy.sanitizePackages(installed));
        return AppModePolicy.sanitizePackages(result);
    }
}
