package com.subhub.app.internal;

import com.subhub.app.people.ProfileData;
import com.subhub.app.people.ProfileImages;

/** Reuses the ordinary profile editor's normalization and native image validation. */
public final class InternalProfileAdapter {
    private InternalProfileAdapter() { }
    public static InternalProfile fromProfileData(ProfileData value) {
        ProfileImages.requireValid(value.avatar);
        // isSwitch belongs to the actual owner's role presentation, not this independent Sub.
        return new InternalProfile(value.name, value.about, value.avatar, value.gender, value.age);
    }
    public static ProfileData toProfileData(InternalProfile value) {
        ProfileData profile = new ProfileData(value.name, value.about, value.avatar, value.gender, value.age);
        ProfileImages.requireValid(profile.avatar);
        if (!value.equals(fromProfileData(profile))) throw new IllegalArgumentException("Noncanonical internal profile");
        return profile;
    }

    public static InternalSubRecord save(InternalSubStore store, InternalSubRecord expected, ProfileData edited) {
        return store.saveProfile(expected, fromProfileData(edited));
    }
}
