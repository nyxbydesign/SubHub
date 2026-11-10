package com.subhub.app.service;

import org.junit.Test;
import java.util.Random;
import static org.junit.Assert.*;

public final class RowMotionEstimatorTest {
    private static double[][] texture() {
        Random random = new Random(43);
        double[][] data = new double[160][4];
        for (double[] row : data) for (int x=0;x<4;x++) row[x]=random.nextInt(200)+20;
        return data;
    }
    private static double[][] shift(double[][] input, int dy) {
        double[][] output = new double[input.length][4];
        for (int y=0;y<input.length;y++) if(y+dy>=0 && y+dy<input.length)
            output[y+dy]=input[y].clone();
        return output;
    }
    @Test public void acceptsTranslationAndTrueStationarity() {
        double[][] a=texture();
        for(int dy:new int[]{-16,-5,0,4,15}) {
            RowMotionEstimator.Result r=RowMotionEstimator.estimate(a,shift(a,dy));
            assertTrue("shift "+dy,r.accepted); assertEquals(dy,r.dy,0);
            assertTrue(r.agreeingBands>=3);
        }
    }
    @Test public void localAnimationDoesNotDragStationaryBackground() {
        double[][] a=texture(), b=shift(a,0);
        for(int y=40;y<80;y++) b[y]=a[(y+7)%160].clone();
        RowMotionEstimator.Result r=RowMotionEstimator.estimate(a,b);
        assertTrue(r.accepted); assertEquals(0,r.dy,0);
    }
    @Test public void distinctTranslationSurvivesBoundedBrightnessChange() {
        double[][] a=texture(), b=shift(a,5);
        for(double[] row:b) for(int x=0;x<4;x++) row[x]+=20;
        RowMotionEstimator.Result r=RowMotionEstimator.estimate(a,b);
        assertTrue(r.accepted); assertEquals(5,r.dy,0);
    }
    @Test public void disagreeingReflowIsRejected() {
        double[][] a=texture(), b=shift(a,5), other=shift(a,-5);
        for(int y=80;y<160;y++) b[y]=other[y];
        assertFalse(RowMotionEstimator.estimate(a,b).accepted);
    }
    @Test public void narrowAnimatedColumnCannotImpersonateWholeViewportMotion() {
        double[][] a=texture();
        for(double[] row:a) for(int x=1;x<4;x++) row[x]=128;
        assertFalse(RowMotionEstimator.estimate(a,shift(a,5)).accepted);
    }
    @Test public void flatRepeatedInvalidAndSearchBoundaryAreRejected() {
        assertFalse(RowMotionEstimator.estimate(new double[160][4],new double[160][4]).accepted);
        double[][] repeated=new double[160][4];
        for(int y=0;y<160;y++) for(int x=0;x<4;x++) repeated[y][x]=(y%4)*60;
        assertFalse(RowMotionEstimator.estimate(repeated,shift(repeated,4)).accepted);
        double[][] a=texture();
        assertFalse(RowMotionEstimator.estimate(a,shift(a,24)).accepted);
        a[0][0]=Double.NaN; assertFalse(RowMotionEstimator.estimate(a,a).accepted);
    }

    @Test public void everyInteriorShiftSurvivesSmallNoiseAcrossIndependentTextures() {
        for(int seed=0;seed<20;seed++) {
            Random random=new Random(6100+seed);
            double[][] previous=new double[160][4];
            for(double[] row:previous) for(int x=0;x<4;x++) row[x]=20+random.nextInt(200);
            for(int dy=-23;dy<=23;dy++) {
                double[][] current=shift(previous,dy);
                for(int y=0;y<160;y++) if(y-dy>=0 && y-dy<160)
                    for(int x=0;x<4;x++) current[y][x]+=random.nextInt(5)-2;
                RowMotionEstimator.Result result=RowMotionEstimator.estimate(previous,current);
                String context="seed="+seed+" dy="+dy;
                assertTrue(context,result.accepted);
                assertEquals(context,dy,result.dy,0);
            }
        }
    }

    @Test public void unrelatedTexturedFramesNeverProduceAcceptedCameraMotion() {
        for(int seed=0;seed<100;seed++) {
            Random random=new Random(6200+seed);
            double[][] previous=new double[160][4],current=new double[160][4];
            for(int y=0;y<160;y++) for(int x=0;x<4;x++) {
                previous[y][x]=20+random.nextInt(200);
                current[y][x]=20+random.nextInt(200);
            }
            assertFalse("seed="+seed,RowMotionEstimator.estimate(previous,current).accepted);
        }
    }
}
