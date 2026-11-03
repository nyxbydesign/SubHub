package com.subhub.app.remote.icons;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import com.subhub.app.util.RemoteAppIcon;
import java.io.ByteArrayOutputStream;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/** Header/hash validation precedes every bounded platform decode. */
final class IconPng {
    private IconPng() { }

    static void validate(RemoteAppIcon icon, byte[] png) {
        validatePixels(icon, png);
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(png, 0, png.length, bounds);
        if (bounds.outWidth != icon.descriptor.width || bounds.outHeight != icon.descriptor.height
                || !"image/png".equals(bounds.outMimeType)) throw new IllegalArgumentException("Invalid PNG decode bounds");
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inScaled = false;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap bitmap = BitmapFactory.decodeByteArray(png, 0, png.length, options);
        if (bitmap == null) throw new IllegalArgumentException("Invalid PNG pixels");
        try {
            if (bitmap.getWidth() != icon.descriptor.width || bitmap.getHeight() != icon.descriptor.height)
                throw new IllegalArgumentException("Invalid PNG pixels");
        } finally { bitmap.recycle(); }
    }

    /** BitmapFactory can return partial pixels after a decode error; prove the complete stream first. */
    static void validatePixels(RemoteAppIcon icon, byte[] png) {
        icon.validatePng(png);
        int depth = png[24] & 255, color = png[25] & 255, palette = 0;
        boolean data = false, afterData = false;
        // Input was already capped at 128 KiB. Concatenation never exceeds the owned PNG length.
        ByteArrayOutputStream compressed = new ByteArrayOutputStream(png.length);
        for (int offset = 8; offset < png.length;) {
            int length = number(png, offset), type = number(png, offset + 4);
            if (type == 0x6163544c || type == 0x6663544c || type == 0x66644154)
                throw new IllegalArgumentException("Animated PNG is not an app icon");
            if (type == 0x504c5445) {
                if (data || palette != 0 || color == 0 || color == 4
                        || length == 0 || length > 768 || length % 3 != 0)
                    throw new IllegalArgumentException("Invalid PNG palette");
                palette = length / 3;
                if (color == 3 && palette > (1 << depth)) throw new IllegalArgumentException("Invalid PNG palette depth");
            } else if (type == 0x49444154) {
                if (afterData) throw new IllegalArgumentException("Nonconsecutive PNG image data");
                data = true; compressed.write(png, offset + 8, length);
            } else if (type != 0x49484452 && type != 0x49454e44 && (type & 0x20000000) == 0) {
                throw new IllegalArgumentException("Unknown critical PNG chunk");
            }
            if (data && type != 0x49444154) afterData = true;
            offset += length + 12;
        }
        if (color == 3 && palette == 0) throw new IllegalArgumentException("Missing PNG palette");
        int channels = color == 2 ? 3 : color == 4 ? 2 : color == 6 ? 4 : 1;
        int bits = channels * depth;
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(compressed.toByteArray());
            int[] x = {0, 4, 0, 2, 0, 1, 0}, y = {0, 0, 4, 0, 2, 0, 1};
            int[] dx = {8, 8, 4, 4, 2, 2, 1}, dy = {8, 8, 8, 4, 4, 2, 2};
            boolean interlaced = png[28] == 1;
            int total = 0;
            for (int pass = 0; pass < (interlaced ? 7 : 1); pass++) {
                int width = interlaced ? span(icon.descriptor.width, x[pass], dx[pass]) : icon.descriptor.width;
                int height = interlaced ? span(icon.descriptor.height, y[pass], dy[pass]) : icon.descriptor.height;
                if (width == 0 || height == 0) continue;
                int rowBytes = (width * bits + 7) / 8;
                total += (rowBytes + 1) * height;
                // 16-bit RGBA plus all seven passes' filter/padding overhead, independent of deflate claims.
                if (total > RemoteAppIcon.MAX_PIXELS * (RemoteAppIcon.MAX_PIXELS * 8 + 7))
                    throw new IllegalArgumentException("PNG pixel budget exceeded");
                byte[] filter = new byte[1], row = new byte[rowBytes], previous = new byte[rowBytes];
                for (int line = 0; line < height; line++) {
                    inflate(inflater, filter); inflate(inflater, row);
                    int kind = filter[0] & 255;
                    if (kind > 4) throw new IllegalArgumentException("Invalid PNG row filter");
                    if (color == 3) {
                        unfilter(row, previous, kind);
                        int mask = (1 << depth) - 1;
                        for (int pixel = 0; pixel < width; pixel++) {
                            int bit = pixel * depth;
                            int index = (row[bit / 8] & 255) >>> (8 - depth - bit % 8) & mask;
                            if (index >= palette) throw new IllegalArgumentException("Invalid PNG palette index");
                        }
                        byte[] swap = previous; previous = row; row = swap;
                    }
                }
            }
            byte[] extra = new byte[1];
            if (inflater.inflate(extra) != 0 || !inflater.finished() || inflater.getRemaining() != 0)
                throw new IllegalArgumentException("Incomplete or excessive PNG pixel stream");
        } catch (DataFormatException invalid) {
            throw new IllegalArgumentException("Invalid PNG deflate/checksum", invalid);
        } finally { inflater.end(); }
    }

    private static int span(int size, int start, int step) { return size <= start ? 0 : (size - start + step - 1) / step; }

    private static void inflate(Inflater inflater, byte[] output) throws DataFormatException {
        int offset = 0;
        while (offset < output.length) {
            int count = inflater.inflate(output, offset, output.length - offset);
            if (count == 0) throw new IllegalArgumentException("Incomplete PNG pixel row");
            offset += count;
        }
    }

    // Indexed PNGs use one byte per filter pixel even when samples are packed into bits.
    private static void unfilter(byte[] row, byte[] previous, int kind) {
        for (int i = 0; i < row.length; i++) {
            int left = i == 0 ? 0 : row[i - 1] & 255, up = previous[i] & 255;
            int corner = i == 0 ? 0 : previous[i - 1] & 255;
            int prediction;
            if (kind == 0) prediction = 0;
            else if (kind == 1) prediction = left;
            else if (kind == 2) prediction = up;
            else if (kind == 3) prediction = (left + up) / 2;
            else {
                int p = left + up - corner, a = Math.abs(p - left), b = Math.abs(p - up), c = Math.abs(p - corner);
                prediction = a <= b && a <= c ? left : b <= c ? up : corner;
            }
            row[i] = (byte) ((row[i] & 255) + prediction);
        }
    }

    private static int number(byte[] bytes, int offset) {
        return (bytes[offset] & 255) << 24 | (bytes[offset + 1] & 255) << 16 | (bytes[offset + 2] & 255) << 8 | bytes[offset + 3] & 255;
    }
}
