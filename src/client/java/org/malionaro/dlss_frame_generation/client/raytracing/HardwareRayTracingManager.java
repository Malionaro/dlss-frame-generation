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
    private VulkanDevice device;
    private boolean successfulFrameLogged;
    private volatile String runtimeStatus = "RT renderer is disabled";
    /**
     * Record the RT pass into its own transient command buffer that the
     * encoder executes in order (the way AetherRT does), instead of
     * Minecraft's current one. Switched on automatically when the shader
     * never starts in the current buffer, or forced by the setting.
     */
    private static volatile boolean ownCommandBuffer;
    private boolean ownCommandBufferChecked;

    public static boolean useOwnCommandBuffer() {
        return ownCommandBuffer || FrameGenConfig.getInstance().rayTracingOwnCommandBuffer;
    }

    /** System time of the last frame the RT pass recorded. */
    private static volatile long lastRecordedFrameNanos;

    public HardwareRayTracingManager(Logger logger) {
        this.logger = logger;
    }

    public void onDeviceRegistered(VulkanDevice device) {
        this.device = device;
        RtShaderPacks.ensureFolder(logger);
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

        if (org.malionaro.dlss_frame_generation.client.render.VulkanRegistry.getActiveEncoder()
                instanceof org.malionaro.dlss_frame_generation.client.mixin.VulkanCommandEncoderAccessor encoder) {
            currentRenderer.noteRenderPassOpen(encoder.dlss_fg$getCurrentRenderPass() != null);
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
        if (recorded) {
            lastRecordedFrameNanos = System.nanoTime();
            // 90 recorded frames are at least 87 finished ones (3 slots in
            // flight): if not one workgroup started, the dispatch is dropped.
            if (!ownCommandBufferChecked && currentRenderer.recordedFrames() >= 90) {
                ownCommandBufferChecked = true;
                if (currentRenderer.startedWorkgroups() == 0 && !useOwnCommandBuffer()) {
                    ownCommandBuffer = true;
                    logger.warn("RT shader never started in Minecraft's command buffer after {} frames; "
                            + "switching the RT pass to its own command buffer", currentRenderer.recordedFrames());
                } else if (currentRenderer.startedWorkgroups() == 0) {
                    logger.error("RT shader never started after {} frames, even in its own command buffer",
                            currentRenderer.recordedFrames());
                } else {
                    logger.info("RT self-check after {} frames: {} workgroups started ({})",
                            currentRenderer.recordedFrames(), currentRenderer.startedWorkgroups(),
                            useOwnCommandBuffer() ? "own command buffer" : "Minecraft's command buffer");
                }
            }
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

    /**
     * Rebuilds the renderer so a newly selected RT shader pack is compiled.
     * Render thread only; close() waits for the device to be idle.
     */
    public void reloadShaderPack() {
        if (device == null || !capabilities.backendReady()) {
            return;
        }
        if (renderer != null) {
            renderer.close();
        }
        renderer = new VulkanRayQueryRenderer(device, logger);
        successfulFrameLogged = false;
        applyConfiguration(FrameGenConfig.getInstance());
    }

    /**
     * RT engine primary visibility: vanilla may skip the raster draw of the
     * section at {@code origin}. Only while the RT pass is running (traced
     * within the last 200 ms), with the option on and no shader pack, for
     * sections resident in the RT scene and entirely inside 70 % of the RT
     * range (beyond that the RT image fades into the raster, which must exist).
     * Render thread.
     */
    public static boolean replacesRasterSection(net.minecraft.core.BlockPos origin) {
        HardwareRayTracingManager manager =
                org.malionaro.dlss_frame_generation.client.FrameGenMod.rayTracingManager();
        if (manager == null || !manager.active || manager.renderer == null
                || System.nanoTime() - lastRecordedFrameNanos > 200_000_000L) {
            return false;
        }
        FrameGenConfig config = FrameGenConfig.getInstance();
        if (!config.rayTracingReplaceTerrain
                || org.malionaro.dlss_frame_generation.client.compat.ForeignShaderPipeline.active()) {
            return false;
        }
        net.minecraft.world.phys.Vec3 camera =
                RayTracingFrameData.cameraPosition(net.minecraft.client.Minecraft.getInstance());
        if (camera == null) {
            return false;
        }
        // Coarse far LOD sections leave out plants, so the raster keeps them.
        double reach = Math.min(config.rayTracingMaxDistance * 0.7,
                org.malionaro.dlss_frame_generation.client.raytracing.scene.SectionScene.DETAIL_RANGE);
        // Farthest corner of the 16^3 section.
        double dx = Math.max(Math.abs(origin.getX() - camera.x), Math.abs(origin.getX() + 16 - camera.x));
        double dy = Math.max(Math.abs(origin.getY() - camera.y), Math.abs(origin.getY() + 16 - camera.y));
        double dz = Math.max(Math.abs(origin.getZ() - camera.z), Math.abs(origin.getZ() + 16 - camera.z));
        if (dx * dx + dy * dy + dz * dz > reach * reach) {
            return false;
        }
        long key = net.minecraft.core.SectionPos.asLong(
                origin.getX() >> 4, origin.getY() >> 4, origin.getZ() >> 4);
        // Around particles the raster terrain stays: the RT pass keeps the
        // textured raster particle there, and it needs the terrain behind it.
        return !ParticleGeometry.sectionHasParticles(key) && manager.renderer.hasSection(key);
    }

    /**
     * The RT pass draws the clouds itself: it ran within the last half second
     * in the overworld with clouds enabled. If it stops, vanilla clouds return.
     */
    public static boolean drawsOwnClouds() {
        HardwareRayTracingManager manager =
                org.malionaro.dlss_frame_generation.client.FrameGenMod.rayTracingManager();
        net.minecraft.client.Minecraft minecraft = net.minecraft.client.Minecraft.getInstance();
        return manager != null && manager.active
                && System.nanoTime() - lastRecordedFrameNanos < 500_000_000L
                && minecraft != null && minecraft.level != null
                && minecraft.level.dimension() == net.minecraft.world.level.Level.OVERWORLD
                && minecraft.options.getCloudStatus() != net.minecraft.client.CloudStatus.OFF
                && !org.malionaro.dlss_frame_generation.client.compat.ForeignShaderPipeline.active();
    }

    public String runtimeStatus() {
        return runtimeStatus;
    }
}
