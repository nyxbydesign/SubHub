package com.subhub.app.update;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;

import com.subhub.app.BuildConfig;
import com.subhub.app.util.LowercaseHex;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Set;

/** Rejects any downloaded APK that cannot be a signed SubHub upgrade. */
public final class UpdateVerifier {
    public enum Failure { HASH, SIZE, PACKAGE, VERSION, SDK, ABI, SIGNATURE, APK }

    public static final class Result {
        public final Failure failure;
        private Result(Failure failure) { this.failure = failure; }
        public static Result success() { return new Result(null); }
        public static Result failure(Failure value) { return new Result(value); }
        public boolean succeeded() { return failure == null; }
    }

    private UpdateVerifier() {}

    public static Result verify(Context context, File apk, UpdateManifest manifest,
            UpdateManifest.Asset asset) {
        try {
            if (!apk.isFile() || apk.length() != asset.size) return Result.failure(Failure.SIZE);
            if (!sha256(apk).equals(asset.sha256)) return Result.failure(Failure.HASH);
            if (!asset.abi.equals("universal") && !supports(asset.abi, Build.SUPPORTED_ABIS)) {
                return Result.failure(Failure.ABI);
            }
            PackageManager manager = context.getPackageManager();
            int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                    ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
            PackageInfo archive = manager.getPackageArchiveInfo(apk.getAbsolutePath(), flags);
            if (archive == null) return Result.failure(Failure.APK);
            if (!UpdateManifest.PACKAGE.equals(archive.packageName)
                    || !manifest.packageName.equals(archive.packageName)) return Result.failure(Failure.PACKAGE);
            if (longVersion(archive) != manifest.versionCode
                    || manifest.versionCode <= BuildConfig.VERSION_CODE) return Result.failure(Failure.VERSION);
            ApplicationInfo application = archive.applicationInfo;
            if (application != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
                    && application.minSdkVersion > Build.VERSION.SDK_INT) return Result.failure(Failure.SDK);
            PackageInfo installed = manager.getPackageInfo(context.getPackageName(), flags);
            Set<String> installedSigners = signerDigests(installed);
            Set<String> archiveSigners = signerDigests(archive);
            if (installedSigners.isEmpty() || !installedSigners.equals(archiveSigners)) {
                return Result.failure(Failure.SIGNATURE);
            }
            return Result.success();
        } catch (Exception exception) {
            return Result.failure(Failure.APK);
        }
    }

    private static boolean supports(String abi, String[] supported) {
        for (String value : supported) if (abi.equals(value)) return true;
        return false;
    }

    @SuppressWarnings("deprecation")
    private static long longVersion(PackageInfo info) {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ? info.getLongVersionCode() : info.versionCode;
    }

    @SuppressWarnings("deprecation")
    private static Set<String> signerDigests(PackageInfo info) throws Exception {
        Signature[] signatures;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            if (info.signingInfo == null) return java.util.Collections.emptySet();
            signatures = info.signingInfo.getApkContentsSigners();
        } else signatures = info.signatures;
        Set<String> values = new HashSet<>();
        if (signatures == null) return values;
        for (Signature signature : signatures) {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(signature.toByteArray());
            values.add(LowercaseHex.encode(digest));
        }
        return values;
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (BufferedInputStream input = new BufferedInputStream(new FileInputStream(file))) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) >= 0) digest.update(buffer, 0, count);
        }
        return LowercaseHex.encode(digest.digest());
    }
}
