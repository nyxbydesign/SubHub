package com.subhub.app.censor.android;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.RectF;
import com.subhub.app.appmode.AppModeManager;
import com.subhub.app.censor.engine.CensorEngine;
import com.subhub.app.diagnostics.CensorLabCensorSession;
import com.subhub.app.penance.PenanceChargeNotifier;
import com.subhub.app.penance.PenanceInfraction;
import com.subhub.app.penance.PenanceManager;
import com.subhub.app.popup.PopupStormManager;
import com.subhub.app.popup.ScopedDetectionHints;
import com.subhub.app.settings.SettingsRepository;
import com.subhub.app.stats.AchievementManager;
import com.subhub.app.stats.StatsRepository;
import com.subhub.app.stats.StatsSnapshot;
import com.subhub.app.stats.OwnerObservation;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;

/** Existing physical-device behavior only. Never construct this adapter for an internal owner. */
final class DeviceCensorProductData implements CensorProductData {
    private final Context physical;
    private final SharedPreferences preferences;
    private final AppModeManager mode;
    private final Function<Context, StatsRepository> statisticsFactory;
    private final Function<Context, PenanceManager> ledgerFactory;
    private StatsRepository statistics;
    private Ledger dwell;
    private DeviceParticipation participation;
    private final AccountingFacts accountingFacts = new AccountingFacts() {
        @Override public Ledger dwellLedger() { return DeviceCensorProductData.this.dwellLedger(); }
        @Override public Ledger detectionLedger() { return DeviceCensorProductData.this.detectionLedger(); }
        @Override public void checkAchievements(StatsSnapshot snapshot, OwnerObservation observed) {
            DeviceCensorProductData.this.checkAchievements(snapshot);
        }
    };
    private final java.util.concurrent.ExecutorService worker = java.util.concurrent.Executors.newSingleThreadExecutor(
            action -> new Thread(action, "Censor-product-accounting"));
    private final AccountingWorker accounting = new AccountingWorker() {
        @Override public void execute(Runnable action) { worker.execute(action); }
        @Override public void close() { worker.shutdown(); }
        @Override public boolean terminated() { return worker.isTerminated(); }
    };

    DeviceCensorProductData(Context physical, Function<Context, StatsRepository> statistics,
            Function<Context, PenanceManager> ledgers) {
        this.physical = Objects.requireNonNull(physical);
        statisticsFactory = Objects.requireNonNull(statistics); ledgerFactory = Objects.requireNonNull(ledgers);
        mode = new AppModeManager(physical); preferences = new SettingsRepository(physical).preferences();
    }
    @Override public SharedPreferences preferences() { return preferences; }
    @Override public String inputMethodPackage() { return mode.inputMethodPackage(); }
    @Override public Participation participation() {
        CensorLabCensorSession.Stamp stamp = CensorLabCensorSession.stamp();
        if (participation == null || participation.stamp != stamp) participation = new DeviceParticipation(stamp);
        return participation;
    }
    private StatsRepository statistics() {
        if (statistics == null) statistics = Objects.requireNonNull(statisticsFactory.apply(physical));
        return statistics;
    }
    @Override public StatsRepository.SessionStamp observedSessionStamp() { return statistics().observedSessionStamp(); }
    @Override public StatsRepository.SessionStamp sessionStamp() { return statistics().sessionStamp(); }
    @Override public AccountingWorker accountingWorker() { return accounting; }
    @Override public AccountingFacts accountingFacts() { return accountingFacts; }
    @Override public Ledger dwellLedger() {
        statistics(); // Preserve physical lookup construction order before ledger migration.
        if (dwell == null) dwell = new DeviceLedger(Objects.requireNonNull(ledgerFactory.apply(physical)));
        return dwell;
    }
    @Override public Ledger detectionLedger() {
        // Preserve the previous physical detection path's fresh ledger and fault-fixture behavior.
        return new DeviceLedger(new PenanceManager(physical));
    }
    @Override public boolean armed(SharedPreferences snapshot) { return snapshot.getBoolean(AppModeManager.KEY_ARMED, false); }
    @Override public StatsRepository.EvidenceRecord recordEvidence(StatsRepository.SessionStamp source,
            Supplier<CensorEngine.EvidenceClaim> claim, Set<String> categories, OwnerObservation observed) {
        // DEVICE intentionally retains its prior clock semantics; internal data uses the captured observation.
        return statistics().recordEvidence(source, claim, categories);
    }
    @Override public void checkAchievements(StatsSnapshot snapshot) { new AchievementManager(physical).checkAchievements(snapshot); }
    @Override public ScopedDetectionHints<RectF>.Binding bindPopupHints(Supplier<List<RectF>> hints) {
        return PopupStormManager.get().bindDetectionHints(hints);
    }
    private static final class DeviceParticipation implements Participation {
        final CensorLabCensorSession.Stamp stamp;
        DeviceParticipation(CensorLabCensorSession.Stamp stamp) { this.stamp = stamp; }
        @Override public boolean lab() { return stamp.active; }
        @Override public boolean current() { return stamp.isCurrent(); }
        @Override public <T> T admitNormal(Supplier<T> admission, T rejected) {
            return CensorLabCensorSession.withCurrentNormal(stamp, admission, rejected);
        }
    }
    private final class DeviceLedger implements Ledger {
        final PenanceManager ledger;
        DeviceLedger(PenanceManager ledger) { this.ledger = ledger; }
        @Override public int dwellSeconds() { return ledger.getDwellSeconds(); }
        @Override public Assessment detection(int count, long now, StatsRepository.SessionStamp source, OwnerObservation observed) {
            return assessment(ledger.recordSourceDetectionResult(count, now, source));
        }
        @Override public Assessment dwell(long now, StatsRepository.SessionStamp source, OwnerObservation observed) {
            return assessment(ledger.recordSourceDwellResult(now, source));
        }
        @Override public void notifyCharge(PenanceInfraction kind, int cents, long now, OwnerObservation observed, BooleanSupplier stillCurrent) {
            PenanceChargeNotifier.show(physical, ledger, kind, cents, now, stillCurrent);
        }
    }
    private static Assessment assessment(PenanceManager.SourceDetectionResult result) {
        return new Assessment(result.status == PenanceManager.SourceDetectionStatus.ACKNOWLEDGED, result.chargedCents);
    }
}
