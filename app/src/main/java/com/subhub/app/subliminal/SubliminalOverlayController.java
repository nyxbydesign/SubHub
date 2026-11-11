package com.subhub.app.subliminal;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.subhub.app.stats.AchievementManager;
import com.subhub.app.stats.StatsRepository;

import java.security.SecureRandom;
import java.util.List;

/** One touch-through accessibility overlay used only while an assigned app is protected. */
public final class SubliminalOverlayController implements AutoCloseable {
    private static final long FADE_MILLIS = 300L;

    private final Context context;
    private final WindowManager windows;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final SecureRandom random = new SecureRandom();
    private final FrameLayout root;
    private final TextView message;
    /** The runtime implementation holds its counted effect permit through the entire callback. */
    public interface Effects {
        boolean run(Runnable effect);
        void recordImpression();
        /** Must be a lock-free capability observation, not a registry/journal query. */
        default boolean revoked() { return false; }
        default void failed() { }
    }

    private final Effects effects;
    private SubliminalSettingsRepository repository;
    private SubliminalSettings settings;
    private List<String> phrases;
    private final WhisperLifecycle lifetime;
    private final Object apply = new Object(), show = new Object(), display = new Object(), fade = new Object();
    private boolean eligible;
    private String lastPhrase;
    private long displayStarted;

    public SubliminalOverlayController(Context context) {
        this(context, new SubliminalSettingsRepository(context), deviceEffects(context));
    }

    /** Window ownership and data/accounting are deliberately separate dependencies. */
    public SubliminalOverlayController(Context context, SubliminalSettingsRepository repository, Effects effects) {
        requireMain();
        // TYPE_ACCESSIBILITY_OVERLAY needs the AccessibilityService's window token. Using the
        // application context loses that token and can abort the event which activates censoring.
        Context windowContext = context;
        this.context = context.getApplicationContext();
        windows = windowContext.getSystemService(WindowManager.class);
        this.effects = java.util.Objects.requireNonNull(effects);
        this.repository = java.util.Objects.requireNonNull(repository);
        settings = repository.load();
        phrases = repository.phrases(settings);

        root = new FrameLayout(this.context);
        root.setClipChildren(true);
        root.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        message = new TextView(this.context);
        message.setTextColor(Color.WHITE);
        message.setGravity(Gravity.CENTER);
        message.setMaxLines(2);
        message.setAlpha(0f);
        message.setShadowLayer(dp(3), 0f, dp(1), Color.BLACK);
        root.addView(message, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT));
        lifetime = new WhisperLifecycle(new WhisperLifecycle.Effects() {
            @Override public boolean run(Runnable effect) { return effects.run(effect); }
            @Override public boolean revoked() { return effects.revoked(); }
            @Override public void failed() { effects.failed(); }
        }, new WhisperLifecycle.Queue() {
            @Override public boolean post(Runnable callback, long delay) {
                return main.postDelayed(callback, delay);
            }
            @Override public void remove(Runnable callback) { main.removeCallbacks(callback); }
        }, new WhisperLifecycle.Window() {
            @Override public void add() { addWindow(); }
            @Override public boolean attached() {
                return root.isAttachedToWindow() || root.getParent() != null;
            }
            @Override public void remove() {
                if (windows == null) throw new IllegalStateException("Whisper window manager unavailable");
                windows.removeViewImmediate(root);
            }
            @Override public void hide() { message.setAlpha(0f); }
        });
    }

    private static Effects deviceEffects(Context context) {
        Context application = context.getApplicationContext();
        StatsRepository statistics = new StatsRepository(application);
        return new Effects() {
            @Override public boolean run(Runnable effect) { effect.run(); return true; }
            @Override public void recordImpression() {
                statistics.recordSubliminalImpression();
                new AchievementManager(application).checkAchievements(statistics.load());
            }
        };
    }

    /** All animation frames are owned callbacks, not untracked ViewPropertyAnimator end actions. */
    private void displayMessage() {
        if (!eligible || !lifetime.hasWindow() || phrases.isEmpty()) return;
        placeInSafeRandomPosition();
        message.setAlpha(0f);
        displayStarted = SystemClock.uptimeMillis();
        effects.recordImpression(); // Exactly one counted enqueue, inside the current effect.
        lifetime.post(fade, this::fadeFrame, 16L);
    }

    private void fadeFrame() {
        if (!eligible || !lifetime.hasWindow() || phrases.isEmpty()) return;
        long elapsed = Math.max(0L, SystemClock.uptimeMillis() - displayStarted);
        long visible = settings.getVisibleMillis();
        float fraction;
        if (elapsed < FADE_MILLIS) fraction = elapsed / (float) FADE_MILLIS;
        else if (elapsed < visible - FADE_MILLIS) fraction = 1f;
        else fraction = Math.max(0f, (visible - elapsed) / (float) FADE_MILLIS);
        // Match the platform animator's default accelerate/decelerate interpolation.
        float eased = (float) (0.5d - Math.cos(Math.PI * fraction) / 2d);
        message.setAlpha(opacityAlpha(settings) * eased);
        if (elapsed < visible) lifetime.post(fade, this::fadeFrame, Math.min(16L, visible - elapsed));
        else scheduleNext(true);
    }

    /**
     * True only when this snapshot was synchronously applied. Busy retains the latest request for
     * retry; a runtime configuration receiver MUST reject false rather than acknowledge a revision.
     */
    public boolean updateSettings(SubliminalSettingsRepository acknowledgedRepository) {
        requireMain();
        if (lifetime.isClosed()) return false;
        repository = java.util.Objects.requireNonNull(acknowledgedRepository);
        return updateSettings();
    }

    public boolean updateSettings() {
        requireMain();
        if (lifetime.isClosed()) return false;
        return lifetime.runOrRetry(apply, () -> {
            SubliminalSettings next = repository.load();
            List<String> nextPhrases = repository.phrases(next);
            hideAndUnschedule();
            settings = next;
            phrases = nextPhrases;
            message.setTextSize(settings.getTextSizeSp());
            if (eligible && !phrases.isEmpty()) {
                lifetime.ensureAdded();
                scheduleNext(false);
            }
        });
    }

    public void setEligible(boolean value) {
        requireMain();
        if (lifetime.isClosed() || eligible == value) return;
        eligible = value; // Desired eligibility survives busy admission and pending config updates.
        if (!eligible) hideAndUnschedule();
        else updateSettings();
    }

    private void addWindow() {
        if (windows == null) throw new IllegalStateException("Whisper window manager unavailable");
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        windows.addView(root, params);
    }

    private void scheduleNext(boolean afterDisplay) {
        lifetime.cancel(show);
        if (!eligible || phrases.isEmpty()) return;
        long minimum = settings.getMinimumIntervalMillis();
        long maximum = Math.max(minimum, settings.getMaximumIntervalMillis());
        long delay = minimum;
        if (maximum > minimum) delay += Math.floorMod(random.nextLong(), maximum - minimum + 1L);
        if (!afterDisplay) delay = Math.max(1_000L, delay);
        lifetime.post(show, this::showNextMessage, delay);
    }

    private void showNextMessage() {
        if (!eligible || phrases.isEmpty() || !lifetime.hasWindow()) return;
        int index = SubliminalPhraseSelection.nextIndex(phrases, lastPhrase, random);
        lastPhrase = phrases.get(index);
        lifetime.cancel(fade);
        message.setAlpha(0f);
        message.setText(lastPhrase);
        message.setTextSize(settings.getTextSizeSp());
        lifetime.post(display, this::displayMessage, 16L);
    }

    static float opacityAlpha(SubliminalSettings settings) {
        int opacity = Math.max(1, Math.min(SubliminalSettings.MAX_OPACITY_PERCENT,
                settings.getOpacityPercent()));
        return opacity / 100f;
    }

    private void placeInSafeRandomPosition() {
        Rect visible = new Rect();
        root.getWindowVisibleDisplayFrame(visible);
        int padding = dp(24);
        int width = Math.max(1, message.getMeasuredWidth());
        int height = Math.max(1, message.getMeasuredHeight());
        int left = Math.max(padding, visible.left + padding);
        int top = Math.max(padding, visible.top + padding);
        int right = Math.max(left, visible.right - padding - width);
        int bottom = Math.max(top, visible.bottom - padding - height);
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) message.getLayoutParams();
        params.leftMargin = randomBetween(left, right);
        params.topMargin = randomBetween(top, bottom);
        message.setLayoutParams(params);
    }

    private int randomBetween(int minimum, int maximum) {
        if (maximum <= minimum) return minimum;
        return minimum + random.nextInt(maximum - minimum + 1);
    }

    private void hideAndUnschedule() {
        lifetime.cancel(show);
        lifetime.cancel(display);
        lifetime.cancel(fade);
        lifetime.hide();
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private static void requireMain() {
        if (Looper.myLooper() != Looper.getMainLooper())
            throw new IllegalStateException("Whisper windows require the main thread");
    }

    /** A runtime may release this component only after its exact window and callbacks are gone. */
    public boolean managedResourcesDrained() { requireMain(); return lifetime.drained(); }

    public void close(Runnable afterDrained) {
        requireMain();
        eligible = false;
        lifetime.close(java.util.Objects.requireNonNull(afterDrained));
    }

    @Override public void close() {
        requireMain();
        eligible = false;
        lifetime.close(); // Repeated close retries uncertain teardown instead of inventing drain.
    }
}
