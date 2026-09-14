package org.malionaro.dlss_frame_generation.client.backend;
import org.malionaro.dlss_frame_generation.client.FrameGenMod;
import org.malionaro.dlss_frame_generation.client.config.FrameGenConfig;
import org.malionaro.dlss_frame_generation.client.render.RuntimeFallbackPolicy;
import org.malionaro.dlss_frame_generation.client.StreamlineBridge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Intel Vulkan backend. Temporal reconstruction is routed through OptiScaler
 * and frame generation uses the explicitly named cross-vendor FSR 3.1 path.
 * Native XeSS-FG remains unavailable because its API requires D3D12 and XeLL.
 */
public class IntelBackend implements FrameGenerationBackend {
    // C10: see NvidiaBackend — native misses are debug-logged, not swallowed.
    private static final Logger LOGGER = LoggerFactory.getLogger("dlss_fg.backend.intel");

    @Override
    public GpuVendor vendor() {
        return GpuVendor.INTEL;
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
                && FrameGenConfig.getInstance().intelXessFgEnabled;
    }

    @Override
    public void applyConfig(FrameGenConfig config) {
        if (!isSupported()) return;

        try {
            // Single entry point for Intel. All mode changes go through here.
            // C3: same session-fallback path as NVIDIA (see AmdBackend).
            int supported = FrameGenerationCompatibilityLayer.queryVulkanRoute(GpuVendor.INTEL).capabilities();
            ProviderLifecycle.State state = FrameGenMod.compatibilityManager() != null
                    ? FrameGenMod.compatibilityManager().providerSnapshot().state()
                    : ProviderLifecycle.State.UNAVAILABLE;
            RuntimeFallbackPolicy.Decision fallback = RuntimeFallbackPolicy.resolve(
                    GpuVendor.INTEL, config.autoFallbackChainEnabled, config.intelXessFgEnabled,
                    config.intelXessMode, supported, state);
            StreamlineBridge.nSetUpscalingMode(GpuVendor.INTEL.vendorId(), fallback.upscalerMode(), config.intelXessSharpness);
            boolean fgEnabled = fallback.frameGenerationEnabled() && config.intelXessFgEnabled;
            StreamlineBridge.nSetFrameGenerationMode(GpuVendor.INTEL.vendorId(), fgEnabled, config.intelXessMfgMultiplier, config.uiRecomposition);
        } catch (UnsatisfiedLinkError e) {
            LOGGER.debug("Intel native applyConfig unavailable: {}", e.getMessage());
        }
    }

    @Override
    public void disableForPause(FrameGenConfig config) {
        try {
            StreamlineBridge.nSetFrameGenerationMode(GpuVendor.INTEL.vendorId(), false, 2, false);
        } catch (UnsatisfiedLinkError ignored) {
            LOGGER.debug("Intel native call unavailable: {}", ignored.getMessage());
        }
    }

    @Override
    public long estimatedVramUsageBytes() {
        if (!isSupported()) return 0;
        try {
            return StreamlineBridge.nGetVramUsage(GpuVendor.INTEL.vendorId());
        } catch (UnsatisfiedLinkError e) {
            LOGGER.debug("Intel VRAM query unavailable: {}", e.getMessage());
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
