package com.subhub.app.commitment;

import java.math.BigDecimal;
import java.security.SecureRandom;

/** Explicit bounds shared by the start dialog and persistent commitment manager. */
public final class PactDuration {
    public static final long MIN = 30L * 60_000;
    public static final long MAX = 30L * 24 * 60 * 60_000;
    private PactDuration() { }
    public static long hours(String input) {
        try {
            long value = new BigDecimal(input.trim().replace(',', '.')).multiply(BigDecimal.valueOf(3_600_000)).longValueExact();
            validate(value, value); return value;
        } catch (ArithmeticException | NumberFormatException error) { throw new IllegalArgumentException("Use 0.5 to 720 hours"); }
    }
    public static void validate(long minimum, long maximum) {
        if (minimum < MIN || maximum > MAX || maximum < minimum) throw new IllegalArgumentException("Use an ordered range between 30 minutes and 30 days");
    }
    public static long choose(long minimum, long maximum, SecureRandom random) {
        validate(minimum, maximum);
        long bound = maximum - minimum + 1;
        long bits, value;
        do { bits = random.nextLong() >>> 1; value = bits % bound; }
        while (bits - value + bound - 1 < 0);
        return minimum + value;
    }
}
