package com.subhub.app.penance;

import android.content.Context;
import android.content.SharedPreferences;

import com.subhub.app.appmode.AppModeManager;
import com.subhub.app.security.HardcoreModeManager;
import com.subhub.app.stats.StatsRepository;
import com.subhub.app.settings.FeatureModuleManager;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.UUID;

/**
 * Local, bounded penance ledger. Detection can create debt entries, but this class never initiates
 * a network request or payment by itself.
 */
public final class PenanceManager {
    public static final String PREFS_NAME = "subhub_penance";
    public static final int DEFAULT_STRIKE_CENTS = 100;
    public static final int DEFAULT_DAILY_CAP_CENTS = 500;
    public static final int DEFAULT_WEEKLY_CAP_CENTS = 2_000;
    public static final int DEFAULT_MERCY_MINUTES = 10;
    public static final int DEFAULT_DWELL_SECONDS = 10;
    public static final int DEFAULT_DETECTION_BATCH = 1;
    public static final int DEFAULT_TAMPER_COOLDOWN_MINUTES = 5;
    public static final int MIN_TAMPER_COOLDOWN_MINUTES = 1;
    public static final int MAX_TAMPER_COOLDOWN_MINUTES = 1_440;
    public static final String CURRENCY = "EUR";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_STRIKE_CENTS = "strike_cents";
    private static final String KEY_DAILY_CAP_CENTS = "daily_cap_cents";
    private static final String KEY_WEEKLY_CAP_CENTS = "weekly_cap_cents";
    private static final String KEY_MERCY_MINUTES = "mercy_minutes";
    private static final String KEY_DWELL_SECONDS = "dwell_seconds";
    private static final String KEY_DETECTION_BATCH = "detection_batch";
    private static final String KEY_DETECTION_REMAINDER = "detection_batch_remainder";
    private static final String KEY_TAMPER_COOLDOWN_MINUTES = "tamper_cooldown_minutes";
    private static final String KEY_LAST_TAMPER_AT = "last_tamper_at";
    private static final String KEY_EVENTS = "events_v1";
    private static final String KEY_TOTAL_PAID_CENTS = "total_paid_cents_v1";
    private static final String LEGACY_KEY_BACKEND_URL = "paypal_backend_url";
    public static final String KEY_PAYPAL_LINK = "paypal_payment_link";
    private static final String KEY_ORDER_ID = "active_order_id";
    private static final String KEY_APPROVAL_URL = "active_approval_url";
    private static final String KEY_PAYPAL_BOUNDARY = "active_paypal_boundary";
    private static final String KEY_CHECKOUT_MODE = "active_checkout_mode";
    private static final int MAX_EVENTS = 200;
    private static final Object LOCK = new Object();

    private final SharedPreferences preferences;
    private final FeatureModuleManager modules;
    private final Context context;

    public PenanceManager(Context context) {
        this.context = context.getApplicationContext();
        preferences = this.context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        modules = new FeatureModuleManager(this.context);
    }

    public boolean isEnabled() {
        return modules.isWalletEnabled() && preferences.getBoolean(KEY_ENABLED, false);
    }

    public int getStrikeCents() {
        return getInfractionCents(PenanceInfraction.NEW_DETECTION);
    }

    public boolean isInfractionEnabled(PenanceInfraction infraction) {
        String key = ruleEnabledKey(infraction);
        if (preferences.contains(key)) {
            return preferences.getBoolean(key, infraction.enabledByDefault());
        }
        return infraction == PenanceInfraction.NEW_DETECTION
                ? infraction.enabledByDefault() : false;
    }

    public int getInfractionCents(PenanceInfraction infraction) {
        String key = ruleCentsKey(infraction);
        int fallback = infraction == PenanceInfraction.NEW_DETECTION
                ? preferences.getInt(KEY_STRIKE_CENTS, DEFAULT_STRIKE_CENTS)
                : infraction.defaultCents();
        return PenancePolicy.clampStrikeCents(preferences.getInt(key, fallback));
    }

    public int getDailyCapCents() {
        return preferences.getInt(KEY_DAILY_CAP_CENTS, DEFAULT_DAILY_CAP_CENTS);
    }

    public int getWeeklyCapCents() {
        return preferences.getInt(KEY_WEEKLY_CAP_CENTS, DEFAULT_WEEKLY_CAP_CENTS);
    }

    public int getMercyMinutes() {
        return preferences.getInt(KEY_MERCY_MINUTES, DEFAULT_MERCY_MINUTES);
    }

    public int getDwellSeconds() {
        return PenancePolicy.clampDwellSeconds(
                preferences.getInt(KEY_DWELL_SECONDS, DEFAULT_DWELL_SECONDS));
    }

    public int getDetectionBatch() {
        return PenancePolicy.clampDetectionBatch(
                preferences.getInt(KEY_DETECTION_BATCH, DEFAULT_DETECTION_BATCH));
    }

    public int getDetectionRemainder() {
        return Math.max(0, Math.min(getDetectionBatch() - 1,
                preferences.getInt(KEY_DETECTION_REMAINDER, 0)));
    }

    public int getTamperCooldownMinutes() {
        return clampTamperCooldown(preferences.getInt(
                KEY_TAMPER_COOLDOWN_MINUTES, DEFAULT_TAMPER_COOLDOWN_MINUTES));
    }

    public int getDailyRemainingCents(long nowMillis) {
        synchronized (LOCK) {
            int used = PenancePolicy.periodTotal(
                    loadEvents(), nowMillis, ZoneId.systemDefault(), true);
            return Math.max(0, getDailyCapCents() - used);
        }
    }

    public int getWeeklyRemainingCents(long nowMillis) {
        synchronized (LOCK) {
            int used = PenancePolicy.periodTotal(
                    loadEvents(), nowMillis, ZoneId.systemDefault(), false);
            return Math.max(0, getWeeklyCapCents() - used);
        }
    }

    public String getPayPalLink() {
        String value = preferences.getString(KEY_PAYPAL_LINK, "");
        return value == null ? "" : value.trim();
    }

    public void savePayPalLink(String value) {
        preferences.edit().putString(KEY_PAYPAL_LINK,
                value == null ? "" : value.trim()).remove(LEGACY_KEY_BACKEND_URL).commit();
    }

    public void configure(boolean enabled, int strikeCents, int dailyCapCents,
            int weeklyCapCents, int mercyMinutes) {
        Map<PenanceInfraction, Integer> rules = new EnumMap<>(PenanceInfraction.class);
        rules.put(PenanceInfraction.NEW_DETECTION, strikeCents);
        configure(enabled, rules, dailyCapCents, weeklyCapCents, mercyMinutes,
                DEFAULT_DWELL_SECONDS, DEFAULT_DETECTION_BATCH);
    }

    public void configure(boolean enabled, Map<PenanceInfraction, Integer> enabledRuleCosts,
            int dailyCapCents, int weeklyCapCents, int mercyMinutes, int dwellSeconds) {
        configure(enabled, enabledRuleCosts, dailyCapCents, weeklyCapCents, mercyMinutes,
                dwellSeconds, getDetectionBatch());
    }

    public void configure(boolean enabled, Map<PenanceInfraction, Integer> enabledRuleCosts,
            int dailyCapCents, int weeklyCapCents, int mercyMinutes, int dwellSeconds,
            int detectionBatch) {
        configure(enabled, enabledRuleCosts, dailyCapCents, weeklyCapCents, mercyMinutes,
                dwellSeconds, detectionBatch, getTamperCooldownMinutes());
    }

    public void configure(boolean enabled, Map<PenanceInfraction, Integer> enabledRuleCosts,
            int dailyCapCents, int weeklyCapCents, int mercyMinutes, int dwellSeconds,
            int detectionBatch, int tamperCooldownMinutes) {
        Map<PenanceInfraction, Integer> rules = enabledRuleCosts == null
                ? Collections.emptyMap() : enabledRuleCosts;
        int largestRule = PenancePolicy.MIN_STRIKE_CENTS;
        for (Map.Entry<PenanceInfraction, Integer> entry : rules.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                largestRule = Math.max(largestRule,
                        PenancePolicy.clampStrikeCents(entry.getValue()));
            }
        }
        int boundedDaily = PenancePolicy.clampDailyCapCents(dailyCapCents, largestRule);
        int boundedWeekly = PenancePolicy.clampWeeklyCapCents(weeklyCapCents, boundedDaily);
        int boundedBatch = PenancePolicy.clampDetectionBatch(detectionBatch);
        boolean resetDetectionProgress = !enabled || !isEnabled()
                || boundedBatch != getDetectionBatch();
        SharedPreferences.Editor editor = preferences.edit()
                .putBoolean(KEY_ENABLED, enabled)
                .putInt(KEY_DAILY_CAP_CENTS, boundedDaily)
                .putInt(KEY_WEEKLY_CAP_CENTS, boundedWeekly)
                .putInt(KEY_MERCY_MINUTES, PenancePolicy.clampMercyMinutes(mercyMinutes))
                .putInt(KEY_DWELL_SECONDS, PenancePolicy.clampDwellSeconds(dwellSeconds))
                .putInt(KEY_DETECTION_BATCH, boundedBatch)
                .putInt(KEY_TAMPER_COOLDOWN_MINUTES,
                        clampTamperCooldown(tamperCooldownMinutes))
                .remove(LEGACY_KEY_BACKEND_URL);
        if (resetDetectionProgress) editor.putInt(KEY_DETECTION_REMAINDER, 0);
        for (PenanceInfraction infraction : PenanceInfraction.values()) {
            boolean ruleEnabled = rules.containsKey(infraction);
            int cents = ruleEnabled && rules.get(infraction) != null
                    ? PenancePolicy.clampStrikeCents(rules.get(infraction))
                    : getInfractionCents(infraction);
            editor.putBoolean(ruleEnabledKey(infraction), ruleEnabled)
                    .putInt(ruleCentsKey(infraction), cents);
            if (infraction == PenanceInfraction.NEW_DETECTION) {
                editor.putInt(KEY_STRIKE_CENTS, cents);
            }
        }
        editor.apply();
    }

    /** Returns the bounded amount added to the ledger in cents. */
    public int recordInfraction(PenanceInfraction infraction, int count, long nowMillis) {
        if (infraction == null || !isEnabled() || !isInfractionEnabled(infraction)
                || !new AppModeManager(context).isArmed() || count <= 0) return 0;
        synchronized (LOCK) {
            if (infraction == PenanceInfraction.TAMPER_ATTEMPT) {
                HardcoreModeManager hardcore = new HardcoreModeManager(context);
                if (!hardcore.isRequested()) return 0;
                long last = preferences.getLong(KEY_LAST_TAMPER_AT, 0L);
                long cooldown = getTamperCooldownMinutes() * 60_000L;
                if (last > 0L && nowMillis >= last && nowMillis - last < cooldown) return 0;
            }
            int billableCount = count;
            if (infraction == PenanceInfraction.NEW_DETECTION) {
                int batch = getDetectionBatch();
                int accumulated = preferences.getInt(KEY_DETECTION_REMAINDER, 0) + count;
                billableCount = accumulated / batch;
                preferences.edit().putInt(
                        KEY_DETECTION_REMAINDER, accumulated % batch).apply();
                if (billableCount <= 0) return 0;
            }
            List<PenanceEvent> events = loadEvents();
            trimHistoryForInsertion(events);
            if (events.size() >= MAX_EVENTS) return 0;
            int amount = PenancePolicy.boundedCharge(events, nowMillis, billableCount,
                    getInfractionCents(infraction), getDailyCapCents(), getWeeklyCapCents(),
                    ZoneId.systemDefault());
            if (amount <= 0) return 0;
            long mercyEnds = nowMillis + getMercyMinutes() * 60_000L;
            events.add(new PenanceEvent(UUID.randomUUID().toString(), nowMillis, mercyEnds,
                    amount, billableCount, infraction, PenanceEvent.Status.OPEN, ""));
            saveEvents(events);
            new StatsRepository(context).recordTributeEvent(amount,
                    infraction == PenanceInfraction.TAMPER_ATTEMPT);
            if (infraction == PenanceInfraction.TAMPER_ATTEMPT) {
                preferences.edit().putLong(KEY_LAST_TAMPER_AT, nowMillis).apply();
            }
            HardcoreAutoPayManager.schedule(context);
            return amount;
        }
    }

    private static int clampTamperCooldown(int minutes) {
        return Math.max(MIN_TAMPER_COOLDOWN_MINUTES,
                Math.min(MAX_TAMPER_COOLDOWN_MINUTES, minutes));
    }

    /** Compatibility entry point for the original new-detection rule. */
    public int recordStrikes(int strikes, long nowMillis) {
        return recordInfraction(PenanceInfraction.NEW_DETECTION, strikes, nowMillis);
    }

    /** Adds one immediate, fixed-price pause purchase without applying infraction caps. */
    public boolean requestPaidPause(long nowMillis) {
        PaidPauseManager pause = new PaidPauseManager(context);
        if (!isEnabled() || !pause.canPurchase()) return false;
        synchronized (LOCK) {
            List<PenanceEvent> events = loadEvents();
            for (PenanceEvent event : events) {
                if (event.getInfraction() == PenanceInfraction.PAID_PAUSE
                        && (event.getStatus() == PenanceEvent.Status.OPEN
                        || event.getStatus() == PenanceEvent.Status.CHECKOUT)) {
                    return true;
                }
            }
            trimHistoryForInsertion(events);
            if (events.size() >= MAX_EVENTS) return false;
            events.add(new PenanceEvent(UUID.randomUUID().toString(), nowMillis, nowMillis,
                    pause.getPriceCents(), 1, PenanceInfraction.PAID_PAUSE,
                    PenanceEvent.Status.OPEN, ""));
            saveEvents(events);
            new StatsRepository(context).recordTributeEvent(pause.getPriceCents(), false);
            return true;
        }
    }

    private static String ruleEnabledKey(PenanceInfraction infraction) {
        return "rule_" + infraction.preferenceKey() + "_enabled";
    }

    private static String ruleCentsKey(PenanceInfraction infraction) {
        return "rule_" + infraction.preferenceKey() + "_cents";
    }

    public PenanceSnapshot snapshot(long nowMillis) {
        synchronized (LOCK) {
            List<PenanceEvent> events = loadEvents();
            int due = 0;
            int mercy = 0;
            int checkout = 0;
            int paid = 0;
            for (PenanceEvent event : events) {
                if (event.isDue(nowMillis)) due += event.getAmountCents();
                else if (event.isInMercy(nowMillis)) mercy += event.getAmountCents();
                else if (event.getStatus() == PenanceEvent.Status.CHECKOUT) {
                    checkout += event.getAmountCents();
                } else if (event.getStatus() == PenanceEvent.Status.PAID) {
                    paid += event.getAmountCents();
                }
            }
            List<PenanceEvent> newestFirst = new ArrayList<>(events);
            newestFirst.sort(Comparator.comparingLong(PenanceEvent::getCreatedAtMillis).reversed());
            return new PenanceSnapshot(isEnabled(), due, mercy, checkout, paid, newestFirst);
        }
    }

    /**
     * Lifetime value of settlements confirmed as paid. Open, grace-period, and checkout entries
     * never contribute. Existing installs migrate from the retained paid ledger the first time
     * this value is read, after which the accumulator survives bounded history trimming.
     */
    public long getTotalPaidCents() {
        synchronized (LOCK) {
            return totalPaidCentsLocked(loadEvents());
        }
    }

    public boolean forgiveLatestInMercy(long nowMillis) {
        synchronized (LOCK) {
            List<PenanceEvent> events = loadEvents();
            for (int index = events.size() - 1; index >= 0; index--) {
                PenanceEvent event = events.get(index);
                if (event.isInMercy(nowMillis)) {
                    events.set(index, event.withStatus(PenanceEvent.Status.FORGIVEN, ""));
                    saveEvents(events);
                    HardcoreAutoPayManager.schedule(context);
                    return true;
                }
            }
            return false;
        }
    }

    /** Safety action: releases every unpaid ledger entry and cancels any local checkout state. */
    public void forgiveAllUnpaid() {
        synchronized (LOCK) {
            List<PenanceEvent> events = loadEvents();
            for (int index = 0; index < events.size(); index++) {
                PenanceEvent event = events.get(index);
                if (event.getStatus() == PenanceEvent.Status.OPEN
                        || event.getStatus() == PenanceEvent.Status.CHECKOUT) {
                    events.set(index, event.withStatus(PenanceEvent.Status.FORGIVEN, ""));
                }
            }
            saveEvents(events);
            clearOrderState();
            HardcoreAutoPayManager.schedule(context);
        }
    }

    public Settlement beginSettlement(long nowMillis) {
        synchronized (LOCK) {
            List<PenanceEvent> events = loadEvents();
            String existingId = "";
            for (PenanceEvent event : events) {
                if (event.getStatus() == PenanceEvent.Status.CHECKOUT) {
                    existingId = event.getSettlementId();
                }
            }
            if (!existingId.isEmpty()) return settlement(events, existingId);

            String settlementId = UUID.randomUUID().toString();
            int amount = 0;
            for (int index = 0; index < events.size(); index++) {
                PenanceEvent event = events.get(index);
                if (event.isDue(nowMillis)) {
                    amount += event.getAmountCents();
                    events.set(index, event.withStatus(
                            PenanceEvent.Status.CHECKOUT, settlementId));
                }
            }
            if (amount <= 0) return null;
            saveEvents(events);
            clearOrderState();
            return settlement(events, settlementId);
        }
    }

    /** Begins an exact-price checkout containing only the pending paid-pause request. */
    public Settlement beginPaidPauseSettlement(long nowMillis) {
        if (!isEnabled() || !new PaidPauseManager(context).canPurchase()) return null;
        synchronized (LOCK) {
            List<PenanceEvent> events = loadEvents();
            for (PenanceEvent event : events) {
                if (event.getStatus() == PenanceEvent.Status.CHECKOUT) {
                    return settlement(events, event.getSettlementId());
                }
            }
            int target = -1;
            for (int index = events.size() - 1; index >= 0; index--) {
                PenanceEvent event = events.get(index);
                if (event.getInfraction() == PenanceInfraction.PAID_PAUSE
                        && event.isDue(nowMillis)) {
                    target = index;
                    break;
                }
            }
            if (target < 0) return null;
            String settlementId = UUID.randomUUID().toString();
            PenanceEvent pause = events.get(target);
            events.set(target, pause.withStatus(
                    PenanceEvent.Status.CHECKOUT, settlementId));
            saveEvents(events);
            clearOrderState();
            return settlement(events, settlementId);
        }
    }

    public void bindOrder(String settlementId, String orderId, String approvalUrl) {
        bindOrder(settlementId, orderId, approvalUrl, "", CheckoutMode.LINK);
    }

    public void bindOrder(String settlementId, String orderId, String approvalUrl,
            String paypalBoundary) {
        bindOrder(settlementId, orderId, approvalUrl, paypalBoundary,
                CheckoutMode.API_APPROVAL);
    }

    private void bindOrder(String settlementId, String orderId, String approvalUrl,
            String paypalBoundary, CheckoutMode mode) {
        if (settlementId == null || settlementId.isEmpty()
                || orderId == null || orderId.isEmpty()) return;
        synchronized (LOCK) {
            List<PenanceEvent> events = loadEvents();
            if (!settlementExists(events, settlementId)
                    || (containsPaidPause(events, settlementId)
                    && !new PaidPauseManager(context).canPurchase())) return;
            preferences.edit().putString(KEY_ORDER_ID, orderId)
                    .putString(KEY_APPROVAL_URL, approvalUrl == null ? "" : approvalUrl)
                    .putString(KEY_PAYPAL_BOUNDARY,
                            paypalBoundary == null ? "" : paypalBoundary)
                    .putString(KEY_CHECKOUT_MODE, mode.name()).apply();
        }
    }

    public void markAutomaticSettlement(String settlementId, String paypalBoundary) {
        if (settlementId == null || settlementId.isEmpty()) return;
        synchronized (LOCK) {
            if (!settlementExists(loadEvents(), settlementId)) return;
            preferences.edit().remove(KEY_ORDER_ID).remove(KEY_APPROVAL_URL)
                    .putString(KEY_PAYPAL_BOUNDARY,
                            paypalBoundary == null ? "" : paypalBoundary)
                    .putString(KEY_CHECKOUT_MODE, CheckoutMode.HARDCORE_AUTO.name()).apply();
        }
    }

    public CheckoutMode getActiveCheckoutMode() {
        try {
            return CheckoutMode.valueOf(preferences.getString(
                    KEY_CHECKOUT_MODE, CheckoutMode.NONE.name()));
        } catch (IllegalArgumentException ignored) {
            return CheckoutMode.NONE;
        }
    }

    public String getActiveOrderId() {
        String value = preferences.getString(KEY_ORDER_ID, "");
        return value == null ? "" : value;
    }

    public String getActiveApprovalUrl() {
        String value = preferences.getString(KEY_APPROVAL_URL, "");
        return value == null ? "" : value;
    }

    public String getActivePayPalBoundary() {
        String value = preferences.getString(KEY_PAYPAL_BOUNDARY, "");
        return value == null ? "" : value;
    }

    public String getActiveSettlementId() {
        synchronized (LOCK) {
            for (PenanceEvent event : loadEvents()) {
                if (event.getStatus() == PenanceEvent.Status.CHECKOUT) {
                    return event.getSettlementId();
                }
            }
            return "";
        }
    }

    public void cancelSettlement(String settlementId) {
        if (settlementId == null || settlementId.isEmpty()) return;
        synchronized (LOCK) {
            List<PenanceEvent> events = loadEvents();
            for (int index = 0; index < events.size(); index++) {
                PenanceEvent event = events.get(index);
                if (event.getStatus() == PenanceEvent.Status.CHECKOUT
                        && settlementId.equals(event.getSettlementId())) {
                    events.set(index, event.withStatus(PenanceEvent.Status.OPEN, ""));
                }
            }
            saveEvents(events);
            clearOrderState();
            HardcoreAutoPayManager.schedule(context);
        }
    }

    public boolean completeSettlement(String settlementId, int paidAmountCents) {
        if (settlementId == null || settlementId.isEmpty() || paidAmountCents <= 0) return false;
        synchronized (LOCK) {
            List<PenanceEvent> events = loadEvents();
            long paidBeforeSettlement = totalPaidCentsLocked(events);
            int expected = 0;
            boolean activatesPause = false;
            for (PenanceEvent event : events) {
                if (event.getStatus() == PenanceEvent.Status.CHECKOUT
                        && settlementId.equals(event.getSettlementId())) {
                    expected += event.getAmountCents();
                    activatesPause |= event.getInfraction() == PenanceInfraction.PAID_PAUSE;
                }
            }
            if (expected != paidAmountCents) return false;
            if (activatesPause && !new PaidPauseManager(context).activate()) return false;
            for (int index = 0; index < events.size(); index++) {
                PenanceEvent event = events.get(index);
                if (event.getStatus() == PenanceEvent.Status.CHECKOUT
                        && settlementId.equals(event.getSettlementId())) {
                    events.set(index, event.withStatus(
                            PenanceEvent.Status.PAID, settlementId));
                }
            }
            saveEvents(events);
            long updatedTotal = paidBeforeSettlement > Long.MAX_VALUE - paidAmountCents
                    ? Long.MAX_VALUE : paidBeforeSettlement + paidAmountCents;
            preferences.edit().putLong(KEY_TOTAL_PAID_CENTS, updatedTotal).apply();
            clearOrderState();
            HardcoreAutoPayManager.schedule(context);
            return true;
        }
    }

    private long totalPaidCentsLocked(List<PenanceEvent> events) {
        if (preferences.contains(KEY_TOTAL_PAID_CENTS)) {
            return Math.max(0L, preferences.getLong(KEY_TOTAL_PAID_CENTS, 0L));
        }
        long migratedTotal = 0L;
        for (PenanceEvent event : events) {
            if (event.getStatus() != PenanceEvent.Status.PAID) continue;
            long amount = Math.max(0, event.getAmountCents());
            migratedTotal = migratedTotal > Long.MAX_VALUE - amount
                    ? Long.MAX_VALUE : migratedTotal + amount;
        }
        preferences.edit().putLong(KEY_TOTAL_PAID_CENTS, migratedTotal).apply();
        return migratedTotal;
    }

    public boolean isPaidPauseSettlement(String settlementId) {
        if (settlementId == null || settlementId.isEmpty()) return false;
        synchronized (LOCK) {
            return containsPaidPause(loadEvents(), settlementId);
        }
    }

    /** Earliest time an open correction window becomes payable, or zero when none exists. */
    public long nextDueAtMillis() {
        synchronized (LOCK) {
            long next = Long.MAX_VALUE;
            for (PenanceEvent event : loadEvents()) {
                if (event.getStatus() == PenanceEvent.Status.OPEN) {
                    next = Math.min(next, event.getMercyEndsAtMillis());
                }
            }
            return next == Long.MAX_VALUE ? 0L : next;
        }
    }

    private List<PenanceEvent> loadEvents() {
        String raw = preferences.getString(KEY_EVENTS, "");
        List<PenanceEvent> events = new ArrayList<>();
        if (raw == null || raw.trim().isEmpty()) return events;
        for (String line : raw.split(";")) {
            String[] parts = line.split(",", -1);
            if (parts.length != 7 && parts.length != 8) continue;
            try {
                PenanceEvent.Status status = PenanceEvent.Status.valueOf(parts[5]);
                PenanceInfraction infraction = parts.length == 8
                        ? PenanceInfraction.valueOf(parts[7])
                        : PenanceInfraction.NEW_DETECTION;
                events.add(new PenanceEvent(parts[0], Long.parseLong(parts[1]),
                        Long.parseLong(parts[2]), Integer.parseInt(parts[3]),
                        Integer.parseInt(parts[4]), infraction, status, parts[6]));
            } catch (IllegalArgumentException ignored) {
                // Ignore malformed private preference entries without losing valid history.
            }
        }
        return events;
    }

    private void saveEvents(List<PenanceEvent> events) {
        trimHistory(events);
        List<String> encoded = new ArrayList<>(events.size());
        for (PenanceEvent event : events) {
            encoded.add(String.join(",", event.getId(),
                    Long.toString(event.getCreatedAtMillis()),
                    Long.toString(event.getMercyEndsAtMillis()),
                    Integer.toString(event.getAmountCents()),
                    Integer.toString(event.getStrikeCount()),
                    event.getStatus().name(), event.getSettlementId(),
                    event.getInfraction().name()));
        }
        preferences.edit().putString(KEY_EVENTS, String.join(";", encoded)).apply();
    }

    private static void trimHistory(List<PenanceEvent> events) {
        while (events.size() > MAX_EVENTS) {
            int removable = -1;
            for (int index = 0; index < events.size(); index++) {
                PenanceEvent.Status status = events.get(index).getStatus();
                if (status == PenanceEvent.Status.PAID || status == PenanceEvent.Status.FORGIVEN) {
                    removable = index;
                    break;
                }
            }
            if (removable < 0) break;
            events.remove(removable);
        }
    }

    private static void trimHistoryForInsertion(List<PenanceEvent> events) {
        while (events.size() >= MAX_EVENTS) {
            int removable = -1;
            for (int index = 0; index < events.size(); index++) {
                PenanceEvent.Status status = events.get(index).getStatus();
                if (status == PenanceEvent.Status.PAID
                        || status == PenanceEvent.Status.FORGIVEN) {
                    removable = index;
                    break;
                }
            }
            if (removable < 0) return;
            events.remove(removable);
        }
    }

    private static boolean settlementExists(List<PenanceEvent> events, String settlementId) {
        for (PenanceEvent event : events) {
            if (event.getStatus() == PenanceEvent.Status.CHECKOUT
                    && settlementId.equals(event.getSettlementId())) return true;
        }
        return false;
    }

    private static boolean containsPaidPause(
            List<PenanceEvent> events, String settlementId) {
        for (PenanceEvent event : events) {
            if (event.getStatus() == PenanceEvent.Status.CHECKOUT
                    && settlementId.equals(event.getSettlementId())
                    && event.getInfraction() == PenanceInfraction.PAID_PAUSE) return true;
        }
        return false;
    }

    private static Settlement settlement(List<PenanceEvent> events, String settlementId) {
        List<PenanceEvent> included = new ArrayList<>();
        int amount = 0;
        for (PenanceEvent event : events) {
            if (event.getStatus() == PenanceEvent.Status.CHECKOUT
                    && settlementId.equals(event.getSettlementId())) {
                included.add(event);
                amount += event.getAmountCents();
            }
        }
        return new Settlement(settlementId, amount, included);
    }

    private void clearOrderState() {
        preferences.edit().remove(KEY_ORDER_ID).remove(KEY_APPROVAL_URL)
                .remove(KEY_PAYPAL_BOUNDARY).remove(KEY_CHECKOUT_MODE).apply();
    }

    public enum CheckoutMode { NONE, LINK, API_APPROVAL, HARDCORE_AUTO }

    public static String formatMoney(int cents) {
        return String.format(Locale.ROOT, "€%.2f", Math.max(0, cents) / 100.0);
    }

    public static final class Settlement {
        private final String id;
        private final int amountCents;
        private final List<PenanceEvent> events;

        private Settlement(String id, int amountCents, List<PenanceEvent> events) {
            this.id = id;
            this.amountCents = amountCents;
            this.events = Collections.unmodifiableList(new ArrayList<>(events));
        }

        public String getId() { return id; }
        public int getAmountCents() { return amountCents; }
        public List<PenanceEvent> getEvents() { return events; }
    }
}
