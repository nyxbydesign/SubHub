package com.subhub.app.service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Refines a coarse vertical image displacement. Spatial evidence only: no clock/camera authority. */
final class SpatialFrameRegistration {
    private static final int RADIUS = 3;
    private static final int GRID_ROWS = 8;
    private static final int GRID_COLUMNS = 4;
    private static final Result REJECTED = new Result(false, 0, 0, 0, 0, 0, 0);

    static Result refine(int[] previous, int[] current, int width, int height,
            int top, int bottom, double coarseDy) {
        if (previous == null || current == null || width < 32 || width > 512
                || height < 64 || height > 512 || (long) width * height != previous.length
                || previous.length != current.length || top < 0 || bottom > height
                || bottom - top < 64 || !Double.isFinite(coarseDy)
                || Math.abs(coarseDy) > (bottom - top) / 3d) return REJECTED;
        float[] before = luminance(previous), after = luminance(current);
        List<Match> matches = new ArrayList<>();
        int features = 0;
        for (int row = 0; row < GRID_ROWS; row++) {
            int firstY = Math.max(top + row * (bottom - top) / GRID_ROWS + RADIUS,
                    top + RADIUS - (int) Math.floor(coarseDy - 3));
            int lastY = Math.min(top + (row + 1) * (bottom - top) / GRID_ROWS - RADIUS - 1,
                    bottom - RADIUS - 2 - (int) Math.ceil(coarseDy + 3));
            for (int column = 0; column < GRID_COLUMNS; column++) {
                int firstX = column * width / GRID_COLUMNS + RADIUS;
                int lastX = (column + 1) * width / GRID_COLUMNS - RADIUS - 1;
                float bestTexture = 144f;
                int selectedX = -1, selectedY = -1;
                for (int y = firstY; y <= lastY; y += 3) {
                    for (int x = firstX; x <= lastX; x += 3) {
                        float texture = variance(before, width, x, y);
                        if (texture > bestTexture && verticalTexture(before, width, x, y) > 16) {
                            bestTexture = texture;
                            selectedX = x;
                            selectedY = y;
                        }
                    }
                }
                if (selectedX < 0) continue;
                features++;
                Match match = match(before, after, width, height, selectedX, selectedY, coarseDy,
                        Math.min(3, (selectedY - top) * 4 / (bottom - top)), column);
                if (match != null) matches.add(match);
            }
        }
        if (matches.size() < 8) return REJECTED;
        List<Match> best = new ArrayList<>();
        for (Match seed : matches) {
            List<Match> group = new ArrayList<>();
            for (Match value : matches) if (Math.abs(value.dy - seed.dy) <= .5) group.add(value);
            if (group.size() > best.size()) best = group;
        }
        if (best.size() < 8 || best.size() < features * .6) return REJECTED;
        double[] shifts = new double[best.size()];
        double error = 0;
        int bands = 0, columns = 0;
        for (int index = 0; index < best.size(); index++) {
            Match value = best.get(index);
            shifts[index] = value.dy;
            error += value.error;
            bands |= 1 << value.band;
            columns |= 1 << value.column;
        }
        if (Integer.bitCount(bands) < 3 || Integer.bitCount(columns) < 2) return REJECTED;
        Arrays.sort(shifts);
        double dy = median(shifts);
        double[] deviations = new double[shifts.length];
        for (int i = 0; i < shifts.length; i++) deviations[i] = Math.abs(shifts[i] - dy);
        Arrays.sort(deviations);
        if (median(deviations) > .375) return REJECTED;
        return new Result(true, dy, best.size(), features,
                Integer.bitCount(bands), Integer.bitCount(columns), error / best.size());
    }

    private static Match match(float[] before, float[] after, int width, int height,
            int x, int y, double coarseDy, int band, int column) {
        double[] scores = new double[13];
        int best = 0;
        for (int index = 0; index < scores.length; index++) {
            scores[index] = score(before, after, width, height, x, y, coarseDy + (index - 6) * .5);
            if (scores[index] < scores[best]) best = index;
        }
        if (best == 0 || best == scores.length - 1 || scores[best] > 32) return null;
        double second = Double.POSITIVE_INFINITY;
        for (int index = 0; index < scores.length; index++) {
            if (Math.abs(index - best) > 2) second = Math.min(second, scores[index]);
        }
        if (second - scores[best] < Math.max(.8, second * .12)) return null;
        double center = coarseDy + (best - 6) * .5;
        double dy = center, error = scores[best];
        for (int step = -4; step <= 4; step++) {
            double candidate = center + step * .125;
            double candidateError = score(before, after, width, height, x, y, candidate);
            if (candidateError < error) { error = candidateError; dy = candidate; }
        }
        double stationary = score(before, after, width, height, x, y, 0);
        if (error > 18) return null;
        if (Math.abs(dy) < .25 ? error > 4 : error > stationary * .8) return null;
        return new Match(dy, error, band, column);
    }

    private static double score(float[] before, float[] after, int width, int height,
            int x, int y, double dy) {
        int shift = (int) Math.floor(dy);
        float fraction = (float) (dy - shift);
        if (y - RADIUS + shift < 0 || y + RADIUS + shift + 1 >= height) return Double.POSITIVE_INFINITY;
        double firstMean = 0, secondMean = 0;
        for (int yy = y - RADIUS; yy <= y + RADIUS; yy++) {
            for (int xx = x - RADIUS; xx <= x + RADIUS; xx++) {
                firstMean += before[yy * width + xx];
                int at = (yy + shift) * width + xx;
                secondMean += after[at] * (1 - fraction) + after[at + width] * fraction;
            }
        }
        double meanDelta = (firstMean - secondMean) / 49;
        double error = 0;
        for (int yy = y - RADIUS; yy <= y + RADIUS; yy++) {
            for (int xx = x - RADIUS; xx <= x + RADIUS; xx++) {
                int at = (yy + shift) * width + xx;
                error += Math.abs(before[yy * width + xx]
                        - (after[at] * (1 - fraction) + after[at + width] * fraction) - meanDelta);
            }
        }
        return error / 49;
    }

    private static float variance(float[] values, int width, int x, int y) {
        float sum = 0, square = 0;
        for (int yy = y - RADIUS; yy <= y + RADIUS; yy++) {
            for (int xx = x - RADIUS; xx <= x + RADIUS; xx++) {
                float value = values[yy * width + xx];
                sum += value;
                square += value * value;
            }
        }
        return square / 49 - (sum / 49) * (sum / 49);
    }

    private static float verticalTexture(float[] values, int width, int x, int y) {
        // A vertical edge can have huge intensity variance but no vertical-position information.
        // Do not count aperture-only patches in the support denominator for vertical registration.
        float square = 0;
        for (int yy = y - RADIUS + 1; yy <= y + RADIUS - 1; yy++) {
            for (int xx = x - RADIUS; xx <= x + RADIUS; xx++) {
                float difference = values[(yy + 1) * width + xx] - values[(yy - 1) * width + xx];
                square += difference * difference;
            }
        }
        return square / 35;
    }

    private static float[] luminance(int[] pixels) {
        float[] result = new float[pixels.length];
        for (int i = 0; i < pixels.length; i++) {
            int color = pixels[i];
            result[i] = (3 * ((color >>> 16) & 255) + 6 * ((color >>> 8) & 255) + (color & 255)) / 10f;
        }
        return result;
    }

    private static double median(double[] sorted) {
        int size = sorted.length;
        return size % 2 == 1 ? sorted[size / 2] : (sorted[size / 2 - 1] + sorted[size / 2]) / 2;
    }

    private static final class Match {
        final double dy, error;
        final int band, column;
        Match(double dy, double error, int band, int column) {
            this.dy = dy; this.error = error; this.band = band; this.column = column;
        }
    }

    static final class Result {
        final boolean accepted;
        final double dy, meanError;
        final int inliers, features, bands, columns;
        Result(boolean accepted, double dy, int inliers, int features, int bands, int columns, double meanError) {
            this.accepted = accepted; this.dy = dy; this.inliers = inliers; this.features = features;
            this.bands = bands; this.columns = columns; this.meanError = meanError;
        }
    }
}
