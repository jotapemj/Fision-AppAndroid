package com.jotapeveloper.fision;

import android.content.Context;
import android.graphics.PointF;
import android.util.AttributeSet;
import android.util.DisplayMetrics;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.LinearSmoothScroller;
import androidx.recyclerview.widget.RecyclerView;

public class SpeedyLinearLayoutManager extends LinearLayoutManager {

    // Base speed for short distances (higher = slower)
    private static final float MILLISECONDS_PER_INCH = 200f;
    // Maximum scroll time: long distances are accelerated to reach the target quickly
    // (raised to snappy after feedback — seek via progressbar felt too slow).
    private static final int   MAX_SCROLL_MS = 400;

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
             * Total-time cap: beyond a certain distance the scroll is accelerated so it
             * does not take seconds to reach the target.
             */
            @Override
            protected int calculateTimeForScrolling(int dx) {
                int linear = super.calculateTimeForScrolling(dx);
                return Math.min(linear, MAX_SCROLL_MS);
            }
        };

        linearSmoothScroller.setTargetPosition(position);
        startSmoothScroll(linearSmoothScroller);
    }
}
