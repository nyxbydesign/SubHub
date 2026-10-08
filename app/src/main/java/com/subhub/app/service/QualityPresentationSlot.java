package com.subhub.app.service;

/** One replaceable presentation, not a durable detection cache. Identity is the publication fence. */
final class QualityPresentationSlot<T> {
    private T value;
    private T displayed;

    synchronized T get() { return value; }

    synchronized void markDisplayed(T source) { displayed = source; }

    /** Pending replacement is not proof that a different source reached the screen. */
    synchronized boolean clearDisplayed(T expected) {
        if (displayed != expected) return false;
        displayed = null;
        return true;
    }

    synchronized T getAndSet(T replacement) {
        T previous = value;
        value = replacement;
        return previous;
    }

    synchronized boolean compareAndSet(T expected, T replacement) {
        if (value != expected) return false;
        value = replacement;
        return true;
    }

    /** Current-only evidence survives fast reads; world-cache handoffs remain single-use. */
    synchronized boolean acquire(T expected, boolean retain) {
        if (value != expected) return false;
        if (!retain) value = null;
        return true;
    }
}
