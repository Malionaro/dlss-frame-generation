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

    void applyConfig(FrameGenConfig config);

    long estimatedVramUsageBytes();

    String statusDescription();
}
