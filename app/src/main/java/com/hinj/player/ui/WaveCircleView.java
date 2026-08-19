package com.hinj.player.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

import androidx.annotation.Nullable;

/**
 * Draws three concentric wavy rings around a circular album-art canvas.
 * Wave amplitude is driven by setAudioLevel(0..1) from the Visualizer;
 * the rings animate continuously via a ValueAnimator phase offset so they
 * remain alive even at silence.
 *
 * Per-point trig is eliminated by precomputing sin/cos look-up tables in
 * the constructor, then using the angle-addition identity at draw time —
 * only 2 sin/cos calls per layer per frame instead of NUM_POINTS * 3.
 */
public class WaveCircleView extends View {

    private static final int NUM_POINTS = 180;

    // 9 harmonics — 3 per layer (indices 0-2 → L0, 3-5 → L1, 6-8 → L2).
    // Different frequency sets per layer give each ring a distinct, organic shape.
    private static final float[] FREQS   = { 5f, 3f, 7f,  4f, 6f, 2f,  6f, 4f, 8f };
    private static final float[] WEIGHTS = { .5f,.3f,.2f, .45f,.35f,.2f, .4f,.4f,.2f };
    private static final float[] PHASE_MULT = { 1.0f, 1.3f, 0.7f }; // per-layer phase speed

    // Look-up tables (built in constructor — no allocs at draw time)
    private final float[] cosAlpha = new float[NUM_POINTS + 1];
    private final float[] sinAlpha = new float[NUM_POINTS + 1];
    // [harmonic h][point i][0=sin, 1=cos] of (FREQS[h] * 2π*i / NUM_POINTS)
    private final float[][][] htable = new float[9][NUM_POINTS + 1][2];

    // Geometry — recalculated in onSizeChanged
    private float cx, cy;
    private float artRadius;
    private final float[] layerBase = new float[3];
    private final float[] layerMaxAmp = new float[3];
    private float minAmp; // always-present gentle wave at silence

    // Audio reactivity
    private volatile float targetLevel = 0f;
    private float smoothedLevel = 0f;

    // Album art
    @Nullable private Bitmap artBitmap;

    // Phase animation
    private float phase = 0f;
    @Nullable private ValueAnimator phaseAnimator;

    // Paints
    private final Paint[] wavePaint = new Paint[3];
    private final Paint artFillPaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint artBitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Path  wavePath = new Path();

    public WaveCircleView(Context ctx) { this(ctx, null); }
    public WaveCircleView(Context ctx, @Nullable AttributeSet a) { this(ctx, a, 0); }
    public WaveCircleView(Context ctx, @Nullable AttributeSet a, int def) {
        super(ctx, a, def);
        setLayerType(LAYER_TYPE_HARDWARE, null);
        buildLookupTables();
        initPaints();
    }

    private void buildLookupTables() {
        double step = 2 * Math.PI / NUM_POINTS;
        for (int i = 0; i <= NUM_POINTS; i++) {
            double angle = step * i;
            cosAlpha[i] = (float) Math.cos(angle);
            sinAlpha[i] = (float) Math.sin(angle);
        }
        for (int h = 0; h < 9; h++) {
            double fStep = FREQS[h] * step;
            for (int i = 0; i <= NUM_POINTS; i++) {
                double arg = fStep * i;
                htable[h][i][0] = (float) Math.sin(arg);
                htable[h][i][1] = (float) Math.cos(arg);
            }
        }
    }

    private void initPaints() {
        // hinj_green = #1ED760 with varying alpha per layer
        wavePaint[0] = strokePaint(0xDD1ED760); // 87% — innermost, most vivid
        wavePaint[1] = strokePaint(0x991ED760); // 60% — middle
        wavePaint[2] = strokePaint(0x551ED760); // 33% — outermost, ethereal

        artFillPaint.setStyle(Paint.Style.FILL);
        artFillPaint.setColor(0xFF1ED760); // green circle when no artwork
    }

    private static Paint strokePaint(int argb) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setColor(argb);
        return p;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        super.onSizeChanged(w, h, oldW, oldH);
        cx = w / 2f;
        cy = h / 2f;
        float min = Math.min(w, h);

        artRadius   = min * 0.30f;
        minAmp      = min * 0.008f; // whisper wave even at silence

        layerBase[0] = artRadius + min * 0.04f;
        layerBase[1] = artRadius + min * 0.09f;
        layerBase[2] = artRadius + min * 0.15f;

        layerMaxAmp[0] = min * 0.04f;
        layerMaxAmp[1] = min * 0.055f;
        layerMaxAmp[2] = min * 0.065f; // max extent = 0.30+0.15+0.065 = 0.515 → stays within view

        wavePaint[0].setStrokeWidth(Math.max(2f,  min * 0.006f));
        wavePaint[1].setStrokeWidth(Math.max(2.5f, min * 0.008f));
        wavePaint[2].setStrokeWidth(Math.max(3f,   min * 0.010f));
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        phaseAnimator = ValueAnimator.ofFloat(0f, (float)(2 * Math.PI));
        phaseAnimator.setDuration(5000);
        phaseAnimator.setRepeatCount(ValueAnimator.INFINITE);
        phaseAnimator.setRepeatMode(ValueAnimator.RESTART);
        phaseAnimator.setInterpolator(new LinearInterpolator());
        phaseAnimator.addUpdateListener(a -> {
            phase = (float) a.getAnimatedValue();
            invalidate();
        });
        phaseAnimator.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (phaseAnimator != null) {
            phaseAnimator.cancel();
            phaseAnimator = null;
        }
        super.onDetachedFromWindow();
    }

    /** Called from the Visualizer callback thread — safe to call from any thread. */
    public void setAudioLevel(float level) {
        targetLevel = Math.min(1f, Math.max(0f, level));
    }

    public void setArtBitmap(@Nullable Bitmap bmp) {
        artBitmap = bmp;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (cx == 0) return;

        // Exponential smoothing: fast attack (punch on beat), slow decay (graceful fade)
        float alpha = targetLevel > smoothedLevel ? 0.35f : 0.05f;
        smoothedLevel += alpha * (targetLevel - smoothedLevel);

        // Draw wave rings outermost-first so inner rings overlap outer
        for (int layer = 2; layer >= 0; layer--) {
            drawWaveRing(canvas, layer, minAmp + smoothedLevel * layerMaxAmp[layer]);
        }

        // Draw circular album art on top
        canvas.save();
        Path clip = new Path();
        clip.addCircle(cx, cy, artRadius, Path.Direction.CW);
        canvas.clipPath(clip);

        if (artBitmap != null && !artBitmap.isRecycled()) {
            float bMin = Math.min(artBitmap.getWidth(), artBitmap.getHeight());
            float scale = 2 * artRadius / bMin;
            float bw = artBitmap.getWidth() * scale;
            float bh = artBitmap.getHeight() * scale;
            canvas.drawBitmap(artBitmap, null,
                    new RectF(cx - bw / 2f, cy - bh / 2f, cx + bw / 2f, cy + bh / 2f),
                    artBitmapPaint);
        } else {
            canvas.drawCircle(cx, cy, artRadius, artFillPaint);
        }
        canvas.restore();
    }

    private void drawWaveRing(Canvas canvas, int layer, float amplitude) {
        // Angle-addition identity: sin(freq*α + phase*mult)
        //   = sin(freq*α)*cos(phase*mult) + cos(freq*α)*sin(phase*mult)
        // So per layer we pay 2 trig calls; per point, just multiplications.
        double phaseArg = phase * PHASE_MULT[layer];
        float sinP = (float) Math.sin(phaseArg);
        float cosP = (float) Math.cos(phaseArg);
        int hBase = layer * 3;
        float base = layerBase[layer];

        wavePath.reset();
        for (int i = 0; i <= NUM_POINTS; i++) {
            float wave = 0f;
            for (int h = hBase; h < hBase + 3; h++) {
                float sinVal = htable[h][i][0] * cosP + htable[h][i][1] * sinP;
                wave += WEIGHTS[h] * sinVal;
            }
            float r = base + amplitude * wave;
            float x = cx + r * cosAlpha[i];
            float y = cy + r * sinAlpha[i];
            if (i == 0) wavePath.moveTo(x, y); else wavePath.lineTo(x, y);
        }
        wavePath.close();
        canvas.drawPath(wavePath, wavePaint[layer]);
    }
}
