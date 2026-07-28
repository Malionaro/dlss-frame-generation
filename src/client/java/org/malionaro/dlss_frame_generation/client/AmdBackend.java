package org.malionaro.dlss_frame_generation.client;

/**
 * AMD FidelityFX backend. The Vulkan proxy swapchain is integrated separately
 * from NVIDIA Streamline; full frame interpolation remains capability-gated
 * until the renderer supplies dense motion vectors and the remaining inputs.
 */
public class AmdBackend implements FrameGenerationBackend {

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
            StreamlineBridge.nSetUpscalingMode(GpuVendor.AMD.vendorId(), config.amdFsrMode, config.amdFsrSharpness);
            StreamlineBridge.nSetFrameGenerationMode(GpuVendor.AMD.vendorId(), config.amdFsrFgEnabled, config.amdFsrMultiplier, config.uiRecomposition);
        } catch (UnsatisfiedLinkError e) {
            // Fallback
        }
    }

    @Override
    public long estimatedVramUsageBytes() {
        if (!isSupported()) return 0;
        try {
            return StreamlineBridge.nGetVramUsage(GpuVendor.AMD.vendorId());
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
