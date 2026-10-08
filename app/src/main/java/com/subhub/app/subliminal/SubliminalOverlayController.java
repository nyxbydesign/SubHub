package com.subhub.app.subliminal;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.subhub.app.stats.AchievementManager;
import com.subhub.app.stats.StatsRepository;

import java.security.SecureRandom;
import java.util.Collections;
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
    private final StatsRepository stats;
    private final SubliminalSettingsRepository repository;
    private SubliminalSettings settings;
    private List<String> phrases = Collections.emptyList();
    private boolean eligible;
    private boolean added;
    private String lastPhrase;

    private final Runnable showNext = this::showNextMessage;

    public SubliminalOverlayController(Context context) {
        // TYPE_ACCESSIBILITY_OVERLAY needs the AccessibilityService's window token. Using the
        // application context loses that token and can abort the event which activates censoring.
        Context windowContext = context;
        this.context = context.getApplicationContext();
        windows = windowContext.getSystemService(WindowManager.class);
        stats = new StatsRepository(this.context);
        repository = new SubliminalSettingsRepository(this.context);
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
    }

    public void updateSettings() {
        settings = repository.load();
        phrases = repository.phrases(settings);
        message.setTextSize(settings.getTextSizeSp());
        if (phrases.isEmpty()) hideAndUnschedule();
        else if (eligible) scheduleNext(false);
    }

    public void setEligible(boolean value) {
        if (eligible == value) return;
        eligible = value;
        if (!eligible) hideAndUnschedule();
        else {
            ensureAdded();
            updateSettings();
            scheduleNext(false);
        }
    }

    private void ensureAdded() {
        if (added || windows == null) return;
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
        added = true;
    }

    private void scheduleNext(boolean afterDisplay) {
        main.removeCallbacks(showNext);
        if (!eligible || phrases.isEmpty()) return;
        long minimum = settings.getMinimumIntervalMillis();
        long maximum = Math.max(minimum, settings.getMaximumIntervalMillis());
        long delay = minimum;
        if (maximum > minimum) {
            delay += Math.floorMod(random.nextLong(), maximum - minimum + 1L);
        }
        if (!afterDisplay) delay = Math.max(1_000L, delay);
        main.postDelayed(showNext, delay);
    }

    private void showNextMessage() {
        if (!eligible || phrases.isEmpty() || !added) return;
        int index = SubliminalPhraseSelection.nextIndex(phrases, lastPhrase, random);
        lastPhrase = phrases.get(index);
        message.animate().cancel();
        message.setAlpha(0f);
        message.setText(phrases.get(index));
        message.setTextSize(settings.getTextSizeSp());
        root.post(() -> {
            if (!eligible || !added) return;
            placeInSafeRandomPosition();
            float targetAlpha = settings.getOpacityPercent() / 100f;
            long hold = Math.max(0L, settings.getVisibleMillis() - (FADE_MILLIS * 2L));
            message.animate().alpha(targetAlpha).setDuration(FADE_MILLIS)
                    .withEndAction(() -> message.animate().alpha(0f).setStartDelay(hold)
                            .setDuration(FADE_MILLIS).withEndAction(() -> scheduleNext(true)).start())
                    .start();
            stats.recordSubliminalImpression();
            new AchievementManager(context).checkAchievements(stats.load());
        });
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
        main.removeCallbacks(showNext);
        message.animate().cancel();
        message.setAlpha(0f);
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    @Override public void close() {
        eligible = false;
        hideAndUnschedule();
        if (added && windows != null) {
            try { windows.removeViewImmediate(root); }
            catch (IllegalArgumentException ignored) { }
        }
        added = false;
    }
}
