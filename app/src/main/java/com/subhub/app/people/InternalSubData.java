package com.subhub.app.people;

import android.content.Context;
import android.content.SharedPreferences;
import com.subhub.app.R;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.internal.*;
import java.io.File;
import java.nio.file.Files;
import java.util.IdentityHashMap;
import java.util.Map;

/** One data-only internal backend. Provisioning evidence is independent of its owned directory. */
public final class InternalSubData {
    private static final String REGISTRY = "subhub_internal_registry", STATE = "provisioning";
    private static final java.util.Set<Context> FAILED = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
    private static final Map<Context, InternalSubData> OPEN = new IdentityHashMap<>();
    private static final Map<Context, java.util.concurrent.CompletableFuture<InternalSubData>> OPENING = new IdentityHashMap<>();
    private final java.util.concurrent.ExecutorService writes = java.util.concurrent.Executors.newSingleThreadExecutor(r -> new Thread(r, "subhub-internal-writes"));
    private volatile Thread ownerThread;
    private volatile boolean ownerDataReady;
    public void execute(Runnable mutation) { writes.execute(() -> { ownerThread = Thread.currentThread(); mutation.run(); }); }
    public boolean isOwnerThread() { return Thread.currentThread() == ownerThread; }
    public boolean ownerDataReady() { return ownerDataReady; }
    public InternalOwnerDataBroker ownerData() {
        if (!ownerDataReady) throw new IllegalStateException("Internal owner data is unavailable");
        return InternalOwnerDataBroker.attached(this);
    }
    /** UI/page workers may await the sole owner worker; that worker and main must never join themselves. */
    public <T> T await(java.util.concurrent.CompletableFuture<T> result) {
        if (isOwnerThread() || android.os.Looper.myLooper() == android.os.Looper.getMainLooper())
            throw new IllegalStateException("Await internal data only from a separate page worker");
        try { return result.get(); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException("Internal data wait interrupted", interrupted); }
        catch (java.util.concurrent.ExecutionException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw new IllegalStateException("Internal data operation failed", cause);
        }
    }
    void initializeOwnerData(Context physical, OwnerDataProvisioning.Decision decision) {
        InternalOwnerDataBroker broker = InternalOwnerDataBroker.attach(physical, this, decision, record -> record.visible);
        await(broker.initialize()); ownerDataReady = true;
    }
    public final InternalSubStore store;
    public final InternalConfigurationStore configuration;
    public final InternalStorageScope storage;
    private volatile InternalSubRecord acknowledged;
    private volatile Presentation presentation;
    private final java.util.concurrent.atomic.AtomicBoolean presentationPending = new java.util.concurrent.atomic.AtomicBoolean();
    private static final class Presentation {
        final InternalSubRecord record; final PersonSnapshot value; final long controller;
        Presentation(InternalSubRecord record, PersonSnapshot value, long controller) { this.record=record; this.value=value; this.controller=controller; }
    }
    public PersonSnapshot peekPresentation() {
        Presentation cached=presentation; InternalSubRecord record=acknowledged;
        return cached!=null&&cached.controller==ControllerPinManager.domWorkspaceRevision()
                &&cached.record.revision==record.revision&&cached.record.generation.equals(record.generation)?cached.value:null;
    }
    public PersonSnapshot loadPresentation(Context physical, InternalSubRecord record) {
        if (android.os.Looper.myLooper()==android.os.Looper.getMainLooper())
            throw new IllegalStateException("Build internal presentation on a page worker");
        long controller=ControllerPinManager.domWorkspaceRevision();
        if(!DummySubPreview.current(physical,record.generation,controller))throw new SecurityException("Internal presentation authority changed");
        Presentation cached=presentation;
        if(cached!=null&&cached.controller==controller&&cached.record.revision==record.revision&&cached.record.generation.equals(record.generation))return cached.value;
        PersonSnapshot value=InternalPersonPresentation.snapshot(physical,record);
        boolean current=DummySubPreview.current(physical,record.generation,controller);
        synchronized(this){if(current&&acknowledged.revision==record.revision&&acknowledged.generation.equals(record.generation))
            presentation=new Presentation(record,value,controller);}
        return value;
    }
    public void requestPresentation(Context physical) {
        if(peekPresentation()!=null||!presentationPending.compareAndSet(false,true))return;
        Context app=physical.getApplicationContext();
        execute(()->{try{loadPresentation(app,acknowledged);}
            catch(RuntimeException unavailable){/* Keep presentation unknown; page-owned loads report the failure. */}
            finally{presentationPending.set(false);}});
    }
    /** Presentation cache only. Mutations and runtime admission independently reread/CAS storage. */
    public InternalSubRecord acknowledged() { return acknowledged; }
    private InternalSubData(FileInternalSubBackend backend) {
        store = InternalConfigurationStore.open(backend);
        configuration = new InternalConfigurationStore(store);
        storage = backend.scope();
        acknowledged = store.read();
        store.addListener((previous, current) -> {
            synchronized (this) {
                if (current.revision > acknowledged.revision) acknowledged = current;
            }
        });
    }
    public static boolean provisioned(Context source) {
        Context app = physical(source);
        synchronized (InternalSubData.class) { if (FAILED.contains(app)) return false; }
        return "ready".equals(app.getSharedPreferences(REGISTRY, 0).getString(STATE, ""));
    }
    public static InternalSubData peek(Context source) {
        Context app = physical(source);
        synchronized (InternalSubData.class) { return FAILED.contains(app) ? null : OPEN.get(app); }
    }
    public static InternalSubData get(Context source) {
        Context app = physical(source);
        InternalSubData cached = peek(app); if (cached != null) return cached;
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper())
            throw new IllegalStateException("Load internal data on the page worker first");
        if (!provisioned(app)) throw new IllegalStateException("Internal Sub is not provisioned");
        java.util.concurrent.CompletableFuture<InternalSubData> pending;
        boolean opener;
        synchronized (InternalSubData.class) {
            if (FAILED.contains(app)) throw new IllegalStateException("Internal Sub provisioning needs recovery");
            cached = OPEN.get(app); if (cached != null) return cached;
            pending = OPENING.get(app); opener = pending == null;
            if (opener) { pending = new java.util.concurrent.CompletableFuture<>(); OPENING.put(app, pending); }
        }
        if (opener) {
            try {
                InternalSubData opened = new InternalSubData(FileInternalSubBackend.openExisting(parent(app, false)));
                // This is the chosen singleton, not a speculative candidate. Ordinary reads never provision.
                try { opened.initializeOwnerData(app, OwnerDataProvisioning.Decision.OPEN_EXISTING); }
                catch (RuntimeException unavailable) {
                    // Retain readable profile data, but the attached failed broker stays unavailable.
                    // A missing runtime namespace is not evidence authorizing profile-only migration.
                    opened.ownerDataReady = false;
                    InternalOwnerDataBroker.attached(opened).closeAfterDrain().exceptionally(ignored -> null);
                }
                synchronized (InternalSubData.class) {
                    if (FAILED.contains(app)) throw new IllegalStateException("Internal Sub provisioning needs recovery");
                    OPEN.put(app, opened); OPENING.remove(app);
                }
                pending.complete(opened);
            } catch (Throwable unavailable) {
                synchronized (InternalSubData.class) { FAILED.add(app); OPENING.remove(app); }
                pending.completeExceptionally(unavailable);
            }
        }
        try { return pending.get(); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException("Internal data open interrupted", interrupted); }
        catch (java.util.concurrent.ExecutionException unavailable) { throw new IllegalStateException("Internal Sub data unavailable", unavailable.getCause()); }
    }
    /** Page workers distinguish absent data from a failed established open. */
    public static boolean loadIfProvisioned(Context source) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper())
            throw new IllegalStateException("Read internal data on the page worker");
        Context app = physical(source);
        String state = app.getSharedPreferences(REGISTRY, 0).getString(STATE, "");
        if (state.isEmpty()) {
            Boolean legacy = legacyVisibility(app);
            if (legacy == null) return false;
            if (!DummySubPreview.authorized(app)) return false;
            provision(app); return true;
        }
        if (!"ready".equals(state)) throw new IllegalStateException("Internal Sub provisioning needs recovery");
        InternalSubData value = get(app);
        try {
            InternalSubRecord durable = value.configuration.read();
            synchronized (value) {
                // A concurrent CAS listener may already have published a newer ACK while this read completed.
                if (durable.revision >= value.acknowledged.revision) value.acknowledged = durable;
            }
        } catch (RuntimeException unavailable) {
            synchronized (InternalSubData.class) { FAILED.add(app); }
            throw unavailable;
        }
        return true;
    }
    /** First provision is explicit toggle or a recognized, one-time legacy visibility migration. */
    static InternalSubData provision(Context source) {
        return provision(source, InternalSubData::syncTrustedDirectory);
    }
    @FunctionalInterface interface DirectorySync { void sync(File trustedParent) throws java.io.IOException; }
    /** Per-call seam lets an owned fixture reject directory acknowledgement without global hooks. */
    static InternalSubData provision(Context source, DirectorySync directorySync) {
        Context app = physical(source);
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper())
            throw new IllegalStateException("Provision internal data on a worker");
        long controller = com.subhub.app.security.ControllerPinManager.domWorkspaceRevision();
        if (!DummySubPreview.authorized(app)) throw new SecurityException("Dom workspace unavailable");
        SharedPreferences registry = app.getSharedPreferences(REGISTRY, 0);
        String state = registry.getString(STATE, "");
        if ("ready".equals(state)) return get(app);
        if (!state.isEmpty()) throw new IllegalStateException("Internal Sub provisioning needs recovery");
        Boolean legacy = legacyVisibility(app);
        // Admission orders this data-only migration before any later controller revocation.
        synchronized (com.subhub.app.appmode.RuntimeSessionIdentity.LOCK) {
            if (!DummySubPreview.authorized(app) || controller != com.subhub.app.security.ControllerPinManager.domWorkspaceRevision())
                throw new SecurityException("Dom workspace changed");
        }
        synchronized (InternalSubData.class) {
            if (FAILED.contains(app)) throw new IllegalStateException("Internal Sub provisioning needs recovery");
            // Claim once before any disk work. No global monitor spans fsync or callbacks.
            FAILED.add(app);
        }
        // Commit can update memory before failing. Either outcome deliberately prevents blind reseeding.
        if (!registry.edit().putString(STATE, "started").commit())
            throw new IllegalStateException("Internal Sub provisioning was not acknowledged");
        InternalSubData value = null;
        try {
            value = new InternalSubData(FileInternalSubBackend.createNew(parent(app, true, directorySync),
                    InternalProfileAdapter.fromProfileData(new ProfileData(app.getString(R.string.dummy_sub_title),
                            app.getString(R.string.dummy_sub_description), "")), InternalConfigurationStore.defaults()));
            // The private owner data must acknowledge initialization before profile READY publication.
            value.initializeOwnerData(app, OwnerDataProvisioning.Decision.FIRST_INTERNAL_CREATE);
            if (Boolean.TRUE.equals(legacy)) value.store.setVisible(value.store.read(), true);
            // Carry only the old visible/hidden choice. No old editor, physical profile, or session authority is imported.
            if (!registry.edit().putString(STATE, "ready").commit()) {
                throw new IllegalStateException("Internal Sub provisioning was not acknowledged");
            }
            synchronized (InternalSubData.class) { OPEN.put(app, value); FAILED.remove(app); } return value;
        } catch (java.io.IOException | RuntimeException unavailable) {
            if (value != null) value.disposeUnpublished();
            throw new IllegalStateException("Internal Sub provisioning failed", unavailable);
        }
    }
    private void disposeUnpublished() {
        java.util.concurrent.CompletableFuture<Void> closing;
        try { closing = InternalOwnerDataBroker.attached(this).closeAfterDrain(); }
        catch (RuntimeException noBroker) { writes.shutdown(); return; }
        closing.whenComplete((ignored, failure) -> writes.shutdown());
        if (!Thread.currentThread().isInterrupted()) {
            try { await(closing); } catch (RuntimeException closedUnavailable) { /* Original provision failure remains authoritative. */ }
        }
    }
    private static Boolean legacyVisibility(Context app) {
        Map<String, ?> old = app.getSharedPreferences(DummySubPreview.PREFERENCES_NAME, 0).getAll();
        if (old.isEmpty()) return null;
        if (old.size() != 1 || !(old.get("enabled") instanceof Boolean))
            throw new IllegalStateException("Legacy internal Sub visibility needs recovery");
        return (Boolean) old.get("enabled");
    }
    private static Context physical(Context source) {
        Context app = source.getApplicationContext();
        if (app instanceof InternalSubContext) throw new SecurityException("Physical application context required");
        return app;
    }
    private static File parent(Context app, boolean create) throws java.io.IOException {
        return parent(app, create, InternalSubData::syncTrustedDirectory);
    }
    private static File parent(Context app, boolean create, DirectorySync directorySync) throws java.io.IOException {
        // Only the framework-provided boundary may resolve platform aliases. Never canonicalize
        // the independently owned internal_subs entry or allow it to become a symlink.
        File trusted = app.getNoBackupFilesDir().getCanonicalFile();
        File parent = new File(trusted, "internal_subs");
        if (create && !Files.exists(parent.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(parent.toPath());
        if (Files.isSymbolicLink(parent.toPath()) || !Files.isDirectory(parent.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
            throw new java.io.IOException("Internal Sub parent unavailable");
        // Seal the new directory entry before profile creation or registry READY acknowledgement.
        // Also sync a preexisting entry: its presence alone is not a durable-create receipt.
        if (create) directorySync.sync(trusted);
        return parent;
    }
    private static void syncTrustedDirectory(File directory) throws java.io.IOException {
        try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(directory.toPath(),
                java.nio.file.StandardOpenOption.READ, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            channel.force(true);
        }
    }
}
