package com.subhub.app.fullaccess;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.format.DateUtils;
import android.text.format.Formatter;
import android.util.Base64;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
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
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Views and operations are owned by the selected tab and are discarded when its lease ends. */
final class FullAccessViewer implements AutoCloseable {
    private static final List<String> SECTIONS = List.of("contacts", "notifications", "sms", "apps", "files", "screen");
    private static final int MAX_IMAGE_BYTES = 1024 * 1024;
    private static final int PREVIEW_ROWS = 2;
    private static final long EXPANDED_REFRESH_MILLIS = 10_000;
    private static final long PREVIEW_REFRESH_MILLIS = 30_000;
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
    private final Map<String, Section> sections = new LinkedHashMap<>();
    private final ArrayDeque<PageRequest> queuedPages = new ArrayDeque<>();
    private Section activeLive;
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

        for (String scope : SECTIONS) {
            LinearLayout card = PersonUi.card(parent);
            card.setTag("full_access_section:" + scope);
            com.subhub.app.util.UiIdentity.sectionIcon(
                    PersonUi.text(card, activity.getText(label(scope)), true), icon(scope));
            LinearLayout body = PersonUi.column(activity);
            card.addView(body, new LinearLayout.LayoutParams(-1, -2));
            sections.put(scope, new Section(scope, body));
        }
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

    void resume() {
        if (resumed) return;
        resumed = true;
        for (Map.Entry<String, Section> entry : sections.entrySet()) {
            Section section = new Section(entry.getKey(), entry.getValue().panel);
            entry.setValue(section);
            if (persistent(section)) showLiveAction(section);
            else {
                showStatus(section, R.string.full_access_waiting, false);
                loadPage(section, "");
            }
        }
        main.postDelayed(guard, SESSION_CHECK_MILLIS);
    }

    void pause() {
        resumed = false;
        for (Section section : sections.values()) {
            section.retire();
            section.panel.removeAllViews();
        }
        activeLive = null;
        main.removeCallbacksAndMessages(null);
        worker.cancelPrefix("full-access-view:");
        queuedPages.clear();
        pageInFlight = false;
    }

    @Override public void close() { pause(); }

    private boolean current(Section session) {
        return sections.get(session.scope) == session && session.live.get() && resumed && allowed.getAsBoolean();
    }

    private static boolean persistent(Section session) {
        return "files".equals(session.scope) || "screen".equals(session.scope);
    }

    private String workKey(Section session, String operation) {
        return "full-access-view:" + session.scope + ":" + operation;
    }

    private void showLiveAction(Section section) {
        section.panel.removeAllViews();
        int action = "files".equals(section.scope) ? R.string.full_access_browse_files : R.string.full_access_view_screen;
        PersonUi.action(section.panel, action, () -> {
            if (current(section)) openLive(section);
        }, true);
    }

    private Section openLive(Section previous) {
        if (activeLive != null) closeLive(activeLive);
        Section session = sections.get(previous.scope);
        activeLive = session;
        showStatus(session, R.string.full_access_waiting, false);
        if (demo == null) openChannel(session);
        else if ("files".equals(session.scope)) {
            session.localFiles = demo.openFiles(session.live::get);
            loadPage(session, "");
        } else {
            showStatus(session, R.string.demo_full_access_screen_note, false);
            PersonUi.action(session.panel, R.string.demo_full_access_screen_start, () -> {
                if (current(session)) demoActivity.requestScreen();
            }, true);
        }
        return session;
    }

    private void closeLive(Section session) {
        session.retire();
        worker.cancelPrefix("full-access-view:" + session.scope + ":");
        Section closed = new Section(session.scope, session.panel);
        sections.put(session.scope, closed);
        if (activeLive == session) activeLive = null;
        showLiveAction(closed);
    }

    void showLocalScreen(Intent approval) {
        Section previous = sections.get("screen");
        if (demo == null || !current(previous)) return;
        Section session = openLive(previous);
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
        if (!current(session) || session.working) return;
        session.working = true;
        if (persistent(session)) requestPage(session, requestedCursor, () -> {});
        else {
            queuedPages.addLast(new PageRequest(session, requestedCursor));
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
            requestPage(request.section, request.cursor, () -> {
                pageInFlight = false;
                requestNextPage();
            });
            return;
        }
    }

    private void requestPage(Section session, String requestedCursor, Runnable complete) {
        Context context = app;
        PersonId requested = target;
        String node = session.folders.isEmpty() ? "" : session.folders.peek().id;
        String operation = "files".equals(session.scope) ? "list" : "page";
        worker.load(workKey(session, "query"), () -> {
            if (demo != null) return session.localFiles == null
                    ? demo.page(session.scope, requestedCursor) : session.localFiles.list(node, requestedCursor);
            JSONObject request = command(operation, node, requestedCursor);
            if ("files".equals(session.scope)) return session.channel.query(request);
            RemoteFeature feature = RemoteFeature.get(context);
            return feature.requestFullAccess(resolve(context, requested), session.scope, request).get();
        }, page -> {
            if (current(session)) {
                session.working = false;
                session.connected = true;
                String state = page.optString("status", "unavailable");
                if ("ok".equals(state) || "ready".equals(state)) {
                    session.cursor = requestedCursor;
                    if (session.lastPage == null || !session.lastPage.toString().equals(page.toString())) {
                        session.lastPage = page;
                        renderPage(session, page);
                    }
                } else {
                    session.lastPage = null;
                    if (persistent(session)) stopWith(session, statusLabel(state));
                    else showStatus(session, statusLabel(state), true);
                }
                if (!persistent(session) && session.cursor.isEmpty()) {
                    long delay = session.expanded ? EXPANDED_REFRESH_MILLIS : PREVIEW_REFRESH_MILLIS;
                    main.removeCallbacks(session.refresh);
                    main.postDelayed(session.refresh, delay);
                }
            }
            complete.run();
        }, failure -> {
            if (current(session)) {
                session.working = false;
                session.lastPage = null;
                stopWith(session, R.string.full_access_unavailable);
            }
            complete.run();
        });
    }

    private void renderPage(Section session, JSONObject page) {
        LinearLayout card = session.panel;
        card.removeAllViews();
        session.image = null;
        if ("files".equals(session.scope) && !session.folders.isEmpty()) {
            Folder folder = session.folders.peek();
            PersonUi.text(card, folder.title, true);
            PersonUi.action(card, R.string.full_access_folder_up, () -> {
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

        JSONArray rows = page.optJSONArray("items");
        int count = rows == null ? 0 : rows.length();
        boolean files = "files".equals(session.scope);
        int limit = files || session.expanded ? 64 : PREVIEW_ROWS;
        if (count == 0) PersonUi.text(card, activity.getString(R.string.full_access_empty), false);
        for (int index = 0; index < Math.min(limit, count); index++) {
            JSONObject item = rows.optJSONObject(index);
            if (item == null) continue;
            if (files) fileRow(card, session, item);
            else dataRow(card, session.scope, item);
        }

        String next = page.optString("nextCursor", "");
        if (files || session.expanded) {
            if (files && !session.cursor.isEmpty()) {
                PersonUi.action(card, R.string.full_access_first_page, () -> {
                    if (current(session) && !session.working) resetDirectory(session);
                }, false);
            } else if (!session.pageHistory.isEmpty()) {
                PersonUi.action(card, R.string.full_access_previous, () -> {
                    if (!current(session) || session.working || session.pageHistory.isEmpty()) return;
                    loadPage(session, session.pageHistory.pop());
                }, false);
            }
            if (!next.isEmpty() && !next.equals(session.cursor)) PersonUi.action(card, R.string.full_access_more, () -> {
                if (!current(session) || session.working) return;
                if (!files) session.pageHistory.push(session.cursor);
                loadPage(session, next);
            }, false);
        }
        if (files) addClose(session);
        else if (session.expanded || count > PREVIEW_ROWS || !next.isEmpty()) {
            int action = session.expanded ? R.string.full_access_show_less : R.string.full_access_show_more;
            PersonUi.action(card, action, () -> {
                if (!current(session) || session.working) return;
                session.expanded = !session.expanded;
                if (!session.expanded && !session.cursor.isEmpty()) resetDirectory(session);
                else renderPage(session, page);
            }, false);
        }
    }

    private void dataRow(LinearLayout card, String scope, JSONObject item) {
        String title = item.optString("title");
        String detail = item.optString("detail");
        if (item.optBoolean("redacted")) detail = activity.getString(R.string.full_access_message_hidden);
        if ("notifications".equals(scope)) {
            String heading = item.optString("notificationTitle");
            String preview = item.optString("notificationText");
            String publicText = heading.isEmpty() ? preview : preview.isEmpty() ? heading : heading + "\n" + preview;
            if (!publicText.isEmpty()) detail = publicText;
        }
        long time = item.optLong("time");
        if (time > 0) detail = detail.isEmpty() ? date(time)
                : activity.getString(R.string.full_access_detail_time, detail, date(time));
        if ("apps".equals(scope) && item.has("todayMillis") && item.has("weekMillis")) {
            detail = activity.getString(R.string.full_access_usage_values,
                    duration(item.optLong("todayMillis")), duration(item.optLong("weekMillis")));
        }
        if (demo != null && "apps".equals(scope) && !item.optString("id").isEmpty()) {
            LinearLayout row = PersonUi.row(card, R.drawable.ic_settings_apps, title, detail, null);
            com.subhub.app.util.AppIcons.bind((ImageView) row.getChildAt(0), item.optString("id"),
                    R.drawable.ic_settings_apps);
        } else PersonUi.row(card, 0, title, detail, null);
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
        LinearLayout entry = PersonUi.column(activity);
        card.addView(entry, new LinearLayout.LayoutParams(-1, -2));
        PersonUi.row(entry, R.drawable.ic_journal_file, title, detail, directory ? () -> {
            if (!current(session) || session.working) return;
            session.folders.push(new Folder(id, title));
            resetDirectory(session);
        } : null);
        if (item.optBoolean("previewable"))
            PersonUi.action(entry, R.string.full_access_preview, () -> preview(session, id), false);
        PersonUi.action(entry, directory ? R.string.full_access_download_folder : R.string.full_access_download,
                () -> download(session, id, title, mime, directory), false);
    }

    private void resetDirectory(Section session) {
        session.pageHistory.clear();
        session.lastPage = null;
        session.cursor = "";
        loadPage(session, "");
    }

    private void preview(Section session, String id) {
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
            if (session.image != null) session.panel.removeView(session.image);
            session.image = frameView(R.string.full_access_preview_image);
            session.image.setImageBitmap(preview);
            session.panel.addView(session.image, 0, new LinearLayout.LayoutParams(-1, -2));
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
                    PersonUi.action(session.panel, R.string.full_access_open_download, () -> openDownloaded(uri, mediaType), false);
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
        if (persistent(session)) {
            closeLive(session);
            Section closed = sections.get(session.scope);
            closed.panel.removeAllViews();
            PersonUi.text(closed.panel, activity.getString(label), false);
            PersonUi.action(closed.panel, R.string.role_retry, () -> {
                if (current(closed)) openLive(closed);
            }, false);
        } else showStatus(session, label, true);
    }

    private void showStatus(Section session, int label, boolean retry) {
        LinearLayout panel = session.panel;
        panel.removeAllViews();
        session.image = null;
        session.status = PersonUi.text(panel, activity.getString(label), false);
        if (retry) PersonUi.action(panel, R.string.role_retry, session.refresh, false);
        if (activeLive == session) addClose(session);
    }

    private void addClose(Section session) {
        PersonUi.action(session.panel, R.string.full_access_close_view, () -> {
            if (current(session)) closeLive(session);
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
    private record PageRequest(Section section, String cursor) { }

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
        final AtomicBoolean live = new AtomicBoolean(true);
        volatile FullAccessChannel channel;
        FullAccessFiles.Session localFiles;
        FullAccessScreen.Session localScreen;
        CompletableFuture<FullAccessChannel> opening;
        boolean working, connected, expanded;

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
