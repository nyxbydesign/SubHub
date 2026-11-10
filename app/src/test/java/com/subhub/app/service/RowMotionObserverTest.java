package com.subhub.app.service;

import org.junit.Test;
import java.util.Random;
import static org.junit.Assert.*;

public final class RowMotionObserverTest {
    private static int[] frame(int shift) {
        int[] out=new int[64*320]; Random random=new Random(55);
        for(int row=0;row<160;row++) for(int column=0;column<4;column++) {
            int value=20+random.nextInt(200), color=0xff000000|value*0x010101;
            for(int y=row*2+shift;y<row*2+shift+2;y++) if(y>=0 && y<320)
                for(int x=column*16;x<(column+1)*16;x++) out[y*64+x]=color;
        }
        return out;
    }
    private static RowMotionObserver.Sample observe(RowMotionObserver observer,int[] frame,long time,boolean active) {
        return observer.observe(frame,64,320,0,320,new RowMotionObserver.Scope(1,1,2,64,320),time,active);
    }
    @Test public void scalesMotionAndCarriesExactPairTimes() {
        RowMotionObserver o=new RowMotionObserver();
        assertFalse(observe(o,frame(0),100,false).accepted);
        RowMotionObserver.Sample s=observe(o,frame(-10),433,true);
        assertTrue(s.accepted); assertEquals(-10,s.dy,0);
        assertEquals(100,s.previousTime); assertEquals(433,s.currentTime);
    }
    @Test public void idleAnimationHasNoAuthorityAndCallerPixelsAreNotRetained() {
        RowMotionObserver o=new RowMotionObserver(); int[] input=frame(0);
        observe(o,input,100,false); java.util.Arrays.fill(input,0);
        assertTrue(observe(o,frame(-10),400,true).accepted);
        assertFalse(observe(o,frame(-20),700,false).accepted);
    }
    @Test public void invalidOrderGapScopeAndGeometryCannotBeCompared() {
        RowMotionObserver o=new RowMotionObserver();
        observe(o,frame(0),100,false);
        assertFalse(observe(o,frame(-10),100,true).accepted);
        assertFalse(observe(o,frame(-10),200,true).accepted);
        assertTrue(observe(o,frame(-20),400,true).accepted);
        assertFalse(observe(o,frame(-10),1200,true).accepted);
        assertFalse(o.observe(frame(-10),64,320,0,320,new RowMotionObserver.Scope(1,2,2,64,320),1400,true).accepted);
        assertFalse(o.observe(frame(-10),64,320,0,320,new RowMotionObserver.Scope(1,2,3,64,320),1500,true).accepted);
        assertFalse(o.observe(frame(-10),64,320,1,320,new RowMotionObserver.Scope(1,2,3,64,320),1600,true).accepted);
        assertFalse(o.observe(null,64,320,0,320,new RowMotionObserver.Scope(1,2,3,64,320),1700,true).accepted);
        assertFalse(o.observe(frame(-10),64,320,0,320,new RowMotionObserver.Scope(1,2,3,64,320),1800,true).accepted);
        assertFalse(o.observe(frame(-10),64,320,0,320,new RowMotionObserver.Scope(2,2,3,64,320),1900,true).accepted);
        assertFalse(o.observe(frame(-10),64,320,0,320,new RowMotionObserver.Scope(2,2,3,128,640),2000,true).accepted);
    }
}
