package com.subhub.app.remote;

import java.util.Set;

/** Producers provide facts; the installed sink binds delivery to the relationship observed then. */
public final class RemoteActivityRecorder {
    interface Sink {
        void foreground(String previousPackage, String packageName, boolean selected, long observedAt);
        void tribute(String ledgerId);
        void limit(String packageName, boolean perApp, int minutes, Set<String> scope, long observedAt);
    }
    private static volatile Sink installed;
    private RemoteActivityRecorder() {}
    static void install(Sink sink) { installed = sink; }
    static void remove(Sink sink) { if (installed == sink) installed = null; }

    public static void foreground(String previousPackage, String packageName, boolean selected, long observedAt) {
        Sink sink = installed;
        if (sink != null) sink.foreground(previousPackage, packageName, selected, observedAt);
    }
    public static void tribute(String ledgerId) {
        Sink sink = installed;
        if (sink != null) sink.tribute(ledgerId);
    }
    public static void limit(String packageName, boolean perApp, int minutes, Set<String> scope, long observedAt) {
        Sink sink = installed;
        if (sink != null) sink.limit(packageName, perApp, minutes, scope, observedAt);
    }
}
