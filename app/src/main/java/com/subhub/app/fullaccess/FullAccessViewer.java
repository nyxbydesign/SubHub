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
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.tabs.TabLayout;
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
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Views and operations are owned by the selected tab and are discarded when its lease ends. */
final class FullAccessViewer implements AutoCloseable {
    private static final List<String> TABS = List.of("contacts", "notifications", "sms", "apps", "files", "screen");
    private static final int MAX_IMAGE_BYTES = 1024 * 1024;
    private static final long REFRESH_MILLIS = 5_000;
    private static final long FRAME_MILLIS = 500;
    private static final long SESSION_CHECK_MILLIS = 1_000;

    private final AppCompatActivity activity;
    private final Context app;
    private final PersonId target;
    private final BooleanSupplier allowed;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AsyncUiScope worker;
    private final LinearLayout panel;
    private final ArrayDeque<Folder> folders = new ArrayDeque<>();
    private final ArrayDeque<String> pageHistory = new ArrayDeque<>();
    private TabSession selectedSession;
    private String selected;
    private String cursor = "";
    private boolean resumed;
    private JSONObject lastPage;
    private TextView status;
    private ImageView image;
    private final Runnable guard = new Runnable() {
        @Override public void run() {
            TabSession session = selectedSession;
            if (!resumed || session == null) return;
            if (!allowed.getAsBoolean()) {
                pause();
                activity.finish();
                return;
            }
            if (session.connected && session.channel != null && !session.channel.isOpen()) {
                stopWith(R.string.full_access_unavailable);
                return;
            }
            main.postDelayed(this, SESSION_CHECK_MILLIS);
        }
    };

    FullAccessViewer(AppCompatActivity activity, LinearLayout parent, PersonId target, String initial,
            BooleanSupplier allowed) {
        this.activity = activity;
        this.app = activity.getApplicationContext();
        this.target = target;
        this.allowed = allowed;
        this.worker = AsyncUiScope.forPage(activity);
        selected = initial != null && TABS.contains(initial) ? initial : TABS.get(0);

        TabLayout tabs = new TabLayout(activity);
        tabs.setTabMode(TabLayout.MODE_SCROLLABLE);
        tabs.setTag("full_access_tabs");
        for (String scope : TABS)
            tabs.addTab(tabs.newTab().setText(label(scope)).setTag(scope), scope.equals(selected));
        parent.addView(tabs, new LinearLayout.LayoutParams(-1, -2));
        panel = PersonUi.column(activity);
        panel.setTag("full_access_view");
        parent.addView(panel, new LinearLayout.LayoutParams(-1, -2));
        tabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override public void onTabSelected(TabLayout.Tab tab) {
                selected = (String) tab.getTag();
                if (resumed) openTab();
            }
            @Override public void onTabUnselected(TabLayout.Tab tab) { }
            @Override public void onTabReselected(TabLayout.Tab tab) { }
        });
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

    String selected() { return selected; }

    void resume() {
        if (resumed) return;
        resumed = true;
        openTab();
    }

    void pause() {
        resumed = false;
        retireTab();
        panel.removeAllViews();
    }

    @Override public void close() { pause(); }

    private void retireTab() {
        TabSession retired = selectedSession;
        selectedSession = null;
        if (retired != null) retired.retire();
        main.removeCallbacksAndMessages(null);
        worker.cancelPrefix("full-access-view:");
        folders.clear();
        pageHistory.clear();
        cursor = "";
        lastPage = null;
        image = null;
        status = null;
    }

    private boolean current(TabSession session) {
        return selectedSession == session && session.live.get() && resumed && allowed.getAsBoolean();
    }

    private void openTab() {
        retireTab();
        if (!resumed || !allowed.getAsBoolean()) return;
        TabSession session = new TabSession(selected);
        selectedSession = session;
        showStatus(R.string.full_access_waiting, false);
        main.postDelayed(guard, SESSION_CHECK_MILLIS);
        if (persistent(session)) openChannel(session);
        else loadPage(session, "");
    }

    private static boolean persistent(TabSession session) {
        return "files".equals(session.scope) || "screen".equals(session.scope);
    }

    private void openChannel(TabSession session) {
        Context context = app;
        PersonId requested = target;
        Handler delivery = main;
        worker.load("full-access-view:connect", () -> resolve(context, requested), peer -> {
            if (!current(session)) return;
            CompletableFuture<FullAccessChannel> opening = RemoteFeature.get(context).openFullAccess(peer, session.scope);
            session.opening = opening;
            opening.whenComplete((channel, failure) -> {
                if (!session.attach(channel)) return;
                delivery.post(() -> {
                    if (!current(session)) { session.retire(); return; }
                    if (failure != null || channel == null) {
                        stopWith(R.string.full_access_unavailable);
                    } else if ("files".equals(session.scope)) {
                        loadPage(session, "");
                    } else {
                        pollScreen(session);
                    }
                });
            });
        }, failure -> { if (current(session)) stopWith(R.string.full_access_unavailable); });
    }

    private void loadPage(TabSession session, String requestedCursor) {
        if (!current(session) || session.working) return;
        session.working = true;
        Context context = app;
        PersonId requested = target;
        String node = folders.isEmpty() ? "" : folders.peek().id;
        String operation = "files".equals(session.scope) ? "list" : "page";
        worker.load("full-access-view:query", () -> {
            JSONObject command = command(operation, node, requestedCursor);
            if ("files".equals(session.scope)) return session.channel.query(command);
            RemoteFeature feature = RemoteFeature.get(context);
            return feature.requestFullAccess(resolve(context, requested), session.scope, command).get();
        }, page -> {
            if (!current(session)) return;
            session.working = false;
            session.connected = true;
            String state = page.optString("status", "unavailable");
            if ("ok".equals(state) || "ready".equals(state)) {
                cursor = requestedCursor;
                if (lastPage == null || !lastPage.toString().equals(page.toString())) {
                    lastPage = page;
                    renderPage(session, page);
                }
                if (!persistent(session) && cursor.isEmpty())
                    main.postDelayed(() -> loadPage(session, ""), REFRESH_MILLIS);
            } else {
                if (persistent(session)) stopWith(statusLabel(state));
                else {
                    lastPage = null;
                    showStatus(statusLabel(state), true);
                    main.postDelayed(() -> loadPage(session, ""), REFRESH_MILLIS);
                }
            }
        }, failure -> { if (current(session)) stopWith(R.string.full_access_unavailable); });
    }

    private void renderPage(TabSession session, JSONObject page) {
        panel.removeAllViews();
        LinearLayout card = PersonUi.card(panel);
        if ("files".equals(session.scope) && !folders.isEmpty()) {
            Folder folder = folders.peek();
            PersonUi.text(card, folder.title, true);
            PersonUi.action(card, R.string.full_access_folder_up, () -> {
                if (!current(session) || session.working) return;
                folders.pop();
                resetDirectory(session);
            }, false);
        }
        long since = page.optLong("since");
        if ("notifications".equals(session.scope) && since > 0)
            PersonUi.text(card, activity.getString(R.string.full_access_notifications_start, date(since)), false);
        if ("apps".equals(session.scope) && !page.optBoolean("usageAvailable"))
            PersonUi.text(card, activity.getString(R.string.full_access_usage_unavailable), false);

        JSONArray rows = page.optJSONArray("items");
        if (rows == null || rows.length() == 0) PersonUi.text(card, activity.getString(R.string.full_access_empty), false);
        if (rows != null) for (int index = 0; index < Math.min(64, rows.length()); index++) {
            JSONObject item = rows.optJSONObject(index);
            if (item == null) continue;
            if ("files".equals(session.scope)) fileRow(card, session, item);
            else dataRow(card, session.scope, item);
        }

        if ("files".equals(session.scope) && !cursor.isEmpty()) {
            PersonUi.action(card, R.string.full_access_first_page, () -> {
                if (current(session) && !session.working) resetDirectory(session);
            }, false);
        } else if (!pageHistory.isEmpty()) {
            PersonUi.action(card, R.string.full_access_previous, () -> {
                if (!current(session) || session.working || pageHistory.isEmpty()) return;
                loadPage(session, pageHistory.pop());
            }, false);
        }
        String next = page.optString("nextCursor", "");
        if (!next.isEmpty() && !next.equals(cursor)) PersonUi.action(card, R.string.full_access_more, () -> {
            if (!current(session) || session.working) return;
            if (!"files".equals(session.scope)) pageHistory.push(cursor);
            loadPage(session, next);
        }, false);
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
        PersonUi.row(card, 0, title, detail, null);
    }

    private String date(long time) {
        return DateUtils.formatDateTime(activity, time, DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_TIME);
    }

    private CharSequence duration(long time) {
        return DateUtils.formatElapsedTime(Math.max(0, time) / 1000);
    }

    private void fileRow(LinearLayout card, TabSession session, JSONObject item) {
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
            folders.push(new Folder(id, title));
            resetDirectory(session);
        } : null);
        if (item.optBoolean("previewable"))
            PersonUi.action(entry, R.string.full_access_preview, () -> preview(session, id), false);
        PersonUi.action(entry, directory ? R.string.full_access_download_folder : R.string.full_access_download,
                () -> download(session, id, title, mime, directory), false);
    }

    private void resetDirectory(TabSession session) {
        pageHistory.clear();
        lastPage = null;
        cursor = "";
        loadPage(session, "");
    }

    private void preview(TabSession session, String id) {
        if (!current(session) || session.working) return;
        session.working = true;
        worker.load("full-access-view:preview", () -> {
            LimitedImage output = new LimitedImage();
            session.channel.transfer(command("preview", id, ""), output);
            return decode(output.bytes());
        }, preview -> {
            if (!current(session)) return;
            session.working = false;
            if (preview == null) {
                showTransient(R.string.full_access_unavailable);
                return;
            }
            if (image != null) panel.removeView(image);
            image = frameView(R.string.full_access_preview_image);
            image.setImageBitmap(preview);
            panel.addView(image, 0, new LinearLayout.LayoutParams(-1, -2));
        }, failure -> {
            if (!current(session)) return;
            session.working = false;
            showTransient(R.string.full_access_unavailable);
        });
    }

    private void download(TabSession session, String id, String title, String mime, boolean directory) {
        if (!current(session) || session.working) return;
        session.working = true;
        Context context = app;
        String name = directory ? title + ".zip" : title;
        String mediaType = directory ? "application/zip" : mime;
        TextView progress = PersonUi.text(panel, activity.getString(R.string.full_access_download_progress, name), false);
        panel.removeView(progress);
        panel.addView(progress, 0);
        AtomicLong bytes = new AtomicLong();
        updateProgress(session, progress, name, bytes);
        worker.load("full-access-view:transfer", () -> FullAccessDownloads.save(context, name, mediaType,
                () -> session.live.get() && session.channel.isCurrent(),
                destination -> session.channel.transfer(command(directory ? "archive" : "download", id, ""),
                        new ProgressOutput(destination, bytes))),
                uri -> {
                    if (!current(session)) return;
                    session.working = false;
                    progress.setText(R.string.full_access_download_ready);
                    PersonUi.action(panel, R.string.full_access_open_download, () -> openDownloaded(uri, mediaType), false);
                }, failure -> {
                    if (!current(session)) return;
                    session.working = false;
                    progress.setText(R.string.full_access_download_failed);
                });
    }

    private void updateProgress(TabSession session, TextView progress, String name, AtomicLong bytes) {
        if (!current(session) || !session.working || progress.getParent() != panel) return;
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

    private void pollScreen(TabSession session) {
        if (!current(session)) return;
        long started = SystemClock.elapsedRealtime();
        worker.load("full-access-view:frame", () -> {
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
                if (image == null) {
                    panel.removeAllViews();
                    image = frameView(R.string.full_access_screen_image);
                    panel.addView(image, new LinearLayout.LayoutParams(-1, -2));
                }
                if (frame.bitmap != null) image.setImageBitmap(frame.bitmap);
            } else if ("awaiting_consent".equals(frame.state)) {
                if (status == null || !activity.getText(R.string.full_access_screen_pending).equals(status.getText()))
                    showStatus(R.string.full_access_screen_pending, false);
            } else {
                stopWith(statusLabel(frame.state));
                return;
            }
            main.postDelayed(() -> pollScreen(session), Math.max(0, FRAME_MILLIS - (SystemClock.elapsedRealtime() - started)));
        }, failure -> { if (current(session)) stopWith(R.string.full_access_unavailable); });
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

    private void stopWith(int label) {
        retireTab();
        showStatus(label, true);
    }

    private void showStatus(int label, boolean retry) {
        panel.removeAllViews();
        image = null;
        status = PersonUi.text(panel, activity.getString(label), false);
        if (retry) PersonUi.action(panel, R.string.role_retry, this::openTab, false);
    }

    private void showTransient(int label) {
        android.widget.Toast.makeText(activity, label, android.widget.Toast.LENGTH_SHORT).show();
    }

    private static int statusLabel(String status) {
        return switch (status) {
            case "disabled" -> R.string.full_access_disabled;
            case "permission_required" -> R.string.full_access_permission_needed;
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

    private static final class TabSession {
        final String scope;
        final AtomicBoolean live = new AtomicBoolean(true);
        volatile FullAccessChannel channel;
        CompletableFuture<FullAccessChannel> opening;
        boolean working, connected;

        TabSession(String scope) { this.scope = scope; }

        synchronized boolean attach(FullAccessChannel opened) {
            if (!live.get()) {
                if (opened != null) opened.close();
                return false;
            }
            channel = opened;
            return true;
        }

        synchronized void retire() {
            live.set(false);
            if (opening != null) opening.cancel(true);
            if (channel != null) channel.close();
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
