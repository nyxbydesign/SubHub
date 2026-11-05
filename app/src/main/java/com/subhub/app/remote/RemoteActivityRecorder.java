package com.subhub.app.remote;

import java.util.Set;

/** Producers provide facts; the installed sink binds delivery to the relationship observed then. */
public final class RemoteActivityRecorder {
    public static final class PaymentAudience {
        public static final PaymentAudience NONE = new PaymentAudience("", "");
        public final String peer, generation;
        public PaymentAudience(String peer, String generation) {
            if (peer.isEmpty() != generation.isEmpty()) throw new IllegalArgumentException("Incomplete payment audience");
            if (!peer.isEmpty()) { RemoteProtocol.peerId(peer); RemoteProtocol.identifier(generation); }
            this.peer = peer; this.generation = generation;
        }
    }
    interface Sink {
        default PaymentAudience paymentAudience(android.content.Context context) { return PaymentAudience.NONE; }
        default void payments() { }
        void foreground(String previousPackage, String packageName, boolean selected, long observedAt);
        void tribute(String ledgerId);
        void limit(String packageName, boolean perApp, int minutes, Set<String> scope, long observedAt);
    }
    private static volatile Sink installed;
    private RemoteActivityRecorder() {}
    static void install(Sink sink) { installed = sink; }
    static void remove(Sink sink) { if (installed == sink) installed = null; }

    public static PaymentAudience paymentAudience(android.content.Context context) {
        Sink sink = installed;
        try { return sink == null ? PaymentAudience.NONE : sink.paymentAudience(context); }
        catch (RuntimeException unavailable) { return PaymentAudience.NONE; }
    }
    public static void paymentCommitted() {
        Sink sink = installed;
        if (sink != null) try { sink.payments(); }
        catch (RuntimeException unavailable) { /* An optional projection cannot change payment completion. */ }
    }

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
