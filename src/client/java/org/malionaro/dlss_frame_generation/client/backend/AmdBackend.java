package org.malionaro.dlss_frame_generation.client.backend;
import org.malionaro.dlss_frame_generation.client.FrameGenMod;
import org.malionaro.dlss_frame_generation.client.config.FrameGenConfig;
import org.malionaro.dlss_frame_generation.client.render.RuntimeFallbackPolicy;
import org.malionaro.dlss_frame_generation.client.StreamlineBridge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * AMD FidelityFX backend. The Vulkan proxy swapchain is integrated separately
 * from NVIDIA Streamline; full frame interpolation remains capability-gated
 * until the renderer supplies dense motion vectors and the remaining inputs.
 */
public class AmdBackend implements FrameGenerationBackend {
    // C10: see NvidiaBackend — native misses are debug-logged, not swallowed.
    private static final Logger LOGGER = LoggerFactory.getLogger("dlss_fg.backend.amd");

    @Override
    public GpuVendor vendor() {
        return GpuVendor.AMD;
    }

    @Override
    public boolean isSupported() {
        return FrameGenerationCompatibilityLayer.queryVulkanRoute(vendor())
                .capabilities() != 0;
    }

    @Override
    public boolean isFrameGenerationActive() {
        return FrameGenerationCompatibilityLayer.queryVulkanRoute(vendor())
                .frameGenerationReady()
                && FrameGenConfig.getInstance().amdFsrFgEnabled;
    }

    @Override
    public void applyConfig(FrameGenConfig config) {
        if (!isSupported()) return;

        try {
            // All AMD upscaling and FG mode changes go through here. Single place, no duplicates.
            // C3: same session-fallback path as NVIDIA — a FAILED/UNAVAILABLE
            // provider degrades to reconstruction instead of showing FG as on.
            int supported = FrameGenerationCompatibilityLayer.queryVulkanRoute(GpuVendor.AMD).capabilities();
            ProviderLifecycle.State state = FrameGenMod.compatibilityManager() != null
                    ? FrameGenMod.compatibilityManager().providerSnapshot().state()
                    : ProviderLifecycle.State.UNAVAILABLE;
            RuntimeFallbackPolicy.Decision fallback = RuntimeFallbackPolicy.resolve(
                    GpuVendor.AMD, config.autoFallbackChainEnabled, config.amdFsrFgEnabled,
                    config.amdFsrMode, supported, state);
            StreamlineBridge.nSetUpscalingMode(GpuVendor.AMD.vendorId(), fallback.upscalerMode(), config.amdFsrSharpness);
            boolean fgEnabled = fallback.frameGenerationEnabled() && config.amdFsrFgEnabled;
            StreamlineBridge.nSetFrameGenerationMode(GpuVendor.AMD.vendorId(), fgEnabled, config.amdFsrMultiplier, config.uiRecomposition);
        } catch (UnsatisfiedLinkError e) {
            LOGGER.debug("AMD native applyConfig unavailable: {}", e.getMessage());
        }
    }

    @Override
    public void disableForPause(FrameGenConfig config) {
        try {
            StreamlineBridge.nSetFrameGenerationMode(GpuVendor.AMD.vendorId(), false, 2, false);
        } catch (UnsatisfiedLinkError ignored) {
            LOGGER.debug("AMD native call unavailable: {}", ignored.getMessage());
        }
    }

    @Override
    public long estimatedVramUsageBytes() {
        if (!isSupported()) return 0;
        try {
            return StreamlineBridge.nGetVramUsage(GpuVendor.AMD.vendorId());
        } catch (UnsatisfiedLinkError e) {
            LOGGER.debug("AMD VRAM query unavailable: {}", e.getMessage());
            return 0;
        }
    }

    @Override
    public String statusDescription() {
        FrameGenerationCompatibilityLayer.Route route =
                FrameGenerationCompatibilityLayer.queryVulkanRoute(vendor());
        return route.status();
    }
}
