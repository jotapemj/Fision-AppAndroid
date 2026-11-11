package com.jotapeveloper.fision.onboarding;

import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.PixelCopy;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.core.content.res.ResourcesCompat;
import androidx.lifecycle.Lifecycle;

import com.google.android.material.button.MaterialButton;
import com.jotapeveloper.fision.R;
import com.jotapeveloper.fision.design.AccentColorManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Guided tour over an activity: one element at a time is shown through a hole
 * in a blurred, dimmed copy of the screen, with a card that explains it.
 * The hole glides and reshapes between steps. Finishing or skipping marks the
 * tour as done; leaving it half way (back) shows it again next time.
 */
public final class CoachMarkOverlay extends FrameLayout {

    /** Bounds of a step's target in window coordinates, or null when it is not on screen. */
    public interface Target {
        @Nullable RectF windowBounds();

        /** Views behind the bounds, read by the settle check. Empty for computed targets. */
        @NonNull default List<View> views() { return Collections.emptyList(); }
    }

    /** One line of a step's legend: an icon and its meaning. */
    public static final class LegendRow {
        final Drawable icon;
        final CharSequence label;
        public LegendRow(Drawable icon, CharSequence label) { this.icon = icon; this.label = label; }
    }

    public static final class Step {
        final Target target;
        /** [body] 0 = title only (e.g. a step explained by its legend). */
        final int title, body;
        /** Hole corner radius in dp; negative = fully rounded (circle or pill). */
        float cornerDp = -1f;
        final List<LegendRow> legend = new ArrayList<>();

        public Step(Target target, @StringRes int title, @StringRes int body) {
            this.target = target; this.title = title; this.body = body;
        }
        public Step corner(float dp) { cornerDp = dp; return this; }
        public Step legend(LegendRow row) { legend.add(row); return this; }
    }

    /** Union of the views that are on screen. */
    public static Target views(View... views) {
        final View[] vs = views;
        return new Target() {
            @Override @Nullable public RectF windowBounds() {
                RectF out = null;
                int[] loc = new int[2];
                for (View v : vs) {
                    if (v == null || !v.isShown() || v.getWidth() == 0 || v.getHeight() == 0) continue;
                    v.getLocationInWindow(loc);
                    RectF r = new RectF(loc[0], loc[1], loc[0] + v.getWidth(), loc[1] + v.getHeight());
                    if (out == null) out = r; else out.union(r);
                }
                return out;
            }
            @NonNull @Override public List<View> views() { return Arrays.asList(vs); }
        };
    }

    private static final long MOVE_MS = 320L;
    private static final long FADE_MS = 200L;
    private static final int  DIM = 0x8C000000;
    private static final float SNAPSHOT_SCALE = 0.25f;
    // Screen settle: sample the targets until they sit still, fully drawn.
    private static final long  SETTLE_POLL_MS = 150L;
    private static final int   SETTLE_TRIES   = 20;

    private final Activity activity;
    private final String tourId;
    private final List<Step> steps = new ArrayList<>();
    private final float dp;
    private final int accent;

    private final View card;
    private final TextView tvCounter, tvTitle, tvBody;
    private final LinearLayout legendBox;
    private final MaterialButton bNext;

    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Path holePath = new Path();
    private final RectF hole = new RectF();
    private float holeRadius;
    private float pulse = -1f;   // 0..1 while the arrival ring expands

    @Nullable private Bitmap snapshot;      // window copy, downscaled
    @Nullable private RenderNode blurNode;  // API 31+: blurred snapshot
    private final int[] selfLoc = new int[2];

    private int index = -1;
    private boolean finishing;
    @Nullable private ValueAnimator moveAnim, pulseAnim;
    @Nullable private OnBackPressedCallback backCallback;

    private CoachMarkOverlay(Activity a, String tourId, List<Step> steps) {
        super(a);
        this.activity = a;
        this.tourId = tourId;
        this.steps.addAll(steps);
        dp = a.getResources().getDisplayMetrics().density;
        accent = AccentColorManager.resolveAccent(a);
        setWillNotDraw(false);
        setClickable(true);
        setAlpha(0f);

        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setColor(accent);

        card = LayoutInflater.from(a).inflate(R.layout.coach_mark_card, this, false);
        tvCounter = card.findViewById(R.id.tv_coachCounter);
        tvTitle   = card.findViewById(R.id.tv_coachTitle);
        tvBody    = card.findViewById(R.id.tv_coachBody);
        legendBox = card.findViewById(R.id.ll_coachLegend);
        bNext     = card.findViewById(R.id.b_coachNext);
        bNext.setBackgroundTintList(ColorStateList.valueOf(accent));
        MaterialButton bSkip = card.findViewById(R.id.b_coachSkip);
        bNext.setOnClickListener(v -> next());
        bSkip.setOnClickListener(v -> finish(true));
        card.setAlpha(0f);
        addView(card, new LayoutParams(cardWidth(a), ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private int cardWidth(Context c) {
        int screen = c.getResources().getDisplayMetrics().widthPixels;
        return (int) Math.min(screen - 32 * dp, 360 * dp);
    }

    /** One tour at a time. Set when the overlay is built (before its snapshot is
     *  taken) and cleared when it ends, so two requests for the same screen
     *  cannot stack two overlays — the one on top would punch its hole in the
     *  dimmed copy painted by the other and show an empty area. */
    @Nullable private static CoachMarkOverlay active;

    /**
     * Starts [tourId] over [activity] unless it is done already. Waits for the
     * screen to settle (see {@link #awaitSettled}), then shows the steps whose
     * target is on screen; nothing happens when none is.
     */
    public static void start(@NonNull Activity activity, @NonNull String tourId, @NonNull List<Step> steps) {
        if (Onboarding.isDone(activity, tourId) || activity.isFinishing()) return;
        if (active != null && (active.finishing || active.activity.isFinishing()
                || active.activity.isDestroyed())) active = null;
        if (active != null) return;
        ViewGroup content = activity.findViewById(android.R.id.content);
        if (content == null) return;
        awaitSettled(activity, content, tourId, steps, SETTLE_TRIES, null);
    }

    /**
     * The overlay paints ONE snapshot of the window, so a screen that is still
     * moving or fading in (tab animations, an empty library settling, the
     * activity enter animation) leaves a hole over an area the snapshot never
     * drew. Polls the targets and starts when their bounds repeat across two
     * samples with nothing fading; falls back to the targets on screen once the
     * tries run out, so the tour is never lost.
     */
    private static void awaitSettled(Activity a, ViewGroup content, String tourId,
                                     List<Step> steps, int tries, @Nullable String previous) {
        if (a.isFinishing() || a.isDestroyed() || active != null) return;
        String now = boundsSignature(steps);
        if (now != null && now.equals(previous)) {
            begin(a, content, tourId, steps);
            return;
        }
        if (tries <= 0) {
            if (resumed(a)) begin(a, content, tourId, steps);
            return;
        }
        content.postDelayed(() -> awaitSettled(a, content, tourId, steps, tries - 1, now),
                SETTLE_POLL_MS);
    }

    /** Bounds and alpha of every visible target. Null while any of them is
     *  moving or fading in, or when none is on screen. */
    @Nullable
    private static String boundsSignature(List<Step> steps) {
        StringBuilder sb = new StringBuilder();
        int present = 0;
        for (Step s : steps) {
            for (View v : s.target.views()) {
                // Alpha rides along: an element fading in changes it every frame,
                // a permanently dimmed one (a muted icon) does not.
                if (v != null && v.isShown()) sb.append((long) (v.getAlpha() * 100)).append('/');
            }
            RectF r = s.target.windowBounds();
            if (r == null) continue;
            present++;
            sb.append((long) r.left).append(':').append((long) r.top).append(':')
              .append((long) r.right).append(':').append((long) r.bottom).append(';');
        }
        return present == 0 ? null : sb.toString();
    }

    private static void begin(Activity a, ViewGroup content, String tourId, List<Step> steps) {
        List<Step> shown = new ArrayList<>();
        for (Step s : steps) if (s.target.windowBounds() != null) shown.add(s);
        if (shown.isEmpty()) return;
        CoachMarkOverlay o = new CoachMarkOverlay(a, tourId, shown);
        active = o;
        o.captureThenShow(content);
    }

    private static boolean resumed(Activity a) {
        if (!(a instanceof ComponentActivity)) return !a.isFinishing();
        return ((ComponentActivity) a).getLifecycle().getCurrentState()
                .isAtLeast(Lifecycle.State.RESUMED);
    }

    // ------------------------------------------------------------------ setup

    /** Copies the window before the overlay covers it (PixelCopy also sees surfaces). */
    private void captureThenShow(ViewGroup content) {
        View decor = activity.getWindow().getDecorView();
        int w = decor.getWidth(), h = decor.getHeight();
        if (w <= 0 || h <= 0) { attach(content); return; }
        Bitmap full = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        try {
            PixelCopy.request(activity.getWindow(), full, result -> {
                if (result == PixelCopy.SUCCESS) {
                    snapshot = Bitmap.createScaledBitmap(full,
                            Math.max(1, (int) (w * SNAPSHOT_SCALE)),
                            Math.max(1, (int) (h * SNAPSHOT_SCALE)), true);
                    buildBlurNode();
                }
                full.recycle();
                if (!activity.isFinishing()) attach(content);
            }, new Handler(Looper.getMainLooper()));
        } catch (IllegalArgumentException e) {
            full.recycle();
            attach(content);
        }
    }

    private void buildBlurNode() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || snapshot == null) return;
        RenderNode n = new RenderNode("coachBlur");
        n.setPosition(0, 0, snapshot.getWidth(), snapshot.getHeight());
        Canvas c = n.beginRecording();
        c.drawBitmap(snapshot, 0f, 0f, null);
        n.endRecording();
        n.setRenderEffect(RenderEffect.createBlurEffect(10f, 10f, Shader.TileMode.CLAMP));
        blurNode = n;
    }

    private void attach(ViewGroup content) {
        content.addView(this, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        if (activity instanceof ComponentActivity) {
            backCallback = new OnBackPressedCallback(true) {
                @Override public void handleOnBackPressed() { finish(false); }
            };
            ((ComponentActivity) activity).getOnBackPressedDispatcher().addCallback(backCallback);
        }
        post(() -> {
            animate().alpha(1f).setDuration(FADE_MS).start();
            showStep(0);
        });
    }

    // ------------------------------------------------------------------ steps

    private void next() {
        if (finishing) return;
        if (index + 1 >= steps.size()) finish(true);
        else showStep(index + 1);
    }

    private void showStep(int i) {
        RectF target = holeFor(steps.get(i));
        if (target == null) {   // scrolled away or hidden meanwhile
            if (i + 1 < steps.size()) showStep(i + 1); else finish(true);
            return;
        }
        boolean first = index < 0;
        index = i;
        float targetRadius = radiusFor(steps.get(i), target);
        if (first) {
            hole.set(target);
            holeRadius = targetRadius;
            invalidate();
            bindCard();
            card.animate().alpha(1f).setDuration(FADE_MS).start();
            startPulse();
            return;
        }
        RectF from = new RectF(hole);
        float fromRadius = holeRadius;
        if (moveAnim != null) moveAnim.cancel();
        moveAnim = ValueAnimator.ofFloat(0f, 1f);
        moveAnim.setDuration(MOVE_MS);
        moveAnim.setInterpolator(new PathInterpolator(0.4f, 0f, 0.2f, 1f));
        moveAnim.addUpdateListener(a -> {
            float f = (float) a.getAnimatedValue();
            hole.set(lerp(from.left, target.left, f), lerp(from.top, target.top, f),
                     lerp(from.right, target.right, f), lerp(from.bottom, target.bottom, f));
            holeRadius = lerp(fromRadius, targetRadius, f);
            invalidate();
        });
        moveAnim.start();
        // The start delay sticks to the view's animator: reset it on every use.
        card.animate().alpha(0f).setStartDelay(0).setDuration(120).withEndAction(() -> {
            bindCard();
            card.animate().alpha(1f).setStartDelay(MOVE_MS - 120).setDuration(160)
                    .withEndAction(this::startPulse).start();
        }).start();
    }

    /** Hole for a step in overlay coordinates, padded around the target. */
    @Nullable
    private RectF holeFor(Step s) {
        RectF r = s.target.windowBounds();
        if (r == null) return null;
        getLocationInWindow(selfLoc);
        r.offset(-selfLoc[0], -selfLoc[1]);
        r.inset(-8 * dp, -8 * dp);
        // Keep the hole and its ring inside the overlay (bottom tabs, toolbar edges).
        float edge = 3 * dp;
        r.left = Math.max(r.left, edge);
        r.top = Math.max(r.top, edge);
        r.right = Math.min(r.right, getWidth() - edge);
        r.bottom = Math.min(r.bottom, getHeight() - edge);
        return r;
    }

    private float radiusFor(Step s, RectF r) {
        if (s.cornerDp >= 0f) return s.cornerDp * dp;
        return Math.min(r.width(), r.height()) / 2f;
    }

    private void bindCard() {
        Step s = steps.get(index);
        tvCounter.setText(getContext().getString(R.string.coach_counter, index + 1, steps.size()));
        tvTitle.setText(s.title);
        tvBody.setVisibility(s.body == 0 ? GONE : VISIBLE);
        if (s.body != 0) tvBody.setText(s.body);
        bNext.setText(index + 1 == steps.size() ? R.string.coach_done : R.string.coach_next);
        legendBox.removeAllViews();
        legendBox.setVisibility(s.legend.isEmpty() ? GONE : VISIBLE);
        for (LegendRow row : s.legend) legendBox.addView(legendRow(row));
        placeCard();
    }

    private View legendRow(LegendRow row) {
        LinearLayout line = new LinearLayout(getContext());
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setPadding(0, (int) (5 * dp), 0, (int) (5 * dp));
        ImageView iv = new ImageView(getContext());
        iv.setImageDrawable(row.icon);
        iv.setImageTintList(ColorStateList.valueOf(Color.WHITE));
        int size = (int) (24 * dp);
        line.addView(iv, new LinearLayout.LayoutParams(size, size));
        TextView tv = new TextView(getContext());
        tv.setText(row.label);
        tv.setTextColor(0xD9FFFFFF);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        tv.setTypeface(ResourcesCompat.getFont(getContext(), R.font.noto_sans_regular));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMarginStart((int) (14 * dp));
        line.addView(tv, lp);
        return line;
    }

    /** Card below the hole when it fits, above otherwise; always on screen. */
    private void placeCard() {
        int w = getWidth(), h = getHeight();
        RectF target = holeFor(steps.get(index));
        if (target == null) target = hole;
        int cw = card.getLayoutParams().width;
        card.measure(MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(h, MeasureSpec.AT_MOST));
        int ch = card.getMeasuredHeight();
        float gap = 18 * dp, margin = 16 * dp;
        float below = target.bottom + gap, above = target.top - gap - ch;
        float y;
        if (below + ch <= h - margin) y = below;
        else if (above >= margin) y = above;
        else y = (h - target.bottom > target.top) ? below : above;
        y = Math.max(margin + topInset(), Math.min(y, h - ch - margin));
        float x = Math.max(margin, Math.min(target.centerX() - cw / 2f, w - cw - margin));
        card.setTranslationX(x);
        card.setTranslationY(y);
    }

    private int topInset() {
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : 0;
    }

    private void startPulse() {
        if (pulseAnim != null) pulseAnim.cancel();
        pulseAnim = ValueAnimator.ofFloat(0f, 1f);
        pulseAnim.setDuration(700);
        pulseAnim.addUpdateListener(a -> { pulse = (float) a.getAnimatedValue(); invalidate(); });
        pulseAnim.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator a) { pulse = -1f; invalidate(); }
        });
        pulseAnim.start();
    }

    private void finish(boolean done) {
        if (finishing) return;
        finishing = true;
        if (active == this) active = null;
        if (done) Onboarding.markDone(activity, tourId);
        if (backCallback != null) backCallback.remove();
        if (moveAnim != null) moveAnim.cancel();
        if (pulseAnim != null) pulseAnim.cancel();
        animate().alpha(0f).setDuration(FADE_MS).withEndAction(() -> {
            ViewGroup parent = (ViewGroup) getParent();
            if (parent != null) parent.removeView(this);
            if (blurNode != null) blurNode.discardDisplayList();
            if (snapshot != null) snapshot.recycle();
            snapshot = null;
        }).start();
    }

    // ------------------------------------------------------------------ drawing

    @Override
    protected void onDraw(Canvas canvas) {
        holePath.reset();
        holePath.addRoundRect(hole, holeRadius, holeRadius, Path.Direction.CW);

        canvas.save();
        canvas.clipOutPath(holePath);
        if (snapshot != null) {
            getLocationInWindow(selfLoc);
            canvas.save();
            canvas.translate(-selfLoc[0], -selfLoc[1]);
            canvas.scale(1f / SNAPSHOT_SCALE, 1f / SNAPSHOT_SCALE);
            if (blurNode != null && canvas.isHardwareAccelerated()) canvas.drawRenderNode(blurNode);
            else canvas.drawBitmap(snapshot, 0f, 0f, bitmapPaint);
            canvas.restore();
        }
        canvas.drawColor(DIM);
        canvas.restore();

        ringPaint.setStrokeWidth(2f * dp);
        ringPaint.setAlpha(255);
        canvas.drawRoundRect(hole, holeRadius, holeRadius, ringPaint);
        if (pulse >= 0f) {
            float grow = pulse * 14f * dp;
            RectF r = new RectF(hole);
            r.inset(-grow, -grow);
            ringPaint.setAlpha((int) (200 * (1f - pulse)));
            canvas.drawRoundRect(r, holeRadius + grow, holeRadius + grow, ringPaint);
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        // The screen below stays untouchable; a tap on the highlighted element advances.
        if (e.getActionMasked() == MotionEvent.ACTION_UP && hole.contains(e.getX(), e.getY())) next();
        return true;
    }

    private static float lerp(float a, float b, float f) { return a + (b - a) * f; }
}
