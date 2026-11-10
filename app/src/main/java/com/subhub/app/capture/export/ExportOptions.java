package com.subhub.app.capture.export;

/** Immutable processing choices. Detection coverage and compression are independent. */
public final class ExportOptions {
    public enum Quality {
        DRAFT(720, .06f, 75), BALANCED(1080, .10f, 90), HIGH(1920, .16f, 95), BEST(3840, .24f, 100);
        public final int longestEdge;
        public final float bitsPerPixel;
        public final int jpegQuality;
        Quality(int edge, float bits, int jpeg) {
            longestEdge = edge; bitsPerPixel = bits; jpegQuality = jpeg;
        }
        public int bitrate(int width, int height, int framesPerSecond) {
            return (int) Math.max(1_000_000L, Math.min(40_000_000L,
                    Math.round((double) width * height * framesPerSecond * bitsPerPixel)));
        }
    }
    public final Quality quality;
    public final int detectEvery;
    public final boolean mute;
    public final boolean deleteOriginals;
    public ExportOptions(Quality quality, int detectEvery, boolean mute, boolean deleteOriginals) {
        if (quality == null || (detectEvery != 1 && detectEvery != 2))
            throw new IllegalArgumentException("Invalid export options");
        this.quality = quality; this.detectEvery = detectEvery;
        this.mute = mute; this.deleteOriginals = deleteOriginals;
    }
    public static ExportOptions defaults() { return new ExportOptions(Quality.BALANCED, 1, false, false); }
    public int[] dimensions(int width, int height) {
        if (width < 2 || height < 2) throw new IllegalArgumentException("Invalid video size");
        double scale = Math.min(1d, quality.longestEdge / (double) Math.max(width, height));
        return new int[] {Math.max(2, Math.min(width & ~1, (int) Math.round(width * scale / 2) * 2)),
                Math.max(2, Math.min(height & ~1, (int) Math.round(height * scale / 2) * 2))};
    }
}
