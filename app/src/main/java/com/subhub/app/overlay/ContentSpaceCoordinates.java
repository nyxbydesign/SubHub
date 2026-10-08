package com.subhub.app.overlay;

import com.subhub.app.detection.BBox;

/** Converts viewport observations into a stable content coordinate system. */
final class ContentSpaceCoordinates {
    private ContentSpaceCoordinates() {}

    static BBox toWorld(
            BBox screenBox,
            long cameraX,
            long cameraY,
            int sourceWidth,
            int sourceHeight,
            int viewportWidth,
            int viewportHeight) {
        if (screenBox == null) return null;
        int worldX = saturatingAdd(screenBox.getX(), scaleCamera(
                cameraX, sourceWidth, viewportWidth));
        int worldY = saturatingAdd(screenBox.getY(), scaleCamera(
                cameraY, sourceHeight, viewportHeight));
        return new BBox(worldX, worldY, screenBox.getWidth(), screenBox.getHeight());
    }

    static BBox toScreen(
            BBox worldBox,
            long cameraX,
            long cameraY,
            int sourceWidth,
            int sourceHeight,
            int viewportWidth,
            int viewportHeight) {
        if (worldBox == null) return null;
        int screenX = saturatingAdd(worldBox.getX(), -scaleCamera(
                cameraX, sourceWidth, viewportWidth));
        int screenY = saturatingAdd(worldBox.getY(), -scaleCamera(
                cameraY, sourceHeight, viewportHeight));
        return new BBox(screenX, screenY, worldBox.getWidth(), worldBox.getHeight());
    }

    private static int scaleCamera(long camera, int sourceSize, int viewportSize) {
        double scaled = camera * (double) Math.max(1, sourceSize)
                / Math.max(1, viewportSize);
        if (scaled >= Integer.MAX_VALUE) return Integer.MAX_VALUE;
        if (scaled <= Integer.MIN_VALUE) return Integer.MIN_VALUE;
        return (int) Math.round(scaled);
    }

    private static int saturatingAdd(int first, int second) {
        long sum = (long) first + second;
        return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, sum));
    }
}
