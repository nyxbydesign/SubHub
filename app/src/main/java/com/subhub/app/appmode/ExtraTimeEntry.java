package com.subhub.app.appmode;

import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.subhub.app.R;
import com.subhub.app.people.PersonId;
import com.subhub.app.people.PersonUi;
import com.subhub.app.remote.ExtraTimeRequests;
import com.subhub.app.security.DeviceRolePreferences;
import com.subhub.app.util.AsyncUiScope;

/** Person-scoped routes remain available from the existing allowance cards. */
public final class ExtraTimeEntry {
    private ExtraTimeEntry() { }

    public static void request(LinearLayout parent, PersonId person, String packageName) {
        TextView action = PersonUi.action(parent, R.string.extra_time_request,
                () -> ExtraTimeActivity.open(parent.getContext(), person, packageName), true);
        action.setTag("extra_time_open:" + packageName);
    }

    public static void review(LinearLayout parent, PersonId person) {
        Context context = parent.getContext();
        if (!DeviceRolePreferences.isDomWorkspace(context)) return;
        TextView action = PersonUi.action(parent, R.string.extra_time_review,
                () -> ExtraTimeActivity.open(context, person, null), false);
        action.setTag("extra_time_review:" + person.value());
        refresh(parent, person);
    }

    /** Called by the owning page's established refresh, without adding another timer. */
    public static void refresh(View root, PersonId person) {
        TextView action = root.findViewWithTag("extra_time_review:" + person.value());
        if (action == null) return;
        Context context = action.getContext();
        if (!DeviceRolePreferences.isDomWorkspace(context)) return;
        AsyncUiScope data = AsyncUiScope.forContext(context);
        String key = "extra-time-count:" + person.value();
        if (data.isPending(key)) return;
        Context application = context.getApplicationContext();
        com.subhub.app.relationships.RelationshipUi.Provider provider =
                com.subhub.app.relationships.RelationshipUi.provider();
        com.subhub.app.security.KeyholderProvider authority = com.subhub.app.people.People.keyholder();
        data.load(key,
                () -> new ExtraTimeRequests(application, person).snapshot(System.currentTimeMillis()), value -> {
                    if (!action.isAttachedToWindow() || !DeviceRolePreferences.isDomWorkspace(context)
                            || provider != com.subhub.app.relationships.RelationshipUi.provider()
                            || authority != com.subhub.app.people.People.keyholder() || !sameRoute(context, person)
                            || root.findViewWithTag("extra_time_review:" + person.value()) != action) return;
                    int count = 0;
                    for (ExtraTimeRequests.Request request : value.requests)
                        if (request.status == ExtraTimeRequests.Status.PENDING) count++;
                    action.setText(count > 0 ? context.getString(R.string.extra_time_review_count, count)
                            : context.getString(R.string.extra_time_review));
                }, ignored -> {
                    if (action.isAttachedToWindow() && sameRoute(context, person))
                        action.setText(R.string.extra_time_review);
                });
    }

    private static boolean sameRoute(Context context, PersonId person) {
        for (int depth = 0; depth < 32; depth++) {
            if (context instanceof android.app.Activity) {
                String route = ((android.app.Activity) context).getIntent()
                        .getStringExtra(com.subhub.app.people.PersonRoutes.EXTRA_PERSON_ID);
                return route == null || route.equals(person.value());
            }
            if (!(context instanceof android.content.ContextWrapper)) break;
            Context parent = ((android.content.ContextWrapper) context).getBaseContext();
            if (parent == context) break;
            context = parent;
        }
        return false;
    }
}
