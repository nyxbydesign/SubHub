package com.subhub.app.remote;

import static org.junit.Assert.*;

import java.io.IOException;
import org.junit.Test;

public final class RemoteSessionFailureSummaryTest {
    @Test public void reportsCauseLocationsWithoutMessagesFilesOrForeignFrames() {
        IOException cause=new IOException("secret-token /private/peer-name");
        cause.setStackTrace(new StackTraceElement[]{
                new StackTraceElement("vendor.PrivatePeer","secretMethod","private-path",1),
                new StackTraceElement("com.subhub.app.remote.LinkingWitness","verify","private-path",247)});
        IllegalStateException wrapper=new IllegalStateException("private-wrapper",cause);
        wrapper.setStackTrace(new StackTraceElement[0]);
        String summary=RemoteFeature.sessionFailureSummary("outgoing-active",wrapper);
        assertTrue(summary.contains("caused-by java.io.IOException"));
        assertTrue(summary.contains("com.subhub.app.remote.LinkingWitness.verify:247"));
        for(String privateValue:new String[]{"secret-token","private-path","peer-name","private-wrapper","vendor.PrivatePeer","secretMethod"})
            assertFalse(summary.contains(privateValue));
    }

    @Test public void boundsCauseCyclesDepthFramesAndTotalCharacters() {
        Throwable first=new IOException(),second=new IOException();first.initCause(second);second.initCause(first);
        assertTrue(RemoteFeature.sessionFailureSummary("incoming",first).contains("cause-cycle"));
        Throwable deep=new IOException();for(int i=0;i<8;i++)deep=new IOException("hidden",deep);
        assertTrue(RemoteFeature.sessionFailureSummary("incoming",deep).contains("cause-truncated"));
        StackTraceElement[] frames=new StackTraceElement[20];
        for(int i=0;i<frames.length;i++)frames[i]=new StackTraceElement("com.subhub.app.remote.Witness","step"+i,"hidden-file",i);
        deep.setStackTrace(frames);String summary=RemoteFeature.sessionFailureSummary("incoming",deep);
        assertTrue(summary.contains(".step5:5"));assertFalse(summary.contains(".step6:6"));
        String longName=new String(new char[500]).replace('\0','a');
        for(Throwable item=deep;item!=null;item=item.getCause()) {
            StackTraceElement[] large=new StackTraceElement[6];
            for(int i=0;i<large.length;i++)large[i]=new StackTraceElement("com.subhub.app."+longName,longName,"hidden-file",1);
            item.setStackTrace(large);
        }
        summary=RemoteFeature.sessionFailureSummary(longName,deep);
        assertTrue(summary.length()<=4096);assertTrue(summary.contains("truncated"));
        assertFalse(summary.contains("hidden-file"));
    }
}
