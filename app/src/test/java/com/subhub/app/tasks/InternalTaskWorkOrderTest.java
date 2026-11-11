package com.subhub.app.tasks;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import org.junit.Test;
import static org.junit.Assert.*;

public final class InternalTaskWorkOrderTest {
    @Test public void asynchronousCleanupFailureDoesNotRejectCurrentWork(){
        List<String> events=new ArrayList<>();CompletableFuture<String> work=new CompletableFuture<>();
        CompletableFuture<Void> cleanup=new CompletableFuture<>();
        var result=InternalTaskWorkOrder.submit(()->{events.add("work");return work;},
                ()->{events.add("cleanup");return cleanup;},()->events.add("retry"));
        assertSame(work,result);assertEquals(List.of("work","cleanup"),events);
        cleanup.completeExceptionally(new IllegalStateException());assertFalse(result.isDone());
        work.complete("ack");assertEquals("ack",result.join());assertEquals(List.of("work","cleanup","retry"),events);
    }
    @Test public void synchronousCleanupFailureCannotPreventAcceptedAssessment(){
        int[] retries={0};CompletableFuture<String> work=CompletableFuture.completedFuture("ack");
        assertSame(work,InternalTaskWorkOrder.submit(()->work,()->{throw new IllegalStateException();},()->retries[0]++));
        assertEquals(1,retries[0]);
    }
    @Test public void pendingCleanupCannotDelayCurrentWorkAndWorkFailureIsNotHidden(){
        CompletableFuture<Void> cleanup=new CompletableFuture<>();CompletableFuture<Void> work=new CompletableFuture<>();
        var result=InternalTaskWorkOrder.submit(()->work,()->cleanup,()->fail("Unexpected cleanup failure"));
        work.completeExceptionally(new SecurityException("revoked"));assertTrue(result.isCompletedExceptionally());
        assertFalse(cleanup.isDone());
    }
}
