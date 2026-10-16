package com.jotapeveloper.fision.brand;

import static com.jotapeveloper.fision.brand.FisionMotion.*;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.Path;
import android.os.SystemClock;
import android.view.View;

import androidx.annotation.Nullable;


/**
 * Animated splash: the nucleus (with its note) fades in growing, the neutron
 * comes in and hits it, the fragments and released neutrons bud out and the
 * FISION wordmark fades in. Particles are solid before the impact and blend
 * into the logo gradient after it. Background: accent focus behind the
 * nucleus fading to black, shown alone for a moment before the mark.
 */
public class FisionSplashView extends View {

    // ---- Timeline (ms; the mark starts BG_LEAD after the background) ----
    private static final float BG_FADE = 250f;     // background focus fade-in
    private static final float BG_LEAD = 350f;     // background alone before the mark
    private static final float SP_APPEAR = 240f;   // nucleus grows in
    private static final float SP_FADE = 220f;     // nucleus fade-in
    private static final float SP_T0 = 300f;       // equivalent of the ring closing
    private static final float BLEND_DUR = 450f;   // solid colors -> gradient
    private static final float WORD_IN = SP_T0 + IMPACT + 380f, WORD_DUR = 320f;
    private static final float END = Math.max(SP_T0 + SETTLED, WORD_IN + WORD_DUR);
    private static final float HOLD = 120f;

    // ---- Logo gradient, radial from the nucleus (measured on the app logo) ----
    private static final float GC_X = 215f, GC_Y = 255.5f;
    private static final float GL_R = 556f, GL_SX = 2.6f;   // left: compressed ellipse
    private static final int[] GL_COLORS = {
            0xFFFF0D01, 0xFFFF1102, 0xFFFF1A06, 0xFFFF290E, 0xFFFD3616, 0xFFF7441F, 0xFFEA542A,
            0xFFE15E32, 0xFFD76839, 0xFFCD7340, 0xFFC17E48, 0xFFB6874F, 0xFFAD8E54, 0xFFA39659,
            0xFF989D5E, 0xFF8DA564, 0xFF80AE6A, 0xFF74B36E, 0xFF68B872, 0xFF5ABE76, 0xFF48C47A,
            0xFF35C87D, 0xFF1ECC80, 0xFF0DD183, 0xFF04D687, 0xFF00DC8B, 0xFF00E28F, 0xFF00E792,
            0xFF00EB96, 0xFF00EF98, 0xFF00F49B};
    private static final float[] GL_POS = {
            0.1367f, 0.1655f, 0.1942f, 0.2230f, 0.2518f, 0.2806f, 0.3094f, 0.3381f, 0.3669f,
            0.3957f, 0.4245f, 0.4532f, 0.4820f, 0.5108f, 0.5396f, 0.5683f, 0.5971f, 0.6259f,
            0.6547f, 0.6835f, 0.7122f, 0.7410f, 0.7698f, 0.7986f, 0.8273f, 0.8561f, 0.8849f,
            0.9137f, 0.9424f, 0.9712f, 1.0000f};
    private static final float GR_R = 332f;                   // right: circular
    private static final int[] GR_COLORS = {
            0xFFFF0400, 0xFFFF0306, 0xFFF81018, 0xFFE52531, 0xFFC53E4B, 0xFFA65462, 0xFF8D6573,
            0xFF836A78, 0xFF6F7583, 0xFF428492, 0xFF278D9B, 0xFF1E93A1, 0xFF09A0AE, 0xFF00AAB8,
            0xFF00B1BF, 0xFF00B9C9, 0xFF00C1D1};
    private static final float[] GR_POS = {
            0.2289f, 0.2771f, 0.3253f, 0.3735f, 0.4217f, 0.4699f, 0.5181f, 0.5663f, 0.6145f,
            0.6627f, 0.7108f, 0.7590f, 0.8072f, 0.8554f, 0.9036f, 0.9518f, 1.0000f};
    /** Solid colors before the impact: neutron green, nucleus red, with a ramp
     *  across the gap between them (the bond after the impact). */
    private static final int SOLID_NEUTRON = 0xFF00F59D, SOLID_NUCLEUS = 0xFFFF0A0A;

    // ---- Background focus ----
    private static final int[] BG_COLORS = {0xFF17604F, 0xFF0C3A30, 0xFF03130F, 0xFF000000};
    private static final float[] BG_POS = {0f, 0.28f, 0.58f, 0.82f};

    private static final String WORD = "FISION";
    /** Wordmark cap height relative to the logo width, as in the app icon. */
    private static final float WORD_CAP = 72f / 507f;

    private final FisionRenderer renderer = new FisionRenderer();
    private final FisionRenderer.State state = new FisionRenderer.State();
    private final Paint gradL = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gradR = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint solid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bg = new Paint();
    private final Paint word = new Paint(Paint.ANTI_ALIAS_FLAG);
    private long startMs = -1;
    private boolean finished;
    @Nullable private Runnable onFinished;

    // Layout (px), recomputed on size change.
    private float logoLeft, logoTop, logoScale, wordTop, wordScale, wordGap;
    private final float[] glyphW = new float[WORD.length()];
    private final Path[] glyphs = new Path[WORD.length()];

    public FisionSplashView(Context c) {
        super(c);
        PorterDuffXfermode srcIn = new PorterDuffXfermode(PorterDuff.Mode.SRC_IN);
        RadialGradient left = new RadialGradient(GC_X, GC_Y, GL_R, GL_COLORS, GL_POS,
                Shader.TileMode.CLAMP);
        Matrix m = new Matrix();
        m.setScale(1f / GL_SX, 1f, GC_X, GC_Y);
        left.setLocalMatrix(m);
        gradL.setShader(left);
        gradL.setXfermode(srcIn);
        gradR.setShader(new RadialGradient(GC_X, GC_Y, GR_R, GR_COLORS, GR_POS,
                Shader.TileMode.CLAMP));
        gradR.setXfermode(srcIn);
        solid.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP));

        word.setColor(Color.WHITE);
        for (int i = 0; i < WORD.length(); i++) glyphs[i] = FisionWordmark.glyph(WORD.charAt(i));
    }

    /** Neutron center x at fission time [tf] (same motion as FisionRenderer). */
    private static float neutronX(float tf) {
        if (tf < N_START) return N_START_X;
        if (tf < IMPACT) return N_START_X + (NEU_CONTACT - N_START_X) * (tf - N_START) / N_FLY;
        return herm(NEU_CONTACT, -N_RECOIL_V, NEU_X, 0f, N_RECOIL, tf - IMPACT);
    }

    /** Nucleus bounce offset at fission time [tf] (same spring as FisionRenderer). */
    private static float bounceX(float tf) {
        if (tf < IMPACT) return 0f;
        float u = tf - IMPACT;
        return BOUNCE_A * (float) Math.exp(-u / BOUNCE_TAU)
                * (float) Math.sin(2 * Math.PI * u / BOUNCE_T);
    }

    /** Runs [r] once the animation has finished. */
    public void setOnFinished(@Nullable Runnable r) { onFinished = r; }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        // Logo square and wordmark, both 80% of the width, centered as a group.
        float logoW = 0.8f * w;
        logoScale = logoW / 512f;
        float capH = WORD_CAP * logoW;
        wordScale = capH / FisionWordmark.CAP;
        float sum = 0f;
        for (int i = 0; i < WORD.length(); i++) {
            glyphW[i] = FisionWordmark.advance(WORD.charAt(i)) * wordScale;
            sum += glyphW[i];
        }
        wordGap = (logoW - sum) / (WORD.length() - 1);
        float gapAbove = 0.04f * w;
        float groupH = logoW + gapAbove + capH;
        logoLeft = (w - logoW) / 2f;
        logoTop = (h - groupH) / 2f;
        wordTop = logoTop + logoW + gapAbove + capH;   // baseline

        float cx = logoLeft + ATOM_X * logoScale, cy = logoTop + ATOM_Y * logoScale;
        float r = (float) Math.max(Math.max(Math.hypot(cx, cy), Math.hypot(w - cx, cy)),
                Math.max(Math.hypot(cx, h - cy), Math.hypot(w - cx, h - cy)));
        bg.setShader(new RadialGradient(cx, cy, Math.max(1f, r), BG_COLORS, BG_POS,
                Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas c) {
        long now = SystemClock.uptimeMillis();
        if (startMs < 0) startMs = now;
        float total = now - startMs;

        // Background first: black window, then the focus fades in alone.
        c.drawColor(Color.BLACK);
        bg.setAlpha(Math.round(255 * smoother(c01(total / BG_FADE))));
        c.drawPaint(bg);

        float t = total - BG_LEAD;            // mark timeline
        if (t < 0f) {
            postInvalidateOnAnimation();
            return;
        }

        state.head = 1f;
        state.tail = 0f;
        state.alpha = smoother(c01(t / SP_FADE));
        state.grow = 0.82f + 0.18f * backOut(c01(t / SP_APPEAR), 1.2f);
        state.tf = t - SP_T0;

        // Mark: white mask in an offscreen layer, colored with SRC_IN (logo
        // gradient), solid colors on top with SRC_ATOP until the impact blend.
        int layer = c.saveLayer(0, 0, getWidth(), getHeight(), null);
        c.save();
        c.translate(logoLeft, logoTop);
        c.scale(logoScale, logoScale);
        renderer.draw(c, state, true, Color.WHITE);
        c.drawRect(-400f, -200f, GC_X, 700f, gradL);
        c.drawRect(GC_X, -200f, 900f, 700f, gradR);
        float blend = smoother(c01((state.tf - IMPACT) / BLEND_DUR));
        if (blend < 1f) {
            // Green -> red ramp across the gap between the neutron's outer edge
            // and the nucleus' outer edge: empty space before the impact (each
            // particle stays fully its color), the bond itself after it (smooth,
            // no hard cut while it blends into the logo gradient).
            float neutronEdge = neutronX(state.tf) + NEU_RM + NEU_SW / 2f;
            float nucleusEdge = ATOM_X + bounceX(state.tf) - ATOM_RM - ATOM_SW / 2f;
            float x0 = Math.min(neutronEdge, nucleusEdge - 0.02f);
            solid.setShader(new LinearGradient(x0, 0f, Math.max(nucleusEdge, x0 + 0.02f), 0f,
                    SOLID_NEUTRON, SOLID_NUCLEUS, Shader.TileMode.CLAMP));
            solid.setAlpha(Math.round(255 * (1f - blend)));
            c.drawRect(-400f, -200f, 900f, 700f, solid);
        }
        c.restore();
        c.restoreToCount(layer);

        // Wordmark.
        float wa = easeOut(c01((t - WORD_IN) / WORD_DUR));
        if (wa > 0f) {
            word.setAlpha(Math.round(255 * wa));
            float x = logoLeft;
            float capH = FisionWordmark.CAP * wordScale;
            for (int i = 0; i < WORD.length(); i++) {
                c.save();
                c.translate(x, wordTop - capH);
                c.scale(wordScale, wordScale);
                c.drawPath(glyphs[i], word);
                c.restore();
                x += glyphW[i] + wordGap;
            }
        }

        if (t < END + HOLD) {
            postInvalidateOnAnimation();
        } else if (!finished) {
            finished = true;
            Runnable r = onFinished;
            if (r != null) post(r);
        }
    }
}
