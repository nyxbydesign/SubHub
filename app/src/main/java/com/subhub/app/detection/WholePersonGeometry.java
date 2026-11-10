package com.subhub.app.detection;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Render-only expansion: triggers stay unchanged and ambiguous people never acquire each other. */
public final class WholePersonGeometry {
    public static final class Coverage {
        public final String category;
        public final int trackId;
        public final BBox trigger, person;
        public final boolean refined;

        Coverage(Detection detection, BBox person, boolean refined) {
            category = detection.getCategory();
            trackId = detection.getTrackId();
            trigger = detection.getBox();
            this.person = union(trigger, person);
            this.refined = refined;
        }
    }

    public List<Coverage> resolve(List<Detection> triggers, List<Detection> cues,
            List<PersonBox> people, int width, int height) {
        if (width <= 0 || height <= 0 || triggers == null) return Collections.emptyList();
        List<Coverage> result = new ArrayList<>();
        for (Detection trigger : triggers) {
            if (!visual(trigger) || trigger.getBox().getArea() <= 0) continue;
            BBox confirmed = matchPerson(trigger.getBox(), people);
            // Multiple plausible model boxes are a reason to keep part coverage, not to guess
            // using the provisional heuristic. An empty/missing model result may use the cues.
            boolean ambiguous = countMatches(trigger.getBox(), people) > 1;
            BBox expanded = confirmed != null ? confirmed
                    : ambiguous ? trigger.getBox() : provisional(trigger, cues, width, height);
            result.add(new Coverage(trigger, expanded, confirmed != null));
        }
        return Collections.unmodifiableList(result);
    }

    private static BBox matchPerson(BBox trigger, List<PersonBox> people) {
        if (countMatches(trigger, people) != 1) return null;
        for (PersonBox person : people) {
            if (matches(trigger, person)) return person.box;
        }
        return null;
    }

    private static int countMatches(BBox trigger, List<PersonBox> people) {
        int count = 0;
        if (people != null) for (PersonBox person : people) {
            if (matches(trigger, person)) count++;
        }
        return count;
    }

    private static boolean matches(BBox trigger, PersonBox person) {
        return person != null && Float.isFinite(person.confidence) && person.confidence >= .35f
                && intersection(trigger, person.box) >= trigger.getArea() * .8
                && person.box.getArea() >= trigger.getArea();
    }

    private static BBox provisional(Detection trigger, List<Detection> cues, int width, int height) {
        if (cues == null) return trigger.getBox();
        BBox head = null, torso = null;
        int pairs = 0;
        for (Detection candidateHead : cues) {
            if (!visual(candidateHead) || !"face".equals(candidateHead.getCategory())) continue;
            BBox h = candidateHead.getBox();
            if (h.getWidth() < 6 || h.getHeight() < 6) continue;
            for (Detection candidateTorso : cues) {
                if (!visual(candidateTorso) || !torso(candidateTorso.getCategory())) continue;
                BBox t = candidateTorso.getBox();
                if (t.getCenterY() <= h.getCenterY()
                        || t.getCenterY() > h.getBottom() + h.getHeight() * 3.5
                        || Math.abs(t.getCenterX() - h.getCenterX()) > h.getWidth() * 1.25
                        || t.getWidth() > h.getWidth() * 4) continue;
                BBox envelope = estimate(h, t, width, height);
                if (intersection(trigger.getBox(), envelope) < trigger.getBox().getArea() * .9) continue;
                boolean otherHead = false;
                for (Detection neighbor : cues) {
                    if (neighbor == candidateHead || !visual(neighbor)
                            || !"face".equals(neighbor.getCategory())) continue;
                    if (h.intersectionOverUnion(neighbor.getBox()) < .3f
                            && intersection(neighbor.getBox(), envelope) > neighbor.getBox().getArea() * .5) {
                        otherHead = true;
                        break;
                    }
                }
                if (otherHead) continue;
                if (head != null && h.intersectionOverUnion(head) < .3f) return trigger.getBox();
                head = h;
                torso = torso == null ? t : union(torso, t);
                pairs++;
            }
        }
        return pairs == 0 ? trigger.getBox() : estimate(head, torso, width, height);
    }

    private static BBox estimate(BBox head, BBox torso, int width, int height) {
        BBox body = union(head, torso);
        // A head/chest pair does not establish that legs exist below a cropped portrait.
        // Unconditional eight-head extrapolation crosses independent tiles in image feeds.
        // Cover the observed body envelope while the person model establishes its full extent.
        int horizontalMargin = Math.round(head.getWidth() * .2f);
        int left = Math.max(0, body.getX() - horizontalMargin);
        int right = Math.min(width, body.getRight() + horizontalMargin);
        int top = Math.max(0, head.getY() - Math.round(head.getHeight() * .2f));
        int bottom = Math.min(height, body.getBottom() + Math.round(head.getHeight() * .2f));
        return new BBox(left, top, Math.max(0, right - left), Math.max(0, bottom - top));
    }

    private static boolean torso(String category) {
        return "breasts".equals(category) || "breasts_covered".equals(category)
                || "male_chest".equals(category) || "belly".equals(category)
                || "belly_covered".equals(category);
    }

    private static boolean visual(Detection value) {
        return value != null && !"text_smut".equals(value.getCategory())
                && (value.getSource() == Detection.ObservationSource.VISUAL
                || value.getSource() == Detection.ObservationSource.QUALITY_VISUAL);
    }

    public static long intersection(BBox a, BBox b) {
        return (long) Math.max(0, Math.min(a.getRight(), b.getRight()) - Math.max(a.getX(), b.getX()))
                * Math.max(0, Math.min(a.getBottom(), b.getBottom()) - Math.max(a.getY(), b.getY()));
    }

    public static BBox union(BBox a, BBox b) {
        int x = Math.min(a.getX(), b.getX()), y = Math.min(a.getY(), b.getY());
        return new BBox(x, y, Math.max(a.getRight(), b.getRight()) - x,
                Math.max(a.getBottom(), b.getBottom()) - y);
    }
}
