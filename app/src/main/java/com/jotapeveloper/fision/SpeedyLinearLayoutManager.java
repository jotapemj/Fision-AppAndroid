package com.jotapeveloper.fision;

import android.content.Context;
import android.graphics.PointF;
import android.util.AttributeSet;
import android.util.DisplayMetrics;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.LinearSmoothScroller;
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

    @Override
    public void smoothScrollToPosition(RecyclerView recyclerView, RecyclerView.State state, int position) {

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
