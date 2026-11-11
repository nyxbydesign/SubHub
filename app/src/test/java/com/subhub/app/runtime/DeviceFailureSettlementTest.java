package com.subhub.app.runtime;

import static org.junit.Assert.*;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public final class DeviceFailureSettlementTest {
    @Test public void throwingCancellationNeverSkipsIndependentFenceOrSettlement() {
        for (int failing = 0; failing < 4; failing++) {
            final int selected = failing;
            List<Integer> calls = new ArrayList<>();
            Throwable original = new IllegalStateException("primary");
            Runnable[] steps = new Runnable[4];
            for (int i = 0; i < steps.length; i++) {
                final int index = i;
                steps[i] = () -> {
                    calls.add(index);
                    if (index == selected) throw new IllegalStateException("uncertain return");
                };
            }
            AndroidDeviceBackend.completeFailureSteps(original, steps);
            assertEquals(List.of(0, 1, 2, 3), calls);
            assertEquals(1, original.getSuppressed().length);
        }
    }
    @Test public void errorAndRepeatedOriginalDoNotRetryOrPreventLaterSettlement() {
        Error original = new AssertionError("primary");
        int[] calls = {0};
        AndroidDeviceBackend.completeFailureSteps(original,
                () -> { throw original; },
                () -> { calls[0]++; throw new AssertionError("uncertain cancellation"); },
                () -> calls[0]++);
        assertEquals(2, calls[0]);
        assertEquals(1, original.getSuppressed().length);
    }
}
