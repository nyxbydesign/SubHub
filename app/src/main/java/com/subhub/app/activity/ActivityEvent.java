package com.subhub.app.activity;

import java.util.Currency;
import java.util.Objects;

/** A source-observed fact; amounts describe assessed Tributes, never payment success. */
public final class ActivityEvent {
    public enum Kind { APP_OPENED, TRIBUTE_TRIGGERED, APP_LIMIT_REACHED, SHARED_LIMIT_REACHED }
    public final String id;
    public final Kind kind;
    public final long observedAt;
    public final String packageName;
    public final long amountCents;
    public final String currency;
    public final String reason;
    public final int limitMinutes;

    public ActivityEvent(String id, Kind kind, long observedAt, String packageName,
            long amountCents, String currency, String reason, int limitMinutes) {
        this.id = required(id, 128);
        this.kind = Objects.requireNonNull(kind);
        if (observedAt < 0 || amountCents < 0 || limitMinutes < 0)
            throw new IllegalArgumentException("Invalid activity values");
        this.packageName = optional(packageName, 255);
        if (!this.packageName.isEmpty() && !this.packageName.matches("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)*"))
            throw new IllegalArgumentException("Invalid application identity");
        this.currency = optional(currency, 3);
        this.reason = optional(reason, 80);
        boolean app = kind == Kind.APP_OPENED || kind == Kind.APP_LIMIT_REACHED;
        boolean limit = kind == Kind.APP_LIMIT_REACHED || kind == Kind.SHARED_LIMIT_REACHED;
        if (app && this.packageName.isEmpty() || limit && limitMinutes == 0)
            throw new IllegalArgumentException("Incomplete activity fact");
        if (kind == Kind.TRIBUTE_TRIGGERED) {
            if (amountCents <= 0) throw new IllegalArgumentException("Missing assessed amount");
            Currency.getInstance(this.currency);
        } else if (amountCents != 0 || !this.currency.isEmpty() || !this.reason.isEmpty()) {
            throw new IllegalArgumentException("Unexpected Tribute fields");
        }
        if (!limit && limitMinutes != 0) throw new IllegalArgumentException("Unexpected limit");
        this.observedAt = observedAt;
        this.amountCents = amountCents;
        this.limitMinutes = limitMinutes;
    }

    static String required(String value, int maximum) {
        String result = optional(value, maximum);
        if (result.isEmpty()) throw new IllegalArgumentException("Missing activity identity");
        return result;
    }

    static String optional(String value, int maximum) {
        if (value == null) return "";
        if (value.length() > maximum) throw new IllegalArgumentException("Oversized activity field");
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isISOControl(character) || character >= 0x202a && character <= 0x202e
                    || character >= 0x2066 && character <= 0x2069)
                throw new IllegalArgumentException("Invalid activity text");
        }
        return value;
    }
}
