package com.subhub.app.overlay;

import com.subhub.app.detection.BBox;
import com.subhub.app.detection.RenderSourceReference;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Presentation memory only: padded footprints, stable labels and a short split deadband. */
final class StableVisualLayout {
    private static final long SPLIT_GRACE_MS = 500L;
    private static final int MAX_REGIONS = 128;
    private final Map<String, Footprint> footprints = new HashMap<>();
    private List<Group> previous = Collections.emptyList();
    private int nextVisualId = -1_500_000_000; // Disjoint from tracker/cache/text IDs.
    private long lastUpdate = -1L;
    private int frozenGeometry, heldGroups;
    private long durationNanos;
    private final Map<Integer, List<Integer>> members = new HashMap<>();

    List<RenderTrackSnapshot> update(List<RenderTrackSnapshot> input, float padding, long now) {
        long started = System.nanoTime();
        try { return updateLayout(input, padding, now); }
        finally { durationNanos = Math.max(0L, System.nanoTime() - started); }
    }

    private List<RenderTrackSnapshot> updateLayout(List<RenderTrackSnapshot> input, float padding, long now) {
        if (now < lastUpdate) clear();
        lastUpdate = now;
        frozenGeometry = 0;
        heldGroups = 0;
        if (input == null || input.isEmpty()) {
            clear();
            return Collections.emptyList();
        }
        List<RenderTrackSnapshot> padded = new ArrayList<>();
        Set<String> active = new HashSet<>();
        boolean bounded = input.size() <= MAX_REGIONS;
        for (RenderTrackSnapshot raw : input) {
            if (raw == null || raw.box().getArea() <= 0L) continue;
            RenderTrackSnapshot item = raw.withPadding(isText(raw)
                    ? Math.min(.025f, padding) : padding);
            String key = key(raw);
            Footprint old = footprints.get(key);
            if (bounded && !isText(raw) && old != null && old.reference.sameBasis(raw.reference())) {
                BBox settled = settleSize(old.box, item.box(), raw.associationBox());
                if (!settled.equals(item.box()) || settled.equals(old.box)) frozenGeometry++;
                item = item.withRenderBox(settled, item.id());
            }
            padded.add(item);
            if (bounded) {
                active.add(key);
                footprints.put(key, new Footprint(item.box(), item.reference()));
            }
        }
        footprints.keySet().retainAll(active);
        VisualRenderRegionConsolidator.Result proposed = VisualRenderRegionConsolidator.consolidate(padded);
        List<Component> components = new ArrayList<>();
        for (int index = 0; index < proposed.regions().size(); index++) {
            components.add(new Component(proposed.regions().get(index), proposed.sources().get(index)));
        }
        if (bounded) retainBriefSplits(components, padded, now);
        List<Group> next = new ArrayList<>();
        List<RenderTrackSnapshot> output = new ArrayList<>();
        members.clear();
        Set<Group> used = new HashSet<>();
        for (Component component : components) {
            if (isText(component.snapshot)) {
                output.add(component.snapshot);
                continue;
            }
            Group old = bounded ? bestPrevious(component, used) : null;
            BBox box = component.snapshot.box();
            if (old != null) {
                BBox rawCoverage = component.sources.get(0).associationBox();
                for (RenderTrackSnapshot source : component.sources) {
                    rawCoverage = union(rawCoverage, source.associationBox());
                }
                BBox settled = settleSize(old.snapshot.box(), box, rawCoverage);
                if (!settled.equals(box) || settled.equals(old.snapshot.box())) frozenGeometry++;
                box = settled;
            }
            int id = old == null ? nextVisualId++ : old.snapshot.id();
            // Layout owns geometry changes (including singletons). Do not reintroduce per-vsync
            // size/position easing after the deadband has already chosen a settled footprint.
            RenderTrackSnapshot rendered = component.snapshot.withGroupBox(box).withRenderBox(box, id);
            output.add(rendered);
            List<Integer> ids = new ArrayList<>();
            for (RenderTrackSnapshot source : component.sources) ids.add(source.sourceId());
            members.put(id, Collections.unmodifiableList(ids));
            Group memory = new Group(rendered, component.keys);
            if (component.heldFrom != null) memory.splitSince = component.heldFrom.splitSince;
            next.add(memory);
            if (old != null) used.add(old);
        }
        previous = bounded ? next : Collections.emptyList();
        return Collections.unmodifiableList(output);
    }

    private void retainBriefSplits(List<Component> components,
            List<RenderTrackSnapshot> padded, long now) {
        BodyOverlapHeuristics bodies = new BodyOverlapHeuristics(padded);
        for (Group old : previous) {
            if (old.keys.size() < 2) continue;
            List<Component> pieces = new ArrayList<>();
            List<RenderTrackSnapshot> sources = new ArrayList<>();
            for (Component component : components) {
                if (!Collections.disjoint(component.keys, old.keys)) {
                    pieces.add(component);
                    sources.addAll(component.sources);
                }
            }
            if (pieces.size() < 2 || sources.size() > 32
                    || !oneBasis(sources) || !bodies.oneBody(sources, Collections.emptyList())
                    || !connectedWithSmallGap(sources)) continue;
            if (old.splitSince < 0L) old.splitSince = now;
            if (now - old.splitSince >= SPLIT_GRACE_MS) continue;
            BBox box = sources.get(0).box();
            for (RenderTrackSnapshot source : sources) box = union(box, source.box());
            // Do not preserve a merge after meaningful movement or a different image layout.
            if (box.getWidth() > old.snapshot.box().getWidth() * 1.25f
                    || box.getHeight() > old.snapshot.box().getHeight() * 1.25f) continue;
            Component held = new Component(pieces.get(0).snapshot.withGroupBox(box), sources);
            held.heldFrom = old;
            int position = components.indexOf(pieces.get(0));
            components.removeAll(pieces);
            components.add(Math.min(position, components.size()), held);
            heldGroups++;
        }
    }

    private Group bestPrevious(Component component, Set<Group> used) {
        Group best = null;
        float score = 0f;
        for (Group old : previous) {
            if (used.contains(old) || !old.snapshot.reference().sameBasis(component.snapshot.reference())) continue;
            int shared = 0;
            for (String key : component.keys) if (old.keys.contains(key)) shared++;
            float overlap = old.snapshot.box().intersectionOverUnion(component.snapshot.box());
            if (shared == 0 && overlap < .65f) continue;
            float candidate = shared * 100f + overlap;
            if (candidate > score) { score = candidate; best = old; }
        }
        return best;
    }

    private static boolean oneBasis(List<RenderTrackSnapshot> items) {
        for (RenderTrackSnapshot item : items) {
            if (!item.reference().sameBasis(items.get(0).reference())) return false;
        }
        return true;
    }

    private static boolean connectedWithSmallGap(List<RenderTrackSnapshot> sources) {
        boolean[] reached = new boolean[sources.size()];
        reached[0] = true;
        int count = 1;
        for (int round = 0; round < sources.size(); round++) {
            boolean changed = false;
            for (int a = 0; a < sources.size(); a++) {
                if (!reached[a]) continue;
                for (int b = 0; b < sources.size(); b++) {
                    if (reached[b]) continue;
                    BBox first = sources.get(a).box(), second = sources.get(b).box();
                    int gapX = Math.max(first.getX(), second.getX()) - Math.min(first.getRight(), second.getRight());
                    int gapY = Math.max(first.getY(), second.getY()) - Math.min(first.getBottom(), second.getBottom());
                    if (gapX <= 6 && gapY <= 6 && (gapX < 0 || gapY < 0)) {
                        reached[b] = true;
                        count++;
                        changed = true;
                    }
                }
            }
            if (!changed) break;
        }
        return count == sources.size();
    }

    private static boolean near(BBox first, BBox second) {
        int tolerance = Math.max(3, Math.min(12,
                Math.round(Math.min(first.getWidth(), first.getHeight()) * .04f)));
        return Math.abs(first.getX() - second.getX()) <= tolerance
                && Math.abs(first.getY() - second.getY()) <= tolerance
                && Math.abs(first.getRight() - second.getRight()) <= tolerance
                && Math.abs(first.getBottom() - second.getBottom()) <= tolerance;
    }

    private static BBox settleSize(BBox previous, BBox proposed, BBox raw) {
        // Stationary noise still uses the position deadband. Real translation must not
        // disable size hysteresis: follow the new center using the settled dimensions.
        if (near(previous, proposed) && contains(previous, raw)) return previous;
        int tolerance = Math.max(3, Math.min(12,
                Math.round(Math.min(previous.getWidth(), previous.getHeight()) * .04f)));
        int width = Math.abs(previous.getWidth() - proposed.getWidth()) <= tolerance
                ? previous.getWidth() : proposed.getWidth();
        int height = Math.abs(previous.getHeight() - proposed.getHeight()) <= tolerance
                ? previous.getHeight() : proposed.getHeight();
        BBox translated = new BBox(proposed.getX() + Math.round((proposed.getWidth() - width) / 2f),
                proposed.getY() + Math.round((proposed.getHeight() - height) / 2f), width, height);
        // Padding can absorb detector noise, but detected pixels must never be clipped.
        return union(translated, raw);
    }

    private static boolean contains(BBox outer, BBox inner) {
        return outer.getX() <= inner.getX() && outer.getY() <= inner.getY()
                && outer.getRight() >= inner.getRight() && outer.getBottom() >= inner.getBottom();
    }

    private static BBox union(BBox a, BBox b) {
        int left = Math.min(a.getX(), b.getX()), top = Math.min(a.getY(), b.getY());
        return new BBox(left, top, Math.max(a.getRight(), b.getRight()) - left,
                Math.max(a.getBottom(), b.getBottom()) - top);
    }

    private static boolean isText(RenderTrackSnapshot item) {
        return item.category() != null && item.category().startsWith("text_");
    }

    private static String key(RenderTrackSnapshot item) {
        return item.id() + ":" + item.isCached() + ":" + item.category();
    }

    int frozenGeometry() { return frozenGeometry; }
    int heldGroups() { return heldGroups; }
    long durationMicros() { return durationNanos / 1_000L; }
    List<Integer> memberIds(int id) { return members.getOrDefault(id, Collections.emptyList()); }

    void clear() {
        footprints.clear();
        members.clear();
        previous = Collections.emptyList();
        lastUpdate = -1L;
        nextVisualId = -1_500_000_000;
        frozenGeometry = 0;
        heldGroups = 0;
    }

    private static final class Footprint {
        final BBox box;
        final RenderSourceReference reference;
        Footprint(BBox box, RenderSourceReference reference) { this.box = box; this.reference = reference; }
    }

    private static final class Component {
        final RenderTrackSnapshot snapshot;
        final List<RenderTrackSnapshot> sources;
        final Set<String> keys = new HashSet<>();
        Group heldFrom;
        Component(RenderTrackSnapshot snapshot, List<RenderTrackSnapshot> sources) {
            this.snapshot = snapshot;
            this.sources = sources;
            for (RenderTrackSnapshot source : sources) keys.add(key(source));
        }
    }

    private static final class Group {
        final RenderTrackSnapshot snapshot;
        final Set<String> keys;
        long splitSince = -1L;
        Group(RenderTrackSnapshot snapshot, Set<String> keys) { this.snapshot = snapshot; this.keys = keys; }
    }
}
