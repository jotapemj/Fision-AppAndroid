package com.jotapeveloper.fision;

import android.content.Context;
import android.graphics.PointF;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.LinearSmoothScroller;
import androidx.recyclerview.widget.OrientationHelper;
import androidx.recyclerview.widget.RecyclerView;

public class SpeedyLinearLayoutManager extends LinearLayoutManager {

    // Base speed for short distances (higher = slower). Used only when the
    // adaptive path is not selected; the adaptive path scales scroll time by
    // sqrt(distance), giving proportional acceleration on long seeks.
    private static final float MILLISECONDS_PER_INCH = 200f;
    // Adaptive scroll-time bounds.
    private static final int   MIN_SCROLL_MS = 80;
    private static final int   MAX_SCROLL_MS = 200;
    // sqrt(distance) curve. Short seeks stay visibly smooth (~120 ms), long
    // seeks scale up proportionally without ever exploding (dx=8000 ≈ 800 ms).
    //   t = BASE + COEFF * sqrt(dx)
    private static final float ADAPT_BASE_MS   = 60f;
    private static final float ADAPT_COEFF_MS  = 8f;

    public SpeedyLinearLayoutManager(Context context) {
        super(context);
    }

    public SpeedyLinearLayoutManager(Context context, int orientation, boolean reverseLayout) {
        super(context, orientation, reverseLayout);
    }

    public SpeedyLinearLayoutManager(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    // Multi-cell moves (seeks, magnet, resync) run on an explicit pixel
    // distance and duration, proportional to the cells travelled:
    //   2 cells → MOVE_MIN_MS ... FAR_CELLS → MOVE_MAX_MS.
    // Farther than FAR_CELLS the move takes a fixed FAR_MOVE_MS.
    // The explicit pixel distance avoids the smooth scroller's seek steps
    // (~10000 px each, every one paying the full scroll time) towards a target
    // that is not laid out. One-cell steps (the beat advance) keep the
    // adaptive scroller below.
    private static final int FAR_CELLS     = 36;
    private static final int MOVE_MIN_MS   = 150;
    private static final int MOVE_MAX_MS   = 800;
    private static final int FAR_MOVE_MS   = 400;

    @Override
    public void smoothScrollToPosition(RecyclerView recyclerView, RecyclerView.State state, int position) {
        View anchor = getChildCount() > 0 ? getChildAt(0) : null;
        if (anchor == null) { startAdaptiveScroll(recyclerView, position); return; }
        OrientationHelper h = OrientationHelper.createHorizontalHelper(this);
        int cell = h.getDecoratedMeasurement(anchor);
        if (cell <= 0) { startAdaptiveScroll(recyclerView, position); return; }
        // Cells share one width: the target start follows from any laid-out child.
        int targetStart = h.getDecoratedStart(anchor) + (position - getPosition(anchor)) * cell;
        int dx = targetStart - h.getStartAfterPadding();
        int cells = Math.round(Math.abs(dx) / (float) cell);
        if (cells <= 1) { startAdaptiveScroll(recyclerView, position); return; }
        int ms;
        if (cells > FAR_CELLS) {
            ms = FAR_MOVE_MS;
        } else {
            float f = (cells - 2) / (float) (FAR_CELLS - 2);
            ms = Math.round(MOVE_MIN_MS + (MOVE_MAX_MS - MOVE_MIN_MS) * f);
        }
        recyclerView.smoothScrollBy(dx, 0, new DecelerateInterpolator(1.5f), ms);
    }

    private void startAdaptiveScroll(RecyclerView recyclerView, int position) {
        final LinearSmoothScroller linearSmoothScroller = new LinearSmoothScroller(recyclerView.getContext()) {

            @Override
            public PointF computeScrollVectorForPosition(int targetPosition) {
                return super.computeScrollVectorForPosition(targetPosition);
            }

            @Override
            protected float calculateSpeedPerPixel(DisplayMetrics displayMetrics) {
                return MILLISECONDS_PER_INCH / displayMetrics.densityDpi;
            }

            /**
             * Adaptive scroll time: sqrt(distance) curve. Short seeks keep a
             * smooth animation, long seeks scale up proportionally without
             * exploding — a full-list seek lands in under a second.
             */
            @Override
            protected int calculateTimeForScrolling(int dx) {
                float px = Math.max(1f, dx);
                int t = (int) (ADAPT_BASE_MS + ADAPT_COEFF_MS * (float) Math.sqrt(px));
                if (t < MIN_SCROLL_MS) t = MIN_SCROLL_MS;
                if (t > MAX_SCROLL_MS) t = MAX_SCROLL_MS;
                return t;
            }
        };

        linearSmoothScroller.setTargetPosition(position);
        startSmoothScroll(linearSmoothScroller);
    }
}
