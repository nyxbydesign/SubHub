package com.subhub.app.service;

import java.util.Arrays;

/** Pure bounded observer of four-column luminance row descriptors. No camera authority. */
final class RowMotionEstimator {
    private static final int BANDS = 4, COLUMNS = 4, MAX_SHIFT = 24;
    static final class Result {
        final boolean accepted;
        final double dy;
        final int agreeingBands;
        Result(boolean accepted, double dy, int agreeingBands) {
            this.accepted = accepted; this.dy = dy; this.agreeingBands = agreeingBands;
        }
    }
    private static final Result REJECTED = new Result(false, 0, 0);

    static Result estimate(double[][] previous, double[][] current) {
        if (!valid(previous) || !valid(current) || previous.length != current.length) return REJECTED;
        int height = previous.length;
        int[] shifts = new int[BANDS];
        boolean[] reliable = new boolean[BANDS];
        for (int band = 0; band < BANDS; band++) {
            int top = band * height / BANDS, bottom = (band + 1) * height / BANDS;
            double texture = 0;
            for (int y = top + 1; y < bottom; y++)
                for (int x = 0; x < COLUMNS; x++) texture += Math.abs(previous[y][x] - previous[y-1][x]);
            if (texture / ((bottom-top-1) * COLUMNS) < 3) continue;
            double[] scores = new double[MAX_SHIFT * 2 + 1];
            // Score stationary fully first. Other candidates may stop once even their partial
            // lower bound exceeds both the best score and its required ambiguity margin.
            int best = MAX_SHIFT;
            scores[best]=score(previous,current,top,bottom,0,Double.POSITIVE_INFINITY);
            // Visit small offsets first to tighten the bound early for ordinary scrolling.
            // Still search every offset; preserve stationary-first then lowest-shift tie order.
            for (int candidate = 1; candidate <= MAX_SHIFT*2; candidate++) {
                int magnitude=(candidate+1)/2;
                int shift=candidate%2==1 ? -magnitude : magnitude;
                int index = shift + MAX_SHIFT;
                double cutoff=Math.max(scores[best]+2,scores[best]/.92);
                scores[index]=score(previous,current,top,bottom,shift,cutoff);
                if (scores[index] < scores[best]
                        || (scores[index] == scores[best] && best != MAX_SHIFT && index < best))
                    best = index;
            }
            int dy = best-MAX_SHIFT;
            double runnerUp = Double.POSITIVE_INFINITY;
            for (int i = 0; i < scores.length; i++)
                if (Math.abs(i-best)>1) runnerUp = Math.min(runnerUp, scores[i]);
            boolean distinct = runnerUp-scores[best] >= Math.max(2, runnerUp*.08);
            boolean improved = dy == 0 ? scores[best] <= 2
                    : scores[best] <= scores[MAX_SHIFT]*.8;
            reliable[band] = distinct && improved && scores[best] <= 32
                    && Math.abs(dy) < MAX_SHIFT
                    && supportedColumns(previous,current,top,bottom,dy)>=2;
            shifts[band] = dy;
        }
        int bestCount = 0; double bestShift = 0;
        for (int seed = 0; seed < BANDS; seed++) {
            if (!reliable[seed]) continue;
            int[] group = new int[BANDS]; int count = 0;
            for (int band = 0; band < BANDS; band++)
                if (reliable[band] && Math.abs(shifts[band]-shifts[seed]) <= 1) group[count++] = shifts[band];
            if (count > bestCount) {
                Arrays.sort(group, 0, count);
                bestShift = count % 2 == 1 ? group[count/2] : (group[count/2-1]+group[count/2])*.5;
                bestCount = count;
            }
        }
        return bestCount >= 3 ? new Result(true, bestShift, bestCount) : REJECTED;
    }

    private static double score(double[][] previous,double[][] current,int top,int bottom,int shift,double cutoff) {
        int from=Math.max(top,-shift),to=Math.min(bottom,previous.length-shift);
        if(to-from<(bottom-top)*.6) return Double.POSITIVE_INFINITY;
        int count=(to-from)*COLUMNS;
        double limit=cutoff*count,error=0;
        for(int y=from;y<to;y++) {
            double[] left=previous[y],right=current[y+shift];
            error+=Math.abs(left[0]-right[0]); error+=Math.abs(left[1]-right[1]);
            error+=Math.abs(left[2]-right[2]); error+=Math.abs(left[3]-right[3]);
            if(error>limit) break;
        }
        return error/count;
    }

    private static int supportedColumns(double[][] previous,double[][] current,int top,int bottom,int dy) {
        int from=Math.max(top,-dy), to=Math.min(bottom,previous.length-dy), supported=0;
        for(int x=0;x<COLUMNS;x++) {
            double texture=0, shifted=0, stationary=0;
            for(int y=top+1;y<bottom;y++) texture+=Math.abs(previous[y][x]-previous[y-1][x]);
            if(texture/(bottom-top-1)<3) continue;
            for(int y=from;y<to;y++) {
                shifted+=Math.abs(previous[y][x]-current[y+dy][x]);
                stationary+=Math.abs(previous[y][x]-current[y][x]);
            }
            shifted/=to-from; stationary/=to-from;
            if(shifted<=32 && (dy==0 ? shifted<=2 : shifted<=stationary*.8)) supported++;
        }
        return supported;
    }

    private static boolean valid(double[][] rows) {
        if (rows == null || rows.length < 96 || rows.length > 320) return false;
        for (double[] row : rows) {
            if (row == null || row.length != COLUMNS) return false;
            for (double value : row) if (!Double.isFinite(value) || value < 0 || value > 255) return false;
        }
        return true;
    }
}
