package org.malionaro.dlss_frame_generation.client.ui;

import net.minecraft.client.Minecraft;
import org.malionaro.dlss_frame_generation.client.FrameGenMod;
import org.malionaro.dlss_frame_generation.client.StreamlineBridge;
import org.malionaro.dlss_frame_generation.client.backend.FrameGenerationBackend;
import org.malionaro.dlss_frame_generation.client.compat.CompatibilityManager;
import org.malionaro.dlss_frame_generation.client.config.FrameGenConfig;

/**
 * One frame-rate snapshot shared by the OSD and the F3 debug screen, so both
 * report the same numbers.
 *
 * <p>{@code baseFps} is what Minecraft's own render loop counts. Generated
 * frames are inserted by the provider at present and never pass through that
 * loop, so the output rate is an estimate of base times multiplier, not a
 * measured presentation rate.</p>
 */
public record FrameGenFpsStats(
        int baseFps,
        boolean active,
        int multiplier,
        FrameGenerationBackend.GpuVendor vendor,
        String inactiveReason
) {
    public int outputFps() {
        return effectiveFps(baseFps, active, multiplier);
    }

    public String technologyName() {
        return switch (vendor) {
            case NVIDIA -> "DLSS-G";
            case AMD -> "FSR 3.1 FG";
            case INTEL -> "FSR 3.1 FG (fallback)";
            case UNKNOWN -> "-";
        };
    }

    public static int effectiveFps(int baseFps, boolean active, int multiplier) {
        if (!active || multiplier <= 1 || baseFps <= 0) {
            return Math.max(0, baseFps);
        }
        return baseFps * multiplier;
    }

    public static FrameGenFpsStats capture() {
        Minecraft minecraft = Minecraft.getInstance();
        int baseFps = minecraft != null ? minecraft.getFps() : 0;

        CompatibilityManager compatibility = FrameGenMod.compatibilityManager();
        FrameGenerationBackend backend = compatibility != null
                ? compatibility.activeVendorBackend()
                : null;
        if (backend == null) {
            return new FrameGenFpsStats(
                    baseFps, false, 1,
                    FrameGenerationBackend.GpuVendor.UNKNOWN, "");
        }

        boolean active;
        try {
            active = backend.isFrameGenerationActive();
        } catch (UnsatisfiedLinkError ignored) {
            active = false;
        }
        int multiplier = active
                ? configuredMultiplier(FrameGenConfig.getInstance(), backend.vendor())
                : 1;
        String reason = "";
        if (!active) {
            try {
                reason = backend.statusDescription();
            } catch (UnsatisfiedLinkError ignored) {
                reason = "";
            }
        }
        return new FrameGenFpsStats(
                baseFps, active, multiplier, backend.vendor(),
                reason == null ? "" : reason);
    }

    private static int configuredMultiplier(
            FrameGenConfig config,
            FrameGenerationBackend.GpuVendor vendor
    ) {
        return switch (vendor) {
            case NVIDIA -> Math.min(
                    config.frameMultiplier,
                    maximumNativeMultiplier(FrameGenerationBackend.GpuVendor.NVIDIA)
            );
            case AMD -> config.amdFsrMultiplier;
            case INTEL -> 2;
            case UNKNOWN -> 1;
        };
    }

    private static int maximumNativeMultiplier(
            FrameGenerationBackend.GpuVendor vendor
    ) {
        try {
            return Math.max(1, StreamlineBridge.nGetMaxFrameMultiplier(vendor.vendorId()));
        } catch (UnsatisfiedLinkError ignored) {
            return 1;
        }
    }
}
