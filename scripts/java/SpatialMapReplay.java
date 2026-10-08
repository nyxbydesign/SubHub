package com.subhub.app.service;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import javax.imageio.ImageIO;

/** Host-only private-corpus replay. Poses relate source images, not timestamp-matched display frames. */
public final class SpatialMapReplay {
    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("Expected source directory and source width/height");
        int sourceWidth = Integer.parseInt(args[1]), sourceHeight = Integer.parseInt(args[2]);
        RowMotionObserver.Scope scope = new RowMotionObserver.Scope(1, 1, 1, sourceWidth, sourceHeight);
        File[] files = new File(args[0]).listFiles(file -> file.getName().matches("frame-[0-9]+\\.png"));
        if (files == null || files.length < 1 || files.length > 64 || !scope.valid()) {
            throw new IllegalArgumentException("Invalid source corpus");
        }
        Arrays.sort(files, Comparator.comparingLong(SpatialMapReplay::timestamp));
        SpatialFrameMap map = new SpatialFrameMap();
        List<String> records = new ArrayList<>();
        int baselines = 0, registered = 0, unknown = 0, bridges = 0;
        long previousId = -1;
        for (File file : files) {
            BufferedImage image = ImageIO.read(file);
            if (image == null) throw new IllegalArgumentException("Invalid source image");
            int width = image.getWidth(), height = image.getHeight();
            int[] pixels = image.getRGB(0, 0, width, height, null, 0, width);
            long id = timestamp(file);
            SpatialFrameMap.Result result = map.observe(pixels, width, height, height / 5, height * 19 / 20,
                    scope, id, id, true);
            if (result.status == SpatialFrameMap.Status.BASELINE) baselines++;
            if (result.status == SpatialFrameMap.Status.REGISTERED) {
                registered++;
                if (result.referenceId != previousId) bridges++;
            }
            if (result.pose == null) unknown++;
            records.add("{\"frameId\":" + id + ",\"status\":\"" + result.status + "\",\"referenceId\":"
                    + result.referenceId + ",\"sourceY\":" + (result.pose == null ? "null" : result.pose.sourceY)
                    + ",\"mapGeneration\":" + (result.pose == null ? "null" : result.pose.mapGeneration)
                    + ",\"sourceDy\":" + result.sourceDy + ",\"inliers\":" + result.inliers + "}");
            previousId = id;
        }
        System.out.println("{\"frames\":" + files.length + ",\"baselines\":" + baselines
                + ",\"registered\":" + registered + ",\"unknown\":" + unknown + ",\"bridges\":" + bridges
                + ",\"records\":[" + String.join(",", records) + "]}");
    }
    private static long timestamp(File file) {
        String name = file.getName();
        return Long.parseLong(name.substring(6, name.length() - 4));
    }
}
