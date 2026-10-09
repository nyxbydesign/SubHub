package com.subhub.app.commitment;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import androidx.core.view.ViewCompat;
import com.subhub.app.R;
import com.subhub.app.capture.CensorRenderer;
import com.subhub.app.detection.BBox;
import com.subhub.app.detection.Detection;
import com.subhub.app.settings.CensorAppearance;
import com.subhub.app.settings.FeatureModuleManager;
import com.subhub.app.settings.SettingsRepository;
import com.subhub.app.util.AsyncUiScope;
import java.util.Collections;

/** A remaining-time ring; hidden time never enters a text node or a rendered source image. */
public final class ServiceCountdownView extends View {
    private static final String MASK_JOB = "service-countdown-mask";
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final Paint image = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint placeholder = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF circle = new RectF(), center = new RectF();
    private final AsyncUiScope jobs;
    private final SettingsRepository settings;
    private String label = "", requestedMask;
    private float fraction;
    private boolean hidden;
    private boolean permanent;
    private Mask mask;
    private ValueAnimator reveal;

    public ServiceCountdownView(Context context, AttributeSet attributes) {
        super(context, attributes);
        settings = new SettingsRepository(context);
        jobs = AsyncUiScope.forContext(context);
        track.setColor(context.getColor(R.color.outline_subtle));
        track.setStyle(Paint.Style.STROKE);
        arc.setColor(context.getColor(R.color.accent_mid));
        arc.setStyle(Paint.Style.STROKE);
        arc.setStrokeCap(Paint.Cap.ROUND);
        text.setColor(context.getColor(R.color.text_primary));
        text.setTypeface(Typeface.DEFAULT_BOLD);
        text.setTextAlign(Paint.Align.CENTER);
        placeholder.setColor(context.getColor(R.color.text_secondary));
        placeholder.setAlpha(100);
        ViewCompat.setScreenReaderFocusable(this, true);
    }

    public void setCountdown(long remaining, long duration, boolean hideTime, boolean animate) {
        permanent = false;
        hidden = hideTime;
        label = CommitmentActivity.formatDuration(remaining);
        setContentDescription(hideTime ? getContext().getString(R.string.pact_time_hidden)
                : getContext().getString(R.string.commitment_active_remaining, label));
        float next = duration <= 0 ? 0 : Math.max(0f, Math.min(1f, (float) remaining / duration));
        setFraction(next, animate);
        if (hidden) requestMask();
        invalidate();
    }

    public void setPermanent(boolean hideTime, boolean animate) {
        permanent = true;
        hidden = hideTime;
        label = "\u221e";
        setContentDescription(getContext().getString(hideTime
                ? R.string.pact_time_hidden : R.string.service_duration_permanent));
        setFraction(1f, animate);
        if (hidden) requestMask();
        invalidate();
    }

    private void setFraction(float next, boolean animate) {
        if (animate && ValueAnimator.areAnimatorsEnabled()) {
            if (reveal != null) reveal.cancel();
            reveal = ValueAnimator.ofFloat(0f, next);
            reveal.setDuration(650);
            reveal.setInterpolator(new DecelerateInterpolator());
            reveal.addUpdateListener(animation -> {
                fraction = (float) animation.getAnimatedValue();
                invalidate();
            });
            reveal.start();
        } else if (reveal == null || !reveal.isRunning()) fraction = next;
    }

    public void stop() {
        if (reveal != null) { reveal.cancel(); reveal = null; }
    }

    private void requestMask() {
        boolean enabled = new FeatureModuleManager(getContext()).isCensorEnabled();
        boolean configured = enabled && settings.preferences().contains(SettingsRepository.KEY_CENSOR_TYPE);
        String key = permanent + ":" + configured + ":" + settings.preferences().getAll().hashCode()
                + ":" + ValueAnimator.areAnimatorsEnabled();
        if (key.equals(requestedMask)) return;
        requestedMask = key;
        CensorAppearance saved = settings.loadAppearance();
        CensorAppearance appearance = configured
                ? new CensorAppearance(saved.getType(), saved.getIntensity(), 0f,
                        saved.isShowBorder(), saved.isAnimateBorder(), saved.getBorderEffect(), saved.isShowText(),
                        saved.getBorderColor(), saved.getEffectPalette(), saved.getPhrases(), false,
                        saved.getReverseStrength(), saved.getReverseCutoutShape(),
                        saved.getErrorTitle(), saved.getErrorMessage(),
                        saved.getGradientStart(), saved.getGradientEnd())
                : new CensorAppearance(CensorAppearance.Type.BLUR, 75, false, false, 0);
        Context app = getContext().getApplicationContext();
        String sourceLabel = permanent ? "\u221e" : "00:00:00";
        boolean animate = ValueAnimator.areAnimatorsEnabled() && (
                appearance.getType() == CensorAppearance.Type.STATIC
                        || appearance.getType() == CensorAppearance.Type.GLITCH
                        || appearance.getType() == CensorAppearance.Type.TAPE
                        || appearance.isShowBorder() && appearance.isAnimateBorder()
                            && (appearance.getBorderEffect() == CensorAppearance.BorderEffect.GRADIENT
                                || appearance.getBorderEffect() == CensorAppearance.BorderEffect.RAINBOW));
        jobs.load(MASK_JOB, () -> makeMask(app, appearance, animate, sourceLabel), ready -> {
            if (mask != null) mask.close();
            mask = ready;
            invalidate();
        }, failure -> {
            // The placeholder contains no time; a decode failure must not reveal the digits.
            if (mask != null) mask.close();
            mask = null;
            invalidate();
        });
    }

    private static Mask makeMask(Context app, CensorAppearance appearance, boolean animated, String sourceLabel) {
        Bitmap source = Bitmap.createBitmap(240, 76, Bitmap.Config.ARGB_8888);
        Bitmap[] frames = new Bitmap[animated ? 120 : 1];
        boolean complete = false;
        try {
            Paint glyphs = new Paint(Paint.ANTI_ALIAS_FLAG);
            glyphs.setColor(app.getColor(R.color.text_primary));
            glyphs.setTypeface(Typeface.DEFAULT_BOLD);
            glyphs.setTextAlign(Paint.Align.CENTER);
            glyphs.setTextSize(38);
            // Weak, translucent or animated Censor styles cannot leak a real hidden countdown.
            new Canvas(source).drawText(sourceLabel, 120, 51, glyphs);
            try (CensorRenderer renderer = appearance.getType() == CensorAppearance.Type.CUSTOM
                    ? new CensorRenderer(app) : new CensorRenderer(app, Collections.emptyList())) {
                java.util.List<Detection> region = Collections.singletonList(new Detection(
                        "COUNTDOWN", "countdown", 1f, new BBox(0, 0, 240, 76), true, true));
                for (int index = 0; index < frames.length; index++) {
                    if (Thread.currentThread().isInterrupted())
                        throw new java.util.concurrent.CancellationException();
                    frames[index] = Bitmap.createBitmap(240, 76, Bitmap.Config.ARGB_8888);
                    renderer.drawAtTime(frames[index], source, region, appearance,
                            index * Mask.CYCLE_MILLIS / frames.length);
                }
            }
            Mask result = new Mask(frames, appearance.getType());
            complete = true;
            return result;
        } finally {
            source.recycle();
            if (!complete) for (Bitmap frame : frames) if (frame != null) frame.recycle();
        }
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float size = Math.min(getWidth(), getHeight());
        float stroke = Math.max(dp(7), size * .047f);
        float radius = Math.max(0, (size - stroke - dp(8)) / 2);
        float x = getWidth() / 2f, y = getHeight() / 2f;
        circle.set(x - radius, y - radius, x + radius, y + radius);
        track.setStrokeWidth(stroke); arc.setStrokeWidth(stroke);
        canvas.drawOval(circle, track);
        canvas.drawArc(circle, -90, 360 * fraction, false, arc);
        float width = radius * 1.65f, height = width * 76 / 240;
        center.set(x - width / 2, y - height / 2, x + width / 2, y + height / 2);
        if (hidden) {
            if (mask != null) {
                canvas.drawBitmap(ValueAnimator.areAnimatorsEnabled()
                        ? mask.frame(android.os.SystemClock.uptimeMillis()) : mask.frames[0], null, center, image);
                if (mask.frames.length > 1 && ValueAnimator.areAnimatorsEnabled()) postInvalidateOnAnimation();
            }
            else canvas.drawRoundRect(center, dp(8), dp(8), placeholder);
        } else {
            text.setTextSize(26 * getResources().getDisplayMetrics().scaledDensity);
            float measured = text.measureText(permanent ? "00:00:00" : label);
            if (measured > width) text.setTextSize(text.getTextSize() * width / measured);
            canvas.drawText(label, x, y - (text.ascent() + text.descent()) / 2, text);
        }
    }

    @Override protected void onDetachedFromWindow() {
        stop();
        jobs.cancel(MASK_JOB);
        if (mask != null) { mask.close(); mask = null; }
        requestedMask = null;
        super.onDetachedFromWindow();
    }

    private float dp(float value) { return value * getResources().getDisplayMetrics().density; }

    private static final class Mask implements AutoCloseable {
        static final long CYCLE_MILLIS = 4000L;
        final Bitmap[] frames;
        final CensorAppearance.Type type;
        final long startedAt = android.os.SystemClock.uptimeMillis();
        Mask(Bitmap[] frames, CensorAppearance.Type type) { this.frames = frames; this.type = type; }
        Bitmap frame(long timeMillis) {
            int index = (int) (Math.max(0L, timeMillis - startedAt) % CYCLE_MILLIS * frames.length / CYCLE_MILLIS);
            return frames[index];
        }
        @Override public void close() { for (Bitmap frame : frames) if (!frame.isRecycled()) frame.recycle(); }
    }
}
