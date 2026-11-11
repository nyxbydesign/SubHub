package com.subhub.app.internal;

import java.util.Base64;
import java.util.Objects;

/** Stored presentation only. This is never a device identity, role, PIN or consent receipt. */
public final class InternalProfile {
    public final String name, about, avatar, gender;
    public final int age;

    /** Inputs must already have been normalized by the normal ProfileData editor. */
    public InternalProfile(String name, String about, String avatar, String gender, int age) {
        this.name = text(name, 80); this.about = text(about, 600);
        this.gender = text(gender, 40); this.avatar = Objects.requireNonNull(avatar);
        if (age < 0 || age > 130) throw new IllegalArgumentException("Invalid internal profile age");
        this.age = age;
        if (avatar.length() > (32768 + 2) / 3 * 4)
            throw new IllegalArgumentException("Oversized internal avatar");
        if (!avatar.isEmpty()) {
            byte[] bytes = Base64.getDecoder().decode(avatar);
            if (bytes.length < 4 || bytes.length > 32768 || bytes[0] != (byte) 0xff
                    || bytes[1] != (byte) 0xd8 || !Base64.getEncoder().encodeToString(bytes).equals(avatar))
                throw new IllegalArgumentException("Invalid internal avatar");
        }
    }

    private static String text(String value, int maximum) {
        if (value == null || value.length() > maximum || value.indexOf('\0') >= 0)
            throw new IllegalArgumentException("Invalid internal profile text");
        return value;
    }

    @Override public boolean equals(Object other) {
        if (!(other instanceof InternalProfile)) return false;
        InternalProfile value = (InternalProfile) other;
        return age == value.age && name.equals(value.name) && about.equals(value.about)
                && avatar.equals(value.avatar) && gender.equals(value.gender);
    }
    @Override public int hashCode() { return Objects.hash(name, about, avatar, gender, age); }
}
