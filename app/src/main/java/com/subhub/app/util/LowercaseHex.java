package com.subhub.app.util;

/** Encodes digest bytes as two lowercase hexadecimal digits per byte. */
public final class LowercaseHex {
    private static final String DIGITS = "0123456789abcdef";

    private LowercaseHex() {}

    public static String encode(byte[] bytes) {
        char[] result = new char[bytes.length * 2];
        int offset = 0;
        for (byte value : bytes) {
            result[offset++] = DIGITS.charAt((value >>> 4) & 15);
            result[offset++] = DIGITS.charAt(value & 15);
        }
        return new String(result);
    }
}
