package com.subhub.app.update;

import java.util.ArrayList;
import java.util.List;

/** Small SemVer 2.0 comparator used for published GitHub release tags. */
public final class SemanticVersion implements Comparable<SemanticVersion> {
    private final long major;
    private final long minor;
    private final long patch;
    private final List<String> prerelease;

    private SemanticVersion(long major, long minor, long patch, List<String> prerelease) {
        this.major = major;
        this.minor = minor;
        this.patch = patch;
        this.prerelease = prerelease;
    }

    public static SemanticVersion parse(String raw) {
        if (raw == null) throw new IllegalArgumentException("Missing version");
        String value = raw.trim();
        if (value.startsWith("v")) value = value.substring(1);
        int build = value.indexOf('+');
        if (build >= 0) value = value.substring(0, build);
        String[] halves = value.split("-", 2);
        String[] core = halves[0].split("\\.", -1);
        if (core.length != 3) throw new IllegalArgumentException("Invalid semantic version");
        List<String> pre = new ArrayList<>();
        if (halves.length == 2) {
            if (halves[1].isEmpty()) throw new IllegalArgumentException("Empty prerelease");
            for (String part : halves[1].split("\\.", -1)) {
                if (part.isEmpty() || !part.matches("[0-9A-Za-z-]+")) {
                    throw new IllegalArgumentException("Invalid prerelease");
                }
                pre.add(part);
            }
        }
        return new SemanticVersion(number(core[0]), number(core[1]), number(core[2]), pre);
    }

    private static long number(String value) {
        if (!value.matches("0|[1-9][0-9]*")) throw new IllegalArgumentException("Invalid version number");
        return Long.parseLong(value);
    }

    public boolean isPrerelease() { return !prerelease.isEmpty(); }

    @Override public int compareTo(SemanticVersion other) {
        int compared = Long.compare(major, other.major);
        if (compared == 0) compared = Long.compare(minor, other.minor);
        if (compared == 0) compared = Long.compare(patch, other.patch);
        if (compared != 0) return compared;
        if (prerelease.isEmpty()) return other.prerelease.isEmpty() ? 0 : 1;
        if (other.prerelease.isEmpty()) return -1;
        int length = Math.min(prerelease.size(), other.prerelease.size());
        for (int index = 0; index < length; index++) {
            String left = prerelease.get(index);
            String right = other.prerelease.get(index);
            boolean leftNumber = left.matches("[0-9]+");
            boolean rightNumber = right.matches("[0-9]+");
            if (leftNumber && rightNumber) compared = Long.compare(Long.parseLong(left), Long.parseLong(right));
            else if (leftNumber != rightNumber) compared = leftNumber ? -1 : 1;
            else compared = left.compareTo(right);
            if (compared != 0) return compared;
        }
        return Integer.compare(prerelease.size(), other.prerelease.size());
    }
}
