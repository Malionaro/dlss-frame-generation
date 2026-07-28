package org.malionaro.dlss_frame_generation.client;

import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanGpuSurface;
import com.mojang.blaze3d.vulkan.VulkanGpuTextureView;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
import org.malionaro.dlss_frame_generation.client.access.VulkanDeviceAccess;
import org.malionaro.dlss_frame_generation.client.mixin.VulkanCommandEncoderAccessor;
import org.malionaro.dlss_frame_generation.client.mixin.VulkanGpuSurfaceAccessor;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.util.Optional;

import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_GENERAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_UNDEFINED;

public final class VanillaVulkanAdapter implements RendererBackendAdapter {

    private VulkanDevice getDevice() {
        return VulkanRegistry.getActiveDevice();
    }

    private VulkanGpuSurface getSurface() {
        return VulkanRegistry.getActiveSurface();
    }

    @Override
    public Optional<FrameResourceSet> captureFrameResources(
            RenderTarget renderTarget,
            RenderTarget outputTarget,
            FrameIdentity identity
    ) {
        VulkanDevice device = getDevice();
        VulkanGpuSurface surface = getSurface();
        if (device == null
                || surface == null
                || renderTarget == null
                || outputTarget == null
                || identity == null
                || !(renderTarget.getColorTextureView()
                        instanceof VulkanGpuTextureView colorView)
                || !(renderTarget.getDepthTextureView()
                        instanceof VulkanGpuTextureView depthView)
                || !(outputTarget.getColorTextureView()
                        instanceof VulkanGpuTextureView outputView)
                || !(surface instanceof VulkanGpuSurfaceAccessor surfaceAccess)
                || !(VulkanRegistry.getActiveEncoder()
                        instanceof VulkanCommandEncoderAccessor encoderAccess)) {
            return Optional.empty();
        }

        VkCommandBuffer commandBuffer =
                encoderAccess.dlss_fg$getCurrentCommandBuffer();
        int imageIndex = surfaceAccess.dlss_fg$getCurrentImageIndex();
        if (commandBuffer == null
                || encoderAccess.dlss_fg$getCurrentRenderPass() != null
                || imageIndex < 0
                || imageIndex >= surfaceAccess.dlss_fg$getSwapchainImages().size()
                || identity.swapchainImageIndex() != imageIndex) {
            return Optional.empty();
        }

        int queueFamily = getGraphicsQueueFamilyIndex();
        FrameResourceSet.ImageResource color = textureResource(
                colorView, queueFamily, VK_IMAGE_LAYOUT_GENERAL);
        FrameResourceSet.ImageResource depth = textureResource(
                depthView, queueFamily, VK_IMAGE_LAYOUT_GENERAL);
        FrameResourceSet.ImageResource output = textureResource(
                outputView, queueFamily, VK_IMAGE_LAYOUT_GENERAL);
        FrameResourceSet.ImageResource backbuffer =
                new FrameResourceSet.ImageResource(
                        surfaceAccess.dlss_fg$getSwapchainImages()
                                .getLong(imageIndex),
                        0L,
                        surfaceAccess.dlss_fg$getSwapchainImageFormat(),
                        VK_IMAGE_LAYOUT_UNDEFINED,
                        queueFamily,
                        output.width(),
                        output.height()
                );
        return Optional.of(new FrameResourceSet(
                identity,
                FrameResourceSet.Source.VANILLA_VULKAN,
                commandBuffer.address(),
                color,
                depth,
                output,
                backbuffer,
                getAcquireSemaphore(),
                getPresentSemaphore(),
                true
        ));
    }

    private static FrameResourceSet.ImageResource textureResource(
            VulkanGpuTextureView view,
            int queueFamily,
            int layout
    ) {
        if (view.texture() == null) {
            return new FrameResourceSet.ImageResource(
                    0L, 0L, 0, VK_IMAGE_LAYOUT_UNDEFINED,
                    queueFamily, 0, 0
            );
        }
        return new FrameResourceSet.ImageResource(
                view.texture().vkImage(),
                view.vkImageView(),
                vulkanFormat(view),
                layout,
                queueFamily,
                view.getWidth(0),
                view.getHeight(0)
        );
    }

    private static int vulkanFormat(VulkanGpuTextureView view) {
        return switch (view.texture().getFormat()) {
            case RGBA8_UNORM -> org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_UNORM;
            case RGBA8_SNORM -> org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_SNORM;
            case RGBA16_FLOAT ->
                    org.lwjgl.vulkan.VK10.VK_FORMAT_R16G16B16A16_SFLOAT;
            case D32_FLOAT -> org.lwjgl.vulkan.VK10.VK_FORMAT_D32_SFLOAT;
            case D32_FLOAT_S8_UINT ->
                    org.lwjgl.vulkan.VK10.VK_FORMAT_D32_SFLOAT_S8_UINT;
            case D24_UNORM_S8_UINT ->
                    org.lwjgl.vulkan.VK10.VK_FORMAT_D24_UNORM_S8_UINT;
            case D16_UNORM -> org.lwjgl.vulkan.VK10.VK_FORMAT_D16_UNORM;
            default -> 0;
        };
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
        return -1;
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
