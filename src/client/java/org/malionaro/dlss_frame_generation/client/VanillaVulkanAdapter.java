package org.malionaro.dlss_frame_generation.client;

import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanGpuSurface;
import com.mojang.blaze3d.vulkan.VulkanGpuTextureView;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
import org.malionaro.dlss_frame_generation.client.access.VulkanDeviceAccess;
import org.malionaro.dlss_frame_generation.client.mixin.VulkanGpuSurfaceAccessor;

public final class VanillaVulkanAdapter implements RendererBackendAdapter {

    private VulkanDevice getDevice() {
        return VulkanRegistry.getActiveDevice();
    }

    private VulkanGpuSurface getSurface() {
        return VulkanRegistry.getActiveSurface();
    }

    @Override
    public long getVulkanInstance() {
        VulkanDevice device = getDevice();
        return device != null ? device.instance().vkInstance().address() : 0;
    }

    @Override
    public long getPhysicalDevice() {
        VulkanDevice device = getDevice();
        if (device instanceof VulkanDeviceAccess access) {
            if (access.dlss_fg$getPhysicalDevice() != null) {
                return access.dlss_fg$getPhysicalDevice().vkPhysicalDevice().address();
            }
        }
        return 0;
    }

    @Override
    public long getVulkanDevice() {
        VulkanDevice device = getDevice();
        return device != null ? device.vkDevice().address() : 0;
    }

    @Override
    public long getGraphicsQueue() {
        VulkanDevice device = getDevice();
        return device != null && device.graphicsQueue() != null ? device.graphicsQueue().vkQueue().address() : 0;
    }

    @Override
    public int getGraphicsQueueFamilyIndex() {
        VulkanDevice device = getDevice();
        return device != null && device.graphicsQueue() != null ? device.graphicsQueue().queueFamilyIndex() : -1;
    }

    @Override
    public long getSwapchain() {
        VulkanGpuSurface surface = getSurface();
        if (surface instanceof VulkanGpuSurfaceAccessor accessor) {
            return accessor.dlss_fg$getSwapchain();
        }
        return 0;
    }

    @Override
    public long getCurrentCommandBuffer() {
        com.mojang.blaze3d.vulkan.VulkanCommandEncoder encoder = VulkanRegistry.getActiveEncoder();
        if (encoder instanceof org.malionaro.dlss_frame_generation.client.mixin.VulkanCommandEncoderAccessor accessor) {
            org.lwjgl.vulkan.VkCommandBuffer buffer = accessor.dlss_fg$getCurrentCommandBuffer();
            if (buffer != null) {
                return buffer.address();
            }
        }
        return 0;
    }

    @Override
    public long getColorImage() {
        long registeredColor = VulkanRegistry.getActiveColorImage();
        if (registeredColor != 0) {
            return registeredColor;
        }

        Minecraft client = Minecraft.getInstance();
        if (client == null || client.gameRenderer == null) return 0;
        RenderTarget target = client.gameRenderer.mainRenderTarget();
        if (target == null) return 0;
        
        if (target.getColorTextureView() instanceof VulkanGpuTextureView vulkanView) {
            if (vulkanView.texture() != null) {
                return vulkanView.texture().vkImage();
            }
        }
        return 0;
    }

    @Override
    public long getDepthImage() {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.gameRenderer == null) return 0;
        RenderTarget target = client.gameRenderer.mainRenderTarget();
        if (target == null) return 0;
        
        if (target.getDepthTextureView() instanceof VulkanGpuTextureView vulkanView) {
            if (vulkanView.texture() != null) {
                return vulkanView.texture().vkImage();
            }
        }
        return 0;
    }

    @Override
    public int getRenderWidth() {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.gameRenderer == null) return 0;
        RenderTarget target = client.gameRenderer.mainRenderTarget();
        return target != null ? target.width : 0;
    }

    @Override
    public int getRenderHeight() {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.gameRenderer == null) return 0;
        RenderTarget target = client.gameRenderer.mainRenderTarget();
        return target != null ? target.height : 0;
    }

    @Override
    public int getCurrentFrameIndex() {
        VulkanGpuSurface surface = getSurface();
        if (surface instanceof VulkanGpuSurfaceAccessor accessor) {
            return accessor.dlss_fg$getCurrentImageIndex();
        }
        return 0;
    }

    @Override
    public boolean isFrameReadyForFG() {
        return getDevice() != null
            && getSurface() != null
            && getSwapchain() != 0
            && getColorImage() != 0
            && getDepthImage() != 0
            && getRenderWidth() > 0
            && getRenderHeight() > 0
            && getCurrentFrameIndex() >= 0;
    }

    @Override
    public long getAcquireSemaphore() {
        VulkanGpuSurface surface = getSurface();
        if (surface instanceof VulkanGpuSurfaceAccessor accessor) {
            long[] semaphores = accessor.dlss_fg$getAcquireSemaphores();
            int idx = accessor.dlss_fg$getCurrentAcquireSemaphore();
            if (semaphores != null && idx >= 0 && idx < semaphores.length) {
                return semaphores[idx];
            }
        }
        return 0;
    }

    @Override
    public long getPresentSemaphore() {
        VulkanGpuSurface surface = getSurface();
        if (surface instanceof VulkanGpuSurfaceAccessor accessor) {
            long[] semaphores = accessor.dlss_fg$getPresentSemaphores();
            int idx = accessor.dlss_fg$getCurrentImageIndex();
            if (semaphores != null && idx >= 0 && idx < semaphores.length) {
                return semaphores[idx];
            }
        }
        return 0;
    }
}
