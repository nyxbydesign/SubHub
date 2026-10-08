package com.subhub.app.overlay;

import com.subhub.app.detection.BBox;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Decoration ownership only: no censor pixels or raw associations are removed. */
final class PersonDecorationLayout {
    static Set<Integer> suppressed(List<RenderTrackSnapshot> tracks, List<BBox> base,
            List<BBox> expanded) {
        int size = tracks.size();
        if (size > 128 || base.size() != size || expanded.size() != size) return Collections.emptySet();
        Set<Integer> suppressed = new HashSet<>();
        for (int child = 0; child < size; child++) {
            RenderTrackSnapshot childTrack = tracks.get(child);
            if ("text_smut".equals(childTrack.category())) continue;
            BBox box = expanded.get(child);
            boolean childExpanded = box.getArea() > base.get(child).getArea();
            for (int parent = 0; parent < size; parent++) {
                if (parent == child) continue;
                RenderTrackSnapshot parentTrack = tracks.get(parent);
                BBox enclosing = expanded.get(parent);
                if ("text_smut".equals(parentTrack.category())
                        || enclosing.getArea() <= base.get(parent).getArea()
                        || !parentTrack.reference().sameBasis(childTrack.reference())
                        || !contains(enclosing, box)) continue;
                // Equal expansions choose one stable owner; input order never swaps the label.
                if (enclosing.getArea() == box.getArea() && childExpanded
                        && parentTrack.id() > childTrack.id()) continue;
                suppressed.add(childTrack.id());
                break;
            }
        }
        return suppressed;
    }

    private static boolean contains(BBox outer, BBox inner) {
        return outer.getX() <= inner.getX() && outer.getY() <= inner.getY()
                && outer.getRight() >= inner.getRight() && outer.getBottom() >= inner.getBottom();
    }
}
