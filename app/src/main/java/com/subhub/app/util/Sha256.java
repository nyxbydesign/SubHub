package com.subhub.app.util;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class Sha256 {
    private Sha256() {}

    public static String file(File file, int bufferBytes) throws IOException {
        if (bufferBytes <= 0) throw new IllegalArgumentException("Buffer size must be positive");
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
        byte[] buffer = new byte[bufferBytes];
        try (FileInputStream input = new FileInputStream(file)) {
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        return LowercaseHex.encode(digest.digest());
    }
}
