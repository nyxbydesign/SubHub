package com.subhub.app.remote;

import android.content.Context;
import java.util.concurrent.CopyOnWriteArrayList;

/** A wake hint after existing source mutations, not a second data store or collection permission. */
public final class RemoteDeviceData {
    private static final CopyOnWriteArrayList<Observer> observers = new CopyOnWriteArrayList<>();
    private static final class Observer {
        final Context context; final Runnable changed;
        Observer(Context context, Runnable changed) { this.context = context; this.changed = changed; }
    }
    private RemoteDeviceData() { }
    static AutoCloseable observe(Context context, Runnable changed) {
        Observer observer = new Observer(context.getApplicationContext(), changed);
        observers.add(observer);
        return () -> observers.remove(observer);
    }
    public static void changed(Context context) {
        Context app = context.getApplicationContext();
        for (Observer observer : observers) if (observer.context == app) {
            try { observer.changed.run(); }
            catch (RuntimeException unavailable) { /* Optional scheduling cannot fail the source mutation. */ }
        }
    }
}
