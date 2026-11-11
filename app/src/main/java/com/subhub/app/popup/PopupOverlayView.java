package com.subhub.app.popup;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;

/** One compact touchable overlay window; touches outside its window continue to the app below. */
final class PopupOverlayView extends View {
    private final PopupStormManager manager;
    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint captionPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint haloPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF destination = new RectF();
    private Popup popup;

    PopupOverlayView(Context context, PopupStormManager manager, Popup popup) {
        super(context);
        this.manager = manager;
        this.popup = popup;
        setBackgroundColor(android.graphics.Color.TRANSPARENT);
        captionPaint.setColor(android.graphics.Color.WHITE);
        captionPaint.setTextAlign(Paint.Align.CENTER);
        captionPaint.setTypeface(Typeface.DEFAULT_BOLD);
        haloPaint.setColor(android.graphics.Color.BLACK);
        haloPaint.setTextAlign(Paint.Align.CENTER);
        haloPaint.setTypeface(Typeface.DEFAULT_BOLD);
    }

    @Override protected void onDraw(Canvas canvas) {
        manager.drawPopup(() -> drawPopup(canvas));
    }

    private void drawPopup(Canvas canvas) {
        float alpha = Math.max(0, Math.min(1, popup.alphaAt(System.currentTimeMillis())));
        if (alpha <= 0) return;
        float centerX = getWidth() / 2f;
        float centerY = getHeight() / 2f;
        int save = canvas.save();
        canvas.rotate(popup.rotation, centerX, centerY);
        bitmapPaint.setAlpha((int) (alpha * 255));
        destination.set(centerX - popup.width / 2f, centerY - popup.height / 2f,
                centerX + popup.width / 2f, centerY + popup.height / 2f);
        Bitmap image = popup.denialBitmap == null ? popup.bitmap : popup.denialBitmap;
        if (image != null && !image.isRecycled()) canvas.drawBitmap(image, null, destination, bitmapPaint);
        if (popup.caption != null && !popup.caption.isBlank()) {
            drawCaption(canvas, popup.caption, centerX, centerY, alpha);
        }
        canvas.restoreToCount(save);
    }

    private void drawCaption(Canvas canvas, String text, float centerX, float centerY, float alpha) {
        float size = Math.max(28, Math.min(popup.height * .28f, 96));
        captionPaint.setTextSize(size);
        haloPaint.setTextSize(size);
        captionPaint.setAlpha((int) (alpha * 255));
        haloPaint.setAlpha((int) (alpha * 200));
        float y = centerY - (captionPaint.descent() + captionPaint.ascent()) / 2f;
        float halo = Math.max(2, size * .08f);
        for (int xOffset = -1; xOffset <= 1; xOffset++) {
            for (int yOffset = -1; yOffset <= 1; yOffset++) {
                if (xOffset != 0 || yOffset != 0) {
                    canvas.drawText(text, centerX + xOffset * halo, y + yOffset * halo, haloPaint);
                }
            }
        }
        canvas.drawText(text, centerX, y, captionPaint);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            manager.onPopupTapped(popup);
            return true;
        }
        return false;
    }
}
