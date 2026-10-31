package com.subhub.app.people;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Immutable, presentation-ready data. Connection and data freshness are separate facts. */
public final class PersonSnapshot {
    public enum Connection { LOCAL, UNPAIRED, PENDING, PAIRED, CONNECTED, OFFLINE, REVOKED, ERROR }
    public enum Freshness { CURRENT, STALE, UNKNOWN }
    public enum Capability { VIEW_CONTRACT, EDIT_CONTRACT, VIEW_STATS, MANAGE_CONNECTION, END_SESSION }

    public final PersonId id;
    public final String name, device, contractName, serviceLabel, currency;
    public final Connection connection;
    public final Freshness freshness;
    public final long lastSyncedAt;
    public final Set<Capability> capabilities;
    public final List<Section> sections;
    public final List<Allowance> allowances;
    public final long protectedSeconds, activityEvents, tributeCents;
    public final int sessions;
    public final boolean serviceActive;
    public final SyncState sync;
    public final String sessionIdentity;
    /** Durable source insertion sequence; zero means unknown. */
    public final long additionOrder;
    public final ProfileData profile;
    public final ProfileData contractDom;
    public final PersonId contractDomId;
    public final java.util.Map<String, Boolean> featureConsent;
    public final boolean consentHardcoreOverride;

    public PersonSnapshot(PersonId id, String name, String device, String contractName,
            String serviceLabel, Connection connection, Freshness freshness, long lastSyncedAt,
            Set<Capability> capabilities, List<Section> sections, List<Allowance> allowances,
            String currency, long protectedSeconds, long activityEvents, long tributeCents, int sessions,
            boolean serviceActive) {
        this(id, name, device, contractName, serviceLabel, connection, freshness, lastSyncedAt,
                capabilities, sections, allowances, currency, protectedSeconds, activityEvents,
                tributeCents, sessions, serviceActive,
                new SyncState(freshness, freshness, freshness, "", ""));
    }

    public PersonSnapshot(PersonId id, String name, String device, String contractName,
            String serviceLabel, Connection connection, Freshness freshness, long lastSyncedAt,
            Set<Capability> capabilities, List<Section> sections, List<Allowance> allowances,
            String currency, long protectedSeconds, long activityEvents, long tributeCents, int sessions,
            boolean serviceActive, SyncState sync) {
        this(id,name,device,contractName,serviceLabel,connection,freshness,lastSyncedAt,capabilities,sections,
                allowances,currency,protectedSeconds,activityEvents,tributeCents,sessions,serviceActive,sync,"");
    }

    public PersonSnapshot(PersonId id, String name, String device, String contractName,
            String serviceLabel, Connection connection, Freshness freshness, long lastSyncedAt,
            Set<Capability> capabilities, List<Section> sections, List<Allowance> allowances,
            String currency, long protectedSeconds, long activityEvents, long tributeCents, int sessions,
            boolean serviceActive, SyncState sync, String sessionIdentity) {
        this(id, name, device, contractName, serviceLabel, connection, freshness, lastSyncedAt,
                capabilities, sections, allowances, currency, protectedSeconds, activityEvents,
                tributeCents, sessions, serviceActive, sync, sessionIdentity, 0);
    }

    public PersonSnapshot(PersonId id, String name, String device, String contractName,
            String serviceLabel, Connection connection, Freshness freshness, long lastSyncedAt,
            Set<Capability> capabilities, List<Section> sections, List<Allowance> allowances,
            String currency, long protectedSeconds, long activityEvents, long tributeCents, int sessions,
            boolean serviceActive, SyncState sync, String sessionIdentity, long additionOrder) {
        this(id, name, device, contractName, serviceLabel, connection, freshness, lastSyncedAt,
                capabilities, sections, allowances, currency, protectedSeconds, activityEvents, tributeCents,
                sessions, serviceActive, sync, sessionIdentity, additionOrder, ProfileData.empty(name),
                Collections.emptyMap(), false, null, null);
    }

    private PersonSnapshot(PersonId id, String name, String device, String contractName,
            String serviceLabel, Connection connection, Freshness freshness, long lastSyncedAt,
            Set<Capability> capabilities, List<Section> sections, List<Allowance> allowances,
            String currency, long protectedSeconds, long activityEvents, long tributeCents, int sessions,
            boolean serviceActive, SyncState sync, String sessionIdentity, long additionOrder,
            ProfileData profile, java.util.Map<String, Boolean> featureConsent, boolean consentHardcoreOverride,
            ProfileData contractDom, PersonId contractDomId) {
        if (additionOrder < 0) throw new IllegalArgumentException("Invalid addition order");
        this.additionOrder=additionOrder;
        this.sessionIdentity=java.util.Objects.requireNonNull(sessionIdentity);
        this.id = java.util.Objects.requireNonNull(id);
        this.name = java.util.Objects.requireNonNull(name);
        this.device = device;
        this.contractName = contractName;
        this.serviceLabel = serviceLabel;
        this.connection = java.util.Objects.requireNonNull(connection);
        this.freshness = java.util.Objects.requireNonNull(freshness);
        this.lastSyncedAt = lastSyncedAt;
        this.capabilities = Collections.unmodifiableSet(capabilities.isEmpty()
                ? EnumSet.noneOf(Capability.class) : EnumSet.copyOf(capabilities));
        this.sections = Collections.unmodifiableList(new ArrayList<>(sections));
        this.allowances = Collections.unmodifiableList(new ArrayList<>(allowances));
        this.currency = currency;
        this.protectedSeconds = protectedSeconds;
        this.activityEvents = activityEvents;
        this.tributeCents = tributeCents;
        this.sessions = sessions;
        this.serviceActive = serviceActive;
        this.sync = java.util.Objects.requireNonNull(sync);
        this.profile = java.util.Objects.requireNonNull(profile);
        this.contractDom = contractDom;
        this.contractDomId = contractDomId;
        this.featureConsent = Collections.unmodifiableMap(new java.util.LinkedHashMap<>(featureConsent));
        this.consentHardcoreOverride = consentHardcoreOverride;
    }

    public PersonSnapshot withProfile(ProfileData value, java.util.Map<String, Boolean> consent) {
        return withProfile(value, consent, false);
    }

    public PersonSnapshot withProfile(ProfileData value, java.util.Map<String, Boolean> consent, boolean hardcoreOverride) {
        return new PersonSnapshot(id, value.name, device, contractName, serviceLabel, connection, freshness,
                lastSyncedAt, capabilities, sections, allowances, currency, protectedSeconds,
                activityEvents, tributeCents, sessions, serviceActive, sync, sessionIdentity, additionOrder,
                value, consent, hardcoreOverride, contractDom, contractDomId);
    }

    public PersonSnapshot withContractDom(ProfileData value) {
        return withContractDom(value, null);
    }

    public PersonSnapshot withContractDom(ProfileData value, PersonId target) {
        return new PersonSnapshot(id, name, device, contractName, serviceLabel, connection, freshness,
                lastSyncedAt, capabilities, sections, allowances, currency, protectedSeconds,
                activityEvents, tributeCents, sessions, serviceActive, sync, sessionIdentity, additionOrder,
                profile, featureConsent, consentHardcoreOverride, java.util.Objects.requireNonNull(value), target);
    }

    public boolean can(Capability capability) { return capabilities.contains(capability); }

    public static final class SyncState {
        public final Freshness contract, stats, runtime;
        public final String desiredRevision, appliedRevision;
        public SyncState(Freshness contract, Freshness stats, Freshness runtime,
                String desiredRevision, String appliedRevision) {
            this.contract = java.util.Objects.requireNonNull(contract);
            this.stats = java.util.Objects.requireNonNull(stats);
            this.runtime = java.util.Objects.requireNonNull(runtime);
            this.desiredRevision = java.util.Objects.requireNonNull(desiredRevision);
            this.appliedRevision = java.util.Objects.requireNonNull(appliedRevision);
        }
        public boolean hasPendingConfiguration() {
            return !desiredRevision.isEmpty() && !desiredRevision.equals(appliedRevision);
        }
    }

    public static final class Section {
        public final String key, title, summary, details;
        public final List<App> apps;
        public Section(String key, String title, String summary, String details) {
            this(key, title, summary, details, Collections.emptyList());
        }
        public Section(String key, String title, String summary, String details, List<App> apps) {
            this.key = key; this.title = title; this.summary = summary; this.details = details;
            this.apps = Collections.unmodifiableList(new ArrayList<>(apps));
        }
    }

    public static final class App {
        public final String packageName, label;
        public App(String packageName, String label) {
            this.packageName = Objects.requireNonNull(packageName);
            this.label = Objects.requireNonNull(label);
        }
    }

    public static final class Allowance {
        public final String packageName, label;
        public final long usedMillis;
        public final int dailyMinutes;
        public Allowance(String packageName, String label, long usedMillis, int dailyMinutes) {
            this.packageName = packageName; this.label = label;
            this.usedMillis = usedMillis; this.dailyMinutes = dailyMinutes;
        }
    }
}
