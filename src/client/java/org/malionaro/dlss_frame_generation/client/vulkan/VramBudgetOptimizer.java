package org.malionaro.dlss_frame_generation.client.vulkan;

import org.malionaro.dlss_frame_generation.client.StreamlineBridge;
import org.malionaro.dlss_frame_generation.client.render.DynamicResolutionController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class VramBudgetOptimizer {
    private static final Logger LOGGER = LoggerFactory.getLogger("dlss_fg.vram");
    private static final double BACKPRESSURE_THRESHOLD = 0.85;
    // Atomics — checkVramPressure runs on the render thread while OSD and
    // diagnostics may read the counters; torn RMW cycles are out.
    private static final java.util.concurrent.atomic.AtomicLong lastFlushTime =
            new java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis());
    private static final java.util.concurrent.atomic.AtomicInteger frameCounter =
            new java.util.concurrent.atomic.AtomicInteger();

    /**
     * Samples the real VRAM budget (VK_EXT_memory_budget) roughly every 200
     * frames. When usage exceeds {@value #BACKPRESSURE_THRESHOLD} of the
     * device-local budget, backpressure is applied via
     * {@link DynamicResolutionController#notifyVramPressure()}. If the native
     * budget query is unavailable, the legacy DLSS-G estimated-usage path is
     * used instead.
     *
     * @param vkPhysicalDevice raw VkPhysicalDevice handle of the active
     *                         adapter (0 skips the budget query)
     */
    public static void checkVramPressure(long vkPhysicalDevice) {
        // Phase0: sample VRAM only every ~200 frames to avoid JNI overhead on hot path
        // Overflow-safe: reset after 200 instead of modulo on ever-increasing int
        if (frameCounter.incrementAndGet() < 200) {
            return;
        }
        frameCounter.set(0);

        double pressure = queryBudgetPressure(vkPhysicalDevice);
        if (pressure >= 0.0) {
            if (pressure > BACKPRESSURE_THRESHOLD
                    && System.currentTimeMillis() - lastFlushTime.get() > 10_000) {
                LOGGER.warn(
                    "VRAM budget pressure {}% of device-local heap; applying backpressure",
                    Math.round(pressure * 100.0)
                );
                DynamicResolutionController.notifyVramPressure();
                lastFlushTime.set(System.currentTimeMillis());
            }
        } else {
            checkLegacyDlssgVramPressure();
        }
    }

    /**
     * Returns usage/budget ratio in [0, 1] for the largest device-local heap,
     * or -1 when the real budget is not available.
     */
    private static double queryBudgetPressure(long vkPhysicalDevice) {
        if (vkPhysicalDevice == 0L) {
            return -1.0;
        }
        long[] outBudgetBytes = new long[1];
        long[] outUsageBytes = new long[1];
        boolean available;
        try {
            available = StreamlineBridge.nQueryMemoryBudget(
                    vkPhysicalDevice, outBudgetBytes, outUsageBytes);
        } catch (UnsatisfiedLinkError e) {
            return -1.0;
        }
        if (!available || outBudgetBytes[0] <= 0L) {
            return -1.0;
        }
        return Math.max(0.0,
                (double) outUsageBytes[0] / (double) outBudgetBytes[0]);
    }

    private static void checkLegacyDlssgVramPressure() {
        try {
            long vramBytes = StreamlineBridge.nGetDlssgVramUsage();
            // Last-resort absolute threshold for drivers without
            // VK_EXT_memory_budget (no budget denominator exists there, so no
            // relative threshold is computable). Unlike before, this path now
            // actually notifies backpressure instead of only debug-logging it.
            long maxVramThreshold = 6L * 1024L * 1024L * 1024L; // 6 GB fallback

            if (vramBytes > maxVramThreshold && (System.currentTimeMillis() - lastFlushTime.get() > 10_000)) {
                LOGGER.warn("VRAM pressure detected ({} MB estimated DLSS-G usage over {} MB fallback threshold). Applying backpressure...",
                        vramBytes / (1024 * 1024), maxVramThreshold / (1024 * 1024));
                // System.gc() removed: explicit GC is a no-op for VRAM and stalls the render thread
                DynamicResolutionController.notifyVramPressure();
                lastFlushTime.set(System.currentTimeMillis());
            }
        } catch (UnsatisfiedLinkError e) {
            LOGGER.debug("Legacy VRAM usage query unavailable: {}", e.getMessage());
        }
    }
}
