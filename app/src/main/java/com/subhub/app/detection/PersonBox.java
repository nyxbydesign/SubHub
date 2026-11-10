package com.subhub.app.detection;

/** Model-neutral, source-frame person geometry supplied by a future joint detector. */
public final class PersonBox {
    public final BBox box;
    public final float confidence;

    public PersonBox(BBox box, float confidence) {
        this.box = java.util.Objects.requireNonNull(box);
        this.confidence = confidence;
    }
}
