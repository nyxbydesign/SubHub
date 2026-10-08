package com.subhub.app.security;

import org.junit.Test;
import static org.junit.Assert.*;

public class TotpTest {
    private static final String RFC_SECRET = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";
    @Test public void matchesRfc6238Sha1VectorsAtSixDigits() throws Exception {
        long[] seconds = {59, 1111111109L, 1111111111L, 1234567890L, 2000000000L, 20000000000L};
        String[] expected = {"287082", "081804", "050471", "005924", "279037", "353130"};
        for (int i = 0; i < seconds.length; i++) assertEquals(expected[i], Totp.code(RFC_SECRET, seconds[i] / 30));
    }
    @Test public void acceptsAdjacentStepsButRejectsReplayAndLargerSkew() throws Exception {
        long time = 1_234_567_890_000L, step = time / Totp.STEP_MILLIS;
        for (long candidate = step - 1; candidate <= step + 1; candidate++) {
            String code = Totp.code(RFC_SECRET, candidate);
            assertEquals(candidate, Totp.verify(RFC_SECRET, code, time, -1));
            assertEquals(-1, Totp.verify(RFC_SECRET, code, time, candidate));
        }
        assertEquals(-1, Totp.verify(RFC_SECRET, Totp.code(RFC_SECRET, step + 2), time, -1));
        assertEquals(-1, Totp.verify(RFC_SECRET, "abcdef", time, -1));
        assertEquals(-1, Totp.verify(RFC_SECRET, "１２３４５６", time, -1));
    }
    @Test public void generatedKeysAreIndependentAndCompatible() throws Exception {
        String a = Totp.newSecret(), b = Totp.newSecret(); assertNotEquals(a, b);
        assertTrue(a.matches("[A-Z2-7]{32}"));
        assertEquals(100, Totp.verify(a, Totp.code(a, 100), 3_000_000L, -1));
    }
}
