package com.subhub.app.popup;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.LruCache;

import androidx.documentfile.provider.DocumentFile;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Memory-bounded decoder and bounded SAF/private-folder image scanner. */
final class PopupBitmapCache {
    private static final int MAX_DECODE_EDGE = 1280;
    private static final int MAX_SCAN_DEPTH = 12;
    private final Context context;
    private final LruCache<String, Bitmap> cache;

    PopupBitmapCache(Context context) {
        this.context = context.getApplicationContext();
        int budgetKb = Math.max(8 * 1024,
                Math.min(48 * 1024, (int) (Runtime.getRuntime().maxMemory() / 1024 / 10)));
        cache = new LruCache<String, Bitmap>(budgetKb) {
            @Override protected int sizeOf(String key, Bitmap value) {
                return Math.max(1, value.getAllocationByteCount() / 1024);
            }
        };
    }

    synchronized Bitmap get(String path) {
        Bitmap existing = cache.get(path);
        if (existing != null && !existing.isRecycled()) return existing;
        Bitmap decoded = decode(path);
        if (decoded != null) cache.put(path, decoded);
        return decoded;
    }

    synchronized void clear() { cache.evictAll(); }

    List<String> listFolderImages(String folder, int maximum) {
        if (folder == null || folder.isBlank() || maximum <= 0) return List.of();
        if (folder.startsWith("content://")) return walkSaf(Uri.parse(folder), maximum);
        return walkFiles(new File(folder), maximum);
    }

    private Bitmap decode(String path) {
        File temporary = null;
        try {
            temporary = File.createTempFile("popup-input-", ".source", context.getCacheDir());
            try (InputStream input = open(path); java.io.OutputStream output = new java.io.FileOutputStream(temporary)) {
                if (com.subhub.app.util.BoundedCopy.copy(input, output, com.subhub.app.util.MediaInput.IMAGE_BYTES) == 0) return null;
            }
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(temporary.getPath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
            int sample = 1;
            while (Math.max(bounds.outWidth / sample, bounds.outHeight / sample) > MAX_DECODE_EDGE) {
                sample *= 2;
            }
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            return BitmapFactory.decodeFile(temporary.getPath(), options);
        } catch (Exception ignored) {
            return null;
        } finally { if (temporary != null) temporary.delete(); }
    }

    private InputStream open(String path) throws Exception {
        if (path.startsWith("asset://")) {
            return context.getAssets().open(path.substring("asset://".length()));
        }
        if (path.startsWith("content://")) {
            InputStream input = context.getContentResolver().openInputStream(Uri.parse(path));
            if (input == null) throw new IllegalStateException("Image is unavailable");
            return input;
        }
        return new FileInputStream(path);
    }

    private List<String> walkFiles(File root, int maximum) {
        List<String> result = new ArrayList<>();
        try {
            File canonicalRoot = root.getCanonicalFile();
            if (!canonicalRoot.isDirectory()) return result;
            ArrayDeque<FileDepth> queue = new ArrayDeque<>();
            queue.add(new FileDepth(canonicalRoot, 0));
            while (!queue.isEmpty() && result.size() < maximum) {
                FileDepth current = queue.removeFirst();
                File[] entries = current.file.listFiles();
                if (entries == null) continue;
                for (File entry : entries) {
                    if (result.size() >= maximum) break;
                    File canonical = entry.getCanonicalFile();
                    if (!inside(canonicalRoot, canonical)) continue;
                    if (canonical.isDirectory() && current.depth < MAX_SCAN_DEPTH) {
                        queue.addLast(new FileDepth(canonical, current.depth + 1));
                    } else if (canonical.isFile() && isImage(canonical.getName())) {
                        result.add(canonical.getAbsolutePath());
                    }
                }
            }
        } catch (Exception ignored) {
            // Invalid/inaccessible directories contribute no images.
        }
        return result;
    }

    private List<String> walkSaf(Uri rootUri, int maximum) {
        List<String> result = new ArrayList<>();
        try {
            DocumentFile root = DocumentFile.fromTreeUri(context, rootUri);
            if (root == null || !root.isDirectory()) return result;
            ArrayDeque<SafDepth> queue = new ArrayDeque<>();
            queue.add(new SafDepth(root, 0));
            while (!queue.isEmpty() && result.size() < maximum) {
                SafDepth current = queue.removeFirst();
                for (DocumentFile entry : current.file.listFiles()) {
                    if (result.size() >= maximum) break;
                    if (entry.isDirectory() && current.depth < MAX_SCAN_DEPTH) {
                        queue.addLast(new SafDepth(entry, current.depth + 1));
                    } else if (entry.isFile() && isImage(entry.getName())) {
                        result.add(entry.getUri().toString());
                    }
                }
            }
        } catch (Exception ignored) {
            // Revoked or malformed SAF trees contribute no images.
        }
        return result;
    }

    private static boolean inside(File root, File candidate) {
        String prefix = root.getPath() + File.separator;
        return candidate.equals(root) || candidate.getPath().startsWith(prefix);
    }

    private static boolean isImage(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".png") || lower.endsWith(".webp")
                || lower.endsWith(".gif") || lower.endsWith(".bmp");
    }

    private static final class FileDepth {
        final File file;
        final int depth;
        FileDepth(File file, int depth) { this.file = file; this.depth = depth; }
    }

    private static final class SafDepth {
        final DocumentFile file;
        final int depth;
        SafDepth(DocumentFile file, int depth) { this.file = file; this.depth = depth; }
    }
}
