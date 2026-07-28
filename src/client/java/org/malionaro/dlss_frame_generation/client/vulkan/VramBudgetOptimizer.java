package org.malionaro.dlss_frame_generation.client.vulkan;

import org.malionaro.dlss_frame_generation.client.StreamlineBridge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class VramBudgetOptimizer {
    private static final Logger LOGGER = LoggerFactory.getLogger("VRAM_Optimizer");
    private static long lastFlushTime = System.currentTimeMillis();

    public static void checkVramPressure() {
        try {
            long vramBytes = StreamlineBridge.nGetDlssgVramUsage();
            long maxVramThreshold = 6L * 1024L * 1024L * 1024L; // 6 GB threshold

            if (vramBytes > maxVramThreshold && (System.currentTimeMillis() - lastFlushTime > 10_000)) {
                LOGGER.info("VRAM pressure detected ({} MB allocated). Flushing idle texture buffers...", vramBytes / (1024 * 1024));
                System.gc();
                lastFlushTime = System.currentTimeMillis();
            }
        } catch (UnsatisfiedLinkError e) {
            // Ignore
        }
    }
}
