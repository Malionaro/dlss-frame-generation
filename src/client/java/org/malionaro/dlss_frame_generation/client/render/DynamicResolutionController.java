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
    // Anti-thrash: every scale change reallocates the whole input pipeline,
    // which costs one very slow frame. Without a cooldown that spike pushed
    // the smoothed frame time back over budget, the controller scaled down
    // again, and the render resolution oscillated across the whole range
    // (visible as screen wobble and periodic FPS drops).
    private static final long MIN_NANOS_BETWEEN_SCALE_CHANGES = 1_500_000_000L;
    // Samples taken right after a scale change include the realloc spike and
    // must not steer the next decision.
    private static final long REALLOC_QUIET_NANOS = 750_000_000L;
    // Upscaling needs real headroom, not just "slightly under budget".
    private static final float UPSCALE_HEADROOM_RATIO = 0.60f;
    private static final float VRAM_PRESSURE_STEP = 0.05f;
    private static final long VRAM_PRESSURE_HYSTERESIS_NANOS = 10_000L * 1_000_000L;

    private float scale = 1.0f;
    private float smoothedFrameTimeMs = 16.6667f;
    private int overBudgetSamples;
    private int underBudgetSamples;
    private long lastScaleChangeNanos;
    private long scaleChangeQuietUntilNanos;

    // Atomic check-then-act. The flag is intentionally process-wide (one
    // GPU, one active controller); the CAS loop makes concurrent notifies safe
    // instead of relying on render-thread confinement.
    private static final java.util.concurrent.atomic.AtomicLong lastVramPressureAcceptedNanos =
            new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicBoolean vramPressureScaleDropPending =
            new java.util.concurrent.atomic.AtomicBoolean();

    /**
     * Called by the VRAM budget optimizer: lowers the scale once by
     * {@value #VRAM_PRESSURE_STEP}. Repeated calls within the 10 s hysteresis
     * are ignored.
     */
    public static void notifyVramPressure() {
        long now = System.nanoTime();
        long last = lastVramPressureAcceptedNanos.get();
        if (last != 0L && now - last < VRAM_PRESSURE_HYSTERESIS_NANOS) {
            return;
        }
        if (!lastVramPressureAcceptedNanos.compareAndSet(last, now)) {
            return;
        }
        vramPressureScaleDropPending.set(true);
    }

    /** Konsumiert einen ausstehenden VRAM-Druck-Abfall genau einmal pro Instanz. */
    private void applyPendingVramPressureDrop() {
        if (vramPressureScaleDropPending.compareAndSet(true, false)) {
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
        // The realloc after a scale change lands here as a multi-hundred-ms
        // outlier. Feeding it into the average is what made the controller
        // undo its own decision and oscillate.
        boolean quietPeriod = System.nanoTime() < scaleChangeQuietUntilNanos;
        smoothedFrameTimeMs += (clampedFrameTime - smoothedFrameTimeMs) * 0.12f;
        if (quietPeriod) {
            return;
        }
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
            // Step down harder the further the frame time is over budget.
            float adaptiveDown = DOWNSCALE_STEP;
            if (smoothedFrameTimeMs > targetFrameTimeMs * 1.20f) {
                adaptiveDown *= 1.5f;
            } else if (smoothedFrameTimeMs > targetFrameTimeMs * 1.35f) {
                adaptiveDown *= 2.0f;
            }
            changeScale(scale - adaptiveDown);
            overBudgetSamples = 0;
        } else if (underBudgetSamples >= SAMPLES_BEFORE_ADJUSTMENT
                && smoothedFrameTimeMs < targetFrameTimeMs * UPSCALE_HEADROOM_RATIO) {
            // Only grow with clear headroom. Scaling up towards a target that
            // is already met just spends the margin and risks thrashing.
            float adaptiveUp = UPSCALE_STEP;
            if (smoothedFrameTimeMs < targetFrameTimeMs * 0.75f) {
                adaptiveUp *= 1.5f;
            }
            changeScale(scale + adaptiveUp);
            underBudgetSamples = 0;
        }
    }

    /**
     * Applies a scale step, honouring the anti-thrash cooldown.
     *
     * <p>Refuses the change while the pipeline is still recovering from the
     * previous reallocation, and ignores samples that land inside the quiet
     * window so the realloc spike cannot immediately reverse the decision.</p>
     */
    private void changeScale(float candidate) {
        long now = System.nanoTime();
        if (now < scaleChangeQuietUntilNanos) {
            return;
        }
        if (lastScaleChangeNanos != 0L
                && now - lastScaleChangeNanos < MIN_NANOS_BETWEEN_SCALE_CHANGES) {
            return;
        }
        float clamped = clampScale(candidate);
        if (Math.abs(clamped - scale) < 1e-4f) {
            return;
        }
        scale = clamped;
        lastScaleChangeNanos = now;
        scaleChangeQuietUntilNanos = now + REALLOC_QUIET_NANOS;
        // Both counters restart so neither direction can fire immediately.
        overBudgetSamples = 0;
        underBudgetSamples = 0;
    }

    /**
     * Applies the current scale to {@code base}. Only the render size changes;
     * the output size stays, so nothing is reallocated at full size.
     */
    public NvidiaDlssRenderResolution.Resolution apply(
            NvidiaDlssRenderResolution.Resolution base,
            boolean enabled
    ) {
        if (!enabled || base == null || base.mode() <= 0) {
            return base;
        }
        // One ratio for both sides, sized by the shared helper so every DRS
        // step keeps the window's aspect ratio (independent rounding made
        // the image stretch a little differently at each step: a wobble).
        double ratio = Math.max(
                MIN_OUTPUT_RATIO,
                Math.min(1.0, base.renderWidth() * (double) scale / Math.max(1, base.outputWidth()))
        );
        int[] size = NvidiaDlssRenderResolution.aspectPreservingSize(
                base.outputWidth(), base.outputHeight(), ratio);
        int[] minimum = NvidiaDlssRenderResolution.minimumRenderSize(base);
        if (minimum != null) {
            // Never below the upscaler's dynamic range: DLSS rejects such
            // frames outright and the picture stops updating.
            while ((size[0] < minimum[0] || size[1] < minimum[1]) && ratio < 1.0) {
                ratio = Math.min(1.0, ratio + 0.005);
                size = NvidiaDlssRenderResolution.aspectPreservingSize(
                        base.outputWidth(), base.outputHeight(), ratio);
            }
        }
        int renderWidth = size[0];
        int renderHeight = size[1];
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
        lastScaleChangeNanos = 0L;
        scaleChangeQuietUntilNanos = 0L;
    }

    private static float clampScale(float value) {
        return Math.max(MIN_SCALE, Math.min(1.0f, value));
    }

}
