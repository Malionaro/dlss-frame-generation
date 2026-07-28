package org.malionaro.dlss_frame_generation.client;

import net.fabricmc.api.ClientModInitializer;
import org.malionaro.dlss_frame_generation.client.motion.MotionVectorDebugRenderer;
import org.malionaro.dlss_frame_generation.client.raytracing.HardwareRayTracingManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FrameGenMod implements ClientModInitializer {
    public static final String MOD_ID = "dlss_fg";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static CompatibilityManager compatibilityManager;
    private static MotionVectorDebugRenderer motionVectorDebugRenderer;
    private static HardwareRayTracingManager rayTracingManager;

    @Override
    public void onInitializeClient() {
        LOGGER.info("Initializing capability-gated Vulkan frame-generation integration");

        rayTracingManager = new HardwareRayTracingManager(LOGGER);
        compatibilityManager = CompatibilityManager.initialize(LOGGER);
        motionVectorDebugRenderer = new MotionVectorDebugRenderer();
        String startupDebugMode =
                System.getProperty("dlss_fg.motionDebug", "").trim();
        if (!startupDebugMode.isEmpty()) {
            try {
                MotionVectorDebugRenderer.DebugMode mode =
                        MotionVectorDebugRenderer.DebugMode.valueOf(
                                startupDebugMode.toUpperCase(
                                        java.util.Locale.ROOT));
                motionVectorDebugRenderer.setDebugMode(mode);
                LOGGER.info("Motion debug startup mode: {}", mode);
            } catch (IllegalArgumentException invalidMode) {
                LOGGER.warn(
                        "Ignoring invalid dlss_fg.motionDebug mode '{}'",
                        startupDebugMode
                );
            }
        }
    }

    public static CompatibilityManager compatibilityManager() {
        return compatibilityManager;
    }

    public static MotionVectorDebugRenderer motionVectorDebugRenderer() {
        return motionVectorDebugRenderer;
    }

    public static HardwareRayTracingManager rayTracingManager() {
        return rayTracingManager;
    }
}
