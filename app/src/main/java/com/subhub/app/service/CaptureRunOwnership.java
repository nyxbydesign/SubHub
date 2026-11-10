package com.subhub.app.service;

import java.util.Objects;
import java.util.UUID;

/** Pure capture-instance ownership; its monitor never obtains runtime locks or Android services. */
final class CaptureRunOwnership {
    static final class Components {
        final String statistics, pact, pause;
        Components(String statistics, String pact, String pause) {
            this.statistics = component(statistics); this.pact = component(pact); this.pause = component(pause);
        }
        /** Main-side End may already have removed the original components before capture destruction. */
        boolean permitsCleanupOf(Components current) {
            return retainedOrCleared(statistics, current.statistics) && retainedOrCleared(pact, current.pact)
                    && retainedOrCleared(pause, current.pause);
        }
        private static boolean retainedOrCleared(String original, String current) {
            return current.isEmpty() || original.equals(current);
        }
        @Override public boolean equals(Object other) {
            if (!(other instanceof Components)) return false;
            Components value = (Components) other;
            return statistics.equals(value.statistics) && pact.equals(value.pact) && pause.equals(value.pause);
        }
        @Override public int hashCode() { return Objects.hash(statistics, pact, pause); }
    }
    static final class Token {
        final String instance, activation;
        final int startId;
        final Components components;
        private volatile boolean closed;
        Token(String instance, String activation, int startId, Components components) {
            uuid(instance); uuid(activation);
            if (startId <= 0) throw new IllegalArgumentException("Invalid capture start ID");
            this.instance = instance; this.activation = activation; this.startId = startId;
            this.components = Objects.requireNonNull(components);
        }
        @Override public boolean equals(Object other) {
            if (!(other instanceof Token)) return false;
            Token value = (Token) other;
            return instance.equals(value.instance) && activation.equals(value.activation) && startId == value.startId
                    && components.equals(value.components);
        }
        @Override public int hashCode() { return Objects.hash(instance, activation, startId, components); }
        boolean isClosed() { return closed; }
        void acknowledgeClosure() { closed = true; }
    }
    private Token current;
    private boolean stopping;

    synchronized Token activate(String instance, int startId, Components components) {
        uuid(instance);
        if (current != null && current.instance.equals(instance) && current.startId == startId) {
            if (!current.components.equals(components)) throw new IllegalStateException("Capture activation identity reused");
            return current;
        }
        if (components.statistics.isEmpty()) throw new IllegalArgumentException("Capture requires owned statistics");
        current = new Token(instance, UUID.randomUUID().toString(), startId, components); stopping = false;
        return current;
    }
    synchronized Token current() { return current; }
    synchronized boolean isRunning() { return current != null; }
    synchronized boolean owns(Token token) { return token != null && token.equals(current); }
    synchronized boolean mayAffectRuntime(Token token, Components components) {
        return owns(token) && token.components.permitsCleanupOf(components);
    }
    /** Retire the exact resource owner even when a replacement session owns current app state. */
    synchronized boolean beginStop(Token token) {
        if (!owns(token)) return false;
        stopping = true; return true;
    }
    synchronized boolean isStopping(Token token) { return owns(token) && stopping; }
    /** Retiring the actual capture must not clear a later instance, even when app components changed. */
    synchronized boolean release(Token token) {
        if (!owns(token)) return false;
        current = null; stopping = false; return true;
    }
    private static String component(String value) {
        if (value == null) throw new IllegalArgumentException("Missing capture component");
        if (!value.isEmpty()) uuid(value);
        return value;
    }
    private static void uuid(String value) {
        if (value == null || !value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
            throw new IllegalArgumentException("Invalid capture identity");
    }
}
