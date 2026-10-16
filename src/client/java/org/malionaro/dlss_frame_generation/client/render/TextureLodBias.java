package org.malionaro.dlss_frame_generation.client.render;

import org.malionaro.dlss_frame_generation.client.compat.ForeignShaderPipeline;

/**
 * Texture mip bias for the terrain sampler while an upscaler is active.
 *
 * <p>The GPU picks mip levels from the screen-space footprint at the render
 * resolution. At 50 % render scale it therefore samples one mip level blurrier
 * than native would, and the upscaler cannot recover detail that was never
 * sampled: distant terrain looks soft and smeared. NVIDIA's DLSS guide asks
 * for a bias of {@code log2(render / output)} so textures resolve with the
 * detail of the output resolution.</p>
 *
 * <p>Minecraft samplers are immutable, so the chunk-layer sampler is rebuilt
 * when the bias changes. The bias is quantized to quarter steps so dynamic
 * resolution does not rebuild it on every small scale change. Render thread
 * only.</p>
 */
public final class TextureLodBias {
    private static final float STEP = 0.25f;
    private static final float MIN_BIAS = -2.0f;

    private static float applied;
    private static float pending;

    private TextureLodBias() {}

    /** Bias the terrain sampler should currently use. */
    public static float desired() {
        NvidiaDlssRenderResolution.Resolution resolution = VulkanRegistry.activeResolution();
        // Keyed on the render size alone, not on whether the last frame was
        // accepted: a reduced image needs the bias even on the blit fallback,
        // and an intermittent rejection must not rebuild the sampler.
        if (resolution.mode() == 0 || ForeignShaderPipeline.active()) {
            return 0.0f;
        }
        return forRatio(resolution.renderWidth() / (double) Math.max(1, resolution.outputWidth()));
    }

    public static float forRatio(double ratio) {
        if (!Double.isFinite(ratio) || ratio <= 0.0 || ratio >= 1.0) {
            return 0.0f;
        }
        double bias = Math.log(ratio) / Math.log(2.0);
        float quantized = (float) (Math.round(bias / STEP) * STEP);
        return Math.max(MIN_BIAS, Math.min(0.0f, quantized));
    }

    /** True when the existing sampler was built with a different bias. */
    public static boolean needsRebuild() {
        return desired() != applied;
    }

    /** Arms {@link #pending()} for exactly one sampler creation. */
    public static void beginTerrainSampler() {
        pending = desired();
    }

    public static void endTerrainSampler() {
        applied = pending;
        pending = 0.0f;
    }

    /** Bias for the sampler being created right now; 0 for every other sampler. */
    public static float pending() {
        return pending;
    }

    public static float applied() {
        return applied;
    }
}
