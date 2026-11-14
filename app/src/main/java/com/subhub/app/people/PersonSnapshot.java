package com.subhub.app.people;

import com.subhub.app.util.RemoteAppIcon;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Immutable, presentation-ready data. Connection and data freshness are separate facts. */
public final class PersonSnapshot {
    public enum Connection { LOCAL, UNPAIRED, PENDING, PAIRED, CONNECTED, IDLE, OFFLINE, REVOKED, ERROR }
    public enum Freshness { CURRENT, STALE, UNKNOWN }
    public enum Capability { VIEW_CONTRACT, EDIT_CONTRACT, VIEW_STATS, MANAGE_CONNECTION, END_SESSION, VIEW_BLACKMAIL }

    public final PersonId id;
    public final String name, device, contractName, serviceLabel, currency;
    public final Connection connection;
    public final Freshness freshness;
    public final long lastSyncedAt;
    /** Verified peer contact, which does not imply that every sync phase completed. */
    public final long lastContactAt;
    /** Last locally observed Online evidence. Explicit background activity never advances it. */
    public final long lastOnlineAt;
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
    public final PeerContact contractDomContact;
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
                Collections.emptyMap(), false, null, null, null);
    }

    private PersonSnapshot(PersonId id, String name, String device, String contractName,
            String serviceLabel, Connection connection, Freshness freshness, long lastSyncedAt,
            Set<Capability> capabilities, List<Section> sections, List<Allowance> allowances,
            String currency, long protectedSeconds, long activityEvents, long tributeCents, int sessions,
            boolean serviceActive, SyncState sync, String sessionIdentity, long additionOrder,
            ProfileData profile, java.util.Map<String, Boolean> featureConsent, boolean consentHardcoreOverride,
            ProfileData contractDom, PersonId contractDomId, PeerContact contractDomContact) {
        this(id, name, device, contractName, serviceLabel, connection, freshness, lastSyncedAt, capabilities,
                sections, allowances, currency, protectedSeconds, activityEvents, tributeCents, sessions,
                serviceActive, sync, sessionIdentity, additionOrder, profile, featureConsent,
                consentHardcoreOverride, contractDom, contractDomId, contractDomContact, lastSyncedAt, 0);
    }

    private PersonSnapshot(PersonId id, String name, String device, String contractName,
            String serviceLabel, Connection connection, Freshness freshness, long lastSyncedAt,
            Set<Capability> capabilities, List<Section> sections, List<Allowance> allowances,
            String currency, long protectedSeconds, long activityEvents, long tributeCents, int sessions,
            boolean serviceActive, SyncState sync, String sessionIdentity, long additionOrder,
            ProfileData profile, java.util.Map<String, Boolean> featureConsent, boolean consentHardcoreOverride,
            ProfileData contractDom, PersonId contractDomId, PeerContact contractDomContact, long lastContactAt, long lastOnlineAt) {
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
        this.lastContactAt = Math.max(0, lastContactAt);
        this.lastOnlineAt = Math.max(0, lastOnlineAt);
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
        if (contractDomContact != null && (contractDom == null || !contractDomContact.id.equals(contractDomId)))
            throw new IllegalArgumentException("Counterpart contact identity differs");
        this.contractDomContact = contractDomContact;
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
                value, consent, hardcoreOverride, contractDom, contractDomId, contractDomContact, lastContactAt, lastOnlineAt);
    }

    public PersonSnapshot withContractDom(ProfileData value) {
        return withContractDom(value, null);
    }

    public PersonSnapshot withContractDom(ProfileData value, PersonId target) {
        return withContractDom(value, target, contractDomContact != null && contractDomContact.id.equals(target)
                ? contractDomContact : null);
    }

    public PersonSnapshot withContractDom(ProfileData value, PersonId target, PeerContact contact) {
        return new PersonSnapshot(id, name, device, contractName, serviceLabel, connection, freshness,
                lastSyncedAt, capabilities, sections, allowances, currency, protectedSeconds,
                activityEvents, tributeCents, sessions, serviceActive, sync, sessionIdentity, additionOrder,
                profile, featureConsent, consentHardcoreOverride, java.util.Objects.requireNonNull(value), target, contact, lastContactAt, lastOnlineAt);
    }

    public PersonSnapshot withLastContactAt(long contact) {
        return new PersonSnapshot(id, name, device, contractName, serviceLabel, connection, freshness,
                lastSyncedAt, capabilities, sections, allowances, currency, protectedSeconds,
                activityEvents, tributeCents, sessions, serviceActive, sync, sessionIdentity, additionOrder,
                profile, featureConsent, consentHardcoreOverride, contractDom, contractDomId, contractDomContact, contact, lastOnlineAt);
    }

    public PersonSnapshot withLastOnlineAt(long observed) {
        return new PersonSnapshot(id, name, device, contractName, serviceLabel, connection, freshness,
                lastSyncedAt, capabilities, sections, allowances, currency, protectedSeconds,
                activityEvents, tributeCents, sessions, serviceActive, sync, sessionIdentity, additionOrder,
                profile, featureConsent, consentHardcoreOverride, contractDom, contractDomId, contractDomContact,
                lastContactAt, observed);
    }

    /** Verified foreground presence for this exact peer generation, never controller authority. */
    public static final class PeerContact {
        public final PersonId id;
        public final Connection state;
        public final long lastOnlineAt;
        public PeerContact(PersonId id, Connection state, long lastOnlineAt) {
            this.id = Objects.requireNonNull(id); this.state = Objects.requireNonNull(state);
            if (id.isLocal() || state == Connection.LOCAL) throw new IllegalArgumentException("Remote contact required");
            this.lastOnlineAt = Math.max(0, lastOnlineAt);
        }
        @Override public boolean equals(Object other) {
            if (!(other instanceof PeerContact)) return false;
            PeerContact value = (PeerContact) other;
            return id.equals(value.id) && state == value.state && lastOnlineAt == value.lastOnlineAt;
        }
        @Override public int hashCode() { return Objects.hash(id, state, lastOnlineAt); }
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
        public final RemoteAppIcon appIcon;
        public App(String packageName, String label) {
            this(packageName, label, null);
        }
        public App(String packageName, String label, RemoteAppIcon appIcon) {
            this.packageName = Objects.requireNonNull(packageName);
            this.label = Objects.requireNonNull(label);
            this.appIcon = matchingIcon(packageName, appIcon);
        }
    }

    public static final class Allowance {
        public final String packageName, label;
        public final long usedMillis, budgetMillis;
        public final int dailyMinutes;
        public final RemoteAppIcon appIcon;
        public Allowance(String packageName, String label, long usedMillis, int dailyMinutes) {
            this(packageName, label, usedMillis, dailyMinutes, null);
        }
        public Allowance(String packageName, String label, long usedMillis, int dailyMinutes, RemoteAppIcon appIcon) {
            this(packageName, label, usedMillis, dailyMinutes, appIcon, Math.max(0L, dailyMinutes * 60_000L));
        }
        public Allowance(String packageName, String label, long usedMillis, int dailyMinutes,
                RemoteAppIcon appIcon, long budgetMillis) {
            this.packageName = packageName; this.label = label;
            this.usedMillis = usedMillis; this.dailyMinutes = dailyMinutes;
            this.appIcon = matchingIcon(packageName, appIcon); this.budgetMillis = Math.max(0, budgetMillis);
        }
        public static List<Allowance> ordered(List<Allowance> values) {
            List<Allowance> result = new ArrayList<>(values);
            result.sort((a, b) -> com.subhub.app.appmode.AppTimeOrder.compare(a.usedMillis, a.budgetMillis, a.label, a.packageName,
                    b.usedMillis, b.budgetMillis, b.label, b.packageName));
            return result;
        }
        public long displayMinutes() { return budgetMillis / 60_000L + (budgetMillis % 60_000L == 0 ? 0 : 1); }
    }

    private static RemoteAppIcon matchingIcon(String packageName, RemoteAppIcon icon) {
        if (icon != null && !icon.packageName.equals(packageName))
            throw new IllegalArgumentException("Icon does not match application");
        return icon;
    }
}
