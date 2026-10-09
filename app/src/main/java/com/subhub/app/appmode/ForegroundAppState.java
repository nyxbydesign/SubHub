package com.subhub.app.appmode;

/** Accepted Accessibility foreground evidence; identity also invalidates in-flight frames. */
public final class ForegroundAppState {
    public static final class Snapshot {
        public final String packageName;

        private Snapshot(String packageName) {
            this.packageName = packageName;
        }
    }

    private static volatile Snapshot current = new Snapshot("");

    private ForegroundAppState() {}

    public static Snapshot snapshot() {
        return current;
    }

    public static boolean isCurrent(Snapshot snapshot) {
        return snapshot == current;
    }

    public static synchronized void update(String packageName) {
        String clean = packageName == null ? "" : packageName.trim();
        if (!clean.equals(current.packageName)) current = new Snapshot(clean);
    }

    public static void clear() {
        update("");
    }
}
