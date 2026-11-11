package com.subhub.app.censor.android;

import android.content.SharedPreferences;
import android.graphics.RectF;
import com.subhub.app.censor.engine.CensorEngine;
import com.subhub.app.penance.PenanceInfraction;
import com.subhub.app.popup.ScopedDetectionHints;
import com.subhub.app.stats.StatsRepository;
import com.subhub.app.stats.StatsSnapshot;
import com.subhub.app.stats.OwnerObservation;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Explicit product data and accounting dependencies, separate from the AccessibilityService OS
 * host. This is NOT an effect/start capability. Owned execution also needs controller/capture/
 * Surface gates and drain binding; no internal factory is exposed by this refactor.
 */
interface CensorProductData {
    /** Own only this product's queued callbacks, never shut down a shared data broker. */
    interface AccountingWorker extends java.util.concurrent.Executor {
        void close();
        boolean terminated();
    }
    /** Immutable participation observation. Normal admission is short and must never perform IO. */
    interface Participation {
        boolean lab();
        boolean current();
        <T> T admitNormal(Supplier<T> admission, T rejected);
    }
    interface Ledger {
        int dwellSeconds();
        Assessment detection(int count, long now, StatsRepository.SessionStamp source, OwnerObservation observed);
        Assessment dwell(long now, StatsRepository.SessionStamp source, OwnerObservation observed);
        void notifyCharge(PenanceInfraction kind, int cents, long now, OwnerObservation observed, BooleanSupplier stillCurrent);
    }
    /**
     * Capture at evidence admission, before either queue. Owned implementations bind immutable
     * rules/feature facts to the applied revision, so delayed events cannot borrow later settings.
     * The physical adapter deliberately retains its existing live-manager behavior.
     */
    interface AccountingFacts {
        Ledger dwellLedger();
        Ledger detectionLedger();
        void checkAchievements(StatsSnapshot snapshot, OwnerObservation observed);
    }
    final class Assessment {
        final boolean acknowledged;
        final int chargedCents;
        Assessment(boolean acknowledged, int chargedCents) {
            if (chargedCents < 0) throw new IllegalArgumentException("Negative assessment");
            this.acknowledged = acknowledged; this.chargedCents = chargedCents;
        }
    }

    SharedPreferences preferences();
    String inputMethodPackage();
    Participation participation();
    /** Pure lane observation only, never a mutable repository capability. */
    StatsRepository.SessionStamp observedSessionStamp();
    /** Worker-only selection; device restoration remains confined to the physical adapter. */
    StatsRepository.SessionStamp sessionStamp();
    AccountingWorker accountingWorker();
    Ledger dwellLedger();
    Ledger detectionLedger();
    AccountingFacts accountingFacts();
    boolean armed(SharedPreferences snapshot);
    StatsRepository.EvidenceRecord recordEvidence(StatsRepository.SessionStamp source,
            Supplier<CensorEngine.EvidenceClaim> claim, Set<String> categories, OwnerObservation observed);
    void checkAchievements(StatsSnapshot snapshot);
    ScopedDetectionHints<RectF>.Binding bindPopupHints(Supplier<List<RectF>> hints);
    /** Internal accounting uncertainty must fence its exact runtime; the legacy adapter is unchanged. */
    default void accountingFailed() { }
}
