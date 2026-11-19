package com.gathin.tvhome;

import android.view.View;
import android.view.ViewTreeObserver;

/** Work that must stay out of Home's next frame: run it once that frame is drawn. */
final class FirstFrame implements ViewTreeObserver.OnDrawListener, Runnable {
    private final View view;
    private final long delay;
    private final Runnable work;
    private boolean drawn;

    private FirstFrame(View view, long delay, Runnable work) {
        this.view = view;
        this.delay = delay;
        this.work = work;
    }

    /**
     * Runs {@code r} on the main thread {@code delayMs} after the next frame of {@code v}'s window is drawn.
     */
    static void afterNextDraw(View v, long delayMs, Runnable r) {
        v.getViewTreeObserver().addOnDrawListener(new FirstFrame(v, delayMs, r));
        v.invalidate();
    }

    @Override
    public void onDraw() {
        if (drawn) return;
        drawn = true;
        view.postDelayed(this, delay);
    }

    @Override
    public void run() {
        // Here, not in onDraw: removing a draw listener while the tree dispatches it throws.
        view.getViewTreeObserver().removeOnDrawListener(this);
        work.run();
    }
}
