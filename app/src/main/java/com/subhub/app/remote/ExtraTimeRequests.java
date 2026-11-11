package com.subhub.app.remote;

import android.content.Context;
import com.subhub.app.appmode.AppModeManager;
import com.subhub.app.appmode.AppTimerManager;
import com.subhub.app.appmode.ExtraTimeAllowance;
import com.subhub.app.people.PersonId;
import com.subhub.app.penance.PenanceManager;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.security.DeviceRolePreferences;
import com.subhub.app.security.SubFeaturePolicy;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** Role-aware app-time requests. Displayed facts never substitute for live action authority. */
public final class ExtraTimeRequests {
    public static final int MIN_MINUTES = 1, MAX_MINUTES = 1440;
    public enum Status { PENDING, APPROVED, DECLINED, EXPIRED }
    public static final class Request {
        public final String id, packageName, appLabel, day, zone, currency;
        public final long requestedAt, usedMillis;
        public final int approvedMinutes, dailyMinutes, tributeCents;
        public final Status status;
        public final com.subhub.app.util.RemoteAppIcon appIcon;
        private Request(ExtraTimeStore.Row row, long now) { this(row, now, null); }
        private Request(ExtraTimeStore.Row row, long now, com.subhub.app.util.RemoteAppIcon appIcon) {
            this.appIcon = appIcon;
            id = row.id; packageName = row.packageName; appLabel = row.label;
            day = row.day; zone = row.zone; requestedAt = row.at; usedMillis = row.appUsed;
            dailyMinutes = row.dailyMinutes; tributeCents = row.tributeCents; currency = row.currency;
            approvedMinutes = row.minutes;
            status = !row.today(now) ? Status.EXPIRED : row.state.equals("SUBMITTING")
                    ? Status.PENDING : Status.valueOf(row.state);
        }
    }
    public static final class App {
        public final String packageName, label;
        /** Zero means there is no active individual daily budget; a shared budget may still apply. */
        public final int dailyMinutes, bonusMinutes;
        public final long usedMillis, remainingMillis, budgetMillis;
        public final double remainingFraction;
        public final boolean exhausted, combinedLimitReached;
        public final Request pending, latest;
        private App(String packageName, String label, int dailyMinutes, int bonusMinutes, long usedMillis,
                boolean exhausted, long remainingMillis, boolean combinedLimitReached, Request pending, Request latest, long budgetMillis, double remainingFraction) {
            this.packageName = packageName; this.label = label; this.dailyMinutes = dailyMinutes;
            this.bonusMinutes = bonusMinutes; this.usedMillis = usedMillis; this.exhausted = exhausted;
            this.remainingMillis = remainingMillis; this.combinedLimitReached = combinedLimitReached;
            this.pending = pending; this.latest = latest; this.budgetMillis = budgetMillis; this.remainingFraction = remainingFraction;
        }
    }
    public static final class Snapshot {
        public final boolean dom, supported, canRequest;
        public final String unavailableReason, domName, personName, currency;
        /** Opaque local UI fence for the current peer and relationship generation. */
        public final String scope;
        public final int tributeCents, pendingCount;
        public final List<App> apps;
        public final List<Request> requests;
        private Snapshot(boolean dom, boolean supported, boolean canRequest, String reason, String domName,
                String personName, int tributeCents, String currency, List<App> apps, List<Request> requests,
                String scope) {
            this.dom = dom; this.supported = supported; this.canRequest = canRequest;
            this.scope = scope;
            unavailableReason = reason; this.domName = domName; this.personName = personName;
            this.tributeCents = tributeCents; this.currency = currency;
            this.apps = Collections.unmodifiableList(apps); this.requests = Collections.unmodifiableList(requests);
            int count = 0; for (Request row : requests) if (row.status == Status.PENDING) count++;
            pendingCount = count;
        }
    }
    private final Context context;
    private final PersonId person;
    private final RemoteFeature feature;
    private final RemoteStore authority;
    private final ExtraTimeStore journal;
    public ExtraTimeRequests(Context context, PersonId person) {
        this.context = context.getApplicationContext(); this.person = java.util.Objects.requireNonNull(person);
        feature = RemoteFeature.get(context); authority = feature.store; journal = new ExtraTimeStore(this.context);
    }
    private RemoteStore.Peer target() {
        if (!feature.recoveryReady()) throw new IllegalStateException("Connection recovery is not ready");
        if (person.isLocal()) {
            if (!DeviceRolePreferences.supportsSub(context) || DeviceRolePreferences.isDomWorkspace(context))
                throw new SecurityException("Open your Sub space to request time");
            RemoteStore.Peer selected = null;
            for (RemoteStore.Peer peer : authority.peers(false))
                if (peer.state == RemoteStore.State.ACTIVE && peer.localRole == RemoteProtocol.Role.SUB) {
                    if (selected != null) throw new SecurityException("Assigned Dom is unavailable");
                    selected = peer;
                }
            return selected;
        }
        feature.requireDomWorkspace();
        RemoteStore.Peer peer = new RemotePersonRepository(context, feature).peer(person);
        requireActive(authority, peer);
        if (peer.localRole != RemoteProtocol.Role.DOM) throw new SecurityException("Choose your Sub's profile");
        return peer;
    }
    public Snapshot snapshot(long now) throws Exception {
        boolean[] changed = {false};
        Snapshot result = authority.authorityAccess(() -> {
            synchronized (SubFeaturePolicy.LOCK) { synchronized (com.subhub.app.appmode.RuntimeSessionIdentity.LOCK) { synchronized (authority) { synchronized (ExtraTimeStore.LOCK) {
                RemoteStore.Peer peer = target(); boolean dom = !person.isLocal();
                boolean supported = peer != null && supported(authority, peer);
                boolean eligible = peer != null && supported && locallyEnabled(context, peer);
                List<ExtraTimeStore.Row> rows = peer == null ? new ArrayList<>() : journal.load(scope(peer));
                if (!dom && peer != null && expireObsolete(context,peer,rows,now)) {
                    journal.save(scope(peer),rows); changed[0] = true;
                }
                List<Request> requests = new ArrayList<>();
                for (ExtraTimeStore.Row row : rows) if (!row.state.equals("SUBMITTING"))
                    requests.add(new Request(row, now, dom
                            ? feature.appIcons.identity(peer.identity, peer.generation, row.packageName) : null));
                requests.sort((left, right) -> Long.compare(right.requestedAt, left.requestedAt));
                PenanceManager tribute = new PenanceManager(context);
                int quote = dom ? 0 : tribute.quoteExtraTimeRequest(now);
                List<App> apps = new ArrayList<>();
                if (!dom) {
                    AppModeManager mode = new AppModeManager(context); AppTimerManager timers = new AppTimerManager(context);
                    Set<String> packages = mode.getIncludedPackages();
                    for (String packageName : packages) {
                        Request pending = null, latest = null;
                        for (Request row : requests) if (row.packageName.equals(packageName)) {
                            if (latest == null) latest = row;
                            if (row.status == Status.PENDING) pending = row;
                        }
                        ExtraTimeAllowance extra = allowanceLocked(context, authority, peer, journal, packageName, now);
                        AppTimerManager.UsageSnapshot usage = timers.snapshot(packageName, now);
                        AppTimerManager.Settings budget = timers.loadSettings();
                        long remaining = extra.remaining(usage.appUsedMillis,AppTimerManager.minutesToMillis(timers.allowanceMinutes(packageName)),
                                usage.totalUsedMillis,AppTimerManager.minutesToMillis(budget.totalMinutes),budget.perAppEnabled,budget.totalEnabled);
                        apps.add(new App(packageName, label(context, packageName), budget.perAppEnabled ? timers.allowanceMinutes(packageName) : 0,
                                (int) (extra.bonusMillis / 60_000L), timers.snapshot(packageName, now).appUsedMillis,
                                timers.limitStatus(packageName, packages, now) != AppTimerManager.LimitStatus.NONE,
                                remaining, budget.totalEnabled && usage.totalUsedMillis >= AppTimerManager.minutesToMillis(budget.totalMinutes), pending, latest,
                                budget.perAppEnabled ? extra.individualBudget(timers.allowanceMinutes(packageName)*60_000L)
                                        : budget.totalEnabled ? Math.max(budget.totalMinutes*60_000L,extra.appCeilingMillis) : 0,
                                com.subhub.app.appmode.AppTimeOrder.forApp(usage.appUsedMillis,timers.allowanceMinutes(packageName)*60_000L,
                                        usage.totalUsedMillis,budget.totalMinutes*60_000L,budget.perAppEnabled,budget.totalEnabled,extra)));
                    }
                    apps.sort((left, right) -> com.subhub.app.appmode.AppTimeOrder.compareFractions(
                            left.remainingFraction,left.label,left.packageName,right.remainingFraction,right.label,right.packageName));
                    eligible &= mode.isEffectivelyArmed(now) && new com.subhub.app.settings.FeatureModuleManager(context).isLimitsEnabled();
                }
                String reason = peer == null ? "Connect to your Dom to request time"
                        : !supported ? "App-time request support has not been confirmed. Reconnect with your Dom and check that both apps are up to date."
                        : !dom && !locallyEnabled(context, peer) ? "App limits are not allowed for this Sub"
                        : !eligible ? "App limits must be active to request time" : "";
                return new Snapshot(dom, supported, !dom && eligible, reason,
                        !dom && peer != null ? peer.name : "", dom && peer != null ? peer.name : "",
                        quote, tribute.getCurrency(), apps, requests, peer == null ? "" : scope(peer));
            } } } }
        });
        if (changed[0]) feature.changed();
        return result;
    }
    public Request submit(String packageName, int expectedTributeCents, String expectedCurrency) throws Exception {
        return submitCurrent(packageName, expectedTributeCents, expectedCurrency, null);
    }
    /** Bind retained UI work to the relationship for which its quote was displayed. */
    public Request submit(String packageName, int expectedTributeCents, String expectedCurrency,
            String expectedScope) throws Exception {
        if (expectedScope == null || expectedScope.isEmpty())
            throw new IllegalArgumentException("Refresh the app-time request before trying again");
        return submitCurrent(packageName, expectedTributeCents, expectedCurrency, expectedScope);
    }
    private Request submitCurrent(String packageName, int expectedTributeCents, String expectedCurrency,
            String expectedScope) throws Exception {
        Request result = authority.authorityAccess(() -> {
            synchronized (SubFeaturePolicy.LOCK) { synchronized (com.subhub.app.appmode.RuntimeSessionIdentity.LOCK) { synchronized (authority) { synchronized (ExtraTimeStore.LOCK) {
                RemoteStore.Peer peer = target();
                if (!person.isLocal() || peer == null || !supported(authority, peer) || !locallyEnabled(context, peer))
                    throw new SecurityException("App-time requests are not available");
                if (expectedScope != null && !expectedScope.equals(scope(peer)))
                    throw new SecurityException("The relationship changed. Refresh the request and try again.");
                long now = System.currentTimeMillis(); AppModeManager mode = new AppModeManager(context);
                if (!mode.isEffectivelyArmed(now) || !new com.subhub.app.settings.FeatureModuleManager(context).isLimitsEnabled())
                    throw new IllegalStateException("App limits are not active");
                AppTimerManager timers = new AppTimerManager(context);
                Set<String> selected = mode.getIncludedPackages();
                if (!selected.contains(packageName) || timers.limitStatus(packageName, selected, now) == AppTimerManager.LimitStatus.NONE)
                    throw new IllegalStateException("This app still has time available");
                List<ExtraTimeStore.Row> rows = journal.load(scope(peer));
                for (ExtraTimeStore.Row row : rows) if (row.packageName.equals(packageName) && row.today(now)
                        && (row.state.equals("PENDING") || row.state.equals("SUBMITTING"))) {
                    finishSubmission(context, authority, peer, journal, rows, row, now);
                    return new Request(ExtraTimeStore.find(rows, row.id), now);
                }
                PenanceManager tribute = new PenanceManager(context);
                if (expectedTributeCents != tribute.quoteExtraTimeRequest(now) || !tribute.getCurrency().equals(expectedCurrency))
                    throw new IllegalArgumentException("The tribute changed. Review the request again.");
                ZoneId zone = ZoneId.systemDefault(); String day = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().toString();
                ExtraTimeStore.Row row = new ExtraTimeStore.Row(UUID.randomUUID().toString(), packageName,
                        label(context, packageName), day, zone.getId(), configuration(context, packageName), now,
                        timers.snapshot(packageName, now).appUsedMillis, timers.allowanceMinutes(packageName),
                        "SUBMITTING", 0, 0, expectedTributeCents, expectedCurrency);
                ExtraTimeStore.makeRoom(rows, now); rows.add(row);
                journal.save(scope(peer), rows); // Stable ID is durable before any financial effect.
                finishSubmission(context, authority, peer, journal, rows, row, now);
                return new Request(ExtraTimeStore.find(rows, row.id), now);
            } } } }
        });
        feature.changed(); return result;
    }
    public void approve(String requestId, int minutes) throws Exception {
        if (minutes < MIN_MINUTES || minutes > MAX_MINUTES) throw new IllegalArgumentException("Enter 1 to 1440 minutes");
        decide(requestId, minutes);
    }
    public void decline(String requestId) throws Exception { decide(requestId, 0); }
    private void decide(String id, int minutes) throws Exception {
        authority.authorityAccess(() -> {
            synchronized (SubFeaturePolicy.LOCK) { synchronized (com.subhub.app.appmode.RuntimeSessionIdentity.LOCK) { synchronized (authority) { synchronized (ExtraTimeStore.LOCK) {
                RemoteStore.Peer peer = target();
                if (person.isLocal() || peer == null || !supported(authority, peer)) throw new SecurityException("Dom review required");
                List<ExtraTimeStore.Row> rows = journal.load(scope(peer)); ExtraTimeStore.Row row = ExtraTimeStore.find(rows, id);
                if (row == null || !row.state.equals("PENDING") || !row.today(System.currentTimeMillis()))
                    throw new IllegalStateException("This request is no longer pending");
                ExtraTimeStore.replace(rows, row.state(minutes == 0 ? "DECLINED" : "APPROVED", minutes, 0));
                journal.save(scope(peer), rows);
                return null;
            } } } }
        });
        feature.changed();
    }
    static ExtraTimePeer.Endpoint endpoint(Context context, RemoteStore authority, String localIdentity, RemoteStore.Peer peer) {
        return new Endpoint(context.getApplicationContext(), authority, peer);
    }
    private static final class Endpoint implements ExtraTimePeer.Endpoint {
        private final Context context; private final RemoteStore authority; private final RemoteStore.Peer peer;
        private final ExtraTimeStore journal;
        Endpoint(Context context, RemoteStore authority, RemoteStore.Peer peer) {
            this.context = context; this.authority = authority; this.peer = peer; journal = new ExtraTimeStore(context);
        }
        public void requireActive() { ExtraTimeRequests.requireActive(authority, peer); }
        public boolean enabled() { return locallyEnabled(context, peer); }
        public JSONObject snapshot() throws Exception {
            return authority.authorityAccess(() -> {
                synchronized (SubFeaturePolicy.LOCK) { synchronized (authority) { synchronized (ExtraTimeStore.LOCK) {
                    requireActive(); JSONArray outgoing = new JSONArray(); long now = System.currentTimeMillis();
                    List<ExtraTimeStore.Row> rows = journal.load(scope(peer));
                    if (peer.localRole == RemoteProtocol.Role.SUB && expireObsolete(context,peer,rows,now))
                        journal.save(scope(peer),rows);
                    if (peer.localRole == RemoteProtocol.Role.SUB && enabled()) {
                        for (ExtraTimeStore.Row row : new ArrayList<>(rows)) if (row.state.equals("SUBMITTING"))
                            finishSubmission(context, authority, peer, journal, rows, row, now);
                    }
                    for (ExtraTimeStore.Row row : rows) if (!row.state.equals("SUBMITTING") && row.today(now)) outgoing.put(row.json(false));
                    return new JSONObject().put("schema", 1).put("rows", outgoing);
                } } }
            });
        }
        public void receive(JSONObject value) throws Exception {
            authority.authorityAccess(() -> {
                synchronized (SubFeaturePolicy.LOCK) { synchronized (authority) { synchronized (ExtraTimeStore.LOCK) {
                    requireActive(); if (!enabled()) throw new SecurityException("App-time requests disabled");
                    RemoteSessionCommands.keys(value, "schema", "rows");
                    if (RemoteProtocol.integer(value,"schema") != 1) throw new SecurityException("Unsupported app-time journal");
                    JSONArray incoming = value.getJSONArray("rows");
                    if (incoming.length() > ExtraTimeStore.MAX_ROWS) throw new SecurityException("Too many app-time requests");
                    List<ExtraTimeStore.Row> rows = journal.load(scope(peer)); long now = System.currentTimeMillis();
                    List<ExtraTimeStore.Row> notices = new ArrayList<>();
                    Set<String> ids = new java.util.HashSet<>();
                    for (int i = 0; i < incoming.length(); i++) {
                        ExtraTimeStore.Row offered = ExtraTimeStore.Row.parse(incoming.getJSONObject(i), false);
                        if (!ids.add(offered.id) || offered.state.equals("SUBMITTING")) throw new SecurityException("Invalid app-time submission");
                        ExtraTimeStore.Row saved = ExtraTimeStore.find(rows, offered.id);
                        if (saved != null && !saved.immutableEquals(offered)) throw new SecurityException("App-time request changed");
                        if (peer.localRole == RemoteProtocol.Role.DOM) {
                            if (saved == null) {
                                // A local submission can expire before it was ever published. It conveys no grant or debit authority.
                                if (offered.state.equals("EXPIRED")) continue;
                                if (!offered.state.equals("PENDING")) throw new SecurityException("Sub cannot decide a request");
                                ExtraTimeStore.makeRoom(rows, now);
                                // At most one concurrent request per app/day may reach review.
                                for (ExtraTimeStore.Row other : rows) if (other.packageName.equals(offered.packageName)
                                        && other.day.equals(offered.day) && other.state.equals("PENDING"))
                                    throw new SecurityException("Duplicate pending app-time request");
                                rows.add(offered.today(now) ? offered : offered.state("EXPIRED", 0, 0));
                                if (offered.today(now)) notices.add(offered);
                            } else if (offered.state.equals("EXPIRED"))
                                ExtraTimeStore.replace(rows, saved.state("EXPIRED", 0, 0));
                            else if (!offered.state.equals("PENDING") && !offered.state.equals(saved.state))
                                throw new SecurityException("Sub cannot change a decision");
                        } else {
                            if (saved == null) throw new SecurityException("Unrequested app-time decision");
                            if (!saved.state.equals("PENDING")) {
                                if (saved.state.equals("EXPIRED")) continue;
                                if (!saved.state.equals(offered.state) || saved.minutes != offered.minutes)
                                    throw new SecurityException("App-time decision changed");
                                continue;
                            }
                            if (offered.state.equals("PENDING")) continue;
                            if (!List.of("APPROVED", "DECLINED", "EXPIRED").contains(offered.state))
                                throw new SecurityException("Invalid app-time outcome");
                            boolean current = saved.today(now) && saved.zone.equals(ZoneId.systemDefault().getId())
                                    && new AppModeManager(context).getIncludedPackages().contains(saved.packageName)
                                    && saved.configuration.equals(configuration(context, saved.packageName));
                            long used = new AppTimerManager(context).snapshot(saved.packageName, now).appUsedMillis;
                            long ceiling = offered.state.equals("APPROVED") && current
                                    ? Math.min(86_400_000L, used + offered.minutes * 60_000L) : 0;
                            ExtraTimeStore.Row outcome = current ? saved.state(offered.state, offered.minutes, ceiling) : saved.state("EXPIRED", 0, 0);
                            ExtraTimeStore.replace(rows,outcome);
                            if (!outcome.state.equals("EXPIRED")) notices.add(outcome);
                        }
                    }
                    requireActive(); journal.save(scope(peer), rows);
                    for (ExtraTimeStore.Row notice : notices) {
                        requireActive();
                        if (peer.localRole == RemoteProtocol.Role.DOM) ExtraTimeNotifications.request(context,peer,notice);
                        else ExtraTimeNotifications.outcome(context,notice);
                    }
                    return null;
                } } }
            });
        }
    }
    private static void finishSubmission(Context context, RemoteStore authority, RemoteStore.Peer peer,
            ExtraTimeStore journal, List<ExtraTimeStore.Row> rows, ExtraTimeStore.Row row, long now) throws Exception {
        if (!row.state.equals("SUBMITTING")) return;
        requireActive(authority, peer);
        PenanceManager tribute = new PenanceManager(context);
        String submission = scope(peer) + ":" + row.id;
        if (!row.today(now) || !row.configuration.equals(configuration(context,row.packageName))) {
            int assessed = tribute.extraTimeRequestReceipt(submission);
            if (assessed >= 0) tribute.recordExtraTimeRequest(submission,assessed,row.currency,now);
            ExtraTimeStore.replace(rows,row.unsubmittedExpiry(Math.max(0,assessed)));
            journal.save(scope(peer),rows); return;
        }
        try {
            int assessed = tribute.recordExtraTimeRequest(submission,row.tributeCents,row.currency,now);
            if (assessed != row.tributeCents) throw new IllegalStateException("App-time tribute receipt mismatch");
        } catch (IllegalArgumentException staleQuote) {
            int assessed = tribute.extraTimeRequestReceipt(submission);
            ExtraTimeStore.replace(rows,row.unsubmittedExpiry(Math.max(0,assessed)));
            journal.save(scope(peer),rows); throw staleQuote;
        }
        requireActive(authority,peer);
        ExtraTimeStore.replace(rows,row.state("PENDING",0,0)); journal.save(scope(peer),rows);
    }
    /** Off-main reconciliation updates the receipt page and peers; enforcement itself remains a read. */
    private static boolean expireObsolete(Context context, RemoteStore.Peer peer, List<ExtraTimeStore.Row> rows,
            long now) throws Exception {
        boolean changed = false;
        java.util.Map<String,String> configurations = new java.util.HashMap<>();
        for (ExtraTimeStore.Row row : new ArrayList<>(rows)) {
            if (!row.state.equals("PENDING") && !row.state.equals("APPROVED")) continue;
            String configuration = configurations.get(row.packageName);
            if (configuration == null) {
                configuration = configuration(context,row.packageName); configurations.put(row.packageName,configuration);
            }
            if (!locallyEnabled(context,peer) || !row.today(now) || !row.zone.equals(ZoneId.systemDefault().getId())
                    || !row.configuration.equals(configuration)) {
                ExtraTimeStore.replace(rows,row.state("EXPIRED",0,0)); changed = true;
            }
        }
        return changed;
    }
    public static ExtraTimeAllowance allowance(Context context, String packageName, long now) {
        try {
            RemoteFeature feature = RemoteFeature.get(context); if (!feature.recoveryReady()) return ExtraTimeAllowance.NONE;
            return feature.store.authorityObservation(() -> {
                synchronized (SubFeaturePolicy.LOCK) { synchronized (feature.store) { synchronized (ExtraTimeStore.LOCK) {
                    if (!SubFeaturePolicy.allowed(context,"limits")) return ExtraTimeAllowance.NONE;
                    RemoteStore.Peer selected = null;
                    for (RemoteStore.Peer peer : feature.store.peers(false))
                        if (peer.state == RemoteStore.State.ACTIVE && peer.localRole == RemoteProtocol.Role.SUB) {
                            if (selected != null) return ExtraTimeAllowance.NONE;
                            selected = peer;
                        }
                    return selected == null ? ExtraTimeAllowance.NONE : allowanceLocked(context,feature.store,selected,new ExtraTimeStore(context),packageName,now);
                } } }
            });
        } catch (Exception unavailable) { return ExtraTimeAllowance.NONE; }
    }
    private static ExtraTimeAllowance allowanceLocked(Context context, RemoteStore authority, RemoteStore.Peer peer,
            ExtraTimeStore journal, String packageName, long now) throws Exception {
        if (peer == null || peer.localRole != RemoteProtocol.Role.SUB) return ExtraTimeAllowance.NONE;
        requireActive(authority, peer); long bonus = 0, ceiling = 0; String config = null;
        for (ExtraTimeStore.Row row : journal.load(scope(peer))) if (row.packageName.equals(packageName)
                && row.state.equals("APPROVED") && now >= row.at && row.today(now) && row.zone.equals(ZoneId.systemDefault().getId())) {
            if (config == null) config = configuration(context,packageName);
            if (row.configuration.equals(config)) {
                bonus = Math.min(86_400_000L, bonus + row.minutes * 60_000L); ceiling = Math.max(ceiling,row.ceiling);
            }
        }
        return new ExtraTimeAllowance(bonus,ceiling);
    }
    private static boolean supported(RemoteStore store, RemoteStore.Peer peer) {
        return "1".equals(store.metadata(peer.identity,peer.generation,"extra_time_version","0"));
    }
    private static boolean locallyEnabled(Context context, RemoteStore.Peer peer) {
        return peer.localRole == RemoteProtocol.Role.DOM || DeviceRolePreferences.supportsSub(context)
                && SubFeaturePolicy.allowed(context,"limits");
    }
    private static void requireActive(RemoteStore store, RemoteStore.Peer peer) {
        if (peer == null) throw new SecurityException("No assigned Dom");
        RemoteStore.Peer live = store.peer(peer.identity,peer.generation);
        if (live == null || live.state != RemoteStore.State.ACTIVE || live.localRole != peer.localRole)
            throw new SecurityException("This relationship is no longer active");
    }
    static String scope(RemoteStore.Peer peer) { return peer.identity + "_" + peer.generation; }
    private static String configuration(Context context, String packageName) throws Exception {
        AppTimerManager timers = new AppTimerManager(context); AppTimerManager.Settings settings = timers.loadSettings();
        JSONObject value = new JSONObject().put("package",packageName)
                .put("included",new AppModeManager(context).getIncludedPackages().contains(packageName))
                .put("individual",settings.perAppEnabled).put("daily",timers.allowanceMinutes(packageName))
                .put("combined",settings.totalEnabled).put("total",settings.totalMinutes);
        return RemoteProtocol.sha256(RemoteProtocol.canonical(value).getBytes(StandardCharsets.UTF_8));
    }
    private static String label(Context context, String packageName) {
        try {
            String value = com.subhub.app.notifications.NotificationText.clean(context.getPackageManager().getApplicationLabel(
                    context.getPackageManager().getApplicationInfo(packageName,0)).toString());
            return value.isEmpty() ? packageName.substring(0,Math.min(160,packageName.length())) : value;
        } catch (android.content.pm.PackageManager.NameNotFoundException unavailable) { return packageName.substring(0,Math.min(160,packageName.length())); }
    }
}
