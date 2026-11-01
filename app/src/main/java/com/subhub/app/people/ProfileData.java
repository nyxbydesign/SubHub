package com.subhub.app.people;

import java.util.Base64;
import java.util.Objects;

/** Public profile fields never contain a device identity, source URI or configuration authority. */
public final class ProfileData {
    public static final int MAX_ABOUT_LENGTH = 600;
    public static final int MAX_AVATAR_BYTES = 32 * 1024;
    public static final int MAX_GENDER_LENGTH = 40;
    public static final int MAX_AGE = 130;
    public final String name, about, avatar, gender;
    /** Zero means not entered; no date of birth or age inference is retained. */
    public final int age;

    public ProfileData(String name, String about, String avatar) {
        this(name, about, avatar, "", 0);
    }

    public ProfileData(String name, String about, String avatar, String gender, int age) {
        this.name = ProfileNames.clean(name).trim();
        this.about = cleanAbout(about);
        this.gender = cleanGender(gender);
        if (age < 0 || age > MAX_AGE) throw new IllegalArgumentException("Invalid profile age");
        this.age = age;
        this.avatar = Objects.requireNonNull(avatar);
        if (avatar.length() > (MAX_AVATAR_BYTES + 2) / 3 * 4)
            throw new IllegalArgumentException("Profile image is too large");
        if (!avatar.isEmpty()) {
            byte[] bytes = Base64.getDecoder().decode(avatar);
            if (bytes.length < 4 || bytes.length > MAX_AVATAR_BYTES
                    || bytes[0] != (byte) 0xff || bytes[1] != (byte) 0xd8
                    || !Base64.getEncoder().encodeToString(bytes).equals(avatar))
                throw new IllegalArgumentException("Invalid profile image");
        }
    }

    public static ProfileData empty(String name) { return new ProfileData(name, "", ""); }

    public static String cleanGender(String value) {
        if (value == null) return "";
        String clean = value.replaceAll("[\\p{Cntrl}]", " ").trim();
        if (clean.length() <= MAX_GENDER_LENGTH) return clean;
        int end = MAX_GENDER_LENGTH;
        if (Character.isHighSurrogate(clean.charAt(end - 1))) end--;
        return clean.substring(0, end).trim();
    }

    public static int parseAge(String value) {
        if (value == null || value.trim().isEmpty()) return 0;
        String clean = value.trim();
        if (!clean.matches("[0-9]{1,3}")) throw new IllegalArgumentException("Invalid profile age");
        int age = Integer.parseInt(clean);
        if (age < 1 || age > MAX_AGE) throw new IllegalArgumentException("Invalid profile age");
        return age;
    }

    public static String cleanAbout(String value) {
        if (value == null) return "";
        String result = value.replace("\r\n", "\n").replace('\r', '\n')
                .replaceAll("[\\p{Cntrl}&&[^\\n]]", " ").trim();
        if (result.length() <= MAX_ABOUT_LENGTH) return result;
        int end = MAX_ABOUT_LENGTH;
        if (Character.isHighSurrogate(result.charAt(end - 1))) end--;
        return result.substring(0, end).trim();
    }

    @Override public boolean equals(Object other) {
        if (!(other instanceof ProfileData)) return false;
        ProfileData value = (ProfileData) other;
        return name.equals(value.name) && about.equals(value.about) && avatar.equals(value.avatar)
                && gender.equals(value.gender) && age == value.age;
    }
    @Override public int hashCode() { return Objects.hash(name, about, avatar, gender, age); }
}
