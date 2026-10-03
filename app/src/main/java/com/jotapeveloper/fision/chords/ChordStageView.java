package com.jotapeveloper.fision.chords;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

/**
 * Current chord + next chord, each as a card (name above its diagram).
 * Fretted instruments lay out left → right and advance by sliding left;
 * the piano lays out top → bottom and advances by sliding up. Cards fade
 * out at the edges along the movement axis (DST_OUT over a hardware layer,
 * so the real player background shows through).
 */
public class ChordStageView extends FrameLayout {

    public enum Mode { FRETTED, PIANO }

    /** Everything one card shows. */
    public static final class CardData {
        final String name;
        @Nullable final String bassLabel;
        @Nullable final ChordShapes.Position position;
        final int[] pianoKeys;
        final String[] pianoNames;

        public CardData(String name, @Nullable String bassLabel,
                        @Nullable ChordShapes.Position position,
                        int[] pianoKeys, String[] pianoNames) {
            this.name = name;
            this.bassLabel = bassLabel;
            this.position = position;
            this.pianoKeys = pianoKeys;
            this.pianoNames = pianoNames;
        }
    }

    private static final int OUT = 0, CUR = 1, NEXT = 2, IN = 3;
    private static final long ADVANCE_MS = 380;

    private final Card[] cards = new Card[3];
    /** cards index playing each role. */
    private int curCard = 0, nextCard = 1, spareCard = 2;
    private Mode mode = Mode.FRETTED;
    private int strings = 6;

    private final Paint fadePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private LinearGradient fadeA, fadeB;

    public ChordStageView(Context c) { this(c, null); }

    public ChordStageView(Context c, @Nullable AttributeSet a) {
        super(c, a);
        for (int i = 0; i < cards.length; i++) {
            cards[i] = new Card(c);
            addView(cards[i].root, new LayoutParams(0, 0));
        }
        fadePaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));
        setLayerType(LAYER_TYPE_HARDWARE, null);
        setWillNotDraw(false);
        setClipChildren(true);
    }

    public void setTypeface(Typeface tf) {
        for (Card card : cards) card.name.setTypeface(tf);
    }

    /** Swipe along the stage axis: +1 next chord, -1 previous chord. */
    public interface OnSwipeListener { void onSwipe(int direction); }

    private static final float SWIPE_MIN_DP = 40f;
    @Nullable private OnSwipeListener swipeListener;
    private float downX, downY;

    public void setOnSwipeListener(@Nullable OnSwipeListener l) { swipeListener = l; }

    // Fretted: left = next, right = previous. Piano (stacked): up = next.
    @Override
    public boolean onTouchEvent(android.view.MotionEvent e) {
        if (swipeListener == null) return super.onTouchEvent(e);
        switch (e.getActionMasked()) {
            case android.view.MotionEvent.ACTION_DOWN:
                downX = e.getX();
                downY = e.getY();
                return true;
            case android.view.MotionEvent.ACTION_UP: {
                float dx = e.getX() - downX, dy = e.getY() - downY;
                float along = mode == Mode.PIANO ? dy : dx;
                float across = mode == Mode.PIANO ? dx : dy;
                float min = SWIPE_MIN_DP * getResources().getDisplayMetrics().density;
                if (Math.abs(along) >= min && Math.abs(along) > Math.abs(across)) {
                    performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
                    swipeListener.onSwipe(along < 0 ? +1 : -1);
                }
                return true;
            }
            default:
                return true;
        }
    }

    public void setMode(Mode mode, int strings) {
        this.mode = mode;
        this.strings = strings;
        for (Card card : cards) card.applyMode(mode, strings);
        layoutCards();
    }

    /** Shows [cur] and [next] in place, no animation. */
    public void show(CardData cur, CardData next) {
        endAnimations();
        cards[curCard].bind(cur);
        cards[nextCard].bind(next);
        place(cards[curCard], CUR);
        place(cards[nextCard], NEXT);
        place(cards[spareCard], IN);
    }

    /** The next card becomes current (slide), [newNext] enters behind it. */
    public void advance(CardData cur, CardData newNext) {
        endAnimations();
        Card out = cards[curCard], in = cards[nextCard], spare = cards[spareCard];
        in.bind(cur);
        spare.bind(newNext);
        place(spare, IN);
        animateTo(out, OUT);
        animateTo(in, CUR);
        animateTo(spare, NEXT);
        int oldCur = curCard;
        curCard = nextCard;
        nextCard = spareCard;
        spareCard = oldCur;
    }

    /** Reverse of {@link #advance}: [cur] (the previous chord) slides back in
     *  as current, the current card becomes the next one and the next leaves. */
    public void retreat(CardData cur, CardData next) {
        endAnimations();
        Card back = cards[spareCard], oldCur = cards[curCard], oldNext = cards[nextCard];
        back.bind(cur);
        place(back, OUT);
        animateTo(back, CUR);
        animateTo(oldCur, NEXT);
        animateTo(oldNext, IN);
        int spare = spareCard;
        spareCard = nextCard;
        nextCard = curCard;
        curCard = spare;
    }

    private void endAnimations() {
        for (Card card : cards) card.root.animate().cancel();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        post(this::layoutCards);
    }

    private void layoutCards() {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;
        int cw = mode == Mode.FRETTED ? (int) (w * 0.52f) : w;
        int ch = mode == Mode.FRETTED ? h : (int) (h * 0.5f);
        for (Card card : cards) {
            LayoutParams lp = (LayoutParams) card.root.getLayoutParams();
            lp.width = cw;
            lp.height = ch;
            lp.gravity = Gravity.TOP | Gravity.START;
            card.root.setLayoutParams(lp);
            card.root.setPivotX(cw / 2f);
            card.root.setPivotY(ch / 2f);
        }
        place(cards[curCard], CUR);
        place(cards[nextCard], NEXT);
        place(cards[spareCard], IN);
        float fade = mode == Mode.FRETTED ? w * 0.10f : h * 0.10f;
        int opaque = Color.BLACK, clear = Color.TRANSPARENT;
        if (mode == Mode.FRETTED) {
            fadeA = new LinearGradient(0, 0, fade, 0, opaque, clear, Shader.TileMode.CLAMP);
            fadeB = new LinearGradient(w, 0, w - fade, 0, opaque, clear, Shader.TileMode.CLAMP);
        } else {
            fadeA = new LinearGradient(0, 0, 0, fade, opaque, clear, Shader.TileMode.CLAMP);
            fadeB = new LinearGradient(0, h, 0, h - fade, opaque, clear, Shader.TileMode.CLAMP);
        }
        invalidate();
    }

    /** Slot geometry: {centerX, centerY, scale, alpha}. */
    private float[] slot(int role) {
        int w = getWidth(), h = getHeight();
        if (mode == Mode.FRETTED) {
            switch (role) {
                case OUT:  return new float[] {-0.22f * w, h / 2f, 0.85f, 0f};
                case CUR:  return new float[] {0.30f * w, h / 2f, 1f, 1f};
                case NEXT: return new float[] {0.77f * w, h / 2f, 0.62f, 0.42f};
                default:   return new float[] {1.20f * w, h / 2f, 0.5f, 0f};
            }
        }
        switch (role) {
            case OUT:  return new float[] {w / 2f, -0.20f * h, 0.9f, 0f};
            case CUR:  return new float[] {w / 2f, 0.27f * h, 1f, 1f};
            case NEXT: return new float[] {w / 2f, 0.76f * h, 0.78f, 0.42f};
            default:   return new float[] {w / 2f, 1.20f * h, 0.6f, 0f};
        }
    }

    private void place(Card card, int role) {
        float[] s = slot(role);
        View v = card.root;
        LayoutParams lp = (LayoutParams) v.getLayoutParams();
        v.setTranslationX(s[0] - lp.width / 2f);
        v.setTranslationY(s[1] - lp.height / 2f);
        v.setScaleX(s[2]);
        v.setScaleY(s[2]);
        v.setAlpha(s[3]);
    }

    private void animateTo(Card card, int role) {
        float[] s = slot(role);
        View v = card.root;
        LayoutParams lp = (LayoutParams) v.getLayoutParams();
        v.animate()
                .translationX(s[0] - lp.width / 2f)
                .translationY(s[1] - lp.height / 2f)
                .scaleX(s[2]).scaleY(s[2]).alpha(s[3])
                .setDuration(ADVANCE_MS)
                .setInterpolator(new DecelerateInterpolator(1.6f))
                .start();
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
        int w = getWidth(), h = getHeight();
        if (fadeA == null) return;
        fadePaint.setShader(fadeA);
        canvas.drawRect(0, 0, w, h, fadePaint);
        fadePaint.setShader(fadeB);
        canvas.drawRect(0, 0, w, h, fadePaint);
    }

    /** Name (and optional bass label) above a fretted or piano diagram. */
    private static final class Card {
        final LinearLayout root;
        final TextView name;
        final TextView bass;
        final FrettedChordView fretted;
        final PianoChordView piano;

        Card(Context c) {
            float d = c.getResources().getDisplayMetrics().density;
            root = new LinearLayout(c);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setGravity(Gravity.CENTER_HORIZONTAL);
            name = new TextView(c);
            name.setTextColor(Color.WHITE);
            name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 36f);
            name.setGravity(Gravity.CENTER);
            name.setSingleLine(true);
            bass = new TextView(c);
            bass.setTextColor(Color.argb(170, 255, 255, 255));
            bass.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
            bass.setGravity(Gravity.CENTER);
            bass.setVisibility(GONE);
            fretted = new FrettedChordView(c);
            piano = new PianoChordView(c);
            LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                    LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
            root.addView(name, nlp);
            root.addView(bass, new LinearLayout.LayoutParams(
                    LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                    LayoutParams.MATCH_PARENT, 0, 1f);
            dlp.topMargin = (int) (10 * d);
            root.addView(fretted, dlp);
            root.addView(piano, new LinearLayout.LayoutParams(
                    LayoutParams.MATCH_PARENT, 0, 1f));
            piano.setVisibility(GONE);
        }

        void applyMode(Mode mode, int strings) {
            fretted.setVisibility(mode == Mode.FRETTED ? VISIBLE : GONE);
            piano.setVisibility(mode == Mode.PIANO ? VISIBLE : GONE);
            fretted.setStrings(strings);
        }

        void bind(@Nullable CardData data) {
            if (data == null) {
                name.setText("");
                bass.setVisibility(GONE);
                fretted.setPosition(null);
                piano.setKeys(new int[0], new String[0]);
                return;
            }
            name.setText(data.name);
            bass.setText(data.bassLabel);
            bass.setVisibility(data.bassLabel != null ? VISIBLE : GONE);
            fretted.setPosition(data.position);
            piano.setKeys(data.pianoKeys, data.pianoNames);
        }
    }
}
