package com.fuad.offlinemeasure.arhelpers;

import android.content.Context;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.OnTouchListener;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/**
 * Helper to detect taps using Android GestureDetector, and pass the taps between UI thread and
 * render thread.
 */
public final class TapHelper implements OnTouchListener {
  public static final class Tap {
    public final float x;
    public final float y;
    public Tap(float x, float y) { this.x = x; this.y = y; }
  }

  private final GestureDetector gestureDetector;
  private final BlockingQueue<Tap> queuedSingleTaps = new ArrayBlockingQueue<>(16);

  /**
   * Creates the tap helper.
   *
   * @param context the application's context.
   */
  public TapHelper(Context context) {
    gestureDetector =
        new GestureDetector(
            context,
            new GestureDetector.SimpleOnGestureListener() {
              @Override
              public boolean onSingleTapUp(MotionEvent e) {
                // Queue tap if there is space. Tap is lost if queue is full.
                queuedSingleTaps.offer(new Tap(e.getX(), e.getY()));
                return true;
              }

              @Override
              public boolean onDown(MotionEvent e) {
                return true;
              }
            });
  }

  /**
   * Polls for a tap.
   *
   * @return if a tap was queued, the copied coordinates for the tap. Otherwise null if no taps are queued.
   */
  public Tap poll() {
    return queuedSingleTaps.poll();
  }

  @Override
  public boolean onTouch(View view, MotionEvent motionEvent) {
    return gestureDetector.onTouchEvent(motionEvent);
  }
}
