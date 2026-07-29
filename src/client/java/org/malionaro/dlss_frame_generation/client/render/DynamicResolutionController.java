package org.malionaro.dlss_frame_generation.client.render;

/**
 * Small, renderer-independent controller for dynamic resolution scaling.
 *
 * <p>The controller deliberately changes scale slowly. Reallocating Minecraft
 * render targets every frame is considerably more expensive than tolerating a
 * short frame-time excursion, so adjustments require several consecutive
 * samples and use asymmetric steps: quality is reduced quickly under load and
 * restored conservatively.</p>
 */
public final class DynamicResolutionController {
    static final float MIN_SCALE = 0.50f;
    private static final float MIN_OUTPUT_RATIO = 1.0f / 3.0f;
    private static final float DOWNSCALE_STEP = 0.05f;
    private static final float UPSCALE_STEP = 0.025f;
    private static final int SAMPLES_BEFORE_ADJUSTMENT = 12;

    private float scale = 1.0f;
    private float smoothedFrameTimeMs = 16.6667f;
    private int overBudgetSamples;
    private int underBudgetSamples;

    public void recordFrameTime(float frameTimeMs, int targetFps, boolean enabled) {
        if (!enabled) {
            reset();
            return;
        }
        if (!Float.isFinite(frameTimeMs) || frameTimeMs <= 0.0f) {
            return;
        }

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
            scale = clampScale(scale - DOWNSCALE_STEP);
            overBudgetSamples = 0;
        } else if (underBudgetSamples >= SAMPLES_BEFORE_ADJUSTMENT) {
            scale = clampScale(scale + UPSCALE_STEP);
            underBudgetSamples = 0;
        }
    }

    public NvidiaDlssRenderResolution.Resolution apply(
            NvidiaDlssRenderResolution.Resolution base,
            boolean enabled
    ) {
        if (!enabled || base == null || base.mode() <= 0) {
            return base;
        }
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
