package com.subhub.app.util;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import com.subhub.app.R;
import java.io.IOException;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

/** Draws a downsampled version of the existing artwork for a soft background. */
public final class SoftBackgroundDrawable extends Drawable {
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private Bitmap artwork;

    @Override public void inflate(Resources resources, XmlPullParser parser, AttributeSet attrs,
            Resources.Theme theme) throws XmlPullParserException, IOException {
        super.inflate(resources, parser, attrs, theme);
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = 4;
        artwork = BitmapFactory.decodeResource(resources, R.drawable.subhub_background, options);
    }

    @Override public void draw(Canvas canvas) {
        if (artwork != null) canvas.drawBitmap(artwork, null, getBounds(), paint);
        else canvas.drawColor(0xff120b19);
    }

    @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
    @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
