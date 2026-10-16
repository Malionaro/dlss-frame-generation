package org.malionaro.dlss_frame_generation.client.compat;

import org.malionaro.dlss_frame_generation.client.FrameGenMod;
import org.malionaro.dlss_frame_generation.client.StreamlineBridge;
import org.malionaro.dlss_frame_generation.client.backend.FrameGenerationBackend;
import org.malionaro.dlss_frame_generation.client.config.FrameGenConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Turns on frame generation and DLSS upscaling when a shader pack starts.
 *
 * <p>Shader packs, including ones with their own path tracing such as
 * Rethinking Voxels, are the heaviest load the game sees, so this is where FG
 * and upscaling matter most. It only switches features on that are off and
 * supported, once per pack start; anything the player changes afterwards
 * stays as they set it until the next start. {@code shaderPackAutoBoost}
 * disables it entirely. Render thread only.</p>
 */
public final class ShaderPackBoost {
    private static final Logger LOGGER = LoggerFactory.getLogger("dlss_fg.compat");
    /** DLSS Quality: the mildest mode that still renders below window size. */
    private static final int NVIDIA_QUALITY_MODE = 2;

    private static boolean lastActive;

    private ShaderPackBoost() {}

    /** Call once per frame; acts on the transition to an active shader pack. */
    public static void update() {
        boolean active = ForeignShaderPipeline.active();
        if (active && !lastActive) {
            apply(FrameGenConfig.getInstance());
        }
        lastActive = active;
    }

    private static void apply(FrameGenConfig config) {
        if (!config.shaderPackAutoBoost) {
            return;
        }
        CompatibilityManager compatibility = FrameGenMod.compatibilityManager();
        FrameGenerationBackend backend = compatibility != null
                ? compatibility.activeVendorBackend()
                : null;
        if (backend == null) {
            return;
        }
        FrameGenerationBackend.GpuVendor vendor = backend.vendor();
        int capabilities;
        try {
            capabilities = StreamlineBridge.nGetBackendSupportedCapabilities(vendor.vendorId());
        } catch (UnsatisfiedLinkError ignored) {
            return;
        }

        boolean changed = false;
        if ((capabilities & StreamlineBridge.CAP_FRAME_GENERATION) != 0
                && !config.isFrameGenerationEnabled(vendor)
                && config.conflictingFrameGenerationVendor(vendor)
                        == FrameGenerationBackend.GpuVendor.UNKNOWN
                && !config.hybridD3D12PresentationEnabled) {
            config.setFrameGenerationEnabled(vendor, true);
            if (vendor == FrameGenerationBackend.GpuVendor.NVIDIA && config.reflexMode == 0) {
                // DLSS-G requires Reflex.
                config.reflexMode = 1;
            }
            changed = true;
        }
        // Upscaling only helps where the engine can render below window size
        // (Vitrail's render scale); Iris always renders at window size.
        if (vendor == FrameGenerationBackend.GpuVendor.NVIDIA
                && VitrailBridge.available()
                && (capabilities & StreamlineBridge.CAP_UPSCALING) != 0
                && config.dlssUpscalingMode == 0) {
            config.setUpscalerMode(vendor, NVIDIA_QUALITY_MODE);
            config.projectionJitterEnabled = true;
            changed = true;
        }
        if (!changed) {
            return;
        }
        config.save();
        FrameGenerationBackend.applyActiveConfig(config);
        LOGGER.info("Shader pack started: enabled frame generation and upscaling automatically"
                + " (turn off with 'Shader packs: auto DLSS + FG')");
    }
}
