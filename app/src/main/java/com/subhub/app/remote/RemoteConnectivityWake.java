package com.subhub.app.remote;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;
import java.util.concurrent.atomic.AtomicBoolean;

/** A stable validated network requests existing constrained work; it never owns a connection. */
final class RemoteConnectivityWake implements AutoCloseable {
    private final ConnectivityManager manager;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ConnectivityManager.NetworkCallback callback;
    private final StableConnectionWake stable;
    RemoteConnectivityWake(Context context, Runnable available) {
        manager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (manager == null) throw new IllegalStateException("Network observation unavailable");
        Handler main = new Handler(Looper.getMainLooper());
        stable = new StableConnectionWake((work, delay) -> {
            main.postDelayed(work, delay);
            return () -> main.removeCallbacks(work);
        }, available);
        callback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network network) { stable.available(network); }
            @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) {
                stable.capabilities(network, capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                        && (android.os.Build.VERSION.SDK_INT < 28
                            || capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)));
            }
            @Override public void onLost(Network network) { stable.lost(network); }
        };
        manager.registerDefaultNetworkCallback(callback);
    }
    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        stable.close();
        try { manager.unregisterNetworkCallback(callback); }
        catch (IllegalArgumentException alreadyRemoved) { /* Closing never re-registers a retired callback. */ }
    }
}
