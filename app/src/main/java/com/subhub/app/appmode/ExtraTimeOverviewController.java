package com.subhub.app.appmode;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import com.subhub.app.people.People;
import com.subhub.app.people.PersonId;
import com.subhub.app.remote.ExtraTimeRequests;
import com.subhub.app.security.DeviceRolePreferences;
import com.subhub.app.security.KeyholderProvider;
import com.subhub.app.util.AsyncUiScope;
import java.util.LinkedHashMap;
import java.util.Map;

/** Retains only app data and accepted request outcomes while the Overview Activity is recreated. */
public final class ExtraTimeOverviewController extends ViewModel {
    public enum Stage { BUSY, SAVED, ERROR }
    public enum Failure { UNAVAILABLE, CHANGED, UNCONFIRMED }
    public static final class Operation {
        public final Stage stage;
        public final ExtraTimeRequests.Request request;
        public final Failure failure;
        public final String previousRequestId;
        Operation(Stage stage, ExtraTimeRequests.Request request, Failure failure, String previousRequestId) {
            this.stage = stage; this.request = request; this.failure = failure; this.previousRequestId = previousRequestId;
        }
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final MutableLiveData<Long> changes = new MutableLiveData<>(0L);
    private final Map<String, Operation> operations = new LinkedHashMap<>();
    private ExtraTimeRequests.Snapshot snapshot;
    private String scope = "";
    private boolean readFailed;
    private long revision;
    private volatile boolean closed;

    public ExtraTimeOverviewController() { }
    public LiveData<Long> changes() { return changes; }
    public long revision() { return revision; }
    public ExtraTimeRequests.Snapshot snapshot() { return snapshot; }
    public boolean readFailed() { return readFailed; }
    public String scope() { return scope; }

    /** The Home load must not overwrite feedback with a snapshot started before a mutation. */
    public void accept(ExtraTimeRequests.Snapshot value, long expectedRevision) {
        if (expectedRevision != revision) return;
        snapshot = value;
        readFailed = value == null;
        if (value == null) return;
        if (!scope.equals(value.scope)) {
            scope = value.scope;
            operations.entrySet().removeIf(entry -> entry.getValue().stage != Stage.BUSY);
        }
        for (ExtraTimeRequests.App app : value.apps) {
            Operation operation = operation(app.packageName);
            if (operation == null || operation.stage == Stage.BUSY) continue;
            ExtraTimeRequests.Request latest = app.latest;
            if (latest != null && (operation.stage == Stage.ERROR && !latest.id.equals(operation.previousRequestId)
                    || operation.request != null && (!latest.id.equals(operation.request.id)
                    || latest.status != ExtraTimeRequests.Status.PENDING)))
                operations.remove(key(scope, app.packageName));
        }
    }

    public ExtraTimeRequests.App app(String packageName) {
        if (snapshot != null) for (ExtraTimeRequests.App app : snapshot.apps)
            if (app.packageName.equals(packageName)) return app;
        return null;
    }

    public Operation operation(String packageName) { return operations.get(key(scope, packageName)); }

    public void submit(Context context, AsyncUiScope data, String packageName,
            String displayedScope, int displayedCents, String displayedCurrency) {
        ExtraTimeRequests.App app = app(packageName);
        Operation previous = operation(packageName);
        if (previous != null && (previous.stage == Stage.BUSY || previous.stage == Stage.SAVED)) return;
        if (snapshot == null || !scope.equals(displayedScope) || !snapshot.supported || !snapshot.canRequest
                || app == null || !app.exhausted || app.pending != null) {
            operations.put(key(scope, packageName), new Operation(Stage.ERROR, null, Failure.UNAVAILABLE,
                    app == null || app.latest == null ? "" : app.latest.id));
            changed();
            return;
        }
        Context application = context.getApplicationContext();
        KeyholderProvider authority = People.keyholder();
        com.subhub.app.relationships.RelationshipUi.Provider provider =
                com.subhub.app.relationships.RelationshipUi.provider();
        DeviceRolePreferences.Mode role = DeviceRolePreferences.get(application);
        String requestKey = key(scope, packageName);
        String previousRequestId = app.latest == null ? "" : app.latest.id;
        operations.put(requestKey, new Operation(Stage.BUSY, null, null, previousRequestId));
        changed(); // The row changes before its worker is queued, including on a slow connection.
        data.loadSerial("overview-extra-time:" + requestKey, () -> {
            ExtraTimeRequests.Request saved = null;
            Failure failure = null;
            try {
                if (authority != People.keyholder() || provider != com.subhub.app.relationships.RelationshipUi.provider()
                        || role != DeviceRolePreferences.get(application) || DeviceRolePreferences.isDomWorkspace(application))
                    throw new SecurityException("Overview request context changed");
                saved = new ExtraTimeRequests(application, PersonId.LOCAL).submit(packageName,
                        displayedCents, displayedCurrency, displayedScope);
            } catch (SecurityException unavailable) { failure = Failure.UNAVAILABLE; }
            catch (IllegalArgumentException changed) { failure = Failure.CHANGED; }
            catch (Exception uncertain) { failure = Failure.UNCONFIRMED; }
            ExtraTimeRequests.Request result = saved;
            Failure problem = failure;
            main.post(() -> {
                if (closed) return;
                operations.put(requestKey, result == null ? new Operation(Stage.ERROR, null, problem, previousRequestId)
                        : new Operation(Stage.SAVED, result, null, previousRequestId));
                // An old relationship's result remains scoped, never projected onto a new Dom.
                if (scope.equals(displayedScope) && authority == People.keyholder()
                        && provider == com.subhub.app.relationships.RelationshipUi.provider()
                        && role == DeviceRolePreferences.get(application) && !DeviceRolePreferences.isDomWorkspace(application))
                    changed();
            });
            return null;
        }, ignored -> { }, ignored -> { });
    }

    public String signature() {
        StringBuilder out = new StringBuilder().append(revision).append('|').append(scope).append('|').append(readFailed);
        if (snapshot == null) return out.toString();
        out.append('|').append(snapshot.supported).append('|').append(snapshot.canRequest)
                .append('|').append(snapshot.tributeCents).append('|').append(snapshot.currency)
                .append('|').append(snapshot.unavailableReason);
        for (ExtraTimeRequests.App app : snapshot.apps) {
            out.append('|').append(app.packageName).append('|').append(app.dailyMinutes).append('|').append(app.bonusMinutes)
                    .append('|').append(app.usedMillis).append('|').append(app.remainingMillis).append('|').append(app.exhausted)
                    .append('|').append(app.combinedLimitReached);
            if (app.pending != null) out.append('|').append(app.pending.id);
        }
        for (ExtraTimeRequests.Request request : snapshot.requests)
            out.append('|').append(request.id).append('|').append(request.status).append('|')
                    .append(request.approvedMinutes).append('|').append(request.tributeCents);
        return out.toString();
    }

    private void changed() { changes.setValue(++revision); }
    private static String key(String scope, String packageName) { return scope + ":" + packageName; }
    @Override protected void onCleared() { closed = true; }
}
