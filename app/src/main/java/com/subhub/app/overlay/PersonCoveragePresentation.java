package com.subhub.app.overlay;

import com.subhub.app.detection.BBox;
import com.subhub.app.detection.Detection;
import com.subhub.app.detection.PersonBox;
import com.subhub.app.detection.RenderSourceReference;
import com.subhub.app.detection.WholePersonGeometry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** Main-thread render state, never a source of detector, tracker, or penalty observations. */
final class PersonCoveragePresentation {
    static final long MAX_AGE_MS = 750L;
    private static final AtomicLong NEXT_GENERATION = new AtomicLong();
    private long generation;
    private long capturedAt;
    private List<Detection> triggers = Collections.emptyList();
    private List<Detection> cues = Collections.emptyList();
    private List<RenderTrackSnapshot> originals = Collections.emptyList();
    private final Map<Integer, CoverageBox> boxes = new HashMap<>();
    private final Map<Integer, Refined> refined = new HashMap<>();
    private int width, height, viewportWidth, viewportHeight;
    private long cameraX, cameraY;
    private boolean world;
    private RenderSourceReference reference = RenderSourceReference.UNKNOWN;

    long begin(List<RenderTrackSnapshot> raw, List<Detection> selected, List<Detection> support,
            int sourceWidth, int sourceHeight, long sourceTime, long now,
            boolean worldSpace, long sourceCameraX, long sourceCameraY,
            int displayWidth, int displayHeight, RenderSourceReference sourceReference) {
        advanceFrame();
        if (selected == null || selected.size() > 128 || support == null || support.size() > 128
                || raw == null || raw.size() > 256 || sourceWidth <= 0 || sourceHeight <= 0
                || sourceTime < 0 || now < sourceTime || now - sourceTime >= MAX_AGE_MS
                || sourceReference == null || worldSpace && (displayWidth <= 0 || displayHeight <= 0
                || sourceReference.isKnown() && sourceReference.sourceUptimeMillis() != sourceTime)) return generation;
        triggers = new ArrayList<>();
        for (Detection item : selected) {
            if (item != null) triggers.add(item.withRenderSourceReference(item.getRenderSourceReference()));
        }
        cues = new ArrayList<>(support);
        originals = new ArrayList<>(raw);
        width = sourceWidth;
        height = sourceHeight;
        capturedAt = sourceTime;
        world = worldSpace;
        cameraX = sourceCameraX;
        cameraY = sourceCameraY;
        viewportWidth = displayWidth;
        viewportHeight = displayHeight;
        reference = sourceReference;
        resolve(Collections.emptyList(), now);
        return generation;
    }

    boolean refine(long token, List<PersonBox> people, long now) {
        if (token != generation || triggers.isEmpty() || !fresh(now) || people == null
                || people.size() > 128) return false;
        resolve(people, now);
        return true;
    }

    private void resolve(List<PersonBox> people, long now) {
        boxes.clear();
        refined.entrySet().removeIf(entry -> now < entry.getValue().capturedAt
                || now - entry.getValue().capturedAt >= MAX_AGE_MS);
        List<WholePersonGeometry.Coverage> coverage = new WholePersonGeometry().resolve(
                triggers, cues, people, width, height);
        for (RenderTrackSnapshot raw : originals) {
            if (raw == null || "text_smut".equals(raw.category()) || raw.isCached()) continue;
            if (world && reference.isKnown() && (!raw.reference().sameBasis(reference)
                    || raw.reference().sourceUptimeMillis() != capturedAt)) continue;
            if (world && !reference.isKnown() && raw.reference().isKnown()) continue;
            BBox chosen = null;
            WholePersonGeometry.Coverage selected = null;
            int matches = 0;
            for (WholePersonGeometry.Coverage item : coverage) {
                if (!raw.category().equals(item.category)) continue;
                if (item.trackId >= 0 && item.trackId != raw.sourceId()) continue;
                // Without anchor provenance only explicit tracker assignment from this exact
                // publication is sufficient. Geometric coincidence alone is not frame proof.
                if (world && !reference.isKnown() && item.trackId != raw.sourceId()) continue;
                BBox trigger = transform(item.trigger);
                if (trigger.intersectionOverUnion(raw.associationBox()) < .5f) continue;
                // Duplicate/ambiguous trigger associations must not bridge neighboring people.
                chosen = transform(item.person);
                selected = item;
                matches++;
            }
            if (matches == 1) {
                BBox trigger = transform(selected.trigger);
                BBox fallback = chosen;
                long proofExpiry = Long.MAX_VALUE;
                if (selected.refined) {
                    if (refined.size() < 128 || refined.containsKey(raw.sourceId())) {
                        refined.put(raw.sourceId(), new Refined(raw.category(), trigger, chosen,
                                reference, capturedAt, width, height, world));
                    }
                } else if (people.isEmpty()) {
                    Refined old = refined.get(raw.sourceId());
                    if (old != null && old.matches(raw.category(), trigger, reference, width, height, world)) {
                        // Use the same hard evidence lifetime as a freshly displayed result.
                        // A shorter reuse-only deadline falls between raw and refined updates
                        // at the ~334 ms capture cadence, producing a visible shrink/grow pulse.
                        proofExpiry = old.capturedAt + MAX_AGE_MS;
                        chosen = new BBox(old.person.getX() + trigger.getX() - old.trigger.getX(),
                                old.person.getY() + trigger.getY() - old.trigger.getY(),
                                old.person.getWidth(), old.person.getHeight());
                    }
                } else {
                    refined.remove(raw.sourceId());
                }
                boxes.put(raw.sourceId(), new CoverageBox(chosen, fallback, proofExpiry));
            }
        }
    }

    BBox expand(RenderTrackSnapshot rendered, BBox current, List<Integer> members, long now) {
        if (boxes.isEmpty() || !fresh(now) || "text_smut".equals(rendered.category())) return current;
        BBox result = current;
        if (members.isEmpty()) return include(result, rendered.sourceId(), rendered, current, now);
        for (Integer member : members) result = include(result, member, rendered, current, now);
        return result;
    }

    private BBox include(BBox result, int id, RenderTrackSnapshot rendered, BBox current, long now) {
        CoverageBox coverage = boxes.get(id);
        if (coverage == null) return result;
        BBox person = now < coverage.proofExpiry ? coverage.person : coverage.fallback;
        // Projection prediction translates the expansion with its raw track. World-camera
        // movement is applied later by the renderer and must not be applied here a second time.
        if (!world) person = new BBox(person.getX() + current.getX() - rendered.box().getX(),
                person.getY() + current.getY() - rendered.box().getY(),
                person.getWidth(), person.getHeight());
        return WholePersonGeometry.union(result, person);
    }

    private BBox transform(BBox value) {
        return world ? ContentSpaceCoordinates.toWorld(value, cameraX, cameraY,
                width, height, viewportWidth, viewportHeight) : value;
    }

    private boolean fresh(long now) {
        return now >= capturedAt && now - capturedAt < MAX_AGE_MS;
    }

    boolean hasActiveCoverage(long now) { return !boxes.isEmpty() && fresh(now); }

    long nextRefreshDelay(long now) {
        if (triggers.isEmpty()) return 0L;
        if (!fresh(now)) {
            clear();
            return 0L;
        }
        long next = capturedAt + MAX_AGE_MS;
        for (CoverageBox box : boxes.values()) {
            if (box.proofExpiry > now) next = Math.min(next, box.proofExpiry);
        }
        return Math.max(1L, next - now);
    }

    void clear() {
        refined.clear();
        advanceFrame();
    }

    /** Replacing a frame invalidates callbacks, but brief matched shape memory avoids pulsing. */
    void advanceFrame() {
        generation = NEXT_GENERATION.incrementAndGet();
        boxes.clear();
        triggers = Collections.emptyList();
        cues = Collections.emptyList();
        originals = Collections.emptyList();
        reference = RenderSourceReference.UNKNOWN;
    }

    private static final class CoverageBox {
        final BBox person, fallback;
        final long proofExpiry;
        CoverageBox(BBox person, BBox fallback, long proofExpiry) {
            this.person = person;
            this.fallback = fallback;
            this.proofExpiry = proofExpiry;
        }
    }

    private static final class Refined {
        final String category;
        final BBox trigger, person;
        final RenderSourceReference reference;
        final long capturedAt;
        final int width, height;
        final boolean world;
        Refined(String category, BBox trigger, BBox person, RenderSourceReference reference,
                long capturedAt, int width, int height, boolean world) {
            this.category = category;
            this.trigger = trigger;
            this.person = person;
            this.reference = reference;
            this.capturedAt = capturedAt;
            this.width = width;
            this.height = height;
            this.world = world;
        }
        boolean matches(String category, BBox next, RenderSourceReference reference,
                int width, int height, boolean world) {
            return this.category.equals(category) && this.width == width && this.height == height
                    && this.world == world && this.reference.sameBasis(reference)
                    && trigger.intersectionOverUnion(next) >= .6f
                    && Math.abs(next.getWidth() - trigger.getWidth()) <= Math.max(2, trigger.getWidth() * .15f)
                    && Math.abs(next.getHeight() - trigger.getHeight()) <= Math.max(2, trigger.getHeight() * .15f);
        }
    }
}
