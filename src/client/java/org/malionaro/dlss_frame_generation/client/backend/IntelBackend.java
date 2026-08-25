package org.malionaro.dlss_frame_generation.client.backend;
import org.malionaro.dlss_frame_generation.client.config.FrameGenConfig;
import org.malionaro.dlss_frame_generation.client.StreamlineBridge;

/**
 * Intel Vulkan backend. Temporal reconstruction is routed through OptiScaler
 * and frame generation uses the explicitly named cross-vendor FSR 3.1 path.
 * Native XeSS-FG remains unavailable because its API requires D3D12 and XeLL.
 */
public class IntelBackend implements FrameGenerationBackend {

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
            StreamlineBridge.nSetUpscalingMode(GpuVendor.INTEL.vendorId(), config.intelXessMode, config.intelXessSharpness);
            StreamlineBridge.nSetFrameGenerationMode(GpuVendor.INTEL.vendorId(), config.intelXessFgEnabled, config.intelXessMfgMultiplier, config.uiRecomposition);
        } catch (UnsatisfiedLinkError e) {
            // Fallback
        }
    }

    @Override
    public void disableForPause(FrameGenConfig config) {
        try {
            StreamlineBridge.nSetFrameGenerationMode(GpuVendor.INTEL.vendorId(), false, 2, false);
        } catch (UnsatisfiedLinkError ignored) {
        }
    }

    @Override
    public long estimatedVramUsageBytes() {
        if (!isSupported()) return 0;
        try {
            return StreamlineBridge.nGetVramUsage(GpuVendor.INTEL.vendorId());
        } catch (UnsatisfiedLinkError e) {
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
