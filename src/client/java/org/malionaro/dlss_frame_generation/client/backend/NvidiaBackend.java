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
            StreamlineBridge.nSetDlssMode(config.dlssUpscalingMode, 0.0f);
            StreamlineBridge.nSetFgMode(config.dlssFgEnabled, config.frameMultiplier, config.uiRecomposition);
            StreamlineBridge.nSetReflexMode(config.reflexMode, config.targetFps);
        } catch (UnsatisfiedLinkError e) {
            // Native bridge call failed fallback
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
