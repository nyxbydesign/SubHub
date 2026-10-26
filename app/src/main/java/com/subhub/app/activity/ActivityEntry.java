package com.subhub.app.activity;

import com.subhub.app.people.PersonId;
import java.util.Objects;

/** A received fact with relationship-scoped identity and device-local read state. */
public final class ActivityEntry {
    public final String key;
    public final PersonId personId;
    public final String personName;
    public final String device;
    public final ActivityEvent event;
    public final long receivedAt;
    public final boolean unread;
    public final String appLabel;

    public ActivityEntry(String key, PersonId personId, String personName, String device,
            ActivityEvent event, long receivedAt, boolean unread, String appLabel) {
        this.key = ActivityEvent.required(key, 512);
        this.personId = Objects.requireNonNull(personId);
        this.personName = ActivityEvent.required(personName, 160);
        this.device = ActivityEvent.optional(device, 160);
        this.event = Objects.requireNonNull(event);
        if (receivedAt < 0) throw new IllegalArgumentException("Invalid receipt time");
        this.receivedAt = receivedAt;
        this.unread = unread;
        String label = ActivityEvent.optional(appLabel, 160);
        this.appLabel = label.isEmpty() ? event.packageName : label;
    }

    public ActivityEntry read() {
        return unread ? new ActivityEntry(key, personId, personName, device, event,
                receivedAt, false, appLabel) : this;
    }
}
