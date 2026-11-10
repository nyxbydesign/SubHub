package com.subhub.app.util;

import android.view.MotionEvent;
import android.view.View;
import java.util.function.BooleanSupplier;

/** A deliberate, one-shot pointer hold without changing the button's ordinary click action. */
public final class FiveSecondBackGesture implements View.OnTouchListener {
    public static final long HOLD_MILLIS = 5_000L;
    private final View button;
    private final BooleanSupplier allowed;
    private final Runnable held;
    private boolean pressed;
    private int pointer = -1;
    private final Runnable deadline = this::fire;

    private void fire() {
        if (!pressed) return;
        boolean invoke = button.isAttachedToWindow() && button.hasWindowFocus()
                && button.isShown() && button.isEnabled() && allowed.getAsBoolean();
        cancel();
        if (invoke) held.run();
    }

    private FiveSecondBackGesture(View button, BooleanSupplier allowed, Runnable held) {
        this.button = java.util.Objects.requireNonNull(button);
        this.allowed = java.util.Objects.requireNonNull(allowed);
        this.held = java.util.Objects.requireNonNull(held);
        button.setOnTouchListener(this);
        button.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View view) { }
            @Override public void onViewDetachedFromWindow(View view) { cancel(); }
        });
    }

    public static FiveSecondBackGesture bind(View button, BooleanSupplier allowed, Runnable held) {
        return new FiveSecondBackGesture(button, allowed, held);
    }

    public void cancel() {
        pressed = false;
        pointer = -1;
        button.removeCallbacks(deadline);
        button.setPressed(false);
    }

    @Override public boolean onTouch(View view, MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                cancel();
                if (!view.isEnabled() || !allowed.getAsBoolean()) return false;
                pointer = event.getPointerId(0);
                pressed = true;
                view.setPressed(true);
                view.postDelayed(deadline, HOLD_MILLIS);
                return true;
            case MotionEvent.ACTION_MOVE:
                int index = event.findPointerIndex(pointer);
                if (pressed && (index < 0 || !inside(event.getX(index), event.getY(index))
                        || !allowed.getAsBoolean())) cancel();
                return true;
            case MotionEvent.ACTION_UP:
                boolean click = pressed && event.getPointerId(event.getActionIndex()) == pointer
                        && inside(event.getX(), event.getY()) && allowed.getAsBoolean();
                cancel();
                if (click) view.performClick();
                return true;
            case MotionEvent.ACTION_POINTER_DOWN:
            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_OUTSIDE:
                cancel();
                return true;
            default:
                return true;
        }
    }

    private boolean inside(float x, float y) {
        return x >= 0 && y >= 0 && x < button.getWidth() && y < button.getHeight();
    }
}
