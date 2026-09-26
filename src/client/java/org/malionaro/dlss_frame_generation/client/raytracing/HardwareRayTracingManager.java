package org.malionaro.dlss_frame_generation.client.raytracing;

import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTextureView;
import net.minecraft.core.BlockPos;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.malionaro.dlss_frame_generation.client.config.FrameGenConfig;
import org.slf4j.Logger;

/**
 * Capability-gated lifecycle for the Vulkan hardware ray-query renderer.
 */
public final class HardwareRayTracingManager {
    private static final boolean RENDERER_BACKEND_IMPLEMENTED = true;

    private final Logger logger;
    private volatile RayTracingCapabilities capabilities = RayTracingCapabilities.waitingForDevice();
    private volatile boolean active;
    private VulkanRayQueryRenderer renderer;
    private boolean successfulFrameLogged;
    private volatile String runtimeStatus = "RT renderer is disabled";

    public HardwareRayTracingManager(Logger logger) {
        this.logger = logger;
    }

    public void onDeviceRegistered(VulkanDevice device) {
        capabilities = RayTracingCapabilities.detect(device, RENDERER_BACKEND_IMPLEMENTED);
        active = false;
        successfulFrameLogged = false;
        runtimeStatus = capabilities.statusMessage();
        if (renderer != null) {
            renderer.close();
            renderer = null;
        }
        if (capabilities.backendReady()) {
            renderer = new VulkanRayQueryRenderer(device, logger);
            applyConfiguration(FrameGenConfig.getInstance());
        }
        logger.info("Hardware ray tracing capability: {} ({})",
                capabilities.state(), capabilities.statusMessage());
    }

    public RayTracingCapabilities capabilities() {
        return capabilities;
    }

    public boolean applyConfiguration(FrameGenConfig config) {
        if (config == null || config.rayTracingMode == FrameGenConfig.RayTracingMode.OFF) {
            active = false;
            runtimeStatus = "RT renderer is disabled in settings";
            return true;
        }
        if (!capabilities.backendReady()) {
            active = false;
            runtimeStatus = capabilities.statusMessage();
            return false;
        }

        active = renderer != null;
        runtimeStatus = active
                ? "RT renderer enabled; waiting for the first rendered frame"
                : "RT renderer could not be created";
        logger.info(
                "Hardware RT configuration applied: mode={}, quality={}, distance={}, samples={}",
                config.rayTracingMode,
                config.rayTracingQuality,
                config.rayTracingMaxDistance,
                config.rayTracingRaysPerPixel
        );
        return true;
    }

    /**
     * Records the RT pass after the raster world and before DLSS/HUD work.
     * Keeping it on the active frame command buffer preserves ordering and
     * prevents the ray pass from shading over Minecraft's UI.
     */
    public RayReconstructionResources renderBeforeUpscale(
            VkCommandBuffer commandBuffer,
            VulkanGpuTextureView colorView,
            VulkanGpuTextureView depthView
    ) {
        VulkanRayQueryRenderer currentRenderer = renderer;
        if (!active || currentRenderer == null) {
            return null;
        }

        boolean recorded = currentRenderer.render(
                commandBuffer,
                colorView,
                depthView,
                FrameGenConfig.getInstance()
        );
        if (!recorded && FrameGenConfig.getInstance().rayTracingMode
                != FrameGenConfig.RayTracingMode.OFF) {
            if (currentRenderer.failed()) {
                active = false;
                runtimeStatus = "RT renderer failed: "
                        + currentRenderer.failureMessage();
            } else {
                runtimeStatus =
                        "RT renderer is waiting for a loaded world and camera";
            }
            // A missing world/camera is temporary and is not treated as a
            // permanent backend failure.
            return null;
        }
        if (recorded && !successfulFrameLogged) {
            successfulFrameLogged = true;
            runtimeStatus = "RT renderer active";
            logger.info("Hardware RT pass rendered its first frame successfully");
        }
        return recorded && FrameGenConfig.getInstance().dlssRayReconstructionEnabled
                ? currentRenderer.rayReconstructionResources()
                : null;
    }

    public void disable() {
        active = false;
    }

    public void onBlockChanged(BlockPos position) {
        VulkanRayQueryRenderer currentRenderer = renderer;
        if (currentRenderer != null) {
            currentRenderer.onBlockChanged(position);
        }
    }

    public boolean isActive() {
        return active;
    }

    public String runtimeStatus() {
        return runtimeStatus;
    }
}
