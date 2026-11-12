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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    private View.OnLayoutChangeListener conversationLayout;
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
        if (demo != null) PersonUi.text(overview, activity.getText(R.string.demo_full_access_local), false);
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
        else if (!detailScrollPending) detailScroll = scroll.position();
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
        else if (persistent(section)) openLive(section);
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
                    } else if ("files".equals(session.scope)) {
                        loadPage(session, "");
                    } else {
                        pollScreen(session);
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
            default -> "page";
        };
        read(session, operation, node, requestedCursor, page -> {
            if (current(session)) {
                session.working = false;
                session.connected = true;
                String state = page.optString("status", "unavailable");
                if ("ok".equals(state) || "ready".equals(state)) {
                    session.legacyRows |= page.optBoolean("legacyRows");
                    session.cursor = requestedCursor;
                    if (session.lastPage == null || !session.lastPage.toString().equals(page.toString())) {
                        session.lastPage = page;
                        renderPage(session, page);
                    }
                    if (detailScrollPending && scroll != null) {
                        detailScrollPending = false;
                        scroll.restorePosition(detailScroll);
                    }
                } else {
                    session.lastPage = null;
                    session.group = null;
                    if (persistent(session)) stopWith(session, statusLabel(session, state));
                    else showStatus(session, statusLabel(state), true);
                }
                if (!persistent(session) && session.cursor.isEmpty()) {
                    main.removeCallbacks(session.refresh);
                    main.postDelayed(session.refresh, REFRESH_MILLIS);
                }
            }
        }, () -> {
            if (current(session)) {
                session.working = false;
                session.lastPage = null;
                stopWith(session, R.string.full_access_unavailable);
            }
        }, complete);
    }

    private void read(Section session, String operation, String node, String cursor,
            Consumer<JSONObject> received, Runnable failed, Runnable complete) {
        Context context = app;
        PersonId requested = target;
        boolean legacy = session.legacyRows;
        worker.load(workKey(session, "query"), () -> {
            if (demo != null) return session.localFiles == null
                    ? demo.page(session.scope, operation, node, cursor) : session.localFiles.list(node, cursor);
            JSONObject request = command(operation, node, cursor);
            if ("files".equals(session.scope)) return session.channel.query(request);
            RemoteFeature feature = RemoteFeature.get(context);
            RemoteStore.Peer peer = resolve(context, requested);
            JSONObject page = feature.requestFullAccess(peer, session.scope, request).get();
            if (cursor.isEmpty() && ("conversations".equals(operation) || "groups".equals(operation))
                    && "update_required".equals(page.optString("status"))) {
                return feature.requestFullAccess(peer, session.scope, command("page", "", cursor)).get()
                        .put("legacyRows", true);
            }
            if (legacy && "page".equals(operation)) page.put("legacyRows", true);
            return page;
        }, page -> {
            try { received.accept(page); }
            finally { complete.run(); }
        }, failure -> {
            try { failed.run(); }
            finally { complete.run(); }
        });
    }

    private void renderPage(Section session, JSONObject page) {
        LinearLayout card = session.panel;
        card.removeAllViews();
        session.image = null;
        session.previewPanel = null;
        if ("files".equals(session.scope) && !session.folders.isEmpty()) {
            Folder folder = session.folders.peek();
            PersonUi.text(card, folder.title, true);
            BlackmailViews.action(card, R.string.full_access_folder_up, () -> {
                if (!current(session) || session.working) return;
                session.folders.pop();
                resetDirectory(session);
            }, false);
        }
        long since = page.optLong("since");
        if ("notifications".equals(session.scope) && since > 0)
            PersonUi.text(card, activity.getString(R.string.full_access_notifications_start, date(since)), false);
        if ("apps".equals(session.scope) && !page.optBoolean("usageAvailable"))
            PersonUi.text(card, activity.getString(R.string.full_access_usage_unavailable), false);
        if (session.legacyRows) PersonUi.text(card, activity.getText(R.string.blackmail_detail_update), false);

        JSONArray rows = page.optJSONArray("items");
        int count = rows == null ? 0 : rows.length();
        boolean files = "files".equals(session.scope);
        int limit = 64;
        if (count == 0) PersonUi.text(card, activity.getString(R.string.full_access_empty), false);
        boolean rowAdded = false;
        for (int index = 0; index < Math.min(limit, count); index++) {
            JSONObject item = rows.optJSONObject(index);
            if (item == null) continue;
            if (rowAdded) BlackmailViews.separator(card);
            if (files) fileRow(card, session, item);
            else dataRow(card, session, item);
            rowAdded = true;
        }

        String next = page.optString("nextCursor", "");
        if (files && !session.cursor.isEmpty()) {
            BlackmailViews.action(card, R.string.full_access_first_page, () -> {
                if (current(session) && !session.working) resetDirectory(session);
            }, false);
        } else if (!session.pageHistory.isEmpty()) {
            BlackmailViews.action(card, R.string.full_access_previous, () -> {
                if (!current(session) || session.working || session.pageHistory.isEmpty()) return;
                loadPage(session, session.pageHistory.pop());
            }, false);
        }
        if (!next.isEmpty() && !next.equals(session.cursor)) BlackmailViews.action(card, R.string.full_access_more, () -> {
            if (!current(session) || session.working) return;
            if (!files) session.pageHistory.push(session.cursor);
            loadPage(session, next);
        }, false);
        if ("sms".equals(session.scope) && page.optBoolean("partial"))
            PersonUi.text(card, activity.getString(R.string.blackmail_messages_partial), false);
        if (files) addClose(session);
    }

    private void dataRow(LinearLayout card, Section session, JSONObject item) {
        String title = item.optString("title");
        String id = item.optString("id");
        switch (session.scope) {
            case "contacts" -> {
                LinearLayout row = BlackmailViews.row(card, title, item.optString("detail"), 0, true,
                        BlackmailViews.photo(item.optString("photo")), null);
                row.setTag("blackmail_contact");
            }
            case "notifications" -> {
                if (session.legacyRows) notificationRow(card, item);
                else notificationGroup(card, session, item, id, title);
            }
            case "sms" -> {
                LinearLayout row = BlackmailViews.row(card, title, dated(messageText(item), item.optLong("time")),
                        0, true, null, id.isEmpty() || session.legacyRows ? null
                                : () -> openConversation(session, id, title));
                row.setTag("blackmail_conversation");
            }
            case "apps" -> appRow(card, item, title, id);
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

    private void notificationGroup(LinearLayout card, Section session, JSONObject item, String id, String title) {
        int count = Math.max(0, item.optInt("count"));
        String summary = activity.getResources().getQuantityString(R.plurals.blackmail_notification_count, count, count);
        NotificationGroup group = session.group;
        boolean expanded = group != null && group.id.equals(id);
        LinearLayout row = BlackmailViews.row(card, title, dated(summary, item.optLong("time")),
                R.drawable.ic_notification_subhub, false, null,
                id.isEmpty() ? null : () -> toggleGroup(session, id));
        row.setTag("blackmail_notification_group");
        if (expanded) renderGroup(card, session, group);
    }

    private void toggleGroup(Section session, String id) {
        if (!current(session) || session.lastPage == null) return;
        if (session.group != null && session.group.id.equals(id)) {
            session.group = null;
            renderPage(session, session.lastPage);
            return;
        }
        NotificationGroup group = new NotificationGroup(id);
        session.group = group;
        loadGroup(session, group, "");
    }

    private void loadGroup(Section session, NotificationGroup group, String cursor) {
        if (group.loading || !current(session) || session.group != group || session.lastPage == null) return;
        group.loading = true;
        group.error = 0;
        renderPage(session, session.lastPage);
        queuedPages.addFirst(new PageRequest(session, complete -> {
            if (session.group != group) { complete.run(); return; }
            read(session, "group", group.id, cursor, response -> {
                if (!current(session) || session.group != group) return;
                group.loading = false;
                String status = response.optString("status");
                if ("ok".equals(status)) {
                    addRows(group.rows, response.optJSONArray("items"));
                    group.next = nextCursor(response, cursor);
                } else group.error = statusLabel(status);
                if (session.lastPage != null) renderPage(session, session.lastPage);
            }, () -> {
                if (!current(session) || session.group != group) return;
                group.loading = false;
                group.error = R.string.full_access_unavailable;
                if (session.lastPage != null) renderPage(session, session.lastPage);
            }, complete);
        }));
        requestNextPage();
    }

    private void renderGroup(LinearLayout card, Section session, NotificationGroup group) {
        LinearLayout groupRows = PersonUi.column(activity);
        groupRows.setTag("blackmail_notification_entries");
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, -2);
        layout.setMarginStart(Math.round(12 * activity.getResources().getDisplayMetrics().density));
        card.addView(groupRows, layout);
        for (JSONObject entry : group.rows.values()) notificationRow(groupRows, entry);
        if (group.loading || group.error != 0 || group.rows.isEmpty()) {
            int label = group.loading ? R.string.full_access_waiting
                    : group.error != 0 ? group.error : R.string.full_access_empty;
            PersonUi.text(groupRows, activity.getText(label), false);
        }
        if (!group.loading && !group.next.isEmpty())
            BlackmailViews.action(groupRows, R.string.blackmail_older_notifications,
                    () -> loadGroup(session, group, group.next), false);
        BlackmailViews.action(groupRows, R.string.blackmail_collapse_notifications,
                () -> toggleGroup(session, group.id), false);
    }

    private void notificationRow(LinearLayout parent, JSONObject item) {
        String heading = item.optString("notificationTitle");
        if (heading.isEmpty()) heading = item.optString("title");
        String detail = item.optString("notificationText");
        if (detail.isEmpty()) detail = item.optString("detail");
        BlackmailViews.row(parent, heading, dated(detail, item.optLong("time")), 0, false, null, null);
    }

    private void openConversation(Section session, String id, String title) {
        if (!current(session) || conversation != null) return;
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
        clearConversationLayout();
        conversation.rows.clear();
        conversation.newestPage = null;
        conversation.older = null;
        conversation = null;
        conversationPanel.removeAllViews();
        conversationPanel.setVisibility(View.GONE);
        detailScroll = conversationScroll;
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
        thread.error = 0;
        if (thread.rows.isEmpty()) renderConversation(session, thread);
        else if (thread.older != null) thread.older.setEnabled(false);
        queuedPages.addFirst(new PageRequest(session, complete -> {
            if (conversation != thread) { complete.run(); return; }
            read(session, "thread", thread.id, cursor, response -> {
                if (!current(session) || conversation != thread) return;
                thread.loading = false;
                String status = response.optString("status");
                if ("ok".equals(status)) {
                    if (cursor.isEmpty()) {
                        String newestPage = response.toString();
                        scheduleConversationRefresh(session, thread);
                        if (newestPage.equals(thread.newestPage)) {
                            if (thread.older != null) thread.older.setEnabled(true);
                            return;
                        }
                        thread.newestPage = newestPage;
                        thread.rows.clear();
                    }
                    addRows(thread.rows, response.optJSONArray("items"));
                    thread.next = nextCursor(response, cursor);
                } else {
                    thread.rows.clear();
                    thread.next = "";
                    thread.newestPage = null;
                    thread.error = statusLabel(status);
                }
                updateConversation(session, thread, cursor.isEmpty());
            }, () -> {
                if (!current(session) || conversation != thread) return;
                thread.loading = false;
                thread.rows.clear();
                thread.next = "";
                thread.newestPage = null;
                thread.error = R.string.full_access_unavailable;
                renderConversation(session, thread);
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

    private void updateConversation(Section session, Conversation thread, boolean firstPage) {
        clearConversationLayout();
        int oldHeight = conversationPanel.getHeight();
        int oldY = scroll == null ? 0 : scroll.getScrollY();
        if (scroll != null && thread.error == 0) {
            conversationLayout = new View.OnLayoutChangeListener() {
                @Override public void onLayoutChange(View view, int left, int top, int right, int bottom,
                        int oldLeft, int oldTop, int oldRight, int oldBottom) {
                    clearConversationLayout();
                    main.post(() -> {
                        if (conversation != thread) return;
                        int target = firstPage ? scroll.getChildAt(0).getHeight()
                                : oldY + Math.max(0, conversationPanel.getHeight() - oldHeight);
                        scroll.scrollTo(0, target);
                    });
                }
            };
            conversationPanel.addOnLayoutChangeListener(conversationLayout);
        }
        renderConversation(session, thread);
    }

    private void clearConversationLayout() {
        if (conversationLayout == null) return;
        conversationPanel.removeOnLayoutChangeListener(conversationLayout);
        conversationLayout = null;
    }

    private void renderConversation(Section session, Conversation thread) {
        conversationPanel.removeAllViews();
        thread.older = null;
        if (thread.error != 0 || thread.loading || thread.rows.isEmpty()) {
            int label = thread.error != 0 ? thread.error
                    : thread.loading ? R.string.full_access_waiting : R.string.full_access_empty;
            PersonUi.text(conversationPanel, activity.getText(label), false);
        }
        if (thread.error == R.string.full_access_unavailable || thread.error == R.string.full_access_busy)
            BlackmailViews.action(conversationPanel, R.string.role_retry,
                    () -> loadConversation(session, thread, ""), false);
        if (!thread.next.isEmpty()) {
            int last = conversationPanel.getChildCount();
            BlackmailViews.action(conversationPanel, R.string.blackmail_older_messages,
                    () -> loadConversation(session, thread, thread.next), false);
            thread.older = conversationPanel.getChildAt(last);
        }
        BlackmailViews.timeline(conversationPanel, List.copyOf(thread.rows.values()), this::messageText);
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

    private static String nextCursor(JSONObject page, String current) {
        String next = page.optString("nextCursor", "");
        return next.equals(current) ? "" : next;
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
        BlackmailViews.row(copy, title, detail, icon, false, null, open);
        int downloadLabel = directory ? R.string.blackmail_download_folder_item : R.string.blackmail_download_item;
        BlackmailViews.fileDownload(entry, activity.getString(downloadLabel, title),
                () -> download(session, id, title, mime, directory));
        card.addView(entry, new LinearLayout.LayoutParams(-1, -2));
    }

    private void resetDirectory(Section session) {
        session.pageHistory.clear();
        session.lastPage = null;
        session.cursor = "";
        loadPage(session, "");
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
            } else if ("awaiting_consent".equals(frame.state)) {
                int message = demo == null ? R.string.full_access_screen_pending : R.string.full_access_waiting;
                if (session.status == null || !activity.getText(message).equals(session.status.getText()))
                    showStatus(session, message, false);
            } else {
                stopWith(session, statusLabel(frame.state));
                return;
            }
            main.postDelayed(() -> pollScreen(session), Math.max(0, FRAME_MILLIS - (SystemClock.elapsedRealtime() - started)));
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
        session.group = null;
        if (persistent(session)) {
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
        if (activeLive == session) addClose(session);
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
            case "busy" -> R.string.full_access_busy;
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

    private static final class Conversation {
        final String id;
        final Map<String, JSONObject> rows = new LinkedHashMap<>();
        String next = "";
        String newestPage;
        Runnable refresh;
        boolean loading;
        int error;
        View older;

        Conversation(String id) { this.id = id; }
    }

    private static final class NotificationGroup {
        final String id;
        final Map<String, JSONObject> rows = new LinkedHashMap<>();
        String next = "";
        boolean loading;
        int error;

        NotificationGroup(String id) { this.id = id; }
    }

    private final class Section {
        final String scope;
        final LinearLayout panel;
        final Runnable refresh = () -> loadPage(this, "");
        final ArrayDeque<Folder> folders = new ArrayDeque<>();
        final ArrayDeque<String> pageHistory = new ArrayDeque<>();
        String cursor = "";
        JSONObject lastPage;
        TextView status;
        ImageView image;
        LinearLayout previewPanel;
        NotificationGroup group;
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

        synchronized void retire() {
            live.set(false);
            main.removeCallbacks(refresh);
            lastPage = null;
            group = null;
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
