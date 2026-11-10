package com.subhub.app.service;

import android.os.Debug;
import android.util.Log;
import org.junit.Test;
import java.util.Arrays;
import java.util.Random;
import static org.junit.Assert.*;

/** Arithmetic/descriptor benchmark only; no capture, settings or render authority. */
public final class RowMotionCpuAndroidTest {
    private static final int WARMUP = 400;
    @Test public void measurePreparedPixelObserverCpuAndWallTime() {
        int[][] frames={frame(0),frame(-10)};
        RowMotionObserver observer=new RowMotionObserver();
        RowMotionObserver.Scope scope=new RowMotionObserver.Scope(1,1,1,1344,2992);
        long[] cpu=new long[60],wall=new long[60];
        long timestamp=1000;
        observer.observe(frames[0],144,320,0,320,scope,timestamp,true);
        for(int i=0;i<WARMUP+60;i++) {
            timestamp+=333;
            long wallStart=System.nanoTime(),cpuStart=Debug.threadCpuTimeNanos();
            RowMotionObserver.Sample sample=observer.observe(frames[(i+1)%2],144,320,0,320,scope,timestamp,true);
            long cpuTime=Debug.threadCpuTimeNanos()-cpuStart,wallTime=System.nanoTime()-wallStart;
            assertTrue(sample.accepted);
            assertEquals(i%2==0 ? -10 : 10,sample.dy,0);
            if(i>=WARMUP) {cpu[i-WARMUP]=cpuTime;wall[i-WARMUP]=wallTime;}
        }
        Arrays.sort(cpu);Arrays.sort(wall);
        Log.i("RowMotionCpuTest","ROW_CPU_PROBE samples=60 warmup="+WARMUP+" cpuMedianUs="+cpu[30]/1000
                +" cpuP95Us="+cpu[56]/1000+" cpuMaxUs="+cpu[59]/1000
                +" wallMedianUs="+wall[30]/1000+" wallP95Us="+wall[56]/1000
                +" wallMaxUs="+wall[59]/1000);
    }
    @Test public void separateDescriptorAndEstimatorCpuCost() throws Exception {
        // Reflect only in this diagnostic; production visibility remains unchanged.
        // Descriptor timing includes invocation overhead, so it is an upper bound.
        java.lang.reflect.Method describe=RowMotionObserver.class.getDeclaredMethod(
                "describe",int[].class,int.class,int.class,int.class);
        describe.setAccessible(true);
        int[][] frames={frame(0),frame(-10)};
        double[][] previous=(double[][])describe.invoke(null,frames[0],144,0,320);
        long[] descriptorCpu=new long[60],estimatorCpu=new long[60];
        for(int i=0;i<WARMUP+60;i++) {
            long start=Debug.threadCpuTimeNanos();
            double[][] current=(double[][])describe.invoke(null,frames[(i+1)%2],144,0,320);
            long prepared=Debug.threadCpuTimeNanos();
            RowMotionEstimator.Result result=RowMotionEstimator.estimate(previous,current);
            long matched=Debug.threadCpuTimeNanos();
            assertTrue(result.accepted);
            assertEquals(i%2==0 ? -5 : 5,result.dy,0);
            if(i>=WARMUP) {descriptorCpu[i-WARMUP]=prepared-start;estimatorCpu[i-WARMUP]=matched-prepared;}
            previous=current;
        }
        Arrays.sort(descriptorCpu);Arrays.sort(estimatorCpu);
        Log.i("RowMotionCpuTest","ROW_STAGE_CPU samples=60 warmup="+WARMUP+" descriptorIncludesReflection=true"
                +" descriptorMedianUs="+descriptorCpu[30]/1000
                +" descriptorP95Us="+descriptorCpu[56]/1000
                +" estimatorMedianUs="+estimatorCpu[30]/1000
                +" estimatorP95Us="+estimatorCpu[56]/1000);
    }

    private static int[] frame(int shift) {
        int[] pixels=new int[144*320];Random random=new Random(60);
        for(int row=0;row<160;row++) for(int column=0;column<4;column++) {
            int value=20+random.nextInt(200),color=0xff000000|value*0x010101;
            for(int y=row*2+shift;y<row*2+shift+2;y++) if(y>=0 && y<320)
                for(int x=column*36;x<(column+1)*36;x++) pixels[y*144+x]=color;
        }
        return pixels;
    }
}
