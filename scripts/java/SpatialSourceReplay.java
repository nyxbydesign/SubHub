package com.subhub.app.service;

import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;

/** Host-only replay of private prepared images. Output is numeric metadata, never source pixels. */
public final class SpatialSourceReplay {
    private static final Pattern ROW = Pattern.compile("ROW_MOTION accepted=(true|false) previousMs=(-?\\d+) "
            + "currentMs=(\\d+) dyMilliPx=(-?\\d+) bands=(\\d+) preparedHeight=(\\d+) sourceHeight=(\\d+) "
            + "costMs=(\\d+)(?: cpuUs=(\\d+))?");

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Expected private source directory and trace");
        Path directory = Path.of(args[0]);
        List<String> records = new ArrayList<>();
        int raw = 0, parsed = 0, coarseAccepted = 0, missing = 0;
        for (String line : Files.readAllLines(Path.of(args[1]), StandardCharsets.UTF_8)) {
            int at = line.indexOf("ROW_MOTION ");
            if (at < 0) continue;
            raw++;
            Matcher row = ROW.matcher(line.substring(at).trim());
            if (!row.matches()) continue;
            parsed++;
            if (!Boolean.parseBoolean(row.group(1))) continue;
            coarseAccepted++;
            long previous = Long.parseLong(row.group(2)), current = Long.parseLong(row.group(3));
            Path firstPath = directory.resolve("frame-" + previous + ".png");
            Path secondPath = directory.resolve("frame-" + current + ".png");
            if (!Files.isRegularFile(firstPath) || !Files.isRegularFile(secondPath)) { missing++; continue; }
            BufferedImage first = ImageIO.read(firstPath.toFile()), second = ImageIO.read(secondPath.toFile());
            if (first == null || second == null || first.getWidth() != second.getWidth()
                    || first.getHeight() != second.getHeight()) throw new IllegalArgumentException("Invalid frame pair");
            int width = first.getWidth(), height = first.getHeight();
            if (height != Integer.parseInt(row.group(6))) throw new IllegalArgumentException("Trace/image height mismatch");
            int[] before = first.getRGB(0, 0, width, height, null, 0, width);
            int[] after = second.getRGB(0, 0, width, height, null, 0, width);
            double coarse = Long.parseLong(row.group(4)) / 1000d;
            long started = System.nanoTime();
            SpatialFrameRegistration.Result result = SpatialFrameRegistration.refine(
                    before, after, width, height, height / 5, height * 19 / 20, coarse);
            long cost = (System.nanoTime() - started) / 1000;
            double scale = Integer.parseInt(row.group(7)) / (double) height;
            records.add("{\"previousMs\":" + previous + ",\"currentMs\":" + current
                    + ",\"accepted\":" + result.accepted + ",\"coarseSourceDy\":" + coarse * scale
                    + ",\"sourceScale\":" + scale
                    + ",\"refinedSourceDy\":" + result.dy * scale + ",\"inliers\":" + result.inliers
                    + ",\"features\":" + result.features + ",\"bands\":" + result.bands
                    + ",\"columns\":" + result.columns + ",\"meanError\":" + result.meanError
                    + ",\"hostCostUs\":" + cost + "}");
        }
        if (raw == 0 || parsed != raw) throw new IllegalArgumentException("Incomplete row parsing");
        System.out.println("{\"schema\":1,\"rawRows\":" + raw + ",\"parsedRows\":" + parsed
                + ",\"coarseAccepted\":" + coarseAccepted + ",\"missingPairs\":" + missing
                + ",\"records\":[" + String.join(",", records) + "]}");
    }
}
