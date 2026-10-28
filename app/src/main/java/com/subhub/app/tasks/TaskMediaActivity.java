package com.subhub.app.tasks;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.MediaController;
import android.widget.VideoView;
import com.subhub.app.R;
import com.subhub.app.people.PersonId;
import com.subhub.app.people.PersonRoutes;
import com.subhub.app.people.PersonUi;
import com.subhub.app.people.RolePage;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.util.SubHubNavigation;
import java.io.File;
import java.io.InputStream;
import org.json.JSONObject;

/** Opens only an attachment re-resolved from the selected relationship's private task store. */
public final class TaskMediaActivity extends RolePage {
    public static final String EXTRA_ATTACHMENT = "subhub.task.attachment";
    public static final String EXTRA_SCOPE = "subhub.task.scope";
    public static final String EXTRA_DOM = "subhub.task.dom";
    private TaskRepository repository;
    private PersonId person;
    private TaskAttachment attachment;
    private String scope;
    private boolean dom, resumed;
    private long generation;
    private ImageView image;
    private VideoView player;
    private MediaController controls;
    private Loaded displayed;
    private final Handler authorizationClock = new Handler(Looper.getMainLooper());
    private final Runnable observeAuthorization = this::observeAuthorization;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (!getIntent().hasExtra(PersonRoutes.EXTRA_PERSON_ID))
            getIntent().putExtra(PersonRoutes.EXTRA_PERSON_ID, PersonId.LOCAL.value());
        try {
            person = PersonRoutes.requireId(getIntent());
            String raw = getIntent().getStringExtra(EXTRA_ATTACHMENT);
            if (raw == null || raw.length() > 512) throw new IllegalArgumentException();
            attachment = TaskAttachment.parse(new JSONObject(raw));
        } catch (Exception invalid) { finish(); return; }
        scope = state == null ? getIntent().getStringExtra(EXTRA_SCOPE) : state.getString(EXTRA_SCOPE);
        dom = state == null ? getIntent().getBooleanExtra(EXTRA_DOM, ControllerPinManager.isDomModeActive())
                : state.getBoolean(EXTRA_DOM);
        repository = new TaskRepository(getApplicationContext(), person);
        page(R.string.tasks_media_title, SubHubNavigation.Screen.PEOPLE, false);
        secondaryBack(this::finish);
        body.setSaveFromParentEnabled(false);
    }

    @Override protected void onResume() {
        resumed = true;
        super.onResume();
        if (body != null) secondaryBack(this::finish);
    }

    @Override protected void onPause() {
        resumed = false;
        ++generation;
        if (data != null) { data.cancel("task-media"); data.cancel("task-play"); }
        clearMedia();
        super.onPause();
    }

    @Override protected void onNewIntent(Intent intent) { super.onNewIntent(intent); finish(); }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString(EXTRA_SCOPE, scope); state.putBoolean(EXTRA_DOM, dom);
        super.onSaveInstanceState(state);
    }

    @Override protected void refresh() {
        if (repository == null || !resumed) return;
        clearMedia();
        data.cancel("task-play");
        if (!liveRole()) { finish(); return; }
        long requested = ++generation;
        TaskRepository repo = repository;
        TaskAttachment media = attachment;
        String expected = scope;
        boolean mode = dom;
        Context app = getApplicationContext();
        loading();
        data.load("task-media", () -> load(repo, media, expected, mode, app), result -> {
            if (!resumed || requested != generation) { result.close(); return; }
            if (!liveRole()) { result.close(); revokeAccess(); return; }
            scope = result.scope;
            displayed = result;
            body.removeAllViews();
            if (result.bitmap != null) {
                image = new ImageView(this);
                image.setTag("task_media_image");
                image.setContentDescription(TasksActivity.mediaLabel(this, attachment));
                image.setAdjustViewBounds(true);
                image.setScaleType(ImageView.ScaleType.FIT_CENTER);
                image.setImageBitmap(result.bitmap);
                body.addView(image, new LinearLayout.LayoutParams(-1, -2));
            } else {
                player = new PrivateVideoView(requested);
                // Authorization is also checked for starts issued by native MediaController controls.
                player.setTag("task_media_player");
                controls = new MediaController(this);
                controls.setAnchorView(player);
                player.setMediaController(controls);
                int height = PersonUi.dp(this, attachment.mime.startsWith("audio/") ? 120 : 280);
                body.addView(player, new LinearLayout.LayoutParams(-1, height));
                player.setOnErrorListener((mp, what, extra) -> { showFailure(); return true; });
                player.setVideoURI(Uri.fromFile(result.file));
                player.setOnPreparedListener(mp -> {
                    if (!resumed || requested != generation) return;
                    if (!liveRole()) { revokeAccess(); return; }
                    // Wait for the user's native play control; never autoplay an attachment.
                    controls.show(0);
                });
                PersonUi.text(body, TasksActivity.mediaLabel(this, attachment), false);
            }
            scheduleAuthorization();
        }, failure -> {
            if (!resumed || requested != generation) return;
            if (failure instanceof SecurityException || !liveRole()) revokeAccess();
            else showFailure();
        });
    }

    private void scheduleAuthorization() {
        authorizationClock.removeCallbacks(observeAuthorization);
        if (resumed && displayed != null && !isFinishing())
            authorizationClock.postDelayed(observeAuthorization, 2_000);
    }

    private void observeAuthorization() {
        if (!resumed || displayed == null || isFinishing()) return;
        if (!liveRole()) { revokeAccess(); return; }
        // A decoder or another authorization request already owns the current read.
        if (data.isPending("task-media") || data.isPending("task-play") || data.isPending("task-observe")) {
            scheduleAuthorization();
            return;
        }
        TaskRepository repo = repository;
        TaskAttachment media = attachment;
        String expected = scope;
        boolean mode = dom;
        long requested = generation;
        data.load("task-observe", () -> { resolve(repo, media, expected, mode); return true; }, allowed -> {
            if (!resumed || requested != generation || displayed == null) return;
            if (!liveRole()) { revokeAccess(); return; }
            scheduleAuthorization();
        }, failure -> {
            if (resumed && requested == generation) revokeAccess();
        });
    }

    private void revokeAccess() {
        ++generation;
        data.cancel("task-media");
        data.cancel("task-play");
        clearMedia();
        finish();
    }

    private void authorizePlayback(VideoView view, long requested) {
        if (!resumed || requested != generation || data.isPending("task-play")) return;
        if (!liveRole()) { revokeAccess(); return; }
        TaskRepository repo = repository;
        TaskAttachment media = attachment;
        String expected = scope;
        boolean mode = dom;
        data.load("task-play", () -> { resolve(repo, media, expected, mode); return true; }, allowed -> {
            if (!resumed || generation != requested || player != view) return;
            if (!liveRole()) { revokeAccess(); return; }
            // MediaController's start routes back through this guard; use the base start once authorized.
            ((PrivateVideoView) view).startAuthorized();
        }, failure -> {
            if (!resumed || requested != generation) return;
            if (failure instanceof SecurityException || !liveRole()) revokeAccess();
            else showFailure();
        });
    }

    private final class PrivateVideoView extends VideoView {
        private final long requested;
        PrivateVideoView(long requested) { super(TaskMediaActivity.this); this.requested = requested; }
        @Override public void start() { authorizePlayback(this, requested); }
        void startAuthorized() { super.start(); }
    }

    private boolean liveRole() {
        try { return dom == ControllerPinManager.isDomModeActive() && person.equals(PersonRoutes.requireId(getIntent())); }
        catch (IllegalArgumentException invalid) { return false; }
    }

    private void showFailure() {
        clearMedia();
        if (!resumed || isFinishing()) return;
        PersonUi.text(body, getString(R.string.tasks_media_unavailable), false);
        PersonUi.action(body, R.string.tasks_refresh, this::refresh, false);
    }

    private void clearMedia() {
        authorizationClock.removeCallbacks(observeAuthorization);
        if (data != null) data.cancel("task-observe");
        if (controls != null) { controls.hide(); controls = null; }
        if (player != null) {
            // MediaController requires a non-null MediaPlayerControl; detach through VideoView.
            player.setMediaController(null);
            player.setOnPreparedListener(null); player.setOnErrorListener(null);
            player.stopPlayback(); player.setVideoURI(null); player = null;
        }
        if (image != null) { image.setImageDrawable(null); image = null; }
        if (body != null) body.removeAllViews();
        if (displayed != null) { displayed.close(); displayed = null; }
    }

    static File resolve(TaskRepository repo, TaskAttachment media, String expected, boolean dom) throws Exception {
        TaskRepository.Target target = repo.target();
        if ((expected != null && !expected.equals(target.scope)) || dom != repo.isDom() || !repo.allowed())
            throw new SecurityException("Attachment access changed");
        TaskAttachment actual = TaskMedia.references(repo.engine()).get(media.hash);
        if (actual == null || !actual.compatible(media))
            throw new SecurityException("Attachment unavailable");
        File file = repo.mediaFile(actual);
        if (!target.scope.equals(repo.target().scope) || dom != repo.isDom())
            throw new SecurityException("Attachment access changed");
        return file;
    }

    static Loaded load(TaskRepository repo, TaskAttachment media, String expected, boolean dom, Context app) throws Exception {
        String scope = repo.target().scope;
        File file = resolve(repo, media, expected == null ? scope : expected, dom);
        String privateRoot = app.getFilesDir().getCanonicalPath() + File.separator;
        if (!file.getCanonicalPath().startsWith(privateRoot)) throw new SecurityException("Private attachment required");
        Bitmap bitmap = null;
        try {
            if (media.mime.startsWith("image/")) {
                BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
                try (InputStream input = repo.openMedia(media)) { BitmapFactory.decodeStream(input, null, bounds); }
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new IllegalArgumentException("Invalid image");
                BitmapFactory.Options options = new BitmapFactory.Options(); options.inSampleSize = 1;
                while (bounds.outWidth / options.inSampleSize > 1600 || bounds.outHeight / options.inSampleSize > 1600)
                    options.inSampleSize *= 2;
                try (InputStream input = repo.openMedia(media)) { bitmap = BitmapFactory.decodeStream(input, null, options); }
                if (bitmap == null) throw new IllegalArgumentException("Invalid image");
            }
            resolve(repo, media, scope, dom);
            return new Loaded(scope, file, bitmap);
        } catch (Exception problem) { if (bitmap != null) bitmap.recycle(); throw problem; }
    }

    static final class Loaded implements AutoCloseable {
        final String scope;
        final File file;
        final Bitmap bitmap;
        Loaded(String scope, File file, Bitmap bitmap) { this.scope = scope; this.file = file; this.bitmap = bitmap; }
        @Override public void close() { if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle(); }
    }
}
