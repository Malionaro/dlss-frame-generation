package org.malionaro.dlss_frame_generation.client;

/**
 * Intel backend placeholder. Intel's XeSS-FG API requires a D3D12 proxy
 * swapchain and cannot run on this renderer's Vulkan swapchain.
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
            StreamlineBridge.nSetUpscalingMode(GpuVendor.INTEL.vendorId(), config.intelXessMode, config.intelXessSharpness);
            StreamlineBridge.nSetFrameGenerationMode(GpuVendor.INTEL.vendorId(), config.intelXessFgEnabled, config.intelXessMfgMultiplier, config.uiRecomposition);
        } catch (UnsatisfiedLinkError e) {
            // Fallback
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
