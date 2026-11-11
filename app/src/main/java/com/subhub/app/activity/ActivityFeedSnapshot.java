package com.subhub.app.activity;

import java.util.List;
import java.util.Objects;

/** Presentation equality avoids rebuilding unchanged rows for receipt/icon notifications. */
final class ActivityFeedSnapshot {
    private ActivityFeedSnapshot() { }

    static boolean same(List<ActivityEntry> before, List<ActivityEntry> after) {
        if (before.size() != after.size()) return false;
        for (int index = 0; index < before.size(); index++) {
            ActivityEntry a = before.get(index), b = after.get(index);
            if (!a.key.equals(b.key) || !a.personId.equals(b.personId) || !a.personName.equals(b.personName)
                    || !a.device.equals(b.device) || a.unread != b.unread || a.receivedAt != b.receivedAt
                    || !a.appLabel.equals(b.appLabel) || !sameIcon(a, b) || !sameEvent(a.event, b.event)) return false;
        }
        return true;
    }

    private static boolean sameIcon(ActivityEntry a, ActivityEntry b) {
        if (a.appIcon == b.appIcon) return true;
        if (a.appIcon == null || b.appIcon == null) return false;
        return a.appIcon.peer.equals(b.appIcon.peer) && a.appIcon.generation.equals(b.appIcon.generation)
                && a.appIcon.packageName.equals(b.appIcon.packageName)
                && Objects.equals(a.appIcon.descriptor, b.appIcon.descriptor);
    }

    private static boolean sameEvent(ActivityEvent a, ActivityEvent b) {
        return a.id.equals(b.id) && a.kind == b.kind && a.observedAt == b.observedAt
                && a.packageName.equals(b.packageName) && a.amountCents == b.amountCents
                && a.currency.equals(b.currency) && a.reason.equals(b.reason) && a.limitMinutes == b.limitMinutes;
    }
}
