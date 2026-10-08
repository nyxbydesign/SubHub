package com.subhub.app.settings;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

/**
 * A small, source-native sample used by the censor style cards.
 *
 * <p>The preview is intentionally synthetic: it communicates the effect without showing
 * private or explicit source imagery, and it keeps the settings screen useful offline.</p>
 */
public final class CensorPreviewView extends View {
    private static final int PLUM = Color.rgb(48, 31, 60);
    private static final int PLUM_LIGHT = Color.rgb(116, 76, 132);
    private static final int MAGENTA = Color.rgb(239, 44, 139);
    private static final int VIOLET = Color.rgb(210, 71, 230);
    private static final int CYAN = Color.rgb(57, 196, 226);
    private static final int INK = Color.rgb(15, 11, 20);

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF scene = new RectF();
    private final RectF target = new RectF();
    private final Path path = new Path();

    public CensorPreviewView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float width = getWidth();
        float height = getHeight();
        if (width <= 0f || height <= 0f) return;

        String style = String.valueOf(getTag());
        float edge = Math.min(width, height) * 0.06f;
        float phoneHeight = Math.min(height - 2 * edge, (width - 2 * edge) / .54f);
        float phoneWidth = phoneHeight * .54f;
        scene.set((width - phoneWidth) / 2f, (height - phoneHeight) / 2f,
                (width + phoneWidth) / 2f, (height + phoneHeight) / 2f);
        target.set(scene.left + scene.width() * .12f,
                scene.top + scene.height() * .30f,
                scene.right - scene.width() * .12f,
                scene.top + scene.height() * .76f);

        paint.setShader(new LinearGradient(scene.left, scene.top, scene.right, scene.bottom,
                Color.rgb(39, 25, 49), Color.rgb(25, 18, 33), Shader.TileMode.CLAMP));
        paint.setStyle(Paint.Style.FILL);
        float corner = scene.width() * .14f;
        canvas.drawRoundRect(scene, corner, corner, paint);
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(PLUM_LIGHT);
        paint.setStrokeWidth(Math.max(1f, scene.width() * .035f));
        canvas.drawRoundRect(scene, corner, corner, paint);

        drawSceneChrome(canvas);
        if ("pixelate".equals(style)) {
            drawPixelate(canvas);
        } else if ("blur".equals(style)) {
            drawBlur(canvas);
        } else if ("custom".equals(style)) {
            drawCustom(canvas);
        } else if ("static".equals(style)) {
            drawStatic(canvas);
        } else if ("glitch".equals(style)) {
            drawGlitch(canvas);
        } else if ("tape".equals(style)) {
            drawTape(canvas);
        } else if ("error".equals(style)) {
            drawError(canvas);
        } else {
            drawBox(canvas);
        }
    }

    private void drawSceneChrome(Canvas canvas) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(PLUM_LIGHT);
        canvas.drawRoundRect(scene.left + scene.width() * .35f,
                scene.top + scene.height() * .065f, scene.right - scene.width() * .35f,
                scene.top + scene.height() * .085f, 2, 2, paint);
        canvas.drawCircle(scene.left + scene.width() * .18f,
                scene.top + scene.height() * .19f, scene.width() * .05f, paint);
        paint.setColor(Color.rgb(83, 57, 102));
        canvas.drawRoundRect(scene.left + scene.width() * .30f,
                scene.top + scene.height() * .17f, scene.right - scene.width() * .12f,
                scene.top + scene.height() * .21f, 2, 2, paint);
        paint.setColor(Color.rgb(75, 48, 89));
        canvas.drawRoundRect(scene.left + scene.width() * .12f,
                scene.top + scene.height() * .81f, scene.right - scene.width() * .12f,
                scene.top + scene.height() * .84f, 2, 2, paint);
        canvas.drawRoundRect(scene.left + scene.width() * .12f,
                scene.top + scene.height() * .87f, scene.left + scene.width() * .60f,
                scene.top + scene.height() * .895f, 2, 2, paint);
        paint.setColor(PLUM_LIGHT);
        canvas.drawRoundRect(scene.left + scene.width() * .35f,
                scene.bottom - scene.height() * .055f, scene.right - scene.width() * .35f,
                scene.bottom - scene.height() * .035f, 2, 2, paint);
    }

    private void drawBox(Canvas canvas) {
        paint.setColor(INK);
        paint.setStyle(Paint.Style.FILL);
        canvas.drawRoundRect(target, target.height() * 0.12f, target.height() * 0.12f, paint);
        paint.setColor(MAGENTA);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(2f, target.height() * 0.045f));
        canvas.drawRoundRect(target, target.height() * 0.12f, target.height() * 0.12f, paint);
        drawBlockedWord(canvas, target.centerX(), target.centerY());
    }

    private void drawPixelate(Canvas canvas) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.rgb(28, 17, 36));
        canvas.drawRect(target, paint);
        int columns = 6;
        int rows = 4;
        float cellWidth = target.width() / columns;
        float cellHeight = target.height() / rows;
        for (int row = 0; row < rows; row++) {
            for (int column = 0; column < columns; column++) {
                paint.setColor(((row + column) % 3 == 0) ? MAGENTA
                        : ((row * 2 + column) % 3 == 0 ? VIOLET : Color.rgb(92, 39, 110)));
                float inset = Math.max(1f, cellWidth * 0.08f);
                canvas.drawRect(target.left + column * cellWidth + inset,
                        target.top + row * cellHeight + inset,
                        target.left + (column + 1) * cellWidth - inset,
                        target.top + (row + 1) * cellHeight - inset, paint);
            }
        }
    }

    private void drawBlur(Canvas canvas) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.argb(224, 66, 41, 83));
        canvas.drawRoundRect(target, target.height() * 0.12f, target.height() * 0.12f, paint);
        paint.setColor(Color.argb(135, 236, 64, 167));
        canvas.drawCircle(target.left + target.width() * 0.38f, target.centerY(),
                target.height() * 0.31f, paint);
        paint.setColor(Color.argb(118, 116, 76, 196));
        canvas.drawCircle(target.left + target.width() * 0.68f, target.centerY(),
                target.height() * 0.34f, paint);
        paint.setColor(Color.argb(98, 247, 183, 235));
        canvas.drawCircle(target.centerX(), target.centerY(), target.height() * 0.20f, paint);
    }

    private void drawCustom(Canvas canvas) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.rgb(27, 16, 35));
        canvas.drawRoundRect(target, target.height() * 0.12f, target.height() * 0.12f, paint);
        float cx = target.centerX();
        float cy = target.centerY();
        float radius = target.height() * 0.28f;
        paint.setColor(Color.rgb(101, 42, 128));
        path.reset();
        path.moveTo(cx - radius * 0.85f, cy + radius * 1.0f);
        path.lineTo(cx - radius * 0.55f, cy - radius * 0.82f);
        path.lineTo(cx - radius * 0.18f, cy - radius * 0.44f);
        path.lineTo(cx, cy - radius * 1.18f);
        path.lineTo(cx + radius * 0.18f, cy - radius * 0.44f);
        path.lineTo(cx + radius * 0.55f, cy - radius * 0.82f);
        path.lineTo(cx + radius * 0.85f, cy + radius * 1.0f);
        path.close();
        canvas.drawPath(path, paint);
        paint.setColor(MAGENTA);
        canvas.drawCircle(cx, cy - radius * 0.05f, radius * 0.25f, paint);
        paint.setColor(Color.WHITE);
        canvas.drawCircle(cx - radius * 0.10f, cy - radius * 0.10f, radius * 0.035f, paint);
        canvas.drawCircle(cx + radius * 0.10f, cy - radius * 0.10f, radius * 0.035f, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1.5f, radius * 0.08f));
        paint.setColor(MAGENTA);
        canvas.drawRoundRect(target, target.height() * 0.12f, target.height() * 0.12f, paint);
    }

    private void drawStatic(Canvas canvas) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.rgb(7, 7, 11));
        canvas.drawRoundRect(target, target.height() * 0.08f, target.height() * 0.08f, paint);
        int[] values = {VIOLET, Color.WHITE, Color.rgb(94, 83, 108), MAGENTA,
                Color.rgb(38, 31, 45), Color.WHITE};
        int columns = 8;
        int rows = 5;
        float cellWidth = target.width() / columns;
        float cellHeight = target.height() / rows;
        int offset = 0;
        for (int row = 0; row < rows; row++) {
            for (int column = 0; column < columns; column++) {
                paint.setColor(values[(row * 3 + column + offset) % values.length]);
                canvas.drawRect(target.left + column * cellWidth,
                        target.top + row * cellHeight,
                        target.left + (column + 1) * cellWidth + 0.5f,
                        target.top + (row + 1) * cellHeight + 0.5f, paint);
            }
            offset += 2;
        }
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1.5f, target.height() * 0.035f));
        paint.setColor(Color.WHITE);
        canvas.drawRoundRect(target, target.height() * 0.08f, target.height() * 0.08f, paint);
    }

    private void drawGlitch(Canvas canvas) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.rgb(7, 5, 12));
        canvas.drawRect(target, paint);
        float[] heights = {0.15f, 0.10f, 0.19f, 0.12f, 0.17f};
        float y = target.top + target.height() * 0.03f;
        for (int index = 0; index < heights.length; index++) {
            float bandHeight = target.height() * heights[index];
            float shift = target.width() * ((index % 2 == 0) ? 0.15f : -0.11f);
            paint.setColor(index % 3 == 0 ? CYAN : (index % 3 == 1 ? MAGENTA : VIOLET));
            canvas.drawRect(target.left + shift, y, target.right + shift * 0.45f,
                    y + bandHeight, paint);
            paint.setColor(index % 2 == 0 ? MAGENTA : CYAN);
            canvas.drawRect(target.left - shift * 0.55f, y + bandHeight * 0.58f,
                    target.right - shift * 0.20f, y + bandHeight, paint);
            y += bandHeight + target.height() * 0.025f;
        }
        paint.setColor(Color.WHITE);
        canvas.drawRect(target.left + target.width() * 0.08f,
                target.centerY() - target.height() * 0.045f,
                target.right - target.width() * 0.05f,
                target.centerY() + target.height() * 0.045f, paint);
    }

    private void drawTape(Canvas canvas) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.rgb(36, 25, 31));
        canvas.drawRoundRect(target, target.height() * 0.10f, target.height() * 0.10f, paint);
        canvas.save();
        canvas.clipRect(target);
        paint.setStrokeWidth(Math.max(4f, target.height() * 0.18f));
        for (int index = -2; index < 8; index++) {
            paint.setColor(index % 2 == 0 ? Color.rgb(246, 207, 61) : MAGENTA);
            float x = target.left + index * target.width() * 0.22f;
            canvas.drawLine(x, target.bottom + target.height() * 0.15f,
                    x + target.width() * 0.40f, target.top - target.height() * 0.15f, paint);
        }
        canvas.restore();
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1.5f, target.height() * 0.035f));
        paint.setColor(Color.rgb(251, 233, 141));
        canvas.drawRoundRect(target, target.height() * 0.10f, target.height() * 0.10f, paint);
    }

    private void drawError(Canvas canvas) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.rgb(232, 228, 234));
        RectF dialog = new RectF(target.left + target.width() * 0.05f,
                target.top + target.height() * 0.10f,
                target.right - target.width() * 0.05f,
                target.bottom - target.height() * 0.10f);
        canvas.drawRoundRect(dialog, target.height() * 0.08f, target.height() * 0.08f, paint);
        paint.setColor(Color.rgb(215, 38, 48));
        canvas.drawCircle(dialog.left + dialog.width() * 0.22f, dialog.centerY(),
                dialog.height() * 0.22f, paint);
        paint.setColor(Color.WHITE);
        paint.setStrokeWidth(Math.max(1.5f, dialog.height() * 0.06f));
        paint.setStyle(Paint.Style.STROKE);
        canvas.drawLine(dialog.left + dialog.width() * 0.16f,
                dialog.centerY() - dialog.height() * 0.08f,
                dialog.left + dialog.width() * 0.28f,
                dialog.centerY() + dialog.height() * 0.08f, paint);
        canvas.drawLine(dialog.left + dialog.width() * 0.28f,
                dialog.centerY() - dialog.height() * 0.08f,
                dialog.left + dialog.width() * 0.16f,
                dialog.centerY() + dialog.height() * 0.08f, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.rgb(74, 62, 80));
        canvas.drawRect(dialog.left + dialog.width() * 0.42f,
                dialog.top + dialog.height() * 0.30f,
                dialog.right - dialog.width() * 0.12f,
                dialog.top + dialog.height() * 0.37f, paint);
        canvas.drawRect(dialog.left + dialog.width() * 0.42f,
                dialog.top + dialog.height() * 0.48f,
                dialog.right - dialog.width() * 0.24f,
                dialog.top + dialog.height() * 0.55f, paint);
        paint.setColor(Color.rgb(0, 120, 215));
        canvas.drawRect(dialog.left + dialog.width() * 0.66f,
                dialog.bottom - dialog.height() * 0.20f,
                dialog.right - dialog.width() * 0.12f,
                dialog.bottom - dialog.height() * 0.08f, paint);
    }

    private void drawBlockedWord(Canvas canvas, float centerX, float centerY) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.rgb(221, 205, 226));
        float width = target.width() * 0.50f;
        float height = Math.max(2f, target.height() * 0.075f);
        canvas.drawRoundRect(centerX - width / 2f, centerY - height / 2f,
                centerX + width / 2f, centerY + height / 2f, height, height, paint);
    }
}
