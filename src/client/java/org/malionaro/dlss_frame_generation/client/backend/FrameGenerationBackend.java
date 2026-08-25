package org.malionaro.dlss_frame_generation.client.backend;
import org.malionaro.dlss_frame_generation.client.config.FrameGenConfig;

/**
 * Common capability-gated interface for vendor frame-generation and upscaling
 * modules. A listed vendor is not considered supported until its native module
 * and renderer integration are both ready.
 */
public interface FrameGenerationBackend {

    enum GpuVendor {
        NVIDIA(0x10DE, "NVIDIA GeForce / RTX"),
        AMD(0x1002, "AMD Radeon / FidelityFX"),
        INTEL(0x8086, "Intel Arc / Iris Xe"),
        UNKNOWN(0x0, "Generic / Unknown GPU");

        private final int vendorId;
        private final String displayName;

        GpuVendor(int vendorId, String displayName) {
            this.vendorId = vendorId;
            this.displayName = displayName;
        }

        public int vendorId() {
            return vendorId;
        }

        public String displayName() {
            return displayName;
        }

        public static GpuVendor fromVendorId(int vendorId) {
            for (GpuVendor vendor : values()) {
                if (vendor.vendorId == vendorId) {
                    return vendor;
                }
            }
            return UNKNOWN;
        }
    }

    GpuVendor vendor();

    boolean isSupported();

    boolean isFrameGenerationActive();

    /**
     * D1: sole nSet* entry point. All StreamlineBridge.nSet* calls for this backend
     * must funnel through applyConfig internally – VulkanRegistry and UI panels
     * must not call nSetDlssMode/nSetUpscalingMode/nSetFgMode directly.
     */
    void applyConfig(FrameGenConfig config);

    /**
     * D1 lifecycle helper – disables frame generation before swapchain reconfiguration.
     * This is a lifecycle-driven native toggle, not a persistent config change; it
     * still funnels through the backend so VulkanRegistry has no direct nSet* path.
     */
    default void disableForSwapchainReconfiguration() {
    }

    /**
     * D1 lifecycle helper – restores configured FG mode after swapchain recreation.
     * Default delegates to applyConfig; vendor backends may override for precise native handling.
     */
    default void restoreAfterSwapchainRecreation(FrameGenConfig config) {
        applyConfig(config);
    }

    /**
     * D1 lifecycle helper – temporarily disables FG when game is paused.
     * Must funnel through backend, not direct VulkanRegistry nSetFrameGenerationMode.
     */
    default void disableForPause(FrameGenConfig config) {
    }

    long estimatedVramUsageBytes();

    String statusDescription();
}
