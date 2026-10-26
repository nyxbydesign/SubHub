package com.subhub.app.people;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.List;

/** A favorite identifies the same peer across relationship generations and display-name changes. */
public final class FavoriteSubStore {
    public static final String PREFERENCES_NAME = "subhub_home_favorite";
    private static final String KEY_IDENTITY = "identity";
    private final SharedPreferences preferences;

    public FavoriteSubStore(Context context) {
        preferences = context.getApplicationContext().getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
    }

    public String identity() { return preferences.getString(KEY_IDENTITY, ""); }

    public static String identity(PersonId id) {
        String value = id.value();
        if (value.startsWith("remote_") && value.length() > 72 && value.charAt(71) == '_') {
            String peer = value.substring(7, 71);
            if (peer.matches("[0-9a-f]{64}")) return "peer:" + peer;
        }
        return value;
    }

    public static boolean eligible(PersonSnapshot person) {
        return person.connection != PersonSnapshot.Connection.REVOKED
                && person.connection != PersonSnapshot.Connection.UNPAIRED
                && person.connection != PersonSnapshot.Connection.PENDING;
    }

    public boolean isFavorite(PersonSnapshot person) {
        return eligible(person) && identity(person.id).equals(identity());
    }

    public boolean toggle(PersonSnapshot person) {
        if (!eligible(person)) return false;
        SharedPreferences.Editor edit = preferences.edit();
        if (isFavorite(person)) edit.remove(KEY_IDENTITY);
        else edit.putString(KEY_IDENTITY, identity(person.id));
        return edit.commit();
    }

    /** Never fall back to another person when the favorite is revoked, absent, or ambiguous. */
    public PersonSnapshot resolve(List<PersonSnapshot> people) {
        PersonSnapshot found = null;
        for (PersonSnapshot person : people) {
            if (!isFavorite(person)) continue;
            if (found != null) return null;
            found = person;
        }
        return found;
    }
}
