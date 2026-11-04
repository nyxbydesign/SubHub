package com.subhub.app.remote;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import java.util.concurrent.atomic.AtomicBoolean;

/** A process-local network return requests existing constrained work; it never owns a connection. */
final class RemoteConnectivityWake implements AutoCloseable {
    private final ConnectivityManager manager;
    private final Object callbackLock = new Object();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ConnectivityManager.NetworkCallback callback;
    RemoteConnectivityWake(Context context, Runnable available) {
        manager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (manager == null) throw new IllegalStateException("Network observation unavailable");
        callback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network network) { synchronized (callbackLock) { if (!closed.get()) available.run(); } }
        };
        manager.registerDefaultNetworkCallback(callback);
    }
    @Override public void close() {
        synchronized (callbackLock) { if (!closed.compareAndSet(false, true)) return; }
        {
            try { manager.unregisterNetworkCallback(callback); }
            catch (IllegalArgumentException alreadyRemoved) { /* Closing never re-registers a retired callback. */ }
        }
    }
}
