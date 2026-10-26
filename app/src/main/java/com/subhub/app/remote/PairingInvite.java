package com.subhub.app.remote;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Bearer invitation for a pinned endpoint; the link itself never grants pairing authority. */
public final class PairingInvite {
    public static final long LIFETIME_MILLIS = 10 * 60_000L;
    public final String id, domIdentity, route;
    public final long expiresAt;
    private final byte[] token;

    private PairingInvite(String id, String domIdentity, String route, long expiresAt, byte[] token) {
        this.id = RemoteProtocol.identifier(id);
        this.domIdentity = RemoteProtocol.peerId(domIdentity);
        if (route == null || route.length() > 4096 || token.length != 32 || expiresAt <= 0) {
            throw new IllegalArgumentException("Invalid invitation");
        }
        this.route = route;
        this.expiresAt = expiresAt;
        this.token = token.clone();
    }

    public static PairingInvite create(String domIdentity, String route, long now) {
        if (now <= 0 || now > Long.MAX_VALUE - LIFETIME_MILLIS) throw new IllegalArgumentException("Invalid clock");
        byte[] token = new byte[32];
        new SecureRandom().nextBytes(token);
        return new PairingInvite(UUID.randomUUID().toString(), domIdentity, route,
                now + LIFETIME_MILLIS, token);
    }

    public String tokenHash() { return RemoteProtocol.sha256(token); }
    public String secret() { return Base64.getUrlEncoder().withoutPadding().encodeToString(token); }
    public String link() {
        return "subhubapp://pair?v=1&id=" + id + "&peer=" + domIdentity
                + "&route=" + encode(route) + "&expires=" + expiresAt + "#" + secret();
    }

    public static PairingInvite parse(String link, long now) {
        if (link == null || link.length() > 8192) throw new IllegalArgumentException("Invalid invitation");
        try {
            URI uri = new URI(link);
            if (!"subhubapp".equals(uri.getScheme()) || !"pair".equals(uri.getHost())
                    || uri.getPort() != -1 || uri.getRawUserInfo() != null
                    || (uri.getPath() != null && !uri.getPath().isEmpty()) || uri.getRawQuery() == null) {
                throw new IllegalArgumentException("Invalid invitation");
            }
            Map<String, String> fields = new HashMap<>();
            for (String part : uri.getRawQuery().split("&", -1)) {
                String[] field = part.split("=", 2);
                if (field.length != 2 || fields.put(decode(field[0]), decode(field[1])) != null) {
                    throw new IllegalArgumentException("Invalid invitation");
                }
            }
            if (fields.size() != 5 || !"1".equals(fields.get("v"))) {
                throw new IllegalArgumentException("Unsupported invitation");
            }
            long expires = Long.parseLong(fields.get("expires"));
            if (now <= 0 || expires <= now || expires - now > LIFETIME_MILLIS) {
                throw new IllegalArgumentException("Invitation expired or clock incorrect");
            }
            return new PairingInvite(fields.get("id"), fields.get("peer"), fields.get("route"),
                    expires, Base64.getUrlDecoder().decode(uri.getRawFragment()));
        } catch (URISyntaxException | NullPointerException invalid) {
            throw new IllegalArgumentException("Invalid invitation");
        }
    }

    public static String fingerprint(String dom, String sub, String inviteId) {
        String digest = RemoteProtocol.sha256((RemoteProtocol.peerId(dom) + ":"
                + RemoteProtocol.peerId(sub) + ":" + RemoteProtocol.identifier(inviteId))
                .getBytes(StandardCharsets.US_ASCII));
        return digest.substring(0, 8) + " " + digest.substring(8, 16)
                + " " + digest.substring(16, 24) + " " + digest.substring(24, 32);
    }

    private static String encode(String value) {
        try { return URLEncoder.encode(value, "UTF-8"); }
        catch (java.io.UnsupportedEncodingException impossible) { throw new AssertionError(impossible); }
    }
    private static String decode(String value) {
        try { return URLDecoder.decode(value, "UTF-8"); }
        catch (java.io.UnsupportedEncodingException impossible) { throw new AssertionError(impossible); }
    }
    @Override public String toString() { return "PairingInvite[redacted]"; }
}
