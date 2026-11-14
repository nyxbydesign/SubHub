package com.subhub.app.atmosphere;

import android.os.Handler;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

/** Keeps each key's click and long-press action within its full native touch slot. */
final class GagKeyPress implements View.OnTouchListener {
    private static final int REPEAT_MILLIS = 55;
    private final Handler handler;
    private final Runnable repeatAction;
    private final Runnable repeat = this::repeat;
    private View pressed;
    private int pointer = -1;
    private boolean repeated;

    GagKeyPress(Handler handler, Runnable repeatAction) {
        this.handler = handler;
        this.repeatAction = repeatAction;
    }

    @Override public boolean onTouch(View view, MotionEvent event) {
        int action = event.getActionMasked();
        int index = event.getActionIndex();
        switch (action) {
            case MotionEvent.ACTION_DOWN -> {
                pointer = event.getPointerId(index);
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                repeated = false;
                if (repeatAction != null) {
                    pressed = view;
                    handler.postDelayed(repeat, ViewConfiguration.getLongPressTimeout());
                }
            }
            case MotionEvent.ACTION_MOVE -> {
                if (pointer == -1) return true;
                int owner = event.findPointerIndex(pointer);
                if (owner < 0 || !inside(view, event, owner)) {
                    pointer = -1;
                    cancelRepeat();
                    finishNativeTouch(view, event, Math.max(0, owner), MotionEvent.ACTION_CANCEL);
                    return true;
                }
            }
            case MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                if (pointer == -1) return true;
                if (pointer != event.getPointerId(index)) return false;
                pointer = -1;
                cancelRepeat();
                boolean cancel = repeated || !inside(view, event, index);
                if (!cancel && action == MotionEvent.ACTION_UP) return false;
                finishNativeTouch(view, event, index, cancel ? MotionEvent.ACTION_CANCEL : MotionEvent.ACTION_UP);
                return true;
            }
            case MotionEvent.ACTION_CANCEL -> {
                pointer = -1;
                cancelRepeat();
            }
            default -> {
                if (pointer == -1) return true;
            }
        }
        return false;
    }

    private static boolean inside(View view, MotionEvent event, int index) {
        float x = event.getX(index);
        float y = event.getY(index);
        return 0 <= x && x < view.getWidth() && 0 <= y && y < view.getHeight();
    }

    private static void finishNativeTouch(View view, MotionEvent event, int index, int action) {
        MotionEvent end = MotionEvent.obtain(event.getDownTime(), event.getEventTime(), action,
                event.getX(index), event.getY(index), event.getMetaState());
        try {
            view.onTouchEvent(end);
        } finally {
            end.recycle();
        }
    }

    void cancelRepeat() {
        handler.removeCallbacks(repeat);
        pressed = null;
    }

    private void repeat() {
        if (pressed == null || !pressed.isPressed() || !pressed.isAttachedToWindow()) return;
        repeated = true;
        repeatAction.run();
        handler.postDelayed(repeat, REPEAT_MILLIS);
    }
}
