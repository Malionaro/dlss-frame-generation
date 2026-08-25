package org.malionaro.dlss_frame_generation.client.backend;
import org.malionaro.dlss_frame_generation.client.config.FrameGenConfig;
import org.malionaro.dlss_frame_generation.client.StreamlineBridge;

/**
 * NVIDIA Streamline backend. Hardware support is exposed separately from
 * per-frame input readiness so the UI can explain both states truthfully.
 */
public class NvidiaBackend implements FrameGenerationBackend {

    @Override
    public GpuVendor vendor() {
        return GpuVendor.NVIDIA;
    }

    @Override
    public boolean isSupported() {
        try {
            return StreamlineBridge.nGetBackendSupportedCapabilities(
                    GpuVendor.NVIDIA.vendorId()) != 0;
        } catch (UnsatisfiedLinkError e) {
            return false;
        }
    }

    @Override
    public boolean isFrameGenerationActive() {
        if (!FrameGenConfig.getInstance().dlssFgEnabled) {
            return false;
        }
        try {
            return StreamlineBridge.nIsActive();
        } catch (UnsatisfiedLinkError error) {
            return false;
        }
    }

    @Override
    public void applyConfig(FrameGenConfig config) {
        if (!isSupported()) return;

        try {
            // Single place for NVIDIA mode changes. VulkanRegistry calls applyConfig, no direct nSet* elsewhere.
            // This respects session-only fallback through one path.
            int supported = StreamlineBridge.nGetBackendSupportedCapabilities(GpuVendor.NVIDIA.vendorId());
            org.malionaro.dlss_frame_generation.client.backend.ProviderLifecycle.State state =
                    org.malionaro.dlss_frame_generation.client.FrameGenMod.compatibilityManager() != null
                            ? org.malionaro.dlss_frame_generation.client.FrameGenMod.compatibilityManager().providerSnapshot().state()
                            : org.malionaro.dlss_frame_generation.client.backend.ProviderLifecycle.State.UNAVAILABLE;
            org.malionaro.dlss_frame_generation.client.render.RuntimeFallbackPolicy.Decision fallback =
                    org.malionaro.dlss_frame_generation.client.render.RuntimeFallbackPolicy.resolve(
                            GpuVendor.NVIDIA, config.autoFallbackChainEnabled, config.dlssFgEnabled,
                            config.dlssUpscalingMode, supported, state);
            int effectiveMode = fallback.upscalerMode();
            StreamlineBridge.nSetDlssMode(effectiveMode, 0.0f);
            boolean fgEnabled = fallback.frameGenerationEnabled() && config.dlssFgEnabled;
            StreamlineBridge.nSetFgMode(fgEnabled, config.frameMultiplier, config.uiRecomposition);
            StreamlineBridge.nSetReflexMode(config.reflexMode, config.targetFps);
        } catch (UnsatisfiedLinkError e) {
            // Native bridge call failed fallback
        }
    }

    @Override
    public void disableForSwapchainReconfiguration() {
        if (!isSupported()) return;
        try {
            // Pause still goes through the backend, not a direct call from VulkanRegistry.
            StreamlineBridge.nSetFgMode(false, FrameGenConfig.getInstance().frameMultiplier, FrameGenConfig.getInstance().uiRecomposition);
        } catch (UnsatisfiedLinkError ignored) {
        }
    }

    @Override
    public void restoreAfterSwapchainRecreation(FrameGenConfig config) {
        if (!isSupported()) return;
        try {
            // Restore after swapchain recreate. Streamline needs the flag set again.
            StreamlineBridge.nSetFgMode(config.dlssFgEnabled, config.frameMultiplier, config.uiRecomposition);
        } catch (UnsatisfiedLinkError ignored) {
        }
    }

    @Override
    public void disableForPause(FrameGenConfig config) {
        try {
            // Keep pause handling in the backend.
            StreamlineBridge.nSetFrameGenerationMode(GpuVendor.NVIDIA.vendorId(), false, config.frameMultiplier, false);
        } catch (UnsatisfiedLinkError ignored) {
        }
    }

    @Override
    public long estimatedVramUsageBytes() {
        if (!isSupported()) return 0;
        try {
            return StreamlineBridge.nGetDlssgVramUsage();
        } catch (UnsatisfiedLinkError e) {
            return 0;
        }
    }

    @Override
    public String statusDescription() {
        try {
            return StreamlineBridge.nGetBackendStatus(GpuVendor.NVIDIA.vendorId());
        } catch (UnsatisfiedLinkError error) {
            if (isSupported()) {
                return "Active Backend: NVIDIA DLSS Frame Generation";
            }
            return "NVIDIA backend unavailable: native bridge mismatch";
        }
    }
}
