package com.subhub.app.tasks;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.VideoView;
import androidx.appcompat.app.AppCompatActivity;
import com.subhub.app.R;
import com.subhub.app.people.PersonUi;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.util.AsyncUiScope;
import java.io.IOException;
import java.util.Locale;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** A private task embed keeps playback and decoded pixels inside its authorized page lifetime. */
final class TaskAttachmentView extends LinearLayout implements AutoCloseable {
    private final TaskRepository repository;
    private final TaskAttachment attachment;
    private final String scope, jobs = "task-embed-" + UUID.randomUUID();
    private final boolean dom;
    private final BooleanSupplier current;
    private final Runnable revoked;
    private final AsyncUiScope data;
    private final Handler clock = new Handler(Looper.getMainLooper());
    private final Runnable observe = this::observe;
    private final Runnable progress = this::updateProgress;
    private TaskMediaActivity.Loaded loaded;
    private ImageView image;
    private Player player;
    private Button play;
    private SeekBar seek;
    private TextView position;
    private long generation;
    private boolean closed, started, ready;

    TaskAttachmentView(AppCompatActivity activity, AsyncUiScope data, TaskRepository repository,
            TaskAttachment attachment, String scope, boolean dom, BooleanSupplier current, Runnable revoked) {
        super(activity);
        this.data = data; this.repository = repository; this.attachment = attachment;
        this.scope = scope; this.dom = dom; this.current = current; this.revoked = revoked;
        setOrientation(VERTICAL);
        setTag("task_inline_attachment:" + attachment.hash);
        status(R.string.tasks_media_loading, "task_inline_loading");
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (!started && !closed) { started = true; load(); }
    }

    @Override protected void onDetachedFromWindow() { close(); super.onDetachedFromWindow(); }

    boolean isClosed() { return closed; }

    private boolean live() {
        try {
            return !closed && current.getAsBoolean() && dom == ControllerPinManager.isDomModeActive()
                    && scope.equals(repository.target().scope) && repository.allowed();
        } catch (RuntimeException denied) { return false; }
    }

    private void load() {
        if (!live()) { deny(); return; }
        clearContent();
        long token = ++generation;
        status(R.string.tasks_media_loading, "task_inline_loading");
        TaskRepository repo = repository; TaskAttachment media = attachment;
        String expected = scope; boolean mode = dom; Context app = getContext().getApplicationContext();
        data.load(jobs + "-load", () -> TaskMediaActivity.load(repo, media, expected, mode, app), result -> {
            if (closed || token != generation) { result.close(); return; }
            if (!live()) { result.close(); deny(); return; }
            loaded = result; removeAllViews();
            if (result.bitmap != null) {
                image = new ImageView(getContext());
                image.setTag("task_inline_image");
                image.setContentDescription(TasksActivity.mediaLabel(getContext(), attachment));
                image.setAdjustViewBounds(true); image.setMaxHeight(bound(320));
                image.setScaleType(ImageView.ScaleType.FIT_CENTER); image.setImageBitmap(result.bitmap);
                addView(image, new LayoutParams(-1, -2));
            } else {
                player = new Player(getContext(), token);
                player.setTag("task_inline_player");
                player.setContentDescription(TasksActivity.mediaLabel(getContext(), attachment));
                addView(player, new LayoutParams(-1, bound(attachment.mime.startsWith("audio/") ? 72 : 260)));
                play = new Button(getContext()); play.setTag("task_inline_play");
                play.setText(R.string.tasks_media_play); play.setEnabled(false);
                play.setOnClickListener(v -> { if (player != null && player.isPlaying()) pause(); else if (player != null) player.start(); });
                addView(play, new LayoutParams(-1, -2));
                seek = new SeekBar(getContext()); seek.setTag("task_inline_seek");
                seek.setContentDescription(getContext().getString(R.string.tasks_media_seek)); seek.setEnabled(false);
                seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                    public void onProgressChanged(SeekBar bar, int value, boolean user) { }
                    public void onStartTrackingTouch(SeekBar bar) { }
                    public void onStopTrackingTouch(SeekBar bar) { authorize(token, false, bar.getProgress()); }
                });
                addView(seek, new LayoutParams(-1, -2));
                position = PersonUi.text(this, "", false); position.setTag("task_inline_position");
                status(R.string.tasks_media_loading, "task_inline_loading");
                player.setOnErrorListener((mp, what, extra) -> { if (token == generation && !closed) failure(false); return true; });
                player.setOnCompletionListener(mp -> { if (live() && token == generation) { play.setText(R.string.tasks_media_play); updateProgress(); } });
                player.setOnPreparedListener(mp -> {
                    if (token != generation || closed) return;
                    if (!live()) { deny(); return; }
                    View loading = findViewWithTag("task_inline_loading");
                    if (loading != null) loading.setVisibility(GONE);
                    ready = true; play.setEnabled(true); seek.setEnabled(true); seek.setMax(Math.max(1, player.getDuration()));
                    updateProgress();
                });
                player.setVideoURI(Uri.fromFile(result.file));
            }
            scheduleObserve();
        }, error -> {
            if (closed || token != generation) return;
            if (error instanceof SecurityException || !live()) deny();
            else failure(error instanceof IOException);
        });
    }

    private int bound(int dp) {
        return Math.max(PersonUi.dp(getContext(), 48), Math.min(PersonUi.dp(getContext(), dp),
                Math.round(getResources().getDisplayMetrics().heightPixels * .4f)));
    }

    private final class Player extends VideoView {
        private final long token;
        Player(Context context, long token) { super(context); this.token = token; }
        @Override public void start() { authorize(token, true, -1); }
        void startAuthorized() { super.start(); }
    }

    private void authorize(long token, boolean start, int offset) {
        if (!live()) { deny(); return; }
        if (token != generation || player == null || !ready) return;
        TaskRepository repo = repository; TaskAttachment media = attachment; String expected = scope; boolean mode = dom;
        Player requested = player;
        data.load(jobs + "-play", () -> { TaskMediaActivity.resolve(repo, media, expected, mode); return true; }, allowed -> {
            if (closed || token != generation || player != requested) return;
            if (!live()) { deny(); return; }
            if (offset >= 0) requested.seekTo(offset);
            if (start) { requested.startAuthorized(); play.setText(R.string.tasks_media_pause); }
            updateProgress();
        }, error -> { if (!closed && token == generation) { if (error instanceof SecurityException || !live()) deny(); else failure(false); } });
    }

    void pause() {
        if (player != null) player.pause();
        if (play != null) play.setText(R.string.tasks_media_play);
    }

    private void updateProgress() {
        clock.removeCallbacks(progress);
        if (closed || player == null || !ready) return;
        if (!live()) { deny(); return; }
        int at = Math.max(0, player.getCurrentPosition()), duration = Math.max(0, player.getDuration());
        if (!seek.isPressed()) seek.setProgress(at);
        position.setText(getContext().getString(R.string.tasks_media_position, duration(at), duration(duration)));
        clock.postDelayed(progress, 500);
    }

    private static String duration(int milliseconds) {
        int seconds = milliseconds / 1000;
        return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }

    private void scheduleObserve() {
        clock.removeCallbacks(observe);
        if (!closed && loaded != null) clock.postDelayed(observe, 2_000);
    }

    private void observe() {
        if (!live()) { deny(); return; }
        if (loaded == null) return;
        if (data.isPending(jobs + "-play")) { scheduleObserve(); return; }
        long token = generation; TaskRepository repo = repository; TaskAttachment media = attachment;
        String expected = scope; boolean mode = dom;
        data.load(jobs + "-observe", () -> { TaskMediaActivity.resolve(repo, media, expected, mode); return true; }, allowed -> {
            if (closed || token != generation) return;
            if (!live()) deny(); else scheduleObserve();
        }, error -> { if (!closed && token == generation) deny(); });
    }

    private void status(int text, String tag) {
        TextView label = PersonUi.text(this, getContext().getString(text), false);
        label.setTag(tag); label.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
    }

    private void failure(boolean offline) {
        clearContent();
        if (closed) return;
        status(offline ? R.string.tasks_media_offline : R.string.tasks_media_unavailable, "task_inline_error");
        PersonUi.action(this, R.string.tasks_refresh, this::load, false).setTag("task_inline_retry");
    }

    private void deny() {
        if (closed) return;
        close();
        // Attachment can be denied during dispatchAttachedToWindow. Refresh after that
        // traversal so a stale page cannot recursively attach another denied embed.
        clock.post(revoked);
    }

    private void clearContent() {
        clock.removeCallbacksAndMessages(null);
        data.cancel(jobs + "-play"); data.cancel(jobs + "-observe");
        ready = false;
        if (player != null) {
            player.setOnPreparedListener(null); player.setOnErrorListener(null); player.setOnCompletionListener(null);
            player.stopPlayback(); player.setVideoURI(null); player = null;
        }
        if (image != null) { image.setImageDrawable(null); image = null; }
        if (loaded != null) { loaded.close(); loaded = null; }
        removeAllViews(); play = null; seek = null; position = null;
    }

    @Override public void close() {
        if (closed) return;
        closed = true; ++generation; data.cancelPrefix(jobs); clearContent();
    }
}
