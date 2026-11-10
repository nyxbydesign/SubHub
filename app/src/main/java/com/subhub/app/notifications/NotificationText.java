package com.subhub.app.notifications;

/** Prevent user-authored control characters from rearranging system notification copy. */
public final class NotificationText {
    private NotificationText() { }
    public static String clean(String value) {
        String clean = value.replaceAll("[\\p{Cntrl}\\u202A-\\u202E\\u2066-\\u2069]", " ").trim();
        return clean.length() <= 160 ? clean : clean.substring(0, 159) + "…";
    }
}
