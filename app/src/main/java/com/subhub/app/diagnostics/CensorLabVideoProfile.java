package com.subhub.app.diagnostics;

import android.media.MediaCodecInfo;
import android.media.MediaCodecList;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Bounded H.264 profiles ordered from visual-analysis fidelity to broad compatibility. */
final class CensorLabVideoProfile {
    private static final String AVC = "video/avc";

    final int width;
    final int height;
    final int frameRate;
    final int bitRate;

    CensorLabVideoProfile(int width, int height, int frameRate, int bitRate) {
        this.width = width;
        this.height = height;
        this.frameRate = frameRate;
        this.bitRate = bitRate;
    }

    static List<CensorLabVideoProfile> candidates(
            int displayWidth, int displayHeight, float refreshRateHz) {
        if (displayWidth <= 0 || displayHeight <= 0) return Collections.emptyList();
        List<CensorLabVideoProfile> profiles = new ArrayList<>();
        if (refreshRateHz >= 50f) {
            addUnique(profiles, scaled(displayWidth, displayHeight, 2_400, 60, 14_000_000));
            addUnique(profiles, scaled(displayWidth, displayHeight, 1_920, 60, 10_000_000));
        }
        addUnique(profiles, scaled(displayWidth, displayHeight, 1_600, 30, 7_000_000));
        addUnique(profiles, scaled(displayWidth, displayHeight, 1_200, 30, 5_000_000));
        return profiles;
    }

    static boolean hasEncoderSupport(CensorLabVideoProfile profile) {
        MediaCodecInfo[] codecs = new MediaCodecList(MediaCodecList.ALL_CODECS).getCodecInfos();
        boolean softwareFallback = false;
        for (MediaCodecInfo codec : codecs) {
            if (!codec.isEncoder() || !supportsType(codec, AVC)) continue;
            try {
                MediaCodecInfo.VideoCapabilities video = codec.getCapabilitiesForType(AVC)
                        .getVideoCapabilities();
                if (!video.areSizeAndRateSupported(
                        profile.width, profile.height, profile.frameRate)) continue;
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q
                        && codec.isHardwareAccelerated()) return true;
                softwareFallback = true;
            } catch (RuntimeException ignored) {
                // A malformed vendor capability must not prevent trying another encoder/profile.
            }
        }
        return softwareFallback;
    }

    private static CensorLabVideoProfile scaled(int sourceWidth, int sourceHeight,
            int maximumLongEdge, int frameRate, int bitRate) {
        int longEdge = Math.max(sourceWidth, sourceHeight);
        float scale = Math.min(1f, maximumLongEdge / (float) longEdge);
        int width = alignedEven(Math.round(sourceWidth * scale));
        int height = alignedEven(Math.round(sourceHeight * scale));
        return new CensorLabVideoProfile(width, height, frameRate, bitRate);
    }

    private static int alignedEven(int value) {
        return Math.max(320, value - Math.floorMod(value, 16));
    }

    private static boolean supportsType(MediaCodecInfo codec, String wanted) {
        for (String type : codec.getSupportedTypes()) {
            if (wanted.equalsIgnoreCase(type)) return true;
        }
        return false;
    }

    private static void addUnique(List<CensorLabVideoProfile> profiles,
            CensorLabVideoProfile candidate) {
        for (CensorLabVideoProfile existing : profiles) {
            if (existing.width == candidate.width && existing.height == candidate.height
                    && existing.frameRate == candidate.frameRate) return;
        }
        profiles.add(candidate);
    }
}
