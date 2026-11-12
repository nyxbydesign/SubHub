package com.subhub.app.fullaccess;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.format.DateUtils;
import android.text.format.Formatter;
import android.util.Base64;
import android.util.LruCache;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import com.subhub.app.R;
import com.subhub.app.people.PersonId;
import com.subhub.app.people.PersonUi;
import com.subhub.app.remote.FullAccessChannel;
import com.subhub.app.remote.RemoteFeature;
import com.subhub.app.remote.RemotePersonRepository;
import com.subhub.app.remote.RemoteProtocol;
import com.subhub.app.remote.RemoteStore;
import com.subhub.app.util.AsyncUiScope;
import com.subhub.app.util.PageScrollView;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** A read-only overview and conversation view bound to the current viewer's authority. */
final class FullAccessViewer implements AutoCloseable {
    private static final List<String> SECTIONS = List.of("contacts", "sms", "notifications", "apps", "files", "screen");
    private static final int MAX_IMAGE_BYTES = 1024 * 1024;
    private static final long REFRESH_MILLIS = 10_000;
    private static final long FRAME_MILLIS = 500;
    private static final long SESSION_CHECK_MILLIS = 1_000;
    private static final long OPEN_MILLIS = 18_000;
    private static final int VISIBLE_DETAILS_PER_REQUEST = 6;
    private static final int VISIBLE_DETAILS_NODE_LENGTH = 512;

    private final AppCompatActivity activity;
    private final Context app;
    private final PersonId target;
    private final DemoFullAccess demo;
    private final DemoFullAccessActivity demoActivity;
    private final BooleanSupplier allowed;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AsyncUiScope worker;
    private final LinearLayout overview;
    private final LinearLayout conversationPanel;
    private final TextView pageTitle;
    private final View pageBack;
    private final PageScrollView scroll;
    private final OnBackPressedCallback pageBackCallback;
    private final Map<String, Section> sections = new LinkedHashMap<>();
    private final ArrayDeque<PageRequest> queuedPages = new ArrayDeque<>();
    private Section activeLive;
    private Conversation conversation;
    private String selectedScope;
    private long routeGeneration;
    private int landingScroll, detailScroll, conversationScroll;
    private boolean detailScrollPending;
    private boolean pageInFlight;
    private boolean resumed;
    private final Runnable guard = new Runnable() {
        @Override public void run() {
            if (!resumed) return;
            if (!allowed.getAsBoolean()) {
                pause();
                activity.finish();
                return;
            }
            Section session = activeLive;
            if (session != null && session.connected && (session.channel != null && !session.channel.isOpen()
                    || session.localFiles != null && !session.localFiles.isStorageGrantCurrent()))
                stopWith(session, R.string.full_access_unavailable);
            main.postDelayed(this, SESSION_CHECK_MILLIS);
        }
    };

    FullAccessViewer(AppCompatActivity activity, LinearLayout parent, PersonId target, BooleanSupplier allowed) {
        this(activity, parent, target, allowed, null, null);
    }

    FullAccessViewer(DemoFullAccessActivity activity, LinearLayout parent, DemoFullAccess demo) {
        this(activity, parent, null, activity::viewerCurrent, demo, activity);
    }

    private FullAccessViewer(AppCompatActivity activity, LinearLayout parent, PersonId target, BooleanSupplier allowed,
            DemoFullAccess demo, DemoFullAccessActivity demoActivity) {
        this.activity = activity;
        this.app = activity.getApplicationContext();
        this.target = target;
        this.demo = demo;
        this.demoActivity = demoActivity;
        this.allowed = allowed;
        worker = AsyncUiScope.forPage(activity);

        overview = PersonUi.column(activity);
        parent.addView(overview, new LinearLayout.LayoutParams(-1, -2));
        conversationPanel = PersonUi.column(activity);
        conversationPanel.setTag("blackmail_thread");
        conversationPanel.setVisibility(View.GONE);
        parent.addView(conversationPanel, new LinearLayout.LayoutParams(-1, -2));
        pageTitle = activity.findViewById(R.id.primary_header_title);
        pageBack = activity.findViewById(R.id.button_back);
        scroll = activity.findViewById(R.id.role_page_scroll);
        pageBackCallback = new OnBackPressedCallback(false) {
            @Override public void handleOnBackPressed() {
                if (conversation != null) closeConversation(true);
                else selectScope(null, false);
            }
        };
        activity.getOnBackPressedDispatcher().addCallback(activity, pageBackCallback);
        if (pageBack != null) pageBack.setOnClickListener(view -> activity.getOnBackPressedDispatcher().onBackPressed());

        for (int start = 0; start < SECTIONS.size(); start += 2) {
            LinearLayout row = BlackmailViews.entryRow(overview);
            for (String scope : SECTIONS.subList(start, Math.min(start + 2, SECTIONS.size()))) {
                BlackmailViews.entry(row, displayLabel(scope), icon(scope), scope, () -> selectScope(scope, true));
                LinearLayout panel = PersonUi.card(parent);
                panel.setTag("blackmail_page:" + scope);
                panel.setVisibility(View.GONE);
                sections.put(scope, new Section(scope, panel));
            }
        }
    }

    private static int displayLabel(String scope) {
        return switch (scope) {
            case "sms" -> R.string.blackmail_messages;
            case "screen" -> R.string.blackmail_share_screen;
            default -> label(scope);
        };
    }

    private static int icon(String scope) {
        return switch (scope) {
            case "contacts" -> R.drawable.ic_nav_people;
            case "notifications" -> R.drawable.ic_notification_subhub;
            case "sms" -> android.R.drawable.ic_dialog_email;
            case "apps" -> R.drawable.ic_settings_apps;
            case "files" -> R.drawable.ic_journal_file;
            case "screen" -> android.R.drawable.ic_menu_view;
            default -> throw new IllegalArgumentException("Unknown Full Access view");
        };
    }

    static int label(String scope) {
        return switch (scope) {
            case "contacts" -> R.string.full_access_contacts;
            case "notifications" -> R.string.full_access_notifications;
            case "sms" -> R.string.full_access_sms;
            case "apps" -> R.string.full_access_apps;
            case "files" -> R.string.full_access_files;
            case "screen" -> R.string.full_access_screen;
            default -> throw new IllegalArgumentException("Unknown Full Access view");
        };
    }

    String selectedScope() { return selectedScope; }

    long screenSelection() { return "screen".equals(selectedScope) ? routeGeneration : 0; }

    int navigationScroll() {
        if (!resumed || scroll == null || conversation != null || detailScrollPending)
            return selectedScope == null ? landingScroll : detailScroll;
        Section selected = sections.get(selectedScope);
        if (selected != null && selected.pages.list != null)
            return selected.pages.list.getFirstVisiblePosition();
        return scroll.position();
    }

    void restoreNavigation(String scope, int position) {
        if (resumed) return;
        selectedScope = scope != null && SECTIONS.contains(scope) ? scope : null;
        ++routeGeneration;
        if (selectedScope == null) landingScroll = Math.max(0, position);
        else detailScroll = Math.max(0, position);
        showRoute();
    }

    void resume() {
        if (resumed) return;
        resumed = true;
        showRoute();
        loadSelected(false);
        main.postDelayed(guard, SESSION_CHECK_MILLIS);
    }

    void pause() {
        rememberScroll();
        resumed = false;
        closeConversation(false);
        retireSections();
        showRoute();
    }

    private void retireSections() {
        main.removeCallbacksAndMessages(null);
        for (Map.Entry<String, Section> entry : sections.entrySet()) {
            Section section = entry.getValue();
            section.retire();
            section.panel.removeAllViews();
            entry.setValue(new Section(section.scope, section.panel));
        }
        activeLive = null;
        worker.cancelPrefix("full-access-view:");
        queuedPages.clear();
        pageInFlight = false;
    }

    private void rememberScroll() {
        if (!resumed || conversation != null || scroll == null) return;
        if (selectedScope == null) landingScroll = scroll.position();
        else if (!detailScrollPending) detailScroll = navigationScroll();
    }

    private void selectScope(String scope, boolean entered) {
        if (!resumed || !allowed.getAsBoolean()) return;
        rememberScroll();
        retireSections();
        selectedScope = scope;
        ++routeGeneration;
        detailScroll = 0;
        detailScrollPending = false;
        showRoute();
        loadSelected(entered);
        main.postDelayed(guard, SESSION_CHECK_MILLIS);
    }

    private void showRoute() {
        overview.setVisibility(selectedScope == null ? View.VISIBLE : View.GONE);
        for (Section section : sections.values())
            section.panel.setVisibility(section.scope.equals(selectedScope) ? View.VISIBLE : View.GONE);
        pageBackCallback.setEnabled(selectedScope != null);
        if (pageTitle != null)
            pageTitle.setText(selectedScope == null ? R.string.full_access_title : displayLabel(selectedScope));
        if (scroll != null) scroll.restorePosition(selectedScope == null ? landingScroll : 0);
    }

    private void loadSelected(boolean entered) {
        if (selectedScope == null || !allowed.getAsBoolean()) return;
        Section section = sections.get(selectedScope);
        detailScrollPending = detailScroll > 0 && !"screen".equals(selectedScope);
        if ("screen".equals(selectedScope) && !entered) screenRequestEnded();
        else if (demo == null || persistent(section)) openLive(section);
        else {
            showStatus(section, R.string.full_access_waiting, false);
            loadPage(section, "");
        }
    }

    @Override public void close() {
        pause();
        pageBackCallback.remove();
        if (pageBack != null) pageBack.setOnClickListener(view -> activity.finish());
    }

    private boolean current(Section session) {
        return session.scope.equals(selectedScope) && sections.get(session.scope) == session
                && session.live.get() && resumed && allowed.getAsBoolean();
    }

    private static boolean persistent(Section session) {
        return "files".equals(session.scope) || "screen".equals(session.scope);
    }

    private static boolean continuous(Section session) {
        return switch (session.scope) {
            case "contacts", "apps", "sms", "notifications" -> true;
            default -> false;
        };
    }

    private String workKey(Section session, String operation) {
        return "full-access-view:" + session.scope + ":" + operation;
    }

    void screenRequestEnded() {
        Section section = sections.get("screen");
        if (!current(section)) return;
        section.panel.removeAllViews();
        BlackmailViews.action(section.panel, R.string.blackmail_share_screen, () -> {
            if (current(section)) openLive(section);
        }, true);
    }

    private void openLive(Section previous) {
        if (activeLive != null) retireLive(activeLive);
        Section session = sections.get(previous.scope);
        activeLive = session;
        showStatus(session, R.string.full_access_waiting, false);
        if (demo == null) openChannel(session);
        else if ("files".equals(session.scope)) {
            session.localFiles = demo.openFiles(session.live::get);
            loadPage(session, "");
        } else {
            demoActivity.requestScreen();
        }
    }

    private void retireLive(Section session) {
        session.retire();
        worker.cancelPrefix("full-access-view:" + session.scope + ":");
        if (continuous(session)) {
            queuedPages.clear();
            pageInFlight = false;
        }
        Section closed = new Section(session.scope, session.panel);
        sections.put(session.scope, closed);
        if (activeLive == session) activeLive = null;
    }

    void showLocalScreen(Intent approval) {
        Section previous = sections.get("screen");
        if (demo == null || !current(previous)) return;
        if (activeLive != null) retireLive(activeLive);
        Section session = sections.get("screen");
        activeLive = session;
        session.localScreen = FullAccessScreen.startLocal(activity, approval,
                () -> session.live.get() && demo.current());
        showStatus(session, R.string.full_access_waiting, false);
        pollScreen(session);
    }

    private void openChannel(Section session) {
        if (continuous(session)) main.postDelayed(() -> {
            if (current(session) && session.channel == null) stopWith(session, R.string.full_access_unavailable);
        }, OPEN_MILLIS);
        Context context = app;
        PersonId requested = target;
        Handler delivery = main;
        worker.load(workKey(session, "connect"), () -> resolve(context, requested), peer -> {
            if (!current(session)) return;
            CompletableFuture<FullAccessChannel> opening = RemoteFeature.get(context).openFullAccess(peer, session.scope);
            session.opening = opening;
            opening.whenComplete((channel, failure) -> {
                if (!session.attach(channel)) return;
                delivery.post(() -> {
                    if (!current(session)) { session.retire(); return; }
                    if (failure != null || channel == null) {
                        stopWith(session, R.string.full_access_unavailable);
                    } else if ("screen".equals(session.scope)) {
                        pollScreen(session);
                    } else {
                        if (continuous(session)) showStatus(session, R.string.blackmail_loading_entries, false);
                        loadPage(session, "");
                    }
                });
            });
        }, failure -> { if (current(session)) stopWith(session, R.string.full_access_unavailable); });
    }

    private void loadPage(Section session, String requestedCursor) {
        if (!current(session) || session.working || conversation != null) return;
        session.working = true;
        if (persistent(session)) requestPage(session, requestedCursor, () -> {});
        else {
            queuedPages.addLast(new PageRequest(session, done -> requestPage(session, requestedCursor, done)));
            requestNextPage();
        }
    }

    /** Avoid competing data queries against one peer. File and screen sessions have their own lifetimes. */
    private void requestNextPage() {
        if (pageInFlight || !resumed) return;
        while (!queuedPages.isEmpty()) {
            PageRequest request = queuedPages.removeFirst();
            if (!current(request.section)) continue;
            pageInFlight = true;
            request.start.accept(() -> {
                pageInFlight = false;
                requestNextPage();
            });
            return;
        }
    }

    private void requestPage(Section session, String requestedCursor, Runnable complete) {
        String node = session.folders.isEmpty() ? "" : session.folders.peek().id;
        String operation = switch (session.scope) {
            case "files" -> "list";
            case "sms" -> session.legacyRows ? "page" : "conversations";
            case "notifications" -> session.legacyRows ? "page" : "groups";
            case "contacts" -> session.channel != null && session.channel.supportsLazyContactPhotos()
                    ? "contact_rows" : "page";
            case "apps" -> session.channel != null && session.channel.supportsLazyAppUsage()
                    ? "app_rows" : "page";
            default -> "page";
        };
        read(session, operation, node, requestedCursor, page -> {
            if (current(session)) {
                session.working = false;
                session.connected = true;
                String state = page.optString("status", "unavailable");
                if (continuous(session)) {
                    receiveList(session, requestedCursor, page, state);
                    return;
                }
                if ("ok".equals(state) || "ready".equals(state)) {
                    session.cursor = requestedCursor;
                    if (session.lastPage == null || !session.lastPage.toString().equals(page.toString())) {
                        session.lastPage = page;
                        renderPage(session, page);
                    }
                    if (detailScrollPending && scroll != null) {
                        detailScrollPending = false;
                        scroll.restorePosition(detailScroll);
                    }
                } else if ("files".equals(session.scope) && "unavailable".equals(state) && !session.folders.isEmpty()) {
                    showFolderFailure(session);
                } else {
                    session.lastPage = null;
                    stopWith(session, statusLabel(session, state));
                }
            }
        }, () -> {
            if (current(session)) {
                session.working = false;
                session.lastPage = null;
                if (continuous(session)) failList(session, requestedCursor, R.string.full_access_unavailable);
                else stopWith(session, R.string.full_access_unavailable);
            }
        }, complete);
    }

    private void receiveList(Section session, String cursor, JSONObject page, String status) {
        if (!"ok".equals(status) && !"ready".equals(status)) {
            failList(session, cursor, statusLabel(status));
            return;
        }
        session.legacyRows |= page.optBoolean("legacyRows");
        boolean usageMissing = "apps".equals(session.scope) && !page.optBoolean("usageAvailable");
        Continuous pages = session.pages;
        int previousPosition = pages.list == null ? 0 : pages.list.getFirstVisiblePosition();
        boolean changed = retireObsoleteNotificationPreviews(session, page);
        if (changed && !cursor.isEmpty()) {
            showList(session);
            loadPage(session, "");
            return;
        }
        changed |= pages.accept(page, cursor, false) || pages.usageMissing != usageMissing;
        pages.usageMissing = usageMissing;
        long since = page.optLong("since");
        changed |= pages.since != since;
        pages.since = since;
        if (session.group != null && !pages.rows.containsKey(session.group.id)) session.group = null;
        if (cursor.isEmpty() && previousPosition > pages.rows.size() && pages.canAdvance()) {
            detailScroll = previousPosition;
            detailScrollPending = true;
        }
        if (changed || pages.list == null) showList(session);
        main.removeCallbacks(session.refresh);
        main.postDelayed(session.refresh, REFRESH_MILLIS);
    }

    private boolean retireObsoleteNotificationPreviews(Section session, JSONObject page) {
        if (!"notifications".equals(session.scope)) return false;
        String revision = page.optString("notificationRevision");
        if (revision.equals(session.notificationRevision)) return false;
        boolean hadRows = session.pages.hasHead;
        session.notificationRevision = revision;
        session.pages.clearData();
        if (session.group != null) {
            session.group.pages.clear();
            session.group = null;
        }
        return hadRows;
    }

    private void failList(Section session, String cursor, int label) {
        session.pages.fail(cursor, label);
        if (!session.pages.hasHead) session.group = null;
        showList(session);
        if (label == R.string.blackmail_refresh_unavailable && session.pages.hasHead) {
            main.removeCallbacks(session.refresh);
            main.postDelayed(session.refresh, REFRESH_MILLIS);
        }
    }

    private void showList(Section session) {
        Continuous pages = session.pages;
        if (pages.list == null) {
            session.panel.removeAllViews();
            pages.list = new BlackmailList(session.panel, scroll, session.scope, false, () -> {
                if (!current(session) || conversation != null) return;
                NotificationGroup group = session.group;
                if (group != null && group.pages.canAdvance() && pages.list.near(group.edgeKey))
                    loadGroup(session, group, group.pages.next);
                else if (pages.canAdvance() && pages.list.nearEdge()) loadPage(session, pages.next);
                else loadVisibleDetails(session);
            });
        }
        List<BlackmailList.Row> entries = new ArrayList<>();
        if (pages.since > 0) entries.add(new BlackmailList.Row("since", (row, position) ->
                PersonUi.text(row, activity.getString(R.string.full_access_notifications_start, date(pages.since)), false)));
        if (pages.usageMissing) entries.add(note("usage", R.string.full_access_usage_unavailable));
        if (session.legacyRows) entries.add(note("legacy", R.string.blackmail_detail_update));
        for (JSONObject item : pages.rows.values()) {
            entries.add(new BlackmailList.Row("item:" + item.optString("id"), (row, position) -> {
                if (position > 0) BlackmailViews.separator(row);
                dataRow(row, session, item);
            }));
            if (session.group != null && session.group.id.equals(item.optString("id")))
                addGroupRows(entries, session, session.group);
        }
        addListStatus(entries, pages, () -> loadPage(session, pages.failedCursor), "list:");
        pages.list.show(entries, false, false);
        if (detailScrollPending) {
            pages.list.setSelection(Math.min(detailScroll, entries.size() - 1));
            if (detailScroll < entries.size() || !pages.canAdvance()) detailScrollPending = false;
        }
    }

    private BlackmailList.Row note(String key, int label) {
        return new BlackmailList.Row(key, (row, position) -> PersonUi.text(row, activity.getText(label), false));
    }

    private void addListStatus(List<BlackmailList.Row> entries, Continuous pages, Runnable retry, String prefix) {
        if (pages.error != 0) {
            entries.add(new BlackmailList.Row(prefix + "error", (row, position) -> {
                int message = pages.error == R.string.blackmail_refresh_unavailable && pages.rows.isEmpty()
                        ? R.string.full_access_unavailable : pages.error;
                PersonUi.text(row, activity.getText(message), false);
                if (pages.error == R.string.full_access_unavailable || pages.error == R.string.full_access_busy
                        || pages.error == R.string.blackmail_refresh_unavailable)
                    BlackmailViews.action(row, R.string.role_retry, retry, false);
            }));
        } else if (pages.partial) {
            entries.add(note(prefix + "partial", R.string.blackmail_messages_partial));
        } else if (pages.repeated) {
            entries.add(note(prefix + "incomplete", R.string.blackmail_list_incomplete));
        } else if (pages.rows.isEmpty()) {
            entries.add(note(prefix + "empty", pages.next.isEmpty() ? R.string.full_access_empty : R.string.full_access_waiting));
        }
    }

    private void loadVisibleDetails(Section session) {
        FullAccessChannel channel = session.channel;
        if (!current(session) || session.working || channel == null || session.pages.list == null
                || session.scopeRevision.isEmpty() || session.pages.error != 0) return;
        boolean photos = "contacts".equals(session.scope) && channel.supportsLazyContactPhotos();
        boolean usage = "apps".equals(session.scope) && channel.supportsLazyAppUsage() && !session.pages.usageMissing;
        if (!photos && !usage) return;
        List<String> ids = new ArrayList<>();
        int nodeLength = 0;
        for (String id : session.pages.list.visibleItemIds()) {
            JSONObject row = session.pages.rows.get(id);
            if (row == null || session.rowDetails.get(id) != null
                    || session.missingDetails.get(id) != null) continue;
            boolean valid = photos ? row.optBoolean("hasPhoto") && id.matches("[0-9]{1,19}")
                    : id.length() <= 255 && id.matches("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)*");
            int nextLength = nodeLength + id.length() + (ids.isEmpty() ? 0 : 1);
            if (!valid || nextLength > VISIBLE_DETAILS_NODE_LENGTH) continue;
            ids.add(id);
            nodeLength = nextLength;
            if (ids.size() == VISIBLE_DETAILS_PER_REQUEST) break;
        }
        if (ids.isEmpty()) return;
        session.working = true;
        queuedPages.addLast(new PageRequest(session, complete ->
                read(session, photos ? "contact_photos" : "app_usage", String.join(",", ids), "", response -> {
                    session.working = false;
                    if (!"ok".equals(response.optString("status"))) return;
                    JSONArray items = response.optJSONArray("items");
                    boolean changed = false;
                    if (items != null) for (int index = 0; index < items.length(); index++) {
                        JSONObject item = items.optJSONObject(index);
                        if (item == null) continue;
                        String id = item.optString("id");
                        if (!ids.contains(id) || !session.pages.rows.containsKey(id)) continue;
                        session.rowDetails.put(id, item);
                        changed = true;
                    }
                    for (String id : ids) {
                        if (session.rowDetails.get(id) == null) session.missingDetails.put(id, true);
                    }
                    if (usage && !response.optBoolean("usageAvailable", true)) {
                        session.pages.usageMissing = true;
                        changed = true;
                    }
                    if (changed) showList(session);
                    else main.post(() -> loadVisibleDetails(session));
                }, () -> session.working = false, complete)));
        requestNextPage();
    }

    private void read(Section session, String operation, String node, String cursor,
            Consumer<JSONObject> received, Runnable failed, Runnable complete) {
        boolean legacy = session.legacyRows;
        FullAccessChannel channel = session.channel;
        worker.load(workKey(session, "query"), () -> {
            if (demo != null) return session.localFiles == null
                    ? demo.page(session.scope, operation, node, cursor) : session.localFiles.list(node, cursor);
            JSONObject page = channel.query(command(operation, node, cursor));
            if (cursor.isEmpty() && ("conversations".equals(operation) || "groups".equals(operation))
                    && "update_required".equals(page.optString("status"))) {
                return channel.query(command("page", "", cursor)).put("legacyRows", true);
            }
            if (legacy && "page".equals(operation)) page.put("legacyRows", true);
            return page;
        }, page -> {
            try {
                if (current(session) && (demo != null || !continuous(session)
                        || acceptRemoteRead(session, channel, operation, cursor, page))) received.accept(page);
            }
            finally { complete.run(); }
        }, failure -> {
            try {
                if (current(session) && demo == null && continuous(session))
                    stopWith(session, R.string.full_access_unavailable);
                else failed.run();
            }
            finally { complete.run(); }
        });
    }

    private boolean acceptRemoteRead(Section session, FullAccessChannel channel, String operation,
            String cursor, JSONObject response) {
        if (session.channel != channel || !channel.isOpen()) {
            stopWith(session, R.string.full_access_unavailable);
            return false;
        }
        String status = response.optString("status");
        String revision = response.optString("scopeRevision");
        if ("temporarily_unavailable".equals(status) && !revision.isEmpty()) {
            if (session.scopeRevision.isEmpty() && !session.pages.hasHead) session.scopeRevision = revision;
            if (revision.equals(session.scopeRevision)) return true;
        }
        if (!"ok".equals(status) && !"ready".equals(status)) {
            int label = "temporarily_unavailable".equals(status)
                    ? R.string.full_access_unavailable : statusLabel(status);
            stopWith(session, label);
            return false;
        }
        boolean changed = session.pages.hasHead && !revision.equals(session.scopeRevision);
        session.scopeRevision = revision;
        if (!changed) return true;

        closeConversation(false);
        session.clearRows();
        if (cursor.isEmpty() && switch (operation) {
            case "page", "contact_rows", "app_rows", "conversations", "groups" -> true;
            default -> false;
        }) return true;

        session.working = false;
        queuedPages.clear();
        session.pages.list = null;
        showStatus(session, R.string.blackmail_loading_entries, false);
        main.post(() -> loadPage(session, ""));
        return false;
    }

    private void renderPage(Section session, JSONObject page) {
        LinearLayout card = session.panel;
        card.removeAllViews();
        session.image = null;
        session.previewPanel = null;
        if (!session.folders.isEmpty()) {
            Folder folder = session.folders.peek();
            PersonUi.text(card, folder.title, true);
            BlackmailViews.action(card, R.string.full_access_folder_up, () -> {
                if (!current(session) || session.working) return;
                session.folders.pop();
                resetDirectory(session);
            }, false);
        }
        JSONArray rows = page.optJSONArray("items");
        int count = rows == null ? 0 : rows.length();
        if (count == 0) PersonUi.text(card, activity.getString(R.string.full_access_empty), false);
        boolean rowAdded = false;
        for (int index = 0; index < Math.min(64, count); index++) {
            JSONObject item = rows.optJSONObject(index);
            if (item == null) continue;
            if (rowAdded) BlackmailViews.separator(card);
            fileRow(card, session, item);
            rowAdded = true;
        }

        String next = page.optString("nextCursor", "");
        if (!session.cursor.isEmpty()) {
            BlackmailViews.action(card, R.string.full_access_first_page, () -> {
                if (current(session) && !session.working) resetDirectory(session);
            }, false);
        }
        if (!next.isEmpty() && !next.equals(session.cursor)) BlackmailViews.action(card, R.string.full_access_more, () -> {
            if (current(session) && !session.working) loadPage(session, next);
        }, false);
        addClose(session);
    }

    private void dataRow(LinearLayout card, Section session, JSONObject item) {
        String title = item.optString("title");
        String id = item.optString("id");
        switch (session.scope) {
            case "contacts" -> {
                JSONObject details = item.optBoolean("hasPhoto") ? session.rowDetails.get(id) : null;
                LinearLayout row = BlackmailViews.row(card, title, item.optString("detail"), 0, true,
                        BlackmailViews.photo((details == null ? item : details).optString("photo")), null);
                row.setTag("blackmail_contact");
            }
            case "notifications" -> {
                if (session.legacyRows) BlackmailViews.notification(card, item, true, null);
                else notificationGroup(card, session, item, id, title);
            }
            case "sms" -> {
                LinearLayout row = BlackmailViews.row(card, title, dated(messageText(item), item.optLong("time")),
                        0, true, null, id.isEmpty() || session.legacyRows ? null
                                : () -> openConversation(session, id, title));
                row.setTag("blackmail_conversation");
            }
            case "apps" -> {
                JSONObject details = session.pages.usageMissing ? null : session.rowDetails.get(id);
                appRow(card, details == null ? item : details, title, id);
            }
            default -> throw new IllegalArgumentException("Unknown data section");
        }
    }

    private void appRow(LinearLayout card, JSONObject item, String title, String id) {
        String detail = item.has("todayMillis") && item.has("weekMillis")
                ? activity.getString(R.string.full_access_usage_values,
                    duration(item.optLong("todayMillis")), duration(item.optLong("weekMillis")))
                : "";
        LinearLayout row = BlackmailViews.row(card, title, detail, R.drawable.ic_settings_apps, false, null, null);
        if (demo != null && !id.isEmpty() && row.getChildAt(0) instanceof FrameLayout icon
                && icon.getChildAt(0) instanceof ImageView image)
            com.subhub.app.util.AppIcons.bind(image, id, R.drawable.ic_settings_apps);
    }

    private String messageText(JSONObject item) {
        String body = item.optString("detail");
        return body.isEmpty() ? activity.getString(R.string.full_access_message_unavailable) : body;
    }

    private String dated(String text, long time) {
        return time <= 0 ? text : text.isEmpty() ? date(time)
                : activity.getString(R.string.full_access_detail_time, text, date(time));
    }

    private void notificationGroup(LinearLayout row, Section session, JSONObject item, String id, String title) {
        int count = Math.max(0, item.optInt("count"));
        String summary = activity.getResources().getQuantityString(R.plurals.blackmail_notification_count, count, count);
        LinearLayout heading = BlackmailViews.row(row, title, dated(summary, item.optLong("time")),
                R.drawable.ic_notification_subhub, false, null,
                id.isEmpty() ? null : () -> toggleGroup(session, id));
        heading.setTag("blackmail_notification_group");
        heading.setSelected(session.group != null && session.group.id.equals(id));
    }

    private void toggleGroup(Section session, String id) {
        if (!current(session) || !session.pages.rows.containsKey(id)) return;
        if (session.group != null) {
            boolean collapse = session.group.id.equals(id);
            session.group.pages.clear();
            session.group = null;
            if (collapse) {
                showList(session);
                return;
            }
        }
        NotificationGroup group = new NotificationGroup(id);
        session.group = group;
        loadGroup(session, group, "");
    }

    private void loadGroup(Section session, NotificationGroup group, String cursor) {
        if (group.loading || !current(session) || session.group != group) return;
        group.loading = true;
        if (!group.pages.hasHead) showList(session);
        queuedPages.addFirst(new PageRequest(session, complete -> {
            if (session.group != group) { complete.run(); return; }
            read(session, "group", group.id, cursor, response -> {
                if (!current(session) || session.group != group) return;
                group.loading = false;
                String status = response.optString("status");
                if ("ok".equals(status) && retireObsoleteNotificationPreviews(session, response)) {
                    showList(session);
                    loadPage(session, "");
                    return;
                }
                if ("ok".equals(status)) group.pages.accept(response, cursor, false);
                else group.pages.fail(cursor, statusLabel(status));
                showList(session);
            }, () -> {
                if (!current(session) || session.group != group) return;
                group.loading = false;
                group.pages.fail(cursor, R.string.full_access_unavailable);
                showList(session);
            }, complete);
        }));
        requestNextPage();
    }

    private void addGroupRows(List<BlackmailList.Row> entries, Section session, NotificationGroup group) {
        Continuous pages = group.pages;
        String prefix = "group:" + group.id + ":";
        group.edgeKey = "item:" + group.id;
        for (JSONObject item : pages.rows.values()) {
            String key = prefix + item.optString("id");
            entries.add(new BlackmailList.Row(key, (row, position) -> {
                row.setTag("blackmail_notification_entry");
                row.setPaddingRelative(Math.round(12 * activity.getResources().getDisplayMetrics().density), 0, 0, 0);
                BlackmailViews.separator(row);
                BlackmailViews.notification(row, item, false, demo == null ? null : group.id);
            }));
            group.edgeKey = key;
        }
        if (group.loading && !pages.hasHead) {
            entries.add(note(prefix + "loading", R.string.full_access_waiting));
        } else {
            addListStatus(entries, pages, () -> loadGroup(session, group, pages.failedCursor), prefix);
        }
    }

    private void openConversation(Section session, String id, String title) {
        if (!current(session) || conversation != null) return;
        detailScroll = navigationScroll();
        conversationScroll = scroll == null ? 0 : scroll.position();
        conversation = new Conversation(id);
        if (pageTitle != null) pageTitle.setText(title);
        session.panel.setVisibility(View.GONE);
        conversationPanel.setVisibility(View.VISIBLE);
        if (scroll != null) scroll.scrollTo(0, 0);
        loadConversation(session, conversation, "");
    }

    private void closeConversation(boolean returnToOverview) {
        if (conversation == null) return;
        if (conversation.refresh != null) main.removeCallbacks(conversation.refresh);
        conversation.pages.clear();
        conversation = null;
        conversationPanel.removeAllViews();
        conversationPanel.setVisibility(View.GONE);
        showRoute();
        if (returnToOverview && resumed) {
            if (scroll != null) scroll.restorePosition(conversationScroll);
            Section section = sections.get(selectedScope);
            if (section.cursor.isEmpty()) loadPage(section, "");
        }
    }

    private void loadConversation(Section session, Conversation thread, String cursor) {
        if (thread.loading || conversation != thread || !current(session)) return;
        thread.loading = true;
        Continuous pages = thread.pages;
        if (pages.list == null && conversationPanel.getChildCount() == 0)
            PersonUi.text(conversationPanel, activity.getText(R.string.blackmail_loading_entries), false);
        queuedPages.addFirst(new PageRequest(session, complete -> {
            if (conversation != thread) { complete.run(); return; }
            read(session, "thread", thread.id, cursor, response -> {
                if (!current(session) || conversation != thread) return;
                thread.loading = false;
                String status = response.optString("status");
                if ("ok".equals(status)) {
                    boolean first = !pages.hasHead;
                    if (pages.accept(response, cursor, true) || pages.list == null)
                        showConversation(session, thread, first, !cursor.isEmpty());
                    if (cursor.isEmpty()) scheduleConversationRefresh(session, thread);
                } else {
                    int label = statusLabel(status);
                    pages.fail(cursor, label);
                    showConversation(session, thread, false, false);
                    if (label == R.string.blackmail_refresh_unavailable && pages.hasHead)
                        scheduleConversationRefresh(session, thread);
                }
            }, () -> {
                if (!current(session) || conversation != thread) return;
                thread.loading = false;
                pages.fail(cursor, R.string.full_access_unavailable);
                showConversation(session, thread, false, false);
            }, complete);
        }));
        requestNextPage();
    }

    private void scheduleConversationRefresh(Section session, Conversation thread) {
        if (thread.refresh != null) main.removeCallbacks(thread.refresh);
        thread.refresh = () -> {
            if (conversation != thread || !current(session)) return;
            if (thread.loading) scheduleConversationRefresh(session, thread);
            else loadConversation(session, thread, "");
        };
        main.postDelayed(thread.refresh, REFRESH_MILLIS);
    }

    private void showConversation(Section session, Conversation thread, boolean first, boolean advance) {
        Continuous pages = thread.pages;
        if (pages.list == null) {
            conversationPanel.removeAllViews();
            pages.list = new BlackmailList(conversationPanel, scroll, "thread", true, () -> {
                if (conversation == thread && current(session) && pages.canAdvance() && pages.list.nearEdge())
                    loadConversation(session, thread, pages.next);
            });
        }
        List<BlackmailList.Row> entries = new ArrayList<>();
        addListStatus(entries, pages, () -> loadConversation(session, thread, pages.failedCursor), "thread:");
        List<JSONObject> items = List.copyOf(pages.rows.values());
        for (int index = items.size() - 1; index >= 0; index--) {
            JSONObject item = items.get(index);
            JSONObject previous = index + 1 < items.size() ? items.get(index + 1) : null;
            entries.add(new BlackmailList.Row("item:" + item.optString("id"), (row, position) ->
                    BlackmailViews.timelineRow(row, item, previous, this::messageText)));
        }
        pages.list.show(entries, first, advance);
    }

    private static void addRows(Map<String, JSONObject> destination, JSONArray items) {
        if (items == null) return;
        for (int index = 0; index < items.length(); index++) {
            JSONObject item = items.optJSONObject(index);
            if (item == null) continue;
            String id = item.optString("id");
            if (!id.isEmpty()) destination.putIfAbsent(id, item);
        }
    }

    private String date(long time) {
        return DateUtils.formatDateTime(activity, time, DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_TIME);
    }

    private CharSequence duration(long time) {
        return DateUtils.formatElapsedTime(Math.max(0, time) / 1000);
    }

    private void fileRow(LinearLayout card, Section session, JSONObject item) {
        String id = item.optString("id");
        if (id.isEmpty()) return;
        String title = item.optString("title");
        boolean directory = item.optBoolean("directory");
        String mime = item.optString("mime", "application/octet-stream");
        long size = item.optLong("size", -1);
        String detail = size < 0 ? "" : Formatter.formatShortFileSize(activity, size);
        boolean previewable = item.optBoolean("previewable");
        Runnable open = directory ? () -> {
            if (!current(session) || session.working) return;
            session.folders.push(new Folder(id, title));
            resetDirectory(session);
        } : previewable ? () -> preview(session, id, title) : null;
        int icon = directory ? android.R.drawable.ic_menu_agenda
                : previewable ? R.drawable.ic_journal_photo : R.drawable.ic_journal_file;
        LinearLayout entry = new LinearLayout(activity);
        entry.setGravity(android.view.Gravity.CENTER_VERTICAL);
        LinearLayout copy = PersonUi.column(activity);
        entry.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout row = BlackmailViews.row(copy, title, detail, icon, false, null, open);
        if (directory) row.setTag("blackmail_folder");
        int downloadLabel = directory ? R.string.blackmail_download_folder_item : R.string.blackmail_download_item;
        BlackmailViews.fileDownload(entry, activity.getString(downloadLabel, title),
                () -> download(session, id, title, mime, directory));
        card.addView(entry, new LinearLayout.LayoutParams(-1, -2));
    }

    private void resetDirectory(Section session) {
        session.lastPage = null;
        session.cursor = "";
        session.panel.removeAllViews();
        if (!session.folders.isEmpty()) PersonUi.text(session.panel, session.folders.peek().title, true);
        PersonUi.text(session.panel, activity.getText(R.string.blackmail_loading_entries), false);
        addClose(session);
        loadPage(session, "");
    }

    private void showFolderFailure(Section session) {
        session.lastPage = null;
        session.panel.removeAllViews();
        PersonUi.text(session.panel, session.folders.peek().title, true);
        PersonUi.text(session.panel, activity.getText(R.string.full_access_unavailable), false);
        BlackmailViews.action(session.panel, R.string.role_retry, () -> {
            if (current(session) && !session.working) resetDirectory(session);
        }, false);
        BlackmailViews.action(session.panel, R.string.full_access_folder_up, () -> {
            if (current(session) && !session.working) {
                session.folders.pop();
                resetDirectory(session);
            }
        }, false);
        addClose(session);
    }

    private void preview(Section session, String id, String title) {
        if (!current(session) || session.working) return;
        session.working = true;
        worker.load(workKey(session, "preview"), () -> {
            LimitedImage output = new LimitedImage();
            session.transfer("preview", id, output);
            return decode(output.bytes());
        }, preview -> {
            if (!current(session)) return;
            session.working = false;
            if (preview == null) {
                showTransient(R.string.full_access_unavailable);
                return;
            }
            if (session.previewPanel != null) session.panel.removeView(session.previewPanel);
            LinearLayout panel = PersonUi.column(activity);
            session.previewPanel = panel;
            PersonUi.text(panel, title, true);
            ImageView image = frameView(R.string.full_access_preview_image);
            image.setImageBitmap(preview);
            panel.addView(image, new LinearLayout.LayoutParams(-1, -2));
            BlackmailViews.action(panel, R.string.blackmail_close_preview, () -> {
                if (!current(session) || session.previewPanel != panel) return;
                session.panel.removeView(panel);
                session.previewPanel = null;
            }, false);
            session.panel.addView(panel, 0, new LinearLayout.LayoutParams(-1, -2));
            if (scroll != null) scroll.post(() -> {
                if (current(session) && session.previewPanel == panel)
                    panel.requestRectangleOnScreen(new Rect(0, 0, panel.getWidth(), panel.getHeight()), false);
            });
        }, failure -> {
            if (!current(session)) return;
            session.working = false;
            showTransient(R.string.full_access_unavailable);
        });
    }

    private void download(Section session, String id, String title, String mime, boolean directory) {
        if (!current(session) || session.working) return;
        session.working = true;
        Context context = app;
        String name = directory ? title + ".zip" : title;
        String mediaType = directory ? "application/zip" : mime;
        TextView progress = PersonUi.text(session.panel, activity.getString(R.string.full_access_download_progress, name), false);
        session.panel.removeView(progress);
        session.panel.addView(progress, 0);
        AtomicLong bytes = new AtomicLong();
        updateProgress(session, progress, name, bytes);
        worker.load(workKey(session, "transfer"), () -> FullAccessDownloads.save(context, name, mediaType,
                () -> session.live.get() && (demo == null ? session.channel.isCurrent()
                        : demo.current() && session.localFiles.isStorageGrantCurrent()),
                destination -> session.transfer(directory ? "archive" : "download", id,
                        new ProgressOutput(destination, bytes))),
                uri -> {
                    if (!current(session)) return;
                    session.working = false;
                    progress.setText(R.string.full_access_download_ready);
                    BlackmailViews.action(session.panel, R.string.full_access_open_download,
                            () -> openDownloaded(uri, mediaType), false);
                }, failure -> {
                    if (!current(session)) return;
                    session.working = false;
                    progress.setText(R.string.full_access_download_failed);
                });
    }

    private void updateProgress(Section session, TextView progress, String name, AtomicLong bytes) {
        if (!current(session) || !session.working || progress.getParent() != session.panel) return;
        progress.setText(activity.getString(R.string.full_access_download_bytes, name,
                Formatter.formatShortFileSize(activity, bytes.get())));
        main.postDelayed(() -> updateProgress(session, progress, name, bytes), 250);
    }

    private void openDownloaded(Uri uri, String mime) {
        if (!resumed || !allowed.getAsBoolean()) return;
        Intent open = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try { activity.startActivity(Intent.createChooser(open, activity.getText(R.string.full_access_open_download))); }
        catch (RuntimeException unavailable) { showTransient(R.string.full_access_unavailable); }
    }

    private void pollScreen(Section session) {
        if (!current(session)) return;
        long started = SystemClock.elapsedRealtime();
        worker.load(workKey(session, "frame"), () -> {
            if (session.localScreen != null) {
                String state = session.localScreen.status();
                byte[] frame = session.localScreen.takeLatest();
                return new ScreenFrame(state, frame == null ? null : decode(frame));
            }
            JSONObject response = session.channel.query(command("frame", "", ""));
            String state = response.optString("status");
            String value = response.optString("image");
            Bitmap bitmap = value.isEmpty() || value.length() > MAX_IMAGE_BYTES * 4L / 3 + 16 ? null
                    : decode(Base64.decode(value, Base64.DEFAULT));
            return new ScreenFrame(state, bitmap);
        }, frame -> {
            if (!current(session)) return;
            session.connected = true;
            if ("streaming".equals(frame.state)) {
                if (session.image == null) {
                    session.panel.removeAllViews();
                    TextView live = PersonUi.text(session.panel, activity.getText(R.string.blackmail_live_screen), false);
                    live.setTextColor(activity.getColor(R.color.accent_text));
                    session.image = frameView(R.string.full_access_screen_image);
                    session.panel.addView(session.image, new LinearLayout.LayoutParams(-1, -2));
                    addClose(session);
                }
                if (frame.bitmap != null) session.image.setImageBitmap(frame.bitmap);
            } else if ("awaiting_consent".equals(frame.state) || "requesting_consent".equals(frame.state)) {
                int message = demo != null ? R.string.full_access_waiting : "awaiting_consent".equals(frame.state)
                        ? R.string.full_access_screen_pending : R.string.full_access_screen_requesting;
                if (session.status == null || !activity.getText(message).equals(session.status.getText()))
                    showStatus(session, message, false);
            } else {
                stopWith(session, statusLabel(frame.state));
                return;
            }
            long interval = "streaming".equals(frame.state) ? FRAME_MILLIS : 1_000;
            main.postDelayed(() -> pollScreen(session), Math.max(0, interval - (SystemClock.elapsedRealtime() - started)));
        }, failure -> { if (current(session)) stopWith(session, R.string.full_access_unavailable); });
    }

    private ImageView frameView(int description) {
        ImageView frame = new ImageView(activity);
        frame.setContentDescription(activity.getString(description));
        frame.setAdjustViewBounds(true);
        frame.setScaleType(ImageView.ScaleType.FIT_CENTER);
        return frame;
    }

    private static Bitmap decode(byte[] bytes) {
        if (bytes.length == 0 || bytes.length > MAX_IMAGE_BYTES) return null;
        BitmapFactory.Options dimensions = new BitmapFactory.Options();
        dimensions.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, dimensions);
        if (dimensions.outWidth < 1 || dimensions.outHeight < 1
                || (long) dimensions.outWidth * dimensions.outHeight > 2_400_000L) return null;
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
    }

    private void stopWith(Section session, int label) {
        session.lastPage = null;
        if (demo == null || persistent(session)) {
            closeConversation(false);
            retireLive(session);
            Section closed = sections.get(session.scope);
            closed.panel.removeAllViews();
            PersonUi.text(closed.panel, activity.getString(label), false);
            BlackmailViews.action(closed.panel, R.string.role_retry, () -> {
                if (current(closed)) openLive(closed);
            }, false);
        } else showStatus(session, label, true);
    }

    private void showStatus(Section session, int label, boolean retry) {
        LinearLayout panel = session.panel;
        panel.removeAllViews();
        session.image = null;
        session.status = PersonUi.text(panel, activity.getString(label), false);
        if (retry) BlackmailViews.action(panel, R.string.role_retry, session.refresh, false);
        if (activeLive == session && persistent(session)) addClose(session);
    }

    private void addClose(Section session) {
        BlackmailViews.action(session.panel, R.string.full_access_close_view, () -> {
            if (current(session)) selectScope(null, false);
        }, false);
    }

    private void showTransient(int label) {
        android.widget.Toast.makeText(activity, label, android.widget.Toast.LENGTH_SHORT).show();
    }

    private int statusLabel(String status) {
        return switch (status) {
            case "disabled" -> R.string.full_access_disabled;
            case "permission_required" -> demo == null ? R.string.full_access_permission_needed : R.string.demo_full_access_permission_missing;
            case "update_required" -> R.string.full_access_update_required;
            case "declined" -> R.string.full_access_screen_declined;
            case "capture_in_use" -> R.string.full_access_screen_busy;
            case "screen_alerts_required" -> R.string.full_access_screen_sub_alerts_required;
            case "busy" -> R.string.full_access_busy;
            case "temporarily_unavailable" -> R.string.blackmail_refresh_unavailable;
            default -> R.string.full_access_unavailable;
        };
    }

    private int statusLabel(Section session, String status) {
        if ("files".equals(session.scope) && "permission_required".equals(status))
            return demo == null ? R.string.blackmail_files_permission_needed : R.string.blackmail_demo_files_permission_needed;
        return statusLabel(status);
    }

    private static JSONObject command(String operation, String node, String cursor) throws IOException {
        try {
            return new JSONObject().put("op", operation).put("node", node).put("cursor", cursor);
        } catch (JSONException impossible) {
            throw new IOException("Full Access request unavailable", impossible);
        }
    }

    private static RemoteStore.Peer resolve(Context context, PersonId id) {
        RemoteStore.Peer peer = new RemotePersonRepository(context, RemoteFeature.get(context)).peer(id);
        if (peer.localRole != RemoteProtocol.Role.DOM || peer.state != RemoteStore.State.ACTIVE)
            throw new SecurityException("Paired Sub unavailable");
        return peer;
    }

    private record ScreenFrame(String state, Bitmap bitmap) { }
    private record Folder(String id, String title) { }
    private record PageRequest(Section section, Consumer<Runnable> start) { }

    static final class Continuous {
        final Map<String, JSONObject> rows = new LinkedHashMap<>();
        final Set<String> consumed = new HashSet<>();
        Map<String, JSONObject> head = Map.of();
        String headNext = "", next = "", failedCursor = "";
        boolean hasHead, partial, repeated, usageMissing;
        long since;
        int error, emptyBatches;
        BlackmailList list;

        boolean accept(JSONObject response, String cursor, boolean thread) {
            Map<String, JSONObject> batch = new LinkedHashMap<>();
            addRows(batch, response.optJSONArray("items"));
            String following = response.optString("nextCursor", "");
            boolean limited = response.optBoolean("partial");
            boolean changed = error != 0;
            if (cursor.isEmpty()) {
                if (hasHead && headNext.equals(following)
                        && List.copyOf(head.keySet()).equals(List.copyOf(batch.keySet()))) {
                    if (!failedCursor.isEmpty()) changed = false;
                    else error = 0;
                    for (var entry : batch.entrySet()) {
                        JSONObject old = head.get(entry.getKey());
                        if (!old.toString().equals(entry.getValue().toString())) {
                            rows.put(entry.getKey(), entry.getValue());
                            changed = true;
                        }
                    }
                    head = batch;
                    boolean update = changed || !partial && limited;
                    partial |= limited;
                    return update;
                }
                if (failedCursor.isEmpty() || !thread) error = 0;
                Map<String, JSONObject> older = new LinkedHashMap<>();
                if (thread && !following.isEmpty() && !batch.isEmpty()) {
                    String boundary = null;
                    for (String id : batch.keySet()) boundary = id;
                    boolean afterBoundary = false;
                    for (var entry : rows.entrySet()) {
                        if (afterBoundary) older.put(entry.getKey(), entry.getValue());
                        else afterBoundary = entry.getKey().equals(boundary);
                    }
                }
                rows.clear();
                rows.putAll(batch);
                if (older.isEmpty()) {
                    consumed.clear();
                    repeated = false;
                    error = 0;
                    failedCursor = "";
                    emptyBatches = batch.isEmpty() ? 1 : 0;
                    next = following;
                } else {
                    older.forEach(rows::putIfAbsent);
                }
                head = batch;
                headNext = following;
                hasHead = true;
                partial = limited;
            } else {
                error = 0;
                failedCursor = "";
                consumed.add(cursor);
                int previousCount = rows.size();
                batch.forEach(rows::putIfAbsent);
                emptyBatches = rows.size() == previousCount ? emptyBatches + 1 : 0;
                repeated = !following.isEmpty() && (consumed.contains(following) || emptyBatches >= 3);
                partial |= limited;
                next = repeated ? "" : following;
            }
            return true;
        }

        boolean canAdvance() {
            return error == 0 && !next.isEmpty();
        }

        void fail(String cursor, int label) {
            if (label == R.string.blackmail_refresh_unavailable) {
                if (!failedCursor.isEmpty() && cursor.isEmpty()) return;
            } else if (cursor.isEmpty() || label == R.string.full_access_disabled
                    || label == R.string.full_access_permission_needed
                    || label == R.string.demo_full_access_permission_missing) {
                clearData();
                cursor = "";
            }
            failedCursor = cursor;
            error = label;
        }

        void clear() {
            clearData();
            list = null;
        }

        private void clearData() {
            rows.clear();
            consumed.clear();
            head = Map.of();
            headNext = "";
            next = "";
            failedCursor = "";
            since = 0;
            error = 0;
            hasHead = false;
            partial = false;
            repeated = false;
            usageMissing = false;
            emptyBatches = 0;
        }
    }

    private static final class Conversation {
        final String id;
        final Continuous pages = new Continuous();
        Runnable refresh;
        boolean loading;

        Conversation(String id) { this.id = id; }
    }

    private static final class NotificationGroup {
        final String id;
        final Continuous pages = new Continuous();
        String edgeKey;
        boolean loading;

        NotificationGroup(String id) { this.id = id; }
    }

    private final class Section {
        final String scope;
        final LinearLayout panel;
        final Runnable refresh = this::refresh;
        final ArrayDeque<Folder> folders = new ArrayDeque<>();
        final Continuous pages = new Continuous();
        final LruCache<String, JSONObject> rowDetails = new LruCache<>(96);
        final LruCache<String, Boolean> missingDetails = new LruCache<>(96);
        String cursor = "";
        String scopeRevision = "";
        JSONObject lastPage;
        TextView status;
        ImageView image;
        LinearLayout previewPanel;
        NotificationGroup group;
        String notificationRevision = "";
        final AtomicBoolean live = new AtomicBoolean(true);
        volatile FullAccessChannel channel;
        FullAccessFiles.Session localFiles;
        FullAccessScreen.Session localScreen;
        CompletableFuture<FullAccessChannel> opening;
        boolean working, connected, legacyRows;

        Section(String scope, LinearLayout panel) {
            this.scope = scope;
            this.panel = panel;
        }

        private void refresh() {
            if (conversation != null || !current(this)) return;
            if (working) main.postDelayed(refresh, REFRESH_MILLIS);
            else loadPage(this, "");
        }

        synchronized boolean attach(FullAccessChannel opened) {
            if (!live.get()) {
                if (opened != null) opened.close();
                return false;
            }
            channel = opened;
            return true;
        }

        void transfer(String operation, String id, OutputStream output) throws IOException {
            if (localFiles == null) channel.transfer(command(operation, id, ""), output);
            else localFiles.transfer(id, operation, output);
        }

        void clearRows() {
            pages.clearData();
            rowDetails.evictAll();
            missingDetails.evictAll();
            if (group != null) group.pages.clear();
            group = null;
        }

        synchronized void retire() {
            live.set(false);
            main.removeCallbacks(refresh);
            lastPage = null;
            clearRows();
            pages.list = null;
            scopeRevision = "";
            image = null;
            status = null;
            previewPanel = null;
            if (opening != null) opening.cancel(true);
            if (channel != null) channel.close();
            if (localFiles != null) localFiles.close();
            if (localScreen != null) localScreen.close();
        }
    }

    private static final class LimitedImage extends OutputStream {
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        @Override public void write(int value) throws IOException {
            requireCapacity(1);
            output.write(value);
        }
        @Override public void write(byte[] value, int offset, int length) throws IOException {
            requireCapacity(length);
            output.write(value, offset, length);
        }
        byte[] bytes() { return output.toByteArray(); }
        private void requireCapacity(int count) throws IOException {
            if (count > MAX_IMAGE_BYTES - output.size()) throw new IOException("Preview exceeds its size limit");
        }
    }

    private static final class ProgressOutput extends OutputStream {
        private final OutputStream output;
        private final AtomicLong bytes;
        ProgressOutput(OutputStream output, AtomicLong bytes) { this.output = output; this.bytes = bytes; }
        @Override public void write(int value) throws IOException {
            output.write(value);
            bytes.incrementAndGet();
        }
        @Override public void write(byte[] value, int offset, int length) throws IOException {
            output.write(value, offset, length);
            bytes.addAndGet(length);
        }
        @Override public void flush() throws IOException { output.flush(); }
    }
}
