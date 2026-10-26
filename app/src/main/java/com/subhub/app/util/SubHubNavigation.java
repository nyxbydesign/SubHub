package com.subhub.app.util;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.view.AccessibilityDelegateCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import androidx.core.widget.ImageViewCompat;

import com.subhub.app.MainActivity;
import com.subhub.app.R;
import com.subhub.app.appmode.AppModeActivity;
import com.subhub.app.atmosphere.AtmosphereActivity;
import com.subhub.app.penance.PenanceActivity;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.settings.GlobalSettingsActivity;
import com.subhub.app.settings.SettingsActivity;

import java.util.ArrayList;
import java.util.List;

/** Role-specific primary destinations; feature editors belong to a person's contracts. */
public final class SubHubNavigation {
    public static final String EXTRA_SECTION_HOME = "subhub.navigation.section_home";
    public enum Screen { HOME, PEOPLE, ACTIVITY, CONTRACT, STATS, CENSOR, LIMITS, MONEY, ATMOSPHERE, SETTINGS }

    private static final AccessibilityDelegateCompat TAB_ACCESSIBILITY =
            new AccessibilityDelegateCompat() {
                @Override public void onInitializeAccessibilityNodeInfo(View host,
                        AccessibilityNodeInfoCompat info) {
                    super.onInitializeAccessibilityNodeInfo(host, info);
                    info.setClassName(Button.class.getName());
                    info.setSelected(host.isSelected());
                }
            };

    private SubHubNavigation() {}

    /** Promotes an existing read-only detail page into a primary role destination. */
    public static void attachToPage(Activity activity, android.widget.LinearLayout content, Screen active) {
        View scroll = (View) content.getParent();
        ((ViewGroup) scroll.getParent()).removeView(scroll);
        android.widget.FrameLayout root = new android.widget.FrameLayout(activity);
        root.setBackgroundResource(R.drawable.bg_main);
        root.addView(scroll, new android.widget.FrameLayout.LayoutParams(-1, -1));
        activity.getLayoutInflater().inflate(R.layout.view_bottom_pill_nav, root, true);
        activity.setContentView(root);
        TextView role = PrimaryHeader.editLockButton(root);
        role.setVisibility(View.VISIBLE);
        role.setOnClickListener(view -> {
            if (ControllerPinManager.isDomModeActive()) {
                com.subhub.app.security.ControllerEditMode.enterSubMode(activity);
            } else com.subhub.app.security.ControllerPinGate.unlock(activity, () -> {
                activity.startActivity(new Intent(activity, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
                activity.finish();
            }, false);
        });
        bind(activity, root, active);
    }

    public static void bind(Activity activity, View root, Screen active) {
        View navigation = root.findViewById(R.id.bottom_navigation);
        boolean domMode = com.subhub.app.security.DeviceRolePreferences.supportsDom(activity)
                && com.subhub.app.security.DeviceRolePreferences.isDomWorkspace(activity);
        setVisible(navigation, true);
        setVisible(root.findViewById(R.id.nav_home), true);
        setVisible(root.findViewById(R.id.nav_censor), false);
        setVisible(root.findViewById(R.id.nav_limits), false);
        setVisible(root.findViewById(R.id.nav_money), false);
        setVisible(root.findViewById(R.id.nav_atmosphere), false);
        setVisible(root.findViewById(R.id.nav_people), domMode);
        setVisible(root.findViewById(R.id.nav_activity), domMode);
        setVisible(root.findViewById(R.id.nav_contract), !domMode);
        setVisible(root.findViewById(R.id.nav_stats), !domMode);
        setVisible(root.findViewById(R.id.nav_settings), true);
        Screen selected = active;
        if (active == Screen.ACTIVITY && !domMode) selected = Screen.HOME;
        if (active == Screen.CENSOR || active == Screen.LIMITS || active == Screen.MONEY
                || active == Screen.ATMOSPHERE || domMode && (active == Screen.STATS || active == Screen.CONTRACT)) {
            selected = domMode ? Screen.PEOPLE : Screen.CONTRACT;
        }
        ((TextView) root.findViewById(R.id.nav_home_label)).setText(R.string.role_overview);
        bindTab(activity, root.findViewById(R.id.nav_home),
                root.findViewById(R.id.nav_home_icon), root.findViewById(R.id.nav_home_label),
                selected, Screen.HOME, MainActivity.class);
        bindTab(activity, root.findViewById(R.id.nav_people),
                root.findViewById(R.id.nav_people_icon), root.findViewById(R.id.nav_people_label),
                selected, Screen.PEOPLE, com.subhub.app.people.PeopleActivity.class);
        bindTab(activity, root.findViewById(R.id.nav_activity),
                root.findViewById(R.id.nav_activity_icon), root.findViewById(R.id.nav_activity_label),
                selected, Screen.ACTIVITY, com.subhub.app.activity.DomActivityActivity.class);
        bindTab(activity, root.findViewById(R.id.nav_contract),
                root.findViewById(R.id.nav_contract_icon), root.findViewById(R.id.nav_contract_label),
                selected, Screen.CONTRACT, com.subhub.app.people.ContractActivity.class);
        bindTab(activity, root.findViewById(R.id.nav_stats),
                root.findViewById(R.id.nav_stats_icon), root.findViewById(R.id.nav_stats_label),
                selected, Screen.STATS, com.subhub.app.stats.StatsActivity.class);
        bindTab(activity, root.findViewById(R.id.nav_settings),
                root.findViewById(R.id.nav_settings_icon), root.findViewById(R.id.nav_settings_label),
                selected, Screen.SETTINGS, GlobalSettingsActivity.class);
        bindNavigationLayout(navigation);
        bindActivityUnread(activity, root, domMode && ControllerPinManager.isDomModeActive());
    }

    private static void bindActivityUnread(Activity activity, View root, boolean domMode) {
        View tab = root.findViewById(R.id.nav_activity);
        if (tab == null || !(activity instanceof androidx.lifecycle.LifecycleOwner)) return;
        ActivityUnread binding = (ActivityUnread) tab.getTag(R.id.nav_activity);
        if (binding == null) {
            binding = new ActivityUnread((androidx.lifecycle.LifecycleOwner) activity, tab);
            tab.setTag(R.id.nav_activity, binding);
        }
        binding.bind(domMode);
    }

    /** Event-driven reads belong to the attached page, never the Home refresh timer. */
    private static final class ActivityUnread implements androidx.lifecycle.DefaultLifecycleObserver,
            View.OnAttachStateChangeListener {
        private final androidx.lifecycle.LifecycleOwner owner;
        private final View tab;
        private final AsyncUiScope data;
        private final String job;
        private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
        private AutoCloseable observation;
        private boolean dom, started, dirty, closed;
        private long unread = -1;

        ActivityUnread(androidx.lifecycle.LifecycleOwner owner, View tab) {
            this.owner = owner;
            this.tab = tab;
            data = AsyncUiScope.forPage(owner);
            job = "navigation-activity-unread:" + System.identityHashCode(tab);
            tab.addOnAttachStateChangeListener(this);
            owner.getLifecycle().addObserver(this);
        }

        void bind(boolean domMode) {
            dom = domMode;
            render();
            if (dom) start(); else stop();
        }

        private void start() {
            if (closed || started || !allowed() || !tab.isAttachedToWindow()
                    || !owner.getLifecycle().getCurrentState().isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) return;
            started = true;
            observation = com.subhub.app.activity.ActivitySources.observe(() -> main.post(this::reload));
            reload();
        }

        private void stop() {
            started = false;
            dirty = false;
            data.cancel(job);
            if (observation != null) {
                try { observation.close(); } catch (Exception ignored) { }
                observation = null;
            }
        }

        private void reload() {
            if (!started || closed || !allowed()) return;
            if (data.isPending(job)) { dirty = true; return; }
            dirty = false;
            android.content.Context application = tab.getContext().getApplicationContext();
            data.load(job, () -> {
                if (!com.subhub.app.security.DeviceRolePreferences.supportsDom(application)
                        || !ControllerPinManager.isDomModeActive()) return 0;
                return com.subhub.app.activity.ActivitySources.repository().unreadCount();
            }, count -> {
                if (!started || closed) return;
                unread = Math.max(0, count.longValue());
                render();
                if (dirty) reload();
            }, failure -> {
                unread = -1;
                render();
                if (dirty) reload();
            });
        }

        private void render() {
            boolean show = allowed() && unread > 0;
            View indicator = tab.findViewById(R.id.nav_activity_unread);
            indicator.setVisibility(show ? View.VISIBLE : View.GONE);
            String label = tab.getResources().getString(R.string.dom_activity_title);
            CharSequence description = show ? label + ", "
                    + tab.getResources().getString(R.string.dom_activity_unread_count, unread) : label;
            tab.setContentDescription(description);
            androidx.appcompat.widget.TooltipCompat.setTooltipText(tab, description);
        }

        private boolean allowed() {
            return dom && ControllerPinManager.isDomModeActive()
                    && com.subhub.app.security.DeviceRolePreferences.supportsDom(tab.getContext());
        }

        @Override public void onStart(androidx.lifecycle.LifecycleOwner owner) { render(); start(); }
        @Override public void onStop(androidx.lifecycle.LifecycleOwner owner) { stop(); }
        @Override public void onDestroy(androidx.lifecycle.LifecycleOwner owner) {
            closed = true;
            stop();
            main.removeCallbacksAndMessages(null);
            tab.removeOnAttachStateChangeListener(this);
            owner.getLifecycle().removeObserver(this);
        }
        @Override public void onViewAttachedToWindow(View view) { start(); }
        @Override public void onViewDetachedFromWindow(View view) { stop(); }
    }

    private static void bindNavigationLayout(View navigation) {
        if (navigation == null) return;
        // Bind once: Home refreshes its state repeatedly while a service is running.
        if (navigation.getTag(R.id.bottom_navigation) == null) {
            navigation.setTag(R.id.bottom_navigation, Boolean.TRUE);
            navigation.addOnLayoutChangeListener((view, left, top, right, bottom,
                    oldLeft, oldTop, oldRight, oldBottom) -> updateNavigationLayout(view));
        }
        updateNavigationLayout(navigation);
    }

    private static void updateNavigationLayout(View navigation) {
        if (!(navigation instanceof GridLayout)
                || !(navigation.getParent() instanceof ViewGroup)) return;
        GridLayout tabs = (GridLayout) navigation;
        ViewGroup page = (ViewGroup) navigation.getParent();
        List<View> visibleTabs = new ArrayList<>();
        for (int index = 0; index < tabs.getChildCount(); index++) {
            View tab = tabs.getChildAt(index);
            if (tab.getVisibility() == View.VISIBLE) visibleTabs.add(tab);
        }
        if (visibleTabs.isEmpty()) return;
        int pageWidth = page.getWidth() > 0 ? page.getWidth()
                : navigation.getResources().getDisplayMetrics().widthPixels;
        int minimumTarget = navigation.getResources()
                .getDimensionPixelSize(R.dimen.control_min_height);
        int horizontalPadding = navigation.getPaddingLeft() + navigation.getPaddingRight();
        int contentWidth = Math.max(0, pageWidth - horizontalPadding);
        int[] naturalWidths = new int[visibleTabs.size()];
        for (int index = 0; index < visibleTabs.size(); index++) {
            ViewGroup tab = (ViewGroup) visibleTabs.get(index);
            TextView label = (TextView) tab.getChildAt(1);
            naturalWidths[index] = Math.max(minimumTarget, (int) Math.ceil(android.text.Layout.getDesiredWidth(
                    label.getText(), label.getPaint())) + label.getCompoundPaddingLeft()
                    + label.getCompoundPaddingRight() + tab.getPaddingLeft() + tab.getPaddingRight());
        }
        // Keep one rail while the actual scaled labels fit. Additional balanced rows
        // preserve complete words and touch targets when the viewport cannot contain it.
        int columns = visibleTabs.size();
        int[] columnWidths = null;
        int naturalWidth = 0;
        for (int candidateRows = 1; candidateRows <= visibleTabs.size(); candidateRows++) {
            columns = (visibleTabs.size() + candidateRows - 1) / candidateRows;
            columnWidths = new int[columns];
            for (int index = 0; index < naturalWidths.length; index++)
                columnWidths[index % columns] = Math.max(columnWidths[index % columns], naturalWidths[index]);
            naturalWidth = 0;
            for (int width : columnWidths) naturalWidth += width;
            if (naturalWidth <= contentWidth || columns == 1) break;
        }
        int rows = (visibleTabs.size() + columns - 1) / columns;
        if (tabs.getColumnCount() < columns) tabs.setColumnCount(columns);
        if (tabs.getRowCount() < rows) tabs.setRowCount(rows);
        ViewGroup.MarginLayoutParams params =
                (ViewGroup.MarginLayoutParams) navigation.getLayoutParams();
        if (params.leftMargin != 0 || params.rightMargin != 0 || params.bottomMargin != 0) {
            params.leftMargin = 0;
            params.rightMargin = 0;
            params.bottomMargin = 0;
            navigation.setLayoutParams(params);
        }
        int extra = contentWidth - naturalWidth;
        for (int column = 0; column < columns; column++) columnWidths[column] = extra >= 0
                ? columnWidths[column] + extra / columns + (column < extra % columns ? 1 : 0)
                : contentWidth / columns;
        int visibleIndex = 0;
        for (int index = 0; index < tabs.getChildCount(); index++) {
            View tab = tabs.getChildAt(index);
            boolean visible = tab.getVisibility() == View.VISIBLE;
            int column = visible ? visibleIndex % columns : 0;
            int row = visible ? visibleIndex / columns : 0;
            int width = visible ? columnWidths[column] : 0;
            GridLayout.LayoutParams tabParams = (GridLayout.LayoutParams) tab.getLayoutParams();
            GridLayout.Spec rowSpec = GridLayout.spec(row, GridLayout.FILL);
            GridLayout.Spec columnSpec = GridLayout.spec(column, GridLayout.FILL);
            if (tabParams.width != width || !tabParams.rowSpec.equals(rowSpec)
                    || !tabParams.columnSpec.equals(columnSpec)) {
                tabParams.width = width;
                tabParams.rowSpec = rowSpec;
                tabParams.columnSpec = columnSpec;
                tab.setLayoutParams(tabParams);
            }
            if (visible) visibleIndex++;
        }
        if (tabs.getColumnCount() != columns) tabs.setColumnCount(columns);
        if (tabs.getRowCount() != rows) tabs.setRowCount(rows);

        // Reserve the measured rail, including labels enlarged by the system font setting.
        // Padding belongs to the scroll content so the final control can scroll above it.
        int pillHeight = Math.max(navigation.getHeight(), navigation.getResources()
                .getDimensionPixelSize(R.dimen.bottom_nav_height));
        int clearance = pillHeight + params.bottomMargin + navigation.getResources()
                .getDimensionPixelSize(R.dimen.page_content_tail_gap);
        for (int index = 0; index < page.getChildCount(); index++) {
            View child = page.getChildAt(index);
            if (!(child instanceof ScrollView)) continue;
            ScrollView scroll = (ScrollView) child;
            if (scroll.getChildCount() == 0) continue;
            if (scroll.getPaddingBottom() != 0) {
                scroll.setPaddingRelative(scroll.getPaddingStart(), scroll.getPaddingTop(),
                        scroll.getPaddingEnd(), 0);
            }
            View content = scroll.getChildAt(0);
            if (content.getPaddingBottom() != clearance) {
                content.setPaddingRelative(content.getPaddingStart(), content.getPaddingTop(),
                        content.getPaddingEnd(), clearance);
            }
        }
        View activityList = page.findViewById(R.id.dom_activity_list);
        if (activityList instanceof android.widget.ListView && activityList.getPaddingBottom() != clearance)
            activityList.setPaddingRelative(activityList.getPaddingStart(), activityList.getPaddingTop(),
                    activityList.getPaddingEnd(), clearance);
    }

    private static void setVisible(View view, boolean visible) {
        if (view != null) view.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    private static void bindTab(Activity activity, View tab, ImageView icon, TextView label,
            Screen active, Screen destination, Class<? extends Activity> target) {
        if (tab == null) return;
        if (tab.getVisibility() != View.VISIBLE) return;
        boolean selected = destination == active;
        tab.setVisibility(View.VISIBLE);
        tab.setSelected(selected);
        tab.setAlpha(selected ? 1f : 0.82f);
        ViewCompat.setScreenReaderFocusable(tab, true);
        ViewCompat.setAccessibilityDelegate(tab, TAB_ACCESSIBILITY);
        if (label != null) label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        if (icon != null) icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        if (label != null) label.setTextColor(activity.getColor(selected
                ? R.color.accent_text : R.color.text_secondary));
        if (icon != null) ImageViewCompat.setImageTintList(icon, ColorStateList.valueOf(
                activity.getColor(selected ? R.color.accent_text : R.color.text_secondary)));
        tab.setBackgroundResource(R.drawable.bg_shell_tab);
        if (label != null) {
            label.setSingleLine(false);
            label.setEllipsize(null);
            label.setBreakStrategy(android.graphics.text.LineBreaker.BREAK_STRATEGY_HIGH_QUALITY);
            tab.setContentDescription(label.getText());
            androidx.appcompat.widget.TooltipCompat.setTooltipText(tab, label.getText());
        }
        tab.setOnClickListener(view -> {
            if (destination == Screen.ACTIVITY && (!ControllerPinManager.isDomModeActive()
                    || !com.subhub.app.security.DeviceRolePreferences.supportsDom(activity))) return;
            if (selected && target.isInstance(activity)) return;
            if (selected && activity instanceof PenanceActivity) {
                ((PenanceActivity) activity).showSectionHome();
                return;
            }
            activity.startActivity(new Intent(activity, target)
                    .putExtra(EXTRA_SECTION_HOME, selected)
                    .addFlags(selected ? Intent.FLAG_ACTIVITY_CLEAR_TOP
                            : Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
            boolean movingForward = destination.ordinal() > active.ordinal();
            activity.overridePendingTransition(
                    movingForward ? R.anim.subhub_page_enter : R.anim.subhub_page_pop_enter,
                    movingForward ? R.anim.subhub_page_exit : R.anim.subhub_page_pop_exit);
        });
    }
}
