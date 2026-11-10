package com.subhub.app.service;

/** Privacy-safe window identity used only before Accessibility proves a real scroll owner. */
final class ProvisionalScrollSurface {
    static final Identity NONE = new Identity("", 0L, -1);

    private ProvisionalScrollSurface() {}

    static Identity forWindow(String packageName, int windowId) {
        String safePackage = packageName == null ? "" : packageName.trim();
        if (safePackage.isEmpty() || windowId < 0) return NONE;
        long token = 0xcbf29ce484222325L;
        for (int index = 0; index < safePackage.length(); index++) {
            token = (token ^ safePackage.charAt(index)) * 0x100000001b3L;
        }
        token = (token ^ windowId) * 0x100000001b3L;
        if (token == 0L) token = 0x9e3779b97f4a7c15L;
        return new Identity("w:" + Long.toUnsignedString(token, 16), token, windowId);
    }

    static final class Identity {
        private final String key;
        private final long telemetryToken;
        private final int windowId;

        private Identity(String key, long telemetryToken, int windowId) {
            this.key = key;
            this.telemetryToken = telemetryToken;
            this.windowId = windowId;
        }

        String key() { return key; }
        long telemetryToken() { return telemetryToken; }
        int windowId() { return windowId; }
        boolean valid() { return !key.isEmpty() && telemetryToken != 0L && windowId >= 0; }
    }
}
