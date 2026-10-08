package com.subhub.app.settings;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

/** Safe, synthetic preview for the border controls on Censor Settings. */
public final class BorderPreviewView extends View {
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF sample = new RectF();
    private final Matrix shaderMatrix = new Matrix();
    private CensorAppearance appearance = CensorAppearance.defaults();

    public BorderPreviewView(Context context, AttributeSet attrs) {
        super(context, attrs);
        border.setStyle(Paint.Style.STROKE);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    public void setAppearance(CensorAppearance value) {
        appearance = value == null ? CensorAppearance.defaults() : value;
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float inset = dp(14);
        float height = Math.min(getHeight() - dp(20), dp(54));
        float top = (getHeight() - height) / 2f;
        sample.set(inset, top, getWidth() - inset, top + height);
        if (sample.isEmpty()) return;

        fill.setShader(new LinearGradient(sample.left, sample.top, sample.right, sample.bottom,
                Color.rgb(24, 19, 31), Color.rgb(10, 9, 14), Shader.TileMode.CLAMP));
        canvas.drawRoundRect(sample, dp(12), dp(12), fill);
        fill.setShader(null);

        if (appearance.isShowBorder()) drawBorder(canvas);
        else {
            border.setShader(null);
            border.setColor(Color.rgb(63, 53, 73));
            border.setAlpha(150);
            border.setStrokeWidth(dp(1));
            canvas.drawRoundRect(sample, dp(12), dp(12), border);
        }
        if (appearance.isShowBorder() && appearance.isAnimateBorder()) {
            postInvalidateDelayed(32L);
        }
    }

    private void drawBorder(Canvas canvas) {
        border.setShader(null);
        border.setColor(appearance.getBorderColor());
        border.setAlpha(255);
        border.setStrokeWidth(dp(2));
        float phase = appearance.isAnimateBorder()
                ? (SystemClock.uptimeMillis() % 4000L) / 4000f * 360f : 0f;
        int save = canvas.save();
        canvas.clipRect(sample);
        switch (appearance.getBorderEffect()) {
            case GLOW:
                for (int step = 4; step >= 1; step--) {
                    border.setStrokeWidth(dp(2 + step * 2));
                    border.setAlpha(28 + step * 13);
                    canvas.drawRoundRect(sample, dp(12), dp(12), border);
                }
                border.setStrokeWidth(dp(2));
                border.setAlpha(255);
                break;
            case GRADIENT:
                LinearGradient gradient = new LinearGradient(
                        sample.left, sample.top, sample.right, sample.bottom,
                        appearance.getGradientStart(), appearance.getGradientEnd(), Shader.TileMode.CLAMP);
                shaderMatrix.setRotate(phase, sample.centerX(), sample.centerY());
                gradient.setLocalMatrix(shaderMatrix);
                border.setShader(gradient);
                break;
            case RAINBOW:
                SweepGradient rainbow = new SweepGradient(sample.centerX(), sample.centerY(),
                        new int[]{Color.RED, Color.YELLOW, Color.GREEN, Color.CYAN,
                                Color.BLUE, Color.MAGENTA, Color.RED}, null);
                shaderMatrix.setRotate(phase, sample.centerX(), sample.centerY());
                rainbow.setLocalMatrix(shaderMatrix);
                border.setShader(rainbow);
                break;
            case CLASSIC:
            default:
                break;
        }
        canvas.drawRoundRect(sample, dp(12), dp(12), border);
        canvas.restoreToCount(save);
        border.setShader(null);
        border.setAlpha(255);
    }

    private static int blend(int from, int to, float fraction) {
        float value = Math.max(0f, Math.min(1f, fraction));
        return Color.rgb(
                Math.round(Color.red(from) + (Color.red(to) - Color.red(from)) * value),
                Math.round(Color.green(from) + (Color.green(to) - Color.green(from)) * value),
                Math.round(Color.blue(from) + (Color.blue(to) - Color.blue(from)) * value));
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
