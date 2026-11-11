package com.subhub.app.service;

import java.lang.reflect.Field;
import static org.junit.Assert.*;

/** Test-only detached pipeline ownership. Never connects a Service or starts native capture. */
public final class AccessibilityPipelineFixture {
    private AccessibilityPipelineFixture() { }
    public static Class<?> type() { return AccessibilityLegacyPipeline.class; }
    public static Object detached(ScreenshotAccessibilityService host) {
        return new AccessibilityLegacyPipeline(host);
    }
    public static Object pipeline(ScreenshotAccessibilityService host) throws Exception {
        Field f = ScreenshotAccessibilityService.class.getDeclaredField("pipeline"); f.setAccessible(true);
        Object value = f.get(host); assertNotNull(value); return value;
    }
    public static Object installDetachedEvents(ScreenshotAccessibilityService host) throws Exception {
        Field f = ScreenshotAccessibilityService.class.getDeclaredField("pipeline"); f.setAccessible(true);
        assertNull("Never replace a fixture's existing owner", f.get(host));
        Object value = detached(host);
        set(value, "deviceInitialized", true); set(value, "recognitionActive", true);
        f.set(host, value); return value;
    }
    public static void set(Object instance, String name, Object value) throws Exception {
        Field f = type().getDeclaredField(name); f.setAccessible(true); f.set(instance, value);
    }
    /** Existing bound DEVICE pipeline, for read/restore fixtures; not the Android Service component. */
    public static Object current() throws Exception {
        Field f = type().getDeclaredField("censorInstance"); f.setAccessible(true); return f.get(null);
    }
    public static final class StatusBinding implements AutoCloseable {
        private final Field selected;
        private final Object previous;
        private Object owned;
        public StatusBinding() throws Exception {
            selected = type().getDeclaredField("censorInstance"); selected.setAccessible(true);
            previous = selected.get(null);
        }
        public void install() throws Exception {
            assertNull("A pre-existing Accessibility binding is not owned by this fixture", previous);
            assertSame("Binding changed during fixture preparation", previous, selected.get(null));
            assertNull(owned);
            Object next = detached(new ScreenshotAccessibilityService());
            set(next, "running", true);
            owned = next; selected.set(null, next);
        }
        public void setRunning(boolean value) throws Exception {
            assertNotNull(owned); assertSame(owned, selected.get(null)); set(owned, "running", value);
        }
        @Override public void close() throws Exception {
            if (owned == null) return;
            assertSame("Never clear another binding", owned, selected.get(null));
            set(owned, "running", false); selected.set(null, previous); owned = null;
        }
    }
}
