package com.subhub.app.journal;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import com.subhub.app.R;
import com.subhub.app.people.PersonId;
import com.subhub.app.people.PersonUi;
import com.subhub.app.util.AsyncUiScope;
import java.text.DateFormat;
import java.util.Date;
import java.util.List;

/** A small live preview reads through exactly the same workspace and audience checks as Journal. */
public final class JournalPreview extends LinearLayout {
    private final JournalRuntime runtime;
    private final AsyncUiScope data;
    private final LifecycleOwner owner;
    private final PersonId target;
    private final boolean dom;
    private final boolean overview, link;
    private final View entryAction;
    private final TextView summary, metadata;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final String key = "journal-preview:" + java.util.UUID.randomUUID();
    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (!isAttachedToWindow()) return;
            if (owner.getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.STARTED)) refresh();
            handler.postDelayed(this, 2000);
        }
    };

    public static void add(LinearLayout parent, boolean dom, PersonId target, Runnable open) {
        if (!(parent.getContext() instanceof Activity) || !(parent.getContext() instanceof LifecycleOwner)) return;
        parent.addView(new JournalPreview((Activity) parent.getContext(), dom, target, open), new LayoutParams(-1, -2));
    }

    public static void addOverview(LinearLayout parent, boolean dom, Runnable open) {
        if (!(parent.getContext() instanceof Activity) || !(parent.getContext() instanceof LifecycleOwner)) return;
        parent.addView(new JournalPreview((Activity) parent.getContext(), dom, PersonId.LOCAL, open, true, false), new LayoutParams(-1, -2));
    }

    public static void addSharedLink(LinearLayout parent, boolean dom, PersonId target, Runnable open) {
        if (!(parent.getContext() instanceof Activity) || !(parent.getContext() instanceof LifecycleOwner)) return;
        parent.addView(new JournalPreview((Activity) parent.getContext(), dom, target, open, false, true), new LayoutParams(-1, -2));
    }

    private JournalPreview(Activity activity, boolean dom, PersonId target, Runnable open) {
        this(activity, dom, target, open, false, false);
    }

    private JournalPreview(Activity activity, boolean dom, PersonId target, Runnable open, boolean overview, boolean link) {
        super(activity); this.dom = dom; this.target = target;
        this.overview = overview; this.link = link;
        owner = (LifecycleOwner) activity; data = AsyncUiScope.forPage(owner); runtime = JournalRuntime.get(activity);
        if (overview) {
            setOrientation(VERTICAL); setTag("home_journal:" + (dom ? "dom" : "sub"));
            LinearLayout header = new LinearLayout(activity); header.setGravity(android.view.Gravity.CENTER_VERTICAL);
            TextView title = com.subhub.app.people.OverviewUi.text(activity, activity.getString(R.string.overview_your_journal), 16, true);
            header.addView(title, new LayoutParams(0, -2, 1));
            metadata = com.subhub.app.people.OverviewUi.text(activity, "", 12, false);
            metadata.setVisibility(GONE); header.addView(metadata, new LayoutParams(-2, -2));
            header.setMinimumHeight(PersonUi.dp(activity, 28)); addView(header, new LayoutParams(-1, -2));
            com.subhub.app.people.OverviewUi.divider(this);
            LinearLayout row = new LinearLayout(activity); row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setMinimumHeight(PersonUi.dp(activity, 52));
            android.widget.ImageView icon = new android.widget.ImageView(activity); icon.setImageResource(R.drawable.ic_journal);
            icon.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            icon.setImageTintList(android.content.res.ColorStateList.valueOf(activity.getColor(R.color.text_primary)));
            row.addView(icon, new LayoutParams(PersonUi.dp(activity, 28), PersonUi.dp(activity, 28)));
            LinearLayout copy = PersonUi.column(activity);
            LayoutParams copySize = new LayoutParams(0, -2, 1); copySize.setMarginStart(PersonUi.dp(activity, 12));
            row.addView(copy, copySize);
            summary = com.subhub.app.people.OverviewUi.text(activity, activity.getString(R.string.profile_journal_loading), 14, false);
            summary.setMaxLines(2); summary.setEllipsize(android.text.TextUtils.TruncateAt.END);
            summary.setTag("overview_journal_preview"); copy.addView(summary, new LayoutParams(-1, -2));
            TextView action = com.subhub.app.people.OverviewUi.text(activity, activity.getString(R.string.overview_open_journal) + "  ›", 12, false);
            action.setTextColor(activity.getColor(R.color.accent_text)); action.setGravity(android.view.Gravity.END);
            action.setPadding(0, PersonUi.dp(activity, 4), 0, 0); copy.addView(action, new LayoutParams(-1, -2));
            addView(row, new LayoutParams(-1, -2)); entryAction = this;
            com.subhub.app.people.OverviewUi.click(this, open); return;
        }
        setOrientation(VERTICAL); setPadding(0, PersonUi.dp(activity, 10), 0, 0);
        View divider = new View(activity); divider.setBackgroundColor(activity.getColor(R.color.outline_subtle));
        addView(divider, new LayoutParams(-1, PersonUi.dp(activity, 1)));
        LinearLayout action = PersonUi.row(this, R.drawable.ic_journal, activity.getString(link ? R.string.overview_shared_journal : R.string.journal_title), "", () -> {
            if (link) try { runtime.checkTarget(dom, target); }
            catch (Exception unavailable) { unavailable(); return; }
            open.run();
        });
        entryAction = action;
        if (link) action.setEnabled(false);
        action.setTag("profile_journal"); action.setMinimumHeight(PersonUi.dp(activity, 52));
        metadata = PersonUi.text(this, "", false); metadata.setTextSize(12); metadata.setVisibility(GONE);
        metadata.setPadding(0, 0, 0, PersonUi.dp(activity, 4));
        summary = PersonUi.text(this, activity.getString(R.string.profile_journal_loading), false);
        summary.setMaxLines(2); summary.setEllipsize(android.text.TextUtils.TruncateAt.END);
        summary.setPadding(0, 0, 0, PersonUi.dp(activity, 6)); summary.setTag("profile_journal_preview");
        if (link) { summary.setVisibility(GONE); setPadding(0, PersonUi.dp(activity, 4), 0, 0); }
    }

    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); handler.post(poll); }
    @Override protected void onDetachedFromWindow() {
        handler.removeCallbacksAndMessages(null); data.cancel(key); super.onDetachedFromWindow();
    }

    private void refresh() {
        JournalRuntime selected = runtime; boolean workspace = dom; PersonId source = target;
        data.load(key, () -> {
            selected.checkTarget(workspace, source);
            if (link) return null;
            List<JournalEntry> entries = selected.list(workspace, source, 0);
            return entries.isEmpty() ? null : entries.get(0);
        }, entry -> {
            if (!isAttachedToWindow()) return;
            try {
                runtime.checkTarget(dom, target);
                if (link) { entryAction.setEnabled(true); summary.setVisibility(GONE); return; }
                // Recheck withdrawal before publishing a result collected on the background worker.
                JournalEntry current = entry == null ? null : runtime.entry(dom, target, entry.id);
                if (current == null) {
                    metadata.setVisibility(GONE);
                    summary.setText(target.isLocal() ? R.string.journal_empty : R.string.journal_shared_empty);
                    return;
                }
                boolean shared = !target.isLocal() || runtime.shared(dom, current.id);
                metadata.setText((overview ? "" : DateFormat.getDateInstance(DateFormat.MEDIUM).format(new Date(current.created)) + " · ")
                        + getContext().getString(shared ? R.string.journal_shared : R.string.journal_private));
                if (overview) {
                    android.graphics.drawable.Drawable icon = getContext().getDrawable(shared ? R.drawable.ic_journal_share : R.drawable.ic_ux_lock);
                    icon.setBounds(0, 0, PersonUi.dp(getContext(), 14), PersonUi.dp(getContext(), 14));
                    icon.setTint(getContext().getColor(R.color.text_secondary));
                    metadata.setCompoundDrawablesRelative(icon, null, null, null); metadata.setCompoundDrawablePadding(PersonUi.dp(getContext(), 4));
                }
                metadata.setVisibility(VISIBLE);
                summary.setText(current.displayTitle());
            } catch (Exception unavailable) { unavailable(); }
        }, failure -> unavailable());
    }

    private void unavailable() {
        if (!isAttachedToWindow()) return;
        metadata.setVisibility(GONE);
        if (link) { entryAction.setEnabled(false); summary.setVisibility(VISIBLE); }
        summary.setText(dom && !com.subhub.app.security.ControllerPinManager.isDomWorkspaceUnlocked()
                ? R.string.profile_journal_locked : R.string.journal_unavailable);
    }
}
