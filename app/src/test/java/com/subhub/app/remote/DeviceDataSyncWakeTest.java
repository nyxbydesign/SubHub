package com.subhub.app.remote;

import static org.junit.Assert.*;
import java.util.*;
import org.junit.Test;

public final class DeviceDataSyncWakeTest {
    private final Clock clock = new Clock();
    private int wakes;
    private final DeviceDataSyncWake gate = new DeviceDataSyncWake(clock, () -> clock.now, () -> wakes++);
    @Test public void continuousUsageCoalescesWithoutPostponingOrStartingAnIdleTimer() {
        for (int second=0;second<60;second++) { gate.telemetry(); clock.advance(1_000); }
        assertEquals(1,wakes);assertEquals(1,clock.jobs.size());
        clock.advance(900_000);assertEquals(1,wakes);assertEquals(1,clock.jobs.size());
        gate.telemetry();clock.advance(60_000);assertEquals(2,wakes);
    }
    @Test public void appOpenBringsPendingUsageForwardAndFencesTheCancelledCallback() {
        gate.telemetry();clock.advance(10_000);gate.activity();
        for(int i=0;i<100;i++){gate.activity();gate.telemetry();}
        clock.advance(999);assertEquals(0,wakes);clock.advance(1);assertEquals(1,wakes);
        clock.jobs.get(0).work.run();clock.advance(60_000);assertEquals(1,wakes);
        assertEquals(2,clock.jobs.size());assertTrue(clock.jobs.get(0).cancelled);
    }
    @Test public void activityAlreadyDueIsNotPostponedByUsageOrMoreActivity() {
        gate.activity();clock.advance(999);gate.telemetry();gate.activity();clock.advance(1);
        assertEquals(1,wakes);assertEquals(1,clock.jobs.size());
    }
    @Test public void lateActivityNeverPostponesAnEarlierTelemetryDeadline() {
        gate.telemetry();clock.advance(59_500);gate.activity();clock.advance(500);
        assertEquals(1,wakes);assertEquals(1,clock.jobs.size());
    }
    @Test public void mutationDuringDeliverySchedulesExactlyOneFollowUp() {
        DeviceDataSyncWake[] ref=new DeviceDataSyncWake[1];
        ref[0]=new DeviceDataSyncWake(clock,()->clock.now,()->{wakes++;if(wakes==1)ref[0].telemetry();});
        ref[0].activity();clock.advance(1_000);assertEquals(1,wakes);
        clock.advance(60_000);assertEquals(2,wakes);clock.advance(60_000);assertEquals(2,wakes);
    }
    @Test public void closeFencesLateCallbacksAndNewSignals() {
        gate.telemetry();gate.close();gate.close();gate.activity();gate.telemetry();
        clock.jobs.get(0).work.run();clock.advance(900_000);assertEquals(0,wakes);assertEquals(1,clock.jobs.size());
    }
    @Test public void unavailableSchedulerDoesNotPoisonTheNextSignal() {
        int[] attempts={0};
        DeviceDataSyncWake retry=new DeviceDataSyncWake((work,delay)->{
            if(attempts[0]++==0)throw new IllegalStateException();return clock.schedule(work,delay);
        },()->clock.now,()->wakes++);
        retry.telemetry();retry.telemetry();clock.advance(60_000);assertEquals(1,wakes);
    }
    private static final class Job {
        final Runnable work;final long at;boolean ran,cancelled;
        Job(Runnable work,long at){this.work=work;this.at=at;}
    }
    private static final class Clock implements DeviceDataSyncWake.Scheduler {
        long now;final List<Job> jobs=new ArrayList<>();
        public Runnable schedule(Runnable work,long delay){Job job=new Job(work,now+delay);jobs.add(job);return ()->job.cancelled=true;}
        void advance(long millis){
            long target=now+millis;
            while(true){Job next=jobs.stream().filter(job->!job.cancelled&&!job.ran&&job.at<=target)
                .min(Comparator.comparingLong(job->job.at)).orElse(null);if(next==null)break;
                now=next.at;next.ran=true;next.work.run();}
            now=target;
        }
    }
}
