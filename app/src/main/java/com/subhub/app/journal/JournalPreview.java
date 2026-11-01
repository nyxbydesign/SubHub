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

    private JournalPreview(Activity activity, boolean dom, PersonId target, Runnable open) {
        super(activity); this.dom = dom; this.target = target;
        owner = (LifecycleOwner) activity; data = AsyncUiScope.forPage(owner); runtime = JournalRuntime.get(activity);
        setOrientation(VERTICAL); setPadding(0, PersonUi.dp(activity, 14), 0, 0);
        View divider = new View(activity); divider.setBackgroundColor(activity.getColor(R.color.outline_subtle));
        addView(divider, new LayoutParams(-1, PersonUi.dp(activity, 1)));
        LinearLayout action = PersonUi.row(this, R.drawable.ic_journal, activity.getString(R.string.journal_title), "", open);
        action.setTag("profile_journal"); action.setMinimumHeight(PersonUi.dp(activity, 52));
        metadata = PersonUi.text(this, "", false); metadata.setTextSize(12); metadata.setVisibility(GONE);
        metadata.setPadding(0, 0, 0, PersonUi.dp(activity, 4));
        summary = PersonUi.text(this, activity.getString(R.string.profile_journal_loading), false);
        summary.setMaxLines(2); summary.setEllipsize(android.text.TextUtils.TruncateAt.END);
        summary.setPadding(0, 0, 0, PersonUi.dp(activity, 6)); summary.setTag("profile_journal_preview");
    }

    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); handler.post(poll); }
    @Override protected void onDetachedFromWindow() {
        handler.removeCallbacksAndMessages(null); data.cancel(key); super.onDetachedFromWindow();
    }

    private void refresh() {
        JournalRuntime selected = runtime; boolean workspace = dom; PersonId source = target;
        data.load(key, () -> {
            selected.checkTarget(workspace, source);
            List<JournalEntry> entries = selected.list(workspace, source, 0);
            return entries.isEmpty() ? null : entries.get(0);
        }, entry -> {
            if (!isAttachedToWindow()) return;
            try {
                runtime.checkTarget(dom, target);
                // Recheck withdrawal before publishing a result collected on the background worker.
                JournalEntry current = entry == null ? null : runtime.entry(dom, target, entry.id);
                if (current == null) {
                    metadata.setVisibility(GONE);
                    summary.setText(target.isLocal() ? R.string.journal_empty : R.string.journal_shared_empty);
                    return;
                }
                boolean shared = !target.isLocal() || runtime.shared(dom, current.id);
                metadata.setText(DateFormat.getDateInstance(DateFormat.MEDIUM).format(new Date(current.created)) + " · "
                        + getContext().getString(shared ? R.string.journal_shared : R.string.journal_private));
                metadata.setVisibility(VISIBLE);
                summary.setText(!current.text.isEmpty() ? current.text : current.audio != null
                        ? getContext().getString(R.string.journal_audio)
                        : getResources().getQuantityString(R.plurals.journal_attachment_count, current.attachments.size(), current.attachments.size()));
            } catch (Exception unavailable) { unavailable(); }
        }, failure -> unavailable());
    }

    private void unavailable() {
        if (!isAttachedToWindow()) return;
        metadata.setVisibility(GONE);
        summary.setText(dom && !com.subhub.app.security.ControllerPinManager.isDomModeActive()
                ? R.string.profile_journal_locked : R.string.journal_unavailable);
    }
}
