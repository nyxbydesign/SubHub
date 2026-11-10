package com.subhub.app.censor.android;

import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;

import java.util.function.BooleanSupplier;

/** Screen eligibility is independent of the remembered app and the enclosing service session. */
public final class CensorScreenState {
    private final BooleanSupplier sample;
    private boolean eligible, screenOff;
    private long generation = 1;

    public CensorScreenState(Context context) { this(() -> interactiveUnlocked(context)); }
    CensorScreenState(BooleanSupplier sample) {
        this.sample = sample;
        eligible = sampled();
    }

    public static boolean interactiveUnlocked(Context context) {
        try {
            PowerManager power = context.getSystemService(PowerManager.class);
            KeyguardManager keyguard = context.getSystemService(KeyguardManager.class);
            return power != null && keyguard != null && power.isInteractive() && !keyguard.isKeyguardLocked();
        } catch (RuntimeException unavailable) { return false; }
    }

    private boolean sampled() {
        try { return sample.getAsBoolean(); }
        catch (RuntimeException unavailable) { return false; }
    }

    /** OFF is an immediate fence; ON and USER_PRESENT still require a current unlocked sample. */
    public synchronized boolean refresh(String action) {
        boolean previous = eligible;
        if (Intent.ACTION_SCREEN_OFF.equals(action)) screenOff = true;
        else if (Intent.ACTION_SCREEN_ON.equals(action) || Intent.ACTION_USER_PRESENT.equals(action)) screenOff = false;
        eligible = !screenOff && sampled();
        if (previous != eligible || Intent.ACTION_SCREEN_OFF.equals(action)) generation++;
        return previous != eligible;
    }

    public synchronized boolean eligible() {
        if (eligible && (screenOff || !sampled())) { eligible = false; generation++; }
        return eligible;
    }
    public synchronized long generation() { return generation; }
    public synchronized boolean isCurrent(long observed) { return generation == observed && eligible(); }
}
