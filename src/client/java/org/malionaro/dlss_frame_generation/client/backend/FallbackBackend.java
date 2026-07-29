package org.malionaro.dlss_frame_generation.client.backend;
import org.malionaro.dlss_frame_generation.client.config.FrameGenConfig;

/**
 * Fallback backend when no supported GPU upscaling or frame generation library is active.
 */
public class FallbackBackend implements FrameGenerationBackend {

    @Override
    public GpuVendor vendor() {
        return GpuVendor.UNKNOWN;
    }

    @Override
    public boolean isSupported() {
        return false;
    }

    @Override
    public boolean isFrameGenerationActive() {
        return false;
    }

    @Override
    public void applyConfig(FrameGenConfig config) {
        // Pass-through
    }

    @Override
    public long estimatedVramUsageBytes() {
        return 0;
    }

    @Override
    public String statusDescription() {
        return "Status: INACTIVE (No compatible RTX / Radeon / Intel Arc GPU or native library loaded)";
    }
}
