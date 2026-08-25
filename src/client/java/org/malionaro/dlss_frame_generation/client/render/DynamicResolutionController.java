package org.malionaro.dlss_frame_generation.client.render;

/**
 * Small controller for dynamic resolution scaling. It does not depend on the renderer.
 *
 * <p>DRS no longer needs WaitIdle or realloc. The controller keeps one max-size
 * image allocated to outputWidth and outputHeight and only moves the viewport
 * via {@link #apply}. No WaitIdle, no recreate on every scale change.
 * Call {@link #apply} before {@code resolve()} so the viewport is valid.</p>
 *
 * <p>Cooldown went from 12 to 6 samples. That reacts faster without flutter.
 * The downscale step went from 0.05 to 0.07, so it drops faster under load.
 * Upscale stays careful, but it grows a bit when far under budget.</p>
 *
 * <p>The controller changes scale slowly on purpose. Recreating Minecraft
 * render targets every frame costs more than living with a short spike in
 * frame time. It waits for several samples in a row and uses different steps.
 * Quality drops fast when overloaded and comes back slowly.</p>
 */
public final class DynamicResolutionController {
    static final float MIN_SCALE = 0.50f;
    private static final float MIN_OUTPUT_RATIO = 1.0f / 3.0f;
    private static final float DOWNSCALE_STEP = 0.07f;
    private static final float UPSCALE_STEP = 0.035f;
    private static final int SAMPLES_BEFORE_ADJUSTMENT = 6;
    private static final float VRAM_PRESSURE_STEP = 0.05f;
    private static final long VRAM_PRESSURE_HYSTERESIS_NANOS = 10_000L * 1_000_000L;

    private float scale = 1.0f;
    private float smoothedFrameTimeMs = 16.6667f;
    private int overBudgetSamples;
    private int underBudgetSamples;

    private static volatile long lastVramPressureAcceptedNanos;
    private static volatile boolean vramPressureScaleDropPending;

    /**
     * Statischer Backpressure-Hook für den VramBudgetOptimizer: setzt intern
     * einen Flag, der den Skalierungsfaktor einmalig um {@value #VRAM_PRESSURE_STEP}
     * senkt. Wiederholte Meldungen innerhalb der 10s-Hysterese werden ignoriert.
     */
    public static void notifyVramPressure() {
        long now = System.nanoTime();
        long last = lastVramPressureAcceptedNanos;
        if (last != 0L && now - last < VRAM_PRESSURE_HYSTERESIS_NANOS) {
            return;
        }
        lastVramPressureAcceptedNanos = now;
        vramPressureScaleDropPending = true;
    }

    /** Konsumiert einen ausstehenden VRAM-Druck-Abfall genau einmal pro Instanz. */
    private void applyPendingVramPressureDrop() {
        if (vramPressureScaleDropPending) {
            vramPressureScaleDropPending = false;
            scale = clampScale(scale - VRAM_PRESSURE_STEP);
        }
    }

    public void recordFrameTime(float frameTimeMs, int targetFps, boolean enabled) {
        if (!enabled) {
            reset();
            return;
        }
        if (!Float.isFinite(frameTimeMs) || frameTimeMs <= 0.0f) {
            return;
        }
        applyPendingVramPressureDrop();

        float clampedFrameTime = Math.max(1.0f, Math.min(250.0f, frameTimeMs));
        smoothedFrameTimeMs += (clampedFrameTime - smoothedFrameTimeMs) * 0.12f;
        float targetFrameTimeMs = 1000.0f / Math.max(30, targetFps);

        if (smoothedFrameTimeMs > targetFrameTimeMs * 1.06f) {
            overBudgetSamples++;
            underBudgetSamples = 0;
        } else if (smoothedFrameTimeMs < targetFrameTimeMs * 0.88f) {
            underBudgetSamples++;
            overBudgetSamples = 0;
        } else {
            overBudgetSamples = 0;
            underBudgetSamples = 0;
        }

        if (overBudgetSamples >= SAMPLES_BEFORE_ADJUSTMENT) {
            // M3: adaptiver Step - je weiter über Budget, desto größerer Schritt (0.07 Basis)
            float adaptiveDown = DOWNSCALE_STEP;
            if (smoothedFrameTimeMs > targetFrameTimeMs * 1.20f) {
                adaptiveDown *= 1.5f;
            } else if (smoothedFrameTimeMs > targetFrameTimeMs * 1.35f) {
                adaptiveDown *= 2.0f;
            }
            scale = clampScale(scale - adaptiveDown);
            overBudgetSamples = 0;
        } else if (underBudgetSamples >= SAMPLES_BEFORE_ADJUSTMENT) {
            float adaptiveUp = UPSCALE_STEP;
            if (smoothedFrameTimeMs < targetFrameTimeMs * 0.75f) {
                adaptiveUp *= 1.5f;
            }
            scale = clampScale(scale + adaptiveUp);
            underBudgetSamples = 0;
        }
    }

    /**
     * M3: Berechnet die Viewport-skalierte Render-Auflösung. Das persistente
     * Max-Size Image bleibt auf {@code outputWidth/outputHeight} allokiert;
     * zurückgegeben wird nur der skalierte Viewport (kein Realloc, kein WaitIdle).
     * <b>Muss vor</b> {@code resolve()} aufgerufen werden, damit der Viewport vor
     * dem Resolve/Upscale gültig ist.
     */
    public NvidiaDlssRenderResolution.Resolution apply(
            NvidiaDlssRenderResolution.Resolution base,
            boolean enabled
    ) {
        if (!enabled || base == null || base.mode() <= 0) {
            return base;
        }
        // M3: Persistente Max-Size Images - outputWidth/Height bleibt die Allokationsgröße,
        // renderWidth/renderHeight ist nur der Viewport (kein Realloc).
        int renderWidth = alignToEight((int) Math.round(
                base.renderWidth() * scale
        ));
        int renderHeight = alignToEight((int) Math.round(
                base.renderHeight() * scale
        ));
        int minimumWidth = alignToEight((int) Math.round(
                base.outputWidth() * MIN_OUTPUT_RATIO
        ));
        int minimumHeight = alignToEight((int) Math.round(
                base.outputHeight() * MIN_OUTPUT_RATIO
        ));
        renderWidth = Math.max(
                Math.max(32, minimumWidth),
                Math.min(base.outputWidth(), renderWidth)
        );
        renderHeight = Math.max(
                Math.max(32, minimumHeight),
                Math.min(base.outputHeight(), renderHeight)
        );
        return new NvidiaDlssRenderResolution.Resolution(
                renderWidth,
                renderHeight,
                base.outputWidth(),
                base.outputHeight(),
                base.mode(),
                renderWidth != base.outputWidth()
                        || renderHeight != base.outputHeight()
        );
    }

    public float scale() {
        return scale;
    }

    public float smoothedFrameTimeMs() {
        return smoothedFrameTimeMs;
    }

    public void reset() {
        scale = 1.0f;
        smoothedFrameTimeMs = 16.6667f;
        overBudgetSamples = 0;
        underBudgetSamples = 0;
    }

    private static float clampScale(float value) {
        return Math.max(MIN_SCALE, Math.min(1.0f, value));
    }

    private static int alignToEight(int value) {
        return Math.max(8, (value + 7) & ~7);
    }
}
