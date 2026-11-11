package com.subhub.app.capture;

import android.graphics.Bitmap;
import android.view.Surface;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Real Bitmap/ImageReader, explicit fake display. No projection grant or hardware-render proof. */
@RunWith(AndroidJUnit4.class)
public final class ScreenCaptureTerminalPoolAndroidTest {
    @Test public void sealedPoolAbandonsPriorAndLateFramesWithoutRecyclingOrReissue() throws Exception {
        ScreenCaptureManager capture = capture(new AtomicInteger(), false);
        Bitmap pooled = Bitmap.createBitmap(320,480,Bitmap.Config.ARGB_8888);
        Bitmap late = Bitmap.createBitmap(320,480,Bitmap.Config.ARGB_8888);
        try {
            capture.start(true); capture.releaseFrame(pooled); assertEquals(1,pool(capture).size());
            capture.sealSubmittedFrames(); assertFalse(capture.managedResourcesDrained());
            capture.releaseFrame(late); assertEquals(1,pool(capture).size()); assertNull(capture.acquireLatestFrame());
            capture.pauseSurface(false); assertNull(capture.acquireLatestFrame());
            capture.close(); capture.close(); assertTrue(capture.managedResourcesDrained());
            assertTrue(pool(capture).isEmpty()); assertFalse(pooled.isRecycled()); assertFalse(late.isRecycled());
            assertThrows(IllegalStateException.class,capture::start);
        } finally { capture.sealSubmittedFrames(); capture.close(); }
    }
    @Test public void sealIsNonblockingWhileCaptureMonitorIsHeld() throws Exception {
        ScreenCaptureManager capture=capture(new AtomicInteger(),false);
        CountDownLatch locked=new CountDownLatch(1),leave=new CountDownLatch(1);
        AtomicReference<Throwable> failure=new AtomicReference<>();
        Thread holder=new Thread(()->{try{synchronized(capture){locked.countDown();assertTrue(leave.await(5,TimeUnit.SECONDS));}}catch(Throwable bad){failure.set(bad);}});
        holder.start();
        try { assertTrue(locked.await(5,TimeUnit.SECONDS)); capture.sealSubmittedFrames(); assertFalse(capture.managedResourcesDrained()); }
        finally { leave.countDown();holder.join(5000);capture.close(); }
        assertFalse(holder.isAlive());assertNull(failure.get());assertTrue(capture.managedResourcesDrained());
    }
    @Test public void failedDisplayCloseStillClosesReaderButNeverAcknowledgesOrRetries() throws Exception {
        AtomicInteger releases=new AtomicInteger(); ScreenCaptureManager capture=capture(releases,true);
        capture.start(); Field reader=ScreenCaptureManager.class.getDeclaredField("imageReader");reader.setAccessible(true);
        android.media.ImageReader actual=(android.media.ImageReader)reader.get(capture);Surface surface=actual.getSurface();
        capture.sealSubmittedFrames();assertThrows(IllegalStateException.class,capture::close);
        assertFalse(surface.isValid());assertFalse(capture.managedResourcesDrained());
        assertThrows(IllegalStateException.class,capture::close);assertEquals(1,releases.get());
        // Fake display threw after no real external action; never turn its uncertainty into a receipt.
    }
    @SuppressWarnings("unchecked") private static ArrayDeque<Bitmap> pool(ScreenCaptureManager capture) throws Exception {
        Field field=ScreenCaptureManager.class.getDeclaredField("framePool");field.setAccessible(true);return (ArrayDeque<Bitmap>)field.get(capture);
    }
    private static ScreenCaptureManager capture(AtomicInteger releases,boolean fail) {
        return new ScreenCaptureManager(320,480,160,320,1f,(w,h,d,s)->new ScreenCaptureManager.DisplaySession(){
            public void surface(Surface value){}
            public void release(){releases.incrementAndGet();if(fail)throw new IllegalStateException("Injected display failure");}
        });
    }
}
