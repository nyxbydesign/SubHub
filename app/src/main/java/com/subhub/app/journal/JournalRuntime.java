package com.subhub.app.journal;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import com.subhub.app.people.PersonId;
import com.subhub.app.people.ProfileStore;
import com.subhub.app.remote.RemoteAssets;
import com.subhub.app.remote.RemoteFeature;
import com.subhub.app.remote.RemotePersonRepository;
import com.subhub.app.remote.RemoteProtocol;
import com.subhub.app.remote.RemoteStore;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.security.DeviceRolePreferences;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** All UI and wire media reads rejoin the active relationship and the current entry reference. */
public final class JournalRuntime {
    private static JournalRuntime instance;
    public final JournalStore entries;
    public final RemoteStore authority;
    private final Context context;
    private final File root;
    private final Map<String, Set<String>> drafts = new HashMap<>();
    private final Map<String, JournalAttachment> draftReferences = new HashMap<>();
    private Runnable changed = () -> { };

    public static synchronized JournalRuntime get(Context context) {
        if (instance == null) {
            RemoteFeature remote = RemoteFeature.get(context);
            instance = new JournalRuntime(context, remote.store, "profile_journal_v1.db",
                    new File(context.getFilesDir(), "profile-journal"));
            instance.changed = remote::changed;
        }
        return instance;
    }

    JournalRuntime(Context context, RemoteStore authority, String database, File root) {
        this.context = context.getApplicationContext(); this.authority = authority; this.root = root;
        entries = new JournalStore(context, database);
    }

    public static String scope(RemoteStore.Peer peer) { return JournalStore.scope(peer.identity + "_" + peer.generation); }
    private static String own(boolean dom) { return dom ? "own-dom" : "own-sub"; }
    public static boolean localShared(PersonId id) {
        return "journal_local_dom".equals(id.value()) || "journal_local_sub".equals(id.value());
    }
    private void requireLocalShared(boolean dom, PersonId id) {
        if (!id.value().equals(dom ? "journal_local_sub" : "journal_local_dom")
                || !DeviceRolePreferences.supportsDom(context) || !DeviceRolePreferences.supportsSub(context)
                || !ControllerPinManager.isConfigured(context)) throw new SecurityException("Local journal unavailable");
    }

    public RemoteStore.Peer peer(PersonId id, boolean dom) {
        if (id == null || id.isLocal()) throw new SecurityException("Missing journal relationship");
        String value = id.value();
        if (!value.startsWith("remote_") || value.length() < 73 || value.charAt(71) != '_')
            throw new SecurityException("Invalid journal relationship");
        synchronized (authority) {
            RemoteStore.Peer peer = authority.peer(value.substring(7, 71), value.substring(72));
            requireActive(peer);
            if (peer.localRole != (dom ? RemoteProtocol.Role.DOM : RemoteProtocol.Role.SUB)
                    || !RemotePersonRepository.id(peer).equals(id)) throw new SecurityException("Journal role mismatch");
            return peer;
        }
    }

    public void requireScreen(boolean dom) {
        if (!ProfileStore.mayEdit(context, dom) || dom && !ControllerPinManager.isDomModeActive())
            throw new SecurityException("Journal workspace unavailable");
    }
    public void checkTarget(boolean dom, PersonId target) {
        synchronized (authority) {
            requireScreen(dom);
            if (localShared(target)) requireLocalShared(dom, target);
            else if (!target.isLocal()) peer(target, dom);
        }
    }

    public void requireActive(RemoteStore.Peer peer) {
        if (peer == null) throw new SecurityException("Journal pairing unavailable");
        RemoteStore.Peer current = authority.peer(peer.identity, peer.generation);
        if (current == null || current.state != RemoteStore.State.ACTIVE || current.localRole != peer.localRole)
            throw new SecurityException("Journal pairing unavailable");
    }

    public List<JournalEntry> list(boolean dom, PersonId target, int offset) throws Exception {
        synchronized (authority) {
            requireScreen(dom);
            if (localShared(target)) { requireLocalShared(dom, target); return entries.localShared(dom, offset); }
            return target.isLocal() ? entries.own(dom, offset) : entries.shared(scope(peer(target, dom)), offset);
        }
    }

    public JournalEntry entry(boolean dom, PersonId target, String id) throws Exception {
        synchronized (authority) {
            requireScreen(dom);
            if (localShared(target)) {
                requireLocalShared(dom, target);
                return entries.audience(!dom, id).contains(dom ? "local-dom" : "local-sub") ? entries.entry(!dom, null, id) : null;
            }
            return entries.entry(dom, target.isLocal() ? null : scope(peer(target, dom)), id);
        }
    }

    public boolean shared(boolean dom, String id) throws Exception {
        synchronized (authority) { requireScreen(dom); return !entries.audience(dom, id).isEmpty(); }
    }

    public void save(boolean dom, JournalEntry entry, boolean share, boolean newlyShared) throws Exception {
        boolean networkChanged;
        synchronized (authority) {
            requireScreen(dom);
            Set<String> active = new LinkedHashSet<>();
            if (DeviceRolePreferences.supportsDom(context) && DeviceRolePreferences.supportsSub(context)
                    && ControllerPinManager.isConfigured(context)) active.add(dom ? "local-sub" : "local-dom");
            for (RemoteStore.Peer peer : authority.peers(false))
                if (peer.state == RemoteStore.State.ACTIVE && peer.localRole == (dom ? RemoteProtocol.Role.DOM : RemoteProtocol.Role.SUB))
                    active.add(scope(peer));
            Set<String> previous = entries.audience(dom, entry.id);
            Set<String> selected = share ? newlyShared ? active : previous : new LinkedHashSet<>();
            selected = new LinkedHashSet<>(selected); selected.retainAll(active);
            if (share && selected.isEmpty()) throw new IllegalStateException("No journal audience");
            RemoteAssets assets = assets(own(dom));
            for (JournalAttachment item : entry.media())
                if (!assets.isComplete(item.hash) || assets.length(item.hash) != item.length)
                    throw new IOException("Journal attachment unavailable");
            entries.save(dom, entry, selected);
            networkChanged = previous.stream().anyMatch(value -> !value.startsWith("local-"))
                    || selected.stream().anyMatch(value -> !value.startsWith("local-"));
        }
        if (networkChanged) changed.run();
    }

    public void delete(boolean dom, String id) throws Exception {
        boolean networkChanged;
        synchronized (authority) {
            requireScreen(dom); networkChanged = entries.audience(dom, id).stream().anyMatch(value -> !value.startsWith("local-"));
            entries.delete(dom, id); prune(own(dom), entries.media(dom, null));
        }
        if (networkChanged) changed.run();
    }

    public RemoteAssets assets(RemoteStore.Peer peer) throws IOException { return assets(scope(peer)); }
    private RemoteAssets assets(String scope) throws IOException { return new RemoteAssets(new File(root, scope)); }

    public synchronized JournalAttachment importFile(boolean dom, String draft, Uri uri) throws Exception {
        requireScreen(dom);
        if (!drafts.containsKey(own(dom) + ":" + draft)) throw new SecurityException("Journal draft is closed");
        prune(own(dom), entries.media(dom, null));
        if (uri == null || !"content".equals(uri.getScheme())) throw new IOException("Choose a file from the picker");
        String name = "Attachment", mime = context.getContentResolver().getType(uri);
        if (mime == null || !mime.matches("[a-z0-9.+-]+/[a-z0-9.+-]+")) mime = "application/octet-stream";
        try (Cursor c = context.getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int size = c.getColumnIndex(OpenableColumns.SIZE), label = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (size >= 0 && !c.isNull(size) && c.getLong(size) > JournalAttachment.MAX_BYTES)
                    throw new IOException("Journal attachment too large");
                if (label >= 0) name = JournalAttachment.cleanName(c.getString(label));
            }
        }
        if (name.isEmpty()) name = "Attachment";
        File temporary = File.createTempFile("journal-import-", ".tmp", context.getCacheDir());
        try {
            try (InputStream input = context.getContentResolver().openInputStream(uri); OutputStream output = new FileOutputStream(temporary)) {
                if (input == null) throw new IOException("Journal attachment unavailable");
                byte[] buffer = new byte[8192]; long total = 0; int count;
                while ((count = input.read(buffer)) != -1) {
                    total += count;
                    if (total > JournalAttachment.MAX_BYTES) throw new IOException("Journal attachment too large");
                    output.write(buffer, 0, count);
                }
            }
            return importCopied(dom, draft, temporary, name, mime);
        } finally { Files.deleteIfExists(temporary.toPath()); }
    }

    public synchronized JournalAttachment importRecording(boolean dom, String draft, File file) throws Exception {
        if (!file.getCanonicalFile().getParentFile().equals(context.getCacheDir().getCanonicalFile())
                || !file.getName().startsWith("journal-audio-")) throw new SecurityException("Invalid recording source");
        if (file.length() > JournalEntry.MAX_RECORDING_BYTES) throw new IOException("Journal recording too large");
        return importCopied(dom, draft, file, "Voice note.m4a", "audio/mp4");
    }

    private JournalAttachment importCopied(boolean dom, String draft, File file, String name, String mime) throws Exception {
        requireScreen(dom);
        if (!drafts.containsKey(own(dom) + ":" + draft)) throw new SecurityException("Journal draft is closed");
        if (file.length() < 1 || file.length() > JournalAttachment.MAX_BYTES) throw new IOException("Invalid journal attachment size");
        JournalAttachment result = new JournalAttachment(RemoteAssets.digest(file), name, mime, file.length());
        RemoteAssets assets = assets(own(dom));
        assets.reserve(result.hash, result.length);
        if (!assets.isComplete(result.hash)) {
            long offset = assets.offset(result.hash);
            try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
                input.seek(offset);
                while (offset < result.length) {
                    byte[] bytes = new byte[(int) Math.min(RemoteAssets.CHUNK_BYTES, result.length - offset)];
                    input.readFully(bytes); offset = assets.append(result.hash, offset, bytes);
                }
            }
            assets.finish(result.hash);
        }
        requireScreen(dom);
        drafts.computeIfAbsent(own(dom) + ":" + draft, ignored -> new HashSet<>()).add(result.hash);
        draftReferences.put(own(dom) + ":" + draft + ":" + result.hash, result);
        return result;
    }

    public synchronized void releaseDraft(boolean dom, String draft) throws Exception {
        drafts.remove(own(dom) + ":" + draft);
        draftReferences.keySet().removeIf(key -> key.startsWith(own(dom) + ":" + draft + ":"));
        prune(own(dom), entries.media(dom, null));
    }

    public synchronized void retainDraft(boolean dom, String draft, List<JournalAttachment> files) {
        Set<String> retained = new HashSet<>();
        drafts.put(own(dom) + ":" + draft, retained);
        draftReferences.keySet().removeIf(key -> key.startsWith(own(dom) + ":" + draft + ":"));
        for (JournalAttachment item : files) {
            retained.add(item.hash); draftReferences.put(own(dom) + ":" + draft + ":" + item.hash, item);
        }
    }

    public synchronized JournalAttachment draftReference(boolean dom, String draft, String hash) {
        requireScreen(dom);
        JournalAttachment file = draftReferences.get(own(dom) + ":" + draft + ":" + hash);
        if (file == null) throw new SecurityException("Journal draft unavailable");
        return file;
    }
    public synchronized File draftFile(boolean dom, String draft, String hash) throws Exception {
        JournalAttachment file = draftReference(dom, draft, hash);
        RemoteAssets assets = assets(own(dom));
        if (assets.length(hash) != file.length) throw new SecurityException("Journal draft size mismatch");
        return assets.finish(hash);
    }

    public boolean mediaReady(boolean dom, PersonId target, String id, JournalAttachment reference) throws Exception {
        synchronized (authority) {
            JournalEntry entry = entry(dom, target, id);
            if (entry == null || entry.media().stream().noneMatch(item -> item.hash.equals(reference.hash))) return false;
            RemoteAssets assets = target.isLocal() ? assets(own(dom))
                    : localShared(target) ? assets(own(!dom)) : assets(peer(target, dom));
            try { return assets.isComplete(reference.hash) && assets.length(reference.hash) == reference.length; }
            catch (IOException missing) { return false; }
        }
    }

    private synchronized void prune(String scope, Set<String> needed) throws IOException {
        needed = new HashSet<>(needed);
        for (Map.Entry<String, Set<String>> draft : drafts.entrySet())
            if (draft.getKey().startsWith(scope + ":")) needed.addAll(draft.getValue());
        File directory = new File(root, scope);
        if (Files.isSymbolicLink(directory.toPath())) throw new IOException("Invalid journal directory");
        File[] files = directory.listFiles();
        if (files == null) return;
        for (File file : files) {
            String name = file.getName();
            if (name.matches("[0-9a-f]{64}\\.(blob|part|size)") && !needed.contains(name.substring(0, 64))
                    && !Files.isSymbolicLink(file.toPath()) && file.isFile()) Files.delete(file.toPath());
        }
    }

    public void pruneRemote(RemoteStore.Peer peer) throws Exception { prune(scope(peer), entries.media(false, scope(peer))); }

    public Map<String, JournalAttachment> published(RemoteStore.Peer peer) throws Exception {
        requireActive(peer);
        Map<String, JournalAttachment> result = new LinkedHashMap<>();
        boolean dom = peer.localRole == RemoteProtocol.Role.DOM;
        for (int offset = 0; offset < JournalStore.MAX_ENTRIES; offset += JournalStore.PAGE_SIZE) {
            List<JournalEntry> page = entries.own(dom, offset);
            for (int i = 0; i < Math.min(page.size(), JournalStore.PAGE_SIZE); i++) {
                JournalEntry entry = page.get(i);
                if (entries.audience(dom, entry.id).contains(scope(peer)))
                    for (JournalAttachment attachment : entry.media()) result.put(attachment.hash, attachment);
            }
            if (page.size() <= JournalStore.PAGE_SIZE) break;
        }
        return result;
    }

    public RemoteAssets ownAssets(RemoteStore.Peer peer) throws Exception {
        requireActive(peer); return assets(own(peer.localRole == RemoteProtocol.Role.DOM));
    }

    public File mediaFile(boolean dom, PersonId target, String id, String hash) throws Exception {
        synchronized (authority) {
            JournalEntry entry = entry(dom, target, id);
            if (entry == null) throw new SecurityException("Journal entry unavailable");
            JournalAttachment reference = null;
            for (JournalAttachment item : entry.media()) if (item.hash.equals(hash)) reference = item;
            if (reference == null) throw new SecurityException("Journal attachment unavailable");
            RemoteAssets assets = target.isLocal() ? assets(own(dom))
                    : localShared(target) ? assets(own(!dom)) : assets(peer(target, dom));
            if (!assets.isComplete(hash) || assets.length(hash) != reference.length) throw new IOException("Journal attachment pending");
            return assets.finish(hash);
        }
    }
}
