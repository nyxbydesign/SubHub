package com.subhub.app.fullaccess;

import android.Manifest;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.UriPermission;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.ThumbnailUtils;
import android.net.Uri;
import android.os.Build;
import android.os.CancellationSignal;
import android.os.Environment;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.DocumentsContract;
import android.provider.Settings;
import android.util.Size;
import android.webkit.MimeTypeMap;

import androidx.annotation.RequiresApi;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Read-only view of user-shared volumes and explicitly selected local storage trees. */
public final class FullAccessFiles {
    private static final String PREFS = "subhub_full_access_files";
    private static final String ROOTS = "tree_roots";
    private static final String PAIRED_STORAGE_EPOCH = "paired_storage_epoch";
    // Before Demo requested storage access, paired setup could use an existing device grant.
    private static final long EXISTING_PAIRED_STORAGE = 0;
    private static final long LOCAL_STORAGE_ONLY = -1;
    private static final String EXTERNAL_PROVIDER = "com.android.externalstorage.documents";
    private static final String BINARY = "application/octet-stream";
    private static final String DIRECTORY = DocumentsContract.Document.MIME_TYPE_DIR;
    private static final int MAX_ROOTS = 16;
    private static final int FIRST_FOLDER_PAGE = 12;
    private static final int PAGE_SIZE = 32;
    private static final int PAGE_SCAN_LIMIT = 256;
    private static final int MAX_HANDLES = 8192;
    private static final int MAX_DEPTH = 64;
    private static final int MAX_ARCHIVE_ENTRIES = 30_000;
    private static final int BUFFER_SIZE = 32 * 1024;
    private static final int THUMBNAIL_SIDE = 640;
    private static final long MAX_IMAGE_SOURCE = 8L * 1024 * 1024;
    private static final long MAX_TRANSFER_BYTES = 32L * 1024 * 1024 * 1024;
    private static final String[] DOCUMENT_COLUMNS = {
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_FLAGS
    };
    private static final Object GRANT_LOCK = new Object();

    private FullAccessFiles() { }

    /** Launch only from an explicit local Files setup action. */
    @RequiresApi(30)
    static Intent broadStorageIntent(Context context) {
        return new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.fromParts("package", context.getPackageName(), null));
    }

    static boolean supportsSharedRoots() { return Build.VERSION.SDK_INT != 29; }

    enum Setup { READY, ANDROID_PERMISSION, PAIRED_REVIEW, ANDROID_10 }

    static Setup setupState(Context context, boolean demo, long epoch) {
        if (!supportsSharedRoots()) return Setup.ANDROID_10;
        return setupState(Build.VERSION.SDK_INT >= 30, deviceStorageGranted(context), demo, pairedStorageEpoch(context), epoch);
    }

    static Setup setupState(boolean supportsExistingGrant, boolean granted, boolean demo, long chosen, long epoch) {
        boolean authorized = sharedRootsAuthorized(demo, supportsExistingGrant, chosen, epoch);
        if (granted) return authorized ? Setup.READY : Setup.PAIRED_REVIEW;
        return !demo && chosen != EXISTING_PAIRED_STORAGE && !authorized ? Setup.PAIRED_REVIEW : Setup.ANDROID_PERMISSION;
    }

    static boolean deviceStorageGranted(Context context) {
        if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager();
        return Build.VERSION.SDK_INT <= 28 && context.checkSelfPermission(
                Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    /** Fence a grant obtained for Demo before handing off to Android's settings or permission UI. */
    static boolean prepareDemoStorageRequest(Context context) {
        synchronized (GRANT_LOCK) {
            return deviceStorageGranted(context) || rootsPreferences(context, null).edit()
                    .putLong(PAIRED_STORAGE_EPOCH, LOCAL_STORAGE_ONLY).commit();
        }
    }

    static boolean approvePairedSharedRoots(Context context, long epoch) {
        if (epoch <= 0 || FullAccessGrant.epoch(context) != epoch) return false;
        synchronized (GRANT_LOCK) {
            return deviceStorageGranted(context) && rootsPreferences(context, null).edit()
                    .putLong(PAIRED_STORAGE_EPOCH, epoch).commit();
        }
    }

    static boolean sharedRootsAvailable(Context context, boolean demo, long epoch) {
        return deviceStorageGranted(context)
                && sharedRootsAuthorized(demo, Build.VERSION.SDK_INT >= 30, pairedStorageEpoch(context), epoch);
    }

    private static long pairedStorageEpoch(Context context) {
        return rootsPreferences(context, null).getLong(PAIRED_STORAGE_EPOCH, EXISTING_PAIRED_STORAGE);
    }

    static boolean sharedRootsAuthorized(boolean demo, boolean supportsExistingGrant, long chosen, long epoch) {
        return demo || (epoch > 0 && (chosen == epoch
                || (supportsExistingGrant && chosen == EXISTING_PAIRED_STORAGE)));
    }

    public static Session open(Context context, BooleanSupplier allowed) {
        return open(context, allowed, null);
    }

    static Session open(Context context, BooleanSupplier allowed, String demoGeneration) {
        Context app = context.getApplicationContext();
        long epoch = demoGeneration == null ? FullAccessGrant.epoch(app) : -1;
        return new Session(app, allowed, demoGeneration, epoch);
    }

    private static boolean hasReadGrant(ContentResolver resolver, Uri tree) {
        for (UriPermission permission : resolver.getPersistedUriPermissions()) {
            if (permission.isReadPermission() && tree.equals(permission.getUri())) return true;
        }
        return false;
    }

    private static android.content.SharedPreferences rootsPreferences(Context context, String demoGeneration) {
        return context.getSharedPreferences(demoGeneration == null ? PREFS : "demo_" + PREFS, Context.MODE_PRIVATE);
    }

    /** Retain the deliberately selected roots of installations that predate volume browsing. */
    private static List<Uri> readableTrees(Context context, String demoGeneration) {
        var choices = rootsPreferences(context, demoGeneration);
        if (demoGeneration != null && !demoGeneration.equals(choices.getString("generation", ""))) return List.of();
        Set<String> selected = choices.getStringSet(ROOTS, Collections.emptySet());
        List<Uri> trees = new ArrayList<>();
        for (UriPermission permission : context.getContentResolver().getPersistedUriPermissions()) {
            Uri uri = permission.getUri();
            if (permission.isReadPermission() && selected.contains(uri.toString()) && validTree(uri)) trees.add(uri);
        }
        return trees;
    }

    private static boolean validTree(Uri tree) {
        if (tree == null || !"content".equals(tree.getScheme()) || !EXTERNAL_PROVIDER.equals(tree.getAuthority())
                || tree.getQuery() != null || tree.getFragment() != null || !DocumentsContract.isTreeUri(tree)) {
            return false;
        }
        try {
            String rootId = DocumentsContract.getTreeDocumentId(tree);
            return tree.getPathSegments().size() == 2 && allowedDocument(rootId, rootId);
        } catch (IllegalArgumentException malformed) {
            return false;
        }
    }

    static boolean allowedDocument(String rootId, String documentId) {
        int colon = rootId.indexOf(':');
        if (colon <= 0 || colon != documentId.indexOf(':') || !documentId.startsWith(rootId.substring(0, colon + 1))) {
            return false;
        }
        String rootPath = rootId.substring(colon + 1);
        String path = documentId.substring(colon + 1);
        if (!rootPath.isEmpty() && !path.equals(rootPath) && !path.startsWith(rootPath + "/")) return false;
        if (path.isEmpty()) return true;
        String[] parts = path.split("/", -1);
        for (String part : parts) {
            if (part.isEmpty() || ".".equals(part) || "..".equals(part) || part.indexOf('\\') >= 0
                    || part.indexOf('\0') >= 0) return false;
        }
        return parts.length < 2 || !protectedStorage(parts[0], parts[1]);
    }

    private static boolean protectedStorage(String parent, String child) {
        return "Android".equalsIgnoreCase(parent) && ("data".equalsIgnoreCase(child)
                || "obb".equalsIgnoreCase(child) || "sandbox".equalsIgnoreCase(child));
    }

    static Path checkedPath(Path root, Path candidate) throws IOException {
        if (!candidate.startsWith(root) || !candidate.equals(candidate.normalize()))
            throw new IOException("Shared folder unavailable");
        Path relative = root.relativize(candidate);
        if (relative.getNameCount() >= 2
                && protectedStorage(relative.getName(0).toString(), relative.getName(1).toString())) {
            throw new IOException("Folder outside shared storage");
        }
        if (!candidate.equals(candidate.toRealPath()) || Files.isSymbolicLink(candidate))
            throw new IOException("Shared item unavailable");
        return candidate;
    }

    @RequiresApi(30)
    private static List<Node> sharedVolumes(Context context) {
        List<Node> roots = new ArrayList<>();
        StorageManager manager = context.getSystemService(StorageManager.class);
        if (manager == null) return roots;
        Set<Path> unique = new HashSet<>();
        for (StorageVolume volume : manager.getStorageVolumes()) {
            if (!mounted(volume.getState())) continue;
            addRoot(roots, unique, volume.getDirectory(), volume.getDescription(context));
            if (roots.size() == MAX_ROOTS) break;
        }
        return roots;
    }

    private static List<Node> legacySharedVolumes(Context context) {
        List<Node> roots = new ArrayList<>();
        StorageManager manager = context.getSystemService(StorageManager.class);
        if (manager == null) return roots;
        Set<Path> unique = new HashSet<>();
        for (File appDirectory : context.getExternalFilesDirs(null)) {
            if (appDirectory == null) continue;
            Path volumeRoot = legacyVolumeRoot(appDirectory.toPath(), context.getPackageName());
            StorageVolume volume = manager.getStorageVolume(appDirectory);
            if (volumeRoot == null || volume == null || !mounted(volume.getState())) continue;
            addRoot(roots, unique, volumeRoot.toFile(), volume.getDescription(context));
            if (roots.size() == MAX_ROOTS) break;
        }
        return roots;
    }

    static Path legacyVolumeRoot(Path appDirectory, String packageName) {
        Path relative = Paths.get("Android", "data", packageName, "files");
        if (!appDirectory.isAbsolute() || !appDirectory.equals(appDirectory.normalize())
                || !appDirectory.endsWith(relative) || appDirectory.getNameCount() <= relative.getNameCount())
            return null;
        Path volume = appDirectory;
        for (int index = 0; index < relative.getNameCount(); index++) volume = volume.getParent();
        return volume;
    }

    private static boolean mounted(String state) {
        return Environment.MEDIA_MOUNTED.equals(state) || Environment.MEDIA_MOUNTED_READ_ONLY.equals(state);
    }

    private static void addRoot(List<Node> roots, Set<Path> unique, File directory, String title) {
        if (directory == null) return;
        try {
            Path path = directory.toPath().toRealPath();
            if (unique.add(path)) roots.add(Node.path(path, path, title, DIRECTORY, -1));
        } catch (IOException | SecurityException unavailable) {
            // A detached or inaccessible volume is omitted.
        }
    }

    public static final class Session implements AutoCloseable {
        private final Context context;
        private final ContentResolver resolver;
        private final BooleanSupplier allowed;
        private final String demoGeneration;
        private final long pairedEpoch;
        private final CancellationSignal cancellation = new CancellationSignal();
        private final Map<String, Node> nodes = new LinkedHashMap<>(64, 0.75f, true);
        private volatile boolean closed;
        private volatile StorageGrant exposedStorage = new StorageGrant(false, Set.of());
        private ChildReader page;
        private String pageNode;
        private String cursorToken;
        private InputStream activeInput;

        private Session(Context context, BooleanSupplier allowed, String demoGeneration, long pairedEpoch) {
            this.context = context;
            resolver = context.getContentResolver();
            this.allowed = allowed;
            this.demoGeneration = demoGeneration;
            this.pairedEpoch = pairedEpoch;
        }

        public JSONObject list(String node, String cursor) throws IOException, JSONException {
            checkLive();
            try {
                if (node == null || node.isEmpty()) {
                    closePage();
                    if (cursor != null && !cursor.isEmpty()) return status("expired");
                    return roots();
                }
                Node directory = lookup(node);
                if (directory == null || !directory.directory()) return status("expired");
                checkAvailable(directory);
                if (cursor == null || cursor.isEmpty()) {
                    closePage();
                    attachPage(node, children(directory));
                } else if (!node.equals(pageNode) || !cursor.equals(cursorToken)) {
                    return status("expired");
                }
                int limit = cursor == null || cursor.isEmpty() ? FIRST_FOLDER_PAGE : PAGE_SIZE;
                JSONArray items = new JSONArray();
                ChildReader reader;
                synchronized (this) { reader = page; }
                if (reader == null) return status("expired");
                for (int scanned = 0; scanned < PAGE_SCAN_LIMIT && items.length() < limit && reader.advance(); scanned++) {
                    if (scanned % 8 == 0) checkLive();
                    Node child = reader.current();
                    if (child != null) items.put(describe(child));
                }
                checkAvailable(directory);
                JSONObject response = status("ready").put("items", items);
                if (!reader.exhausted()) {
                    cursorToken = UUID.randomUUID().toString();
                    return response.put("nextCursor", cursorToken);
                }
                closePage();
                return response;
            } catch (SecurityException | IOException | DirectoryIteratorException
                     | android.os.OperationCanceledException unavailable) {
                closePage();
                checkLive();
                return status(missingRootStatus());
            }
        }

        /**
         * Checks OS grants for roots already exposed by this connection. This performs no file
         * traversal or document-provider calls, and can run while the source worker is blocked.
         */
        public boolean isStorageGrantCurrent() {
            if (closed) return false;
            StorageGrant exposed = exposedStorage;
            try {
                if (exposed.broad && !sharedRootsAvailable()) return false;
                if (exposed.trees.isEmpty()) return true;
                Set<Uri> readable = new HashSet<>();
                for (UriPermission permission : resolver.getPersistedUriPermissions()) {
                    if (permission.isReadPermission()) readable.add(permission.getUri());
                }
                return readable.containsAll(exposed.trees);
            } catch (RuntimeException unavailable) {
                return false;
            }
        }

        private JSONObject roots() throws IOException, JSONException {
            JSONArray items = new JSONArray();
            boolean exposedBroad = false;
            Set<Uri> exposedTrees = new HashSet<>(exposedStorage.trees);
            if (sharedRootsAvailable()) {
                List<Node> roots = Build.VERSION.SDK_INT >= 30 ? sharedVolumes(context) : legacySharedVolumes(context);
                for (Node root : roots) items.put(describe(root));
                exposedBroad = items.length() > 0;
            }
            if (items.length() == 0) {
                for (Uri tree : readableTrees(context, demoGeneration)) {
                    checkLive();
                    try {
                        String id = DocumentsContract.getTreeDocumentId(tree);
                        Uri document = DocumentsContract.buildDocumentUriUsingTree(tree, id);
                        try (Cursor row = query(document)) {
                            if (row.moveToFirst()) {
                                Node root = documentNode(tree, row);
                                if (root != null && root.directory()) {
                                    items.put(describe(root));
                                    exposedTrees.add(tree);
                                }
                            }
                        }
                    } catch (IOException | SecurityException unavailable) {
                        // Other granted roots remain usable.
                    }
                }
            }
            checkLive();
            exposedStorage = new StorageGrant(exposedStorage.broad || exposedBroad, Set.copyOf(exposedTrees));
            if (!isStorageGrantCurrent()) throw new IOException("Storage access ended");
            return status(items.length() == 0 ? missingRootStatus() : "ready").put("items", items);
        }

        public String mime(String node, String operation) throws IOException {
            Node selected = requireNode(node);
            switch (operation) {
                case "preview":
                    if (!selected.previewable()) throw new IOException("Preview unavailable");
                    return "image/jpeg";
                case "download":
                    if (selected.directory()) throw new IOException("Select a file");
                    return selected.mime;
                case "archive":
                    if (!selected.directory()) throw new IOException("Select a folder");
                    return "application/zip";
                default:
                    throw new IOException("Unsupported file request");
            }
        }

        public void transfer(String node, String operation, OutputStream output) throws IOException {
            Node selected = requireNode(node);
            mime(node, operation);
            try {
                switch (operation) {
                    case "preview":
                        preview(selected, output);
                        break;
                    case "download":
                        copy(selected, output, new TransferLimit());
                        break;
                    case "archive":
                        archive(selected, output);
                        break;
                    default:
                        throw new IOException("Unsupported file request");
                }
                checkAvailable(selected);
            } catch (SecurityException | android.os.OperationCanceledException unavailable) {
                throw new IOException("File access ended");
            }
        }

        private void preview(Node node, OutputStream output) throws IOException {
            Bitmap thumbnail = null;
            try {
                Size bounds = new Size(THUMBNAIL_SIDE, THUMBNAIL_SIDE);
                if (Build.VERSION.SDK_INT >= 29 && node.document != null) {
                    try {
                        thumbnail = resolver.loadThumbnail(node.document, bounds, cancellation);
                    } catch (IOException unsupported) {
                        if (node.mime.startsWith("image/")) thumbnail = sampleImage(node);
                    }
                } else if (Build.VERSION.SDK_INT >= 29 && node.mime.startsWith("video/")) {
                    Path safe = checkedPath(node.root, node.path);
                    thumbnail = ThumbnailUtils.createVideoThumbnail(safe.toFile(), bounds, cancellation);
                } else if (node.mime.startsWith("image/")) {
                    thumbnail = sampleImage(node);
                }
                if (thumbnail == null) throw new IOException("Preview unavailable");
                checkAvailable(node);
                if (!thumbnail.compress(Bitmap.CompressFormat.JPEG, 80, output))
                    throw new IOException("Preview unavailable");
            } finally {
                if (thumbnail != null) thumbnail.recycle();
            }
        }

        private Bitmap sampleImage(Node node) throws IOException {
            if (node.size < 0 || node.size > MAX_IMAGE_SOURCE) return null;
            byte[] image;
            try (InputStream input = openInput(node)) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream((int) node.size);
                byte[] buffer = new byte[BUFFER_SIZE];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    checkAvailable(node);
                    if (bytes.size() + count > MAX_IMAGE_SOURCE) return null;
                    bytes.write(buffer, 0, count);
                }
                image = bytes.toByteArray();
            } finally {
                clearInput();
            }
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(image, 0, image.length, options);
            if (options.outWidth <= 0 || options.outHeight <= 0) return null;
            int sample = 1;
            while ((Math.max(options.outWidth, options.outHeight) / sample) > THUMBNAIL_SIDE) sample *= 2;
            options.inJustDecodeBounds = false;
            options.inSampleSize = sample;
            return BitmapFactory.decodeByteArray(image, 0, image.length, options);
        }

        private void archive(Node directory, OutputStream output) throws IOException {
            TransferLimit limit = new TransferLimit();
            // Closing the ZIP releases its compressor. The protocol stream remains owned by the caller.
            try (ZipOutputStream zip = new ZipOutputStream(new FilterOutputStream(output) {
                @Override public void close() throws IOException { flush(); }
            })) {
                zip.setLevel(1);
                appendDirectory(directory, "", zip, limit, 0);
            } catch (DirectoryIteratorException unavailable) {
                throw new IOException("Shared folder changed");
            }
        }

        private void appendDirectory(Node folder, String prefix, ZipOutputStream zip, TransferLimit limit, int depth)
                throws IOException {
            if (depth >= MAX_DEPTH) throw new IOException("Folder nesting limit reached");
            checkAvailable(folder);
            Map<String, Integer> usedNames = new HashMap<>();
            try (ChildReader reader = children(folder)) {
                while (reader.advance()) {
                    checkLive();
                    Node child = reader.current();
                    if (child == null) continue;
                    limit.entry();
                    String name = prefix + uniqueName(child.title, usedNames);
                    if (child.directory()) {
                        zip.putNextEntry(new ZipEntry(name + "/"));
                        zip.closeEntry();
                        appendDirectory(child, name + "/", zip, limit, depth + 1);
                    } else {
                        zip.putNextEntry(new ZipEntry(name));
                        copy(child, zip, limit);
                        zip.closeEntry();
                    }
                }
            }
        }

        private void copy(Node node, OutputStream output, TransferLimit limit) throws IOException {
            byte[] buffer = new byte[BUFFER_SIZE];
            try (InputStream input = openInput(node)) {
                int count;
                while ((count = input.read(buffer)) != -1) {
                    checkAvailable(node);
                    limit.bytes(count);
                    output.write(buffer, 0, count);
                }
            } finally {
                clearInput();
            }
        }

        private InputStream openInput(Node node) throws IOException {
            checkAvailable(node);
            InputStream input;
            if (node.path != null) {
                Path safe = checkedPath(node.root, node.path);
                if (!Files.isRegularFile(safe, LinkOption.NOFOLLOW_LINKS)) throw new IOException("File unavailable");
                input = Files.newInputStream(safe, LinkOption.NOFOLLOW_LINKS);
            } else {
                var descriptor = resolver.openAssetFileDescriptor(node.document, "r", cancellation);
                if (descriptor == null) throw new IOException("File unavailable");
                try {
                    input = descriptor.createInputStream();
                } catch (IOException | RuntimeException failure) {
                    descriptor.close();
                    throw failure;
                }
            }
            try {
                if (node.path != null) checkedPath(node.root, node.path);
                checkAvailable(node);
                synchronized (this) {
                    if (closed) throw new IOException("File view closed");
                    activeInput = input;
                }
                return input;
            } catch (IOException | RuntimeException ended) {
                input.close();
                throw ended;
            }
        }

        private synchronized void clearInput() { activeInput = null; }

        private ChildReader children(Node folder) throws IOException {
            checkAvailable(folder);
            if (folder.path != null) return new PathReader(folder, Files.newDirectoryStream(checkedPath(folder.root, folder.path)));
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(folder.tree,
                    DocumentsContract.getDocumentId(folder.document));
            return new DocumentReader(folder.tree, query(children));
        }

        private Cursor query(Uri uri) throws IOException {
            checkLive();
            Cursor cursor = resolver.query(uri, DOCUMENT_COLUMNS, null, null, null, cancellation);
            if (cursor == null) throw new IOException("Folder unavailable");
            return cursor;
        }

        private synchronized void attachPage(String node, ChildReader reader) throws IOException {
            if (closed) {
                reader.close();
                throw new IOException("File view closed");
            }
            page = reader;
            pageNode = node;
        }

        private void closePage() {
            ChildReader old;
            synchronized (this) {
                old = page;
                page = null;
                pageNode = null;
                cursorToken = null;
            }
            closeQuietly(old);
        }

        private void checkLive() throws IOException {
            if (closed || cancellation.isCanceled() || !allowed.getAsBoolean()) throw new IOException("File view closed");
        }

        private void checkAvailable(Node node) throws IOException {
            checkLive();
            if (node.path != null ? !sharedRootsAvailable() : !hasReadGrant(resolver, node.tree))
                throw new IOException("Storage access unavailable");
        }

        private boolean sharedRootsAvailable() {
            return FullAccessFiles.sharedRootsAvailable(context, demoGeneration != null, pairedEpoch);
        }

        private String missingRootStatus() {
            return sharedRootsAvailable() || !readableTrees(context, demoGeneration).isEmpty()
                    ? "unavailable" : "permission_required";
        }

        private synchronized Node lookup(String id) {
            return id == null || id.length() != 36 ? null : nodes.get(id);
        }

        private Node requireNode(String id) throws IOException {
            checkLive();
            Node node = lookup(id);
            if (node == null) throw new IOException("Refresh this folder");
            checkAvailable(node);
            return node;
        }

        private JSONObject describe(Node node) throws JSONException, IOException {
            String id = UUID.randomUUID().toString();
            synchronized (this) {
                if (closed) throw new IOException("File view closed");
                if (nodes.size() == MAX_HANDLES) nodes.remove(nodes.keySet().iterator().next());
                nodes.put(id, node);
            }
            return new JSONObject().put("id", id).put("title", node.title).put("directory", node.directory())
                    .put("mime", node.mime).put("size", node.size).put("previewable", node.previewable());
        }

        @Override public void close() {
            InputStream input;
            synchronized (this) {
                if (closed) return;
                closed = true;
                input = activeInput;
                activeInput = null;
                nodes.clear();
            }
            try {
                cancellation.cancel();
            } finally {
                closeQuietly(input);
                closePage();
            }
        }
    }

    private record StorageGrant(boolean broad, Set<Uri> trees) { }

    private interface ChildReader extends Closeable {
        boolean advance();
        boolean exhausted();
        Node current() throws IOException;
    }

    private static final class PathReader implements ChildReader {
        private final Node folder;
        private final DirectoryStream<Path> stream;
        private final Iterator<Path> iterator;
        private Path child;
        private boolean exhausted;

        PathReader(Node folder, DirectoryStream<Path> stream) {
            this.folder = folder;
            this.stream = stream;
            iterator = stream.iterator();
        }

        @Override public boolean advance() {
            if (!iterator.hasNext()) {
                exhausted = true;
                return false;
            }
            child = iterator.next();
            return true;
        }

        @Override public boolean exhausted() { return exhausted || !iterator.hasNext(); }

        @Override public Node current() {
            try {
                Path safe = checkedPath(folder.root, child);
                BasicFileAttributes info = Files.readAttributes(safe, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                String name = safe.getFileName().toString();
                if (info.isDirectory()) return Node.path(folder.root, safe, name, DIRECTORY, -1);
                if (!info.isRegularFile()) return null;
                int dot = name.lastIndexOf('.');
                String mime = dot >= 0 ? MimeTypeMap.getSingleton().getMimeTypeFromExtension(
                        name.substring(dot + 1).toLowerCase(Locale.ROOT)) : null;
                return Node.path(folder.root, safe, name, mime == null ? BINARY : mime, info.size());
            } catch (IOException | SecurityException unavailable) {
                // Paths that move, point outside the grant, or cannot be read have no list entry.
                return null;
            }
        }

        @Override public void close() throws IOException { stream.close(); }
    }

    private static final class DocumentReader implements ChildReader {
        private final Uri tree;
        private final Cursor cursor;
        private boolean exhausted;

        DocumentReader(Uri tree, Cursor cursor) {
            this.tree = tree;
            this.cursor = cursor;
        }

        @Override public boolean advance() {
            if (cursor.moveToNext()) return true;
            exhausted = true;
            return false;
        }

        @Override public boolean exhausted() { return exhausted || cursor.isLast(); }

        @Override public Node current() { return documentNode(tree, cursor); }

        @Override public void close() { cursor.close(); }
    }

    private static Node documentNode(Uri tree, Cursor row) {
        String id = row.getString(0);
        String title = row.getString(1);
        String mime = row.getString(2);
        if (id == null || !allowedDocument(DocumentsContract.getTreeDocumentId(tree), id)
                || (row.getLong(4) & DocumentsContract.Document.FLAG_VIRTUAL_DOCUMENT) != 0) return null;
        return Node.document(tree, DocumentsContract.buildDocumentUriUsingTree(tree, id),
                title == null || title.isEmpty() ? "Shared item" : title, mime == null ? BINARY : mime,
                row.isNull(3) ? -1 : row.getLong(3));
    }

    private static final class Node {
        private final Path root;
        private final Path path;
        private final Uri tree;
        private final Uri document;
        private final String title;
        private final String mime;
        private final long size;

        private Node(Path root, Path path, Uri tree, Uri document, String title, String mime, long size) {
            this.root = root;
            this.path = path;
            this.tree = tree;
            this.document = document;
            this.title = title;
            this.mime = mime;
            this.size = size;
        }

        static Node path(Path root, Path path, String title, String mime, long size) {
            return new Node(root, path, null, null, title, mime, size);
        }

        static Node document(Uri tree, Uri document, String title, String mime, long size) {
            return new Node(null, null, tree, document, title, mime, size);
        }

        boolean directory() { return DIRECTORY.equals(mime); }

        boolean previewable() {
            if (Build.VERSION.SDK_INT >= 29 && (document != null || mime.startsWith("video/")))
                return mime.startsWith("image/") || mime.startsWith("video/");
            return mime.startsWith("image/") && size >= 0 && size <= MAX_IMAGE_SOURCE;
        }
    }

    private static final class TransferLimit {
        private long bytes;
        private int entries;

        void bytes(int count) throws IOException {
            bytes += count;
            if (bytes > MAX_TRANSFER_BYTES) throw new IOException("File transfer size limit reached");
        }

        void entry() throws IOException {
            if (++entries > MAX_ARCHIVE_ENTRIES) throw new IOException("Folder item limit reached");
        }
    }

    /** Safe ZIP/Downloads component. Never use a remote name as a path. */
    public static String safeName(String name) {
        String safe = name == null ? "" : name.replaceAll("[\\p{Cntrl}\\p{Cf}<>:\"/\\\\|?*]", "_").trim()
                .replaceAll("[. ]+$", "");
        if (safe.length() > 120) {
            int length = Character.isHighSurrogate(safe.charAt(119)) ? 119 : 120;
            safe = safe.substring(0, length).replaceAll("[. ]+$", "");
        }
        byte[] utf8 = safe.getBytes(StandardCharsets.UTF_8);
        if (utf8.length > 220) {
            int length = 220;
            while ((utf8[length] & 0xc0) == 0x80) length--;
            safe = new String(utf8, 0, length, StandardCharsets.UTF_8).replaceAll("[. ]+$", "");
        }
        if (safe.isEmpty()) return "File";
        String first = safe.split("\\.", 2)[0].toUpperCase(Locale.ROOT);
        if (first.matches("CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9]")) return "_" + safe;
        return safe;
    }

    private static String uniqueName(String name, Map<String, Integer> used) {
        String safe = safeName(name);
        String key = safe.toLowerCase(Locale.ROOT);
        int suffix = used.getOrDefault(key, 0);
        if (suffix == 0) {
            used.put(key, 1);
            return safe;
        }
        String candidate;
        do {
            candidate = safe + " (" + ++suffix + ")";
        } while (used.containsKey(candidate.toLowerCase(Locale.ROOT)));
        used.put(key, suffix);
        used.put(candidate.toLowerCase(Locale.ROOT), 1);
        return candidate;
    }

    private static JSONObject status(String status) throws JSONException {
        return new JSONObject().put("status", status);
    }

    private static void closeQuietly(Closeable value) {
        if (value != null) {
            try {
                value.close();
            } catch (IOException | RuntimeException unavailable) {
                // The peer is already fenced from further reads.
            }
        }
    }
}
