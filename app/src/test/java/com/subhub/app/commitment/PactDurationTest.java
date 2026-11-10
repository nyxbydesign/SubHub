package com.subhub.app.commitment;

import org.junit.Test;
import java.security.SecureRandom;
import static org.junit.Assert.*;

public class PactDurationTest {
    @Test public void parsesFractionalHoursAndValidatesRange() {
        assertEquals(PactDuration.MIN, PactDuration.hours("0,5"));
        assertEquals(PactDuration.MAX, PactDuration.hours("720"));
    }
    @Test public void drawsStayWithinAgreedBoundsAndFixedDurationsStayFixed() {
        SecureRandom random = new SecureRandom();
        for (int i = 0; i < 500; i++) {
            long value = PactDuration.choose(PactDuration.MIN, PactDuration.MAX, random);
            assertTrue(value >= PactDuration.MIN && value <= PactDuration.MAX);
        }
        assertEquals(PactDuration.MIN, PactDuration.choose(PactDuration.MIN, PactDuration.MIN, random));
    }
    @Test(expected = IllegalArgumentException.class) public void refusesReversedRange() {
        PactDuration.validate(PactDuration.MAX, PactDuration.MIN);
    }
    @Test(expected = IllegalArgumentException.class) public void refusesExcessiveDuration() { PactDuration.hours("721"); }
}
