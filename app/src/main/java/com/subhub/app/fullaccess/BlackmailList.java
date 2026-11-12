package com.subhub.app.fullaccess;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import com.subhub.app.people.PersonUi;
import com.subhub.app.util.PageScrollView;
import java.util.List;
import java.util.function.BiConsumer;

/** A viewport-sized, recycled list within the shared Blackmail page shell. */
final class BlackmailList extends ListView {
    private final PageScrollView viewport;
    private final Rows rows = new Rows();
    private final boolean newestAtEnd;
    private final Runnable onEdge;
    private final View.OnLayoutChangeListener resize = (view, left, top, right, bottom,
            oldLeft, oldTop, oldRight, oldBottom) -> {
        if (bottom - top != oldBottom - oldTop) requestLayout();
    };

    BlackmailList(LinearLayout parent, PageScrollView viewport, String scope, boolean newestAtEnd, Runnable onEdge) {
        super(parent.getContext());
        this.viewport = viewport;
        this.newestAtEnd = newestAtEnd;
        this.onEdge = onEdge;
        setTag("blackmail_continuous:" + scope);
        setDivider(null);
        setNestedScrollingEnabled(true);
        setItemsCanFocus(true);
        setStackFromBottom(newestAtEnd);
        setAdapter(rows);
        setOnScrollListener(new OnScrollListener() {
            @Override public void onScrollStateChanged(AbsListView list, int state) { }
            @Override public void onScroll(AbsListView list, int first, int visible, int total) {
                if (visible > 0) signalEdge();
            }
        });
        parent.addView(this, new LinearLayout.LayoutParams(-1, -2));
    }

    private void signalEdge() {
        removeCallbacks(onEdge);
        post(onEdge);
    }

    boolean nearEdge() {
        return isShown() && getChildCount() > 0 && (newestAtEnd ? getFirstVisiblePosition() <= 2
                : getLastVisiblePosition() >= rows.getCount() - 3);
    }

    boolean near(String key) {
        int start = Math.max(0, getFirstVisiblePosition() - 2);
        int end = Math.min(rows.getCount(), getLastVisiblePosition() + 3);
        for (int index = start; index < end; index++) {
            if (key.equals(rows.getItem(index).key)) return true;
        }
        return false;
    }

    void show(List<Row> entries, boolean first, boolean advance) {
        int position = getFirstVisiblePosition();
        View firstChild = getChildAt(0);
        String anchor = position < rows.entries.size() ? rows.entries.get(position).key : null;
        int top = firstChild == null ? 0 : firstChild.getTop();
        boolean newest = newestAtEnd && (first || !advance && !canScrollVertically(1));
        rows.entries = entries;
        rows.notifyDataSetChanged();
        if (entries.isEmpty()) return;
        if (newest) {
            setSelection(entries.size() - 1);
        } else if (anchor != null) {
            for (int index = 0; index < entries.size(); index++) {
                if (anchor.equals(entries.get(index).key)) {
                    position = index;
                    break;
                }
            }
            setSelectionFromTop(Math.min(position, entries.size() - 1), top);
        }
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (viewport != null) viewport.addOnLayoutChangeListener(resize);
    }

    @Override protected void onDetachedFromWindow() {
        if (viewport != null) viewport.removeOnLayoutChangeListener(resize);
        removeCallbacks(onEdge);
        super.onDetachedFromWindow();
    }

    @Override protected void onMeasure(int width, int height) {
        Context context = getContext();
        int available = viewport == null ? 0 : viewport.getMeasuredHeight();
        if (available <= 0) available = context.getResources().getDisplayMetrics().heightPixels;
        View child = this;
        while (child != viewport && child.getParent() instanceof ViewGroup parent) {
            if (child.getLayoutParams() instanceof MarginLayoutParams margins)
                available -= margins.topMargin + margins.bottomMargin;
            available -= parent.getPaddingTop() + parent.getPaddingBottom();
            for (int index = 0; index < parent.getChildCount(); index++) {
                View sibling = parent.getChildAt(index);
                if (sibling == child || sibling.getVisibility() == View.GONE) continue;
                available -= sibling.getMeasuredHeight();
                if (sibling.getLayoutParams() instanceof MarginLayoutParams margins)
                    available -= margins.topMargin + margins.bottomMargin;
            }
            child = parent;
        }
        int minimum = Math.round(96 * context.getResources().getDisplayMetrics().density);
        super.onMeasure(width, MeasureSpec.makeMeasureSpec(Math.max(minimum, available), MeasureSpec.EXACTLY));
    }

    record Row(String key, BiConsumer<LinearLayout, Integer> render) { }

    private final class Rows extends BaseAdapter {
        List<Row> entries = List.of();

        @Override public int getCount() { return entries.size(); }
        @Override public Row getItem(int position) { return entries.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override public View getView(int position, View recycled, ViewGroup parent) {
            LinearLayout holder = recycled instanceof LinearLayout column ? column : PersonUi.column(getContext());
            holder.removeAllViews();
            holder.setTag(null);
            holder.setPaddingRelative(0, 0, 0, 0);
            holder.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
            getItem(position).render.accept(holder, position);
            return holder;
        }
    }
}
