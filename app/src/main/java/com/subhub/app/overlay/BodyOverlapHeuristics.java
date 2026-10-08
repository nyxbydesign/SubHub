package com.subhub.app.overlay;

import com.subhub.app.detection.BBox;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Comparator;

/** Cheap render grouping hints, not person recognition or detector confirmation. */
final class BodyOverlapHeuristics {
    private final List<RenderTrackSnapshot> heads = new ArrayList<>();
    private final Map<RenderTrackSnapshot, Integer> owners = new IdentityHashMap<>();

    BodyOverlapHeuristics(List<RenderTrackSnapshot> ordered) {
        ordered = new ArrayList<>(ordered);
        ordered.sort(Comparator.comparing(RenderTrackSnapshot::isCached)
                .thenComparingInt(item -> item.associationBox().getY())
                .thenComparingInt(item -> item.associationBox().getX())
                .thenComparingInt(RenderTrackSnapshot::id));
        // Input has stable geometry/id order. Compare to original representatives rather than
        // growing unions: a chain of face boxes must not collapse three distinct heads.
        for (RenderTrackSnapshot item : ordered) {
            if (!isHead(item.category())) continue;
            // Conflicting labels on essentially the same raw region are not independent
            // person evidence. Keep both masks, but do not let an ambiguous face hint
            // veto torso consolidation. Padded overlap alone must never trigger this.
            if (hasContradictoryBodyLabel(item, ordered)) continue;
            int owner = -1;
            for (int index = 0; index < heads.size(); index++) {
                RenderTrackSnapshot head = heads.get(index);
                if (item.reference().sameBasis(head.reference())
                        && duplicateHead(item.associationBox(), head.associationBox())) {
                    owner = index;
                    break;
                }
            }
            if (owner < 0) {
                owner = heads.size();
                heads.add(item);
            }
            owners.put(item, owner);
        }
        for (RenderTrackSnapshot item : ordered) {
            if (!isHead(item.category())) owners.put(item, nearestHead(item));
        }
    }

    private static boolean hasContradictoryBodyLabel(RenderTrackSnapshot head,
            List<RenderTrackSnapshot> items) {
        for (RenderTrackSnapshot item : items) {
            if (isBody(item.category()) && head.reference().sameBasis(item.reference())
                    && head.associationBox().intersectionOverUnion(item.associationBox()) >= .85f) {
                return true;
            }
        }
        return false;
    }

    boolean oneBody(List<RenderTrackSnapshot> first, List<RenderTrackSnapshot> second) {
        int owner = -1;
        int left = Integer.MAX_VALUE, right = Integer.MIN_VALUE, bottom = Integer.MIN_VALUE;
        for (List<RenderTrackSnapshot> group : List.of(first, second)) {
            for (RenderTrackSnapshot item : group) {
                left = Math.min(left, item.box().getX());
                right = Math.max(right, item.box().getRight());
                bottom = Math.max(bottom, item.box().getBottom());
                int candidate = owners.getOrDefault(item, -1);
                if (candidate < 0) continue;
                if (owner >= 0 && candidate != owner) return false;
                owner = candidate;
            }
        }
        if (owner >= 0) {
            RenderTrackSnapshot anchor = heads.get(owner);
            BBox anchorBox = anchor.associationBox();
            for (int index = 0; index < heads.size(); index++) {
                if (index == owner) continue;
                RenderTrackSnapshot other = heads.get(index);
                if (!other.reference().sameBasis(anchor.reference())) continue;
                BBox box = other.associationBox();
                // Do not let an upper-card body envelope swallow the next card's head, even
                // when cached body fragments were all assigned to the upper head.
                if (box.getCenterX() > left && box.getCenterX() < right
                        && box.getCenterY() >= anchorBox.getY()
                        && box.getCenterY() < bottom) return false;
            }
        }
        return true;
    }

    float bodyAffinity(RenderTrackSnapshot first, RenderTrackSnapshot second) {
        if (!isBody(first.category()) || !isBody(second.category())) return 0f;
        BBox a = first.box();
        BBox b = second.box();
        // These are viewport/world pixels, not model-input pixels. Avoid joining tiny details.
        if (Math.min(Math.min(a.getWidth(), a.getHeight()),
                Math.min(b.getWidth(), b.getHeight())) < 48) return 0f;
        float overlap = overlapOfSmaller(a, b);
        if (overlap <= 0f) return 0f;
        int firstOwner = owners.getOrDefault(first, -1);
        int secondOwner = owners.getOrDefault(second, -1);
        boolean sameHead = firstOwner >= 0 && firstOwner == secondOwner;
        float horizontal = overlapX(a, b) / (float) Math.max(1,
                Math.min(a.getWidth(), b.getWidth()));
        float vertical = overlapY(a, b) / (float) Math.max(1,
                Math.min(a.getHeight(), b.getHeight()));
        // Favor plausible overlap. A shared head permits partial torso/abdomen overlap; without
        // heads require stronger aligned overlap, but do not demand a visible face.
        float minimumOverlap = sameHead ? .08f : .25f;
        float minimumAlignment = sameHead ? .55f : .75f;
        if (overlap < minimumOverlap || Math.max(horizontal, vertical) < minimumAlignment) return 0f;
        return overlap;
    }

    private int nearestHead(RenderTrackSnapshot item) {
        if (!isBody(item.category())) return -1;
        int best = -1;
        float bestScore = Float.MAX_VALUE;
        BBox body = item.associationBox();
        for (int index = 0; index < heads.size(); index++) {
            RenderTrackSnapshot head = heads.get(index);
            if (!item.reference().sameBasis(head.reference())) continue;
            BBox box = head.associationBox();
            float pixelDx = Math.abs(body.getCenterX() - box.getCenterX());
            float pixelDy = body.getCenterY() - box.getCenterY();
            float dx = pixelDx / Math.max(1, box.getWidth());
            float dy = pixelDy / Math.max(1, box.getHeight());
            if (dy < -.25f || dy > 6f || dx > 1.6f
                    || body.getWidth() > box.getWidth() * 4f) continue;
            // Head-relative dimensions are useful eligibility bounds, but not a common
            // ranking unit: dividing each candidate by its own size makes a distant
            // large head beat a nearby small head in the next image card. Rank eligible
            // heads in the shared source coordinate space, retaining horizontal priority.
            float score = pixelDx * 2f + Math.max(0f, pixelDy) * .25f;
            if (score < bestScore) {
                bestScore = score;
                best = index;
            }
        }
        return best;
    }

    private static boolean duplicateHead(BBox a, BBox b) {
        return overlapOfSmaller(a, b) >= .60f
                && Math.abs(a.getCenterX() - b.getCenterX())
                        <= Math.min(a.getWidth(), b.getWidth()) * .45f
                && Math.abs(a.getCenterY() - b.getCenterY())
                        <= Math.min(a.getHeight(), b.getHeight()) * .45f;
    }

    private static boolean isHead(String category) {
        return "face".equals(category) || "face_female".equals(category)
                || "face_male".equals(category);
    }

    private static boolean isBody(String category) {
        if (category == null) return false;
        switch (category) {
            case "breasts": case "breasts_covered": case "male_chest":
            case "belly": case "belly_covered": case "buttocks": case "buttocks_covered":
            case "genitals_female": case "genitals_male": case "genitals_covered":
            case "anus": case "anus_covered": case "armpits": case "armpits_covered":
            case "feet": case "feet_covered": return true;
            default: return false;
        }
    }

    private static float overlapOfSmaller(BBox a, BBox b) {
        long smaller = Math.min(a.getArea(), b.getArea());
        return smaller <= 0 ? 0f : (long) overlapX(a, b) * overlapY(a, b) / (float) smaller;
    }

    private static int overlapX(BBox a, BBox b) {
        return Math.max(0, Math.min(a.getRight(), b.getRight()) - Math.max(a.getX(), b.getX()));
    }

    private static int overlapY(BBox a, BBox b) {
        return Math.max(0, Math.min(a.getBottom(), b.getBottom()) - Math.max(a.getY(), b.getY()));
    }
}
