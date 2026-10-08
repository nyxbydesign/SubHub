package com.subhub.app.service;

import com.subhub.app.detection.BBox;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Bounded object-local image correspondence. Neither a person identifier nor a display camera. */
final class SourcePatchMatcher {
    private static final int RADIUS = 3;
    private static final int SAMPLES = 49;

    static final class Image {
        final int width, height;
        private final byte[] gray;
        private Image(int[] pixels, int width, int height) {
            this.width = width;
            this.height = height;
            gray = new byte[pixels.length];
            for (int i = 0; i < pixels.length; i++) {
                int color = pixels[i];
                gray[i] = (byte) ((3 * ((color >>> 16) & 255) + 6 * ((color >>> 8) & 255)
                        + (color & 255)) / 10);
            }
        }
        static Image copyOf(int[] pixels, int width, int height) {
            return pixels != null && width >= 32 && height >= 64 && width <= 512 && height <= 512
                    && (long) width * height == pixels.length ? new Image(pixels, width, height) : null;
        }
        private double sample(double x, double y) {
            int left = (int) Math.floor(x), top = (int) Math.floor(y);
            if (left < 0 || top < 0 || left + 1 >= width || top + 1 >= height) return Double.NaN;
            double fx = x - left, fy = y - top;
            int at = top * width + left;
            return ((gray[at] & 255) * (1 - fx) + (gray[at + 1] & 255) * fx) * (1 - fy)
                    + ((gray[at + width] & 255) * (1 - fx) + (gray[at + width + 1] & 255) * fx) * fy;
        }
    }

    static final class Match {
        final double sourceDy;
        final int patches;
        Match(double sourceDy, int patches) { this.sourceDy = sourceDy; this.patches = patches; }
    }

    static Match match(Image previous, Image current, BBox oldBox, BBox newBox,
            int sourceWidth, int sourceHeight) {
        if (previous == null || current == null || previous.width != current.width
                || previous.height != current.height || sourceWidth <= 0 || sourceHeight <= 0
                || oldBox == null || newBox == null || oldBox.getArea() <= 0 || newBox.getArea() <= 0) return null;
        double sx = previous.width / (double) sourceWidth, sy = previous.height / (double) sourceHeight;
        double width = oldBox.getWidth() * sx, height = oldBox.getHeight() * sy;
        double widthRatio = newBox.getWidth() / (double) oldBox.getWidth();
        double heightRatio = newBox.getHeight() / (double) oldBox.getHeight();
        if (width < 18 || height < 18 || widthRatio < .8 || widthRatio > 1.25
                || heightRatio < .8 || heightRatio > 1.25) return null;
        double coarse = (newBox.getY() + newBox.getHeight() / 2d
                - oldBox.getY() - oldBox.getHeight() / 2d) * sy;
        double modelXDelta = (newBox.getX() + newBox.getWidth() / 2d
                - oldBox.getX() - oldBox.getWidth() / 2d) * sx;
        if (Math.abs(coarse) > current.height / 3d || Math.abs(modelXDelta) > 1.5) return null;
        // This experiment is vertical-only. Detector-box x jitter is not image-motion evidence.
        double xDelta = 0;
        // Four disjoint patches, covering both halves of the object rather than one moving edge.
        List<double[]> supported = new ArrayList<>();
        for (int row = 0; row < 2; row++) for (int column = 0; column < 2; column++) {
            int x = (int) Math.round(oldBox.getX() * sx + width * (.25 + column * .5));
            int y = (int) Math.round(oldBox.getY() * sy + height * (.25 + row * .5));
            double[] reference = patch(previous, x, y);
            if (reference == null || variance(reference) < 144 || verticalTexture(reference) < 16) continue;
            boolean repetitive = false;
            for (int shift = -6; shift <= 6; shift += 2) {
                if (shift != 0 && error(reference, previous, x, y, 0, shift) < 1) repetitive = true;
            }
            if (repetitive) continue;
            double[] scores = new double[13];
            int best = 0;
            for (int index = 0; index < scores.length; index++) {
                scores[index] = error(reference, current, x, y, xDelta, coarse + (index - 6) * .5);
                if (scores[index] < scores[best]) best = index;
            }
            if (best == 0 || best == 12 || !Double.isFinite(scores[best]) || scores[best] > 24) continue;
            double second = Double.POSITIVE_INFINITY;
            for (int index = 0; index < scores.length; index++) {
                if (Math.abs(index - best) > 2) second = Math.min(second, scores[index]);
            }
            if (!Double.isFinite(second) || second - scores[best] < Math.max(1, second * .15)) continue;
            double dy = coarse + (best - 6) * .5, bestError = scores[best];
            for (int step = -4; step <= 4; step++) {
                double candidate = coarse + (best - 6) * .5 + step * .125;
                double score = error(reference, current, x, y, xDelta, candidate);
                if (score < bestError) { bestError = score; dy = candidate; }
            }
            if (bestError > 8) continue;
            double correlation = correlation(reference, current, x, y, xDelta, dy);
            if (!Double.isFinite(correlation) || correlation < .95) continue;
            supported.add(new double[] {dy, row, column});
        }
        for (double[] seed : supported) {
            double[] shifts = new double[4];
            int count = 0, rows = 0, columns = 0;
            for (double[] value : supported) if (Math.abs(value[0] - seed[0]) <= .5) {
                shifts[count++] = value[0];
                rows |= 1 << (int) value[1];
                columns |= 1 << (int) value[2];
            }
            if (count >= 3 && rows == 3 && columns == 3) {
                Arrays.sort(shifts, 0, count);
                double dy = count % 2 == 1 ? shifts[count / 2]
                        : (shifts[count / 2 - 1] + shifts[count / 2]) / 2;
                return new Match(dy / sy, count);
            }
        }
        return null;
    }

    private static double[] patch(Image image, int x, int y) {
        if (x - RADIUS < 0 || y - RADIUS < 0 || x + RADIUS >= image.width - 1
                || y + RADIUS >= image.height - 1) return null;
        double[] values = new double[SAMPLES];
        int at = 0;
        for (int yy = -RADIUS; yy <= RADIUS; yy++) for (int xx = -RADIUS; xx <= RADIUS; xx++) {
            values[at++] = image.sample(x + xx, y + yy);
        }
        return values;
    }

    private static double error(double[] reference, Image image, int x, int y, double dx, double dy) {
        double delta = 0;
        int at = 0;
        for (int yy = -RADIUS; yy <= RADIUS; yy++) for (int xx = -RADIUS; xx <= RADIUS; xx++) {
            double value = image.sample(x + xx + dx, y + yy + dy);
            if (!Double.isFinite(value)) return Double.POSITIVE_INFINITY;
            delta += reference[at++] - value;
        }
        delta /= SAMPLES;
        double error = 0;
        at = 0;
        for (int yy = -RADIUS; yy <= RADIUS; yy++) for (int xx = -RADIUS; xx <= RADIUS; xx++) {
            error += Math.abs(reference[at++] - image.sample(x + xx + dx, y + yy + dy) - delta);
        }
        return error / SAMPLES;
    }

    private static double correlation(double[] reference, Image image, int x, int y, double dx, double dy) {
        double first = 0, second = 0, firstSquare = 0, secondSquare = 0, cross = 0;
        int at = 0;
        for (int yy = -RADIUS; yy <= RADIUS; yy++) for (int xx = -RADIUS; xx <= RADIUS; xx++) {
            double a = reference[at++], b = image.sample(x + xx + dx, y + yy + dy);
            first += a; second += b; firstSquare += a * a; secondSquare += b * b; cross += a * b;
        }
        double denominator = Math.sqrt((firstSquare - first * first / SAMPLES)
                * (secondSquare - second * second / SAMPLES));
        return denominator > 0 ? (cross - first * second / SAMPLES) / denominator : -1;
    }

    private static double variance(double[] values) {
        double sum = 0, square = 0;
        for (double value : values) { sum += value; square += value * value; }
        return square / SAMPLES - sum * sum / (SAMPLES * SAMPLES);
    }

    private static double verticalTexture(double[] values) {
        double square = 0;
        for (int y = 1; y < 6; y++) for (int x = 0; x < 7; x++) {
            double difference = values[(y + 1) * 7 + x] - values[(y - 1) * 7 + x];
            square += difference * difference;
        }
        return square / 35;
    }
}
