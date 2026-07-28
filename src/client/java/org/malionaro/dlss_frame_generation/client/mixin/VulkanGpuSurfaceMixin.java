package org.malionaro.dlss_frame_generation.client.mixin;

import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import com.mojang.blaze3d.vulkan.VulkanGpuSurface;
import com.mojang.blaze3d.vulkan.VulkanGpuTextureView;
import org.malionaro.dlss_frame_generation.client.FrameGenerationCompatibilityLayer;
import org.malionaro.dlss_frame_generation.client.FrameGenMod;
import org.malionaro.dlss_frame_generation.client.StreamlineBridge;
import org.malionaro.dlss_frame_generation.client.VulkanRegistry;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.KHRSwapchain;
import org.lwjgl.vulkan.VkAllocationCallbacks;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPresentInfoKHR;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkSwapchainCreateInfoKHR;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.IntBuffer;
import java.nio.LongBuffer;

@Mixin(value = VulkanGpuSurface.class, remap = false)
public class VulkanGpuSurfaceMixin {
    private static boolean useProviderProxy() {
        return FrameGenerationCompatibilityLayer.proxySwapchainAvailable();
    }

    @Redirect(
        method = "configure",
        at = @At(
            value = "INVOKE",
            target = "Lorg/lwjgl/vulkan/KHRSwapchain;vkCreateSwapchainKHR(Lorg/lwjgl/vulkan/VkDevice;Lorg/lwjgl/vulkan/VkSwapchainCreateInfoKHR;Lorg/lwjgl/vulkan/VkAllocationCallbacks;Ljava/nio/LongBuffer;)I"
        )
    )
    private int createProviderProxySwapchain(
        VkDevice device,
        VkSwapchainCreateInfoKHR createInfo,
        VkAllocationCallbacks allocator,
        LongBuffer swapchain
    ) {
        if (useProviderProxy()) {
            int result = FrameGenerationCompatibilityLayer.createProxySwapchain(
                createInfo.address(),
                allocator == null ? 0L : allocator.address(),
                MemoryUtil.memAddress(swapchain)
            );
            if (result != StreamlineBridge.NATIVE_NOT_HANDLED) return result;
        }
        return KHRSwapchain.vkCreateSwapchainKHR(device, createInfo, allocator, swapchain);
    }

    @Redirect(
        method = "destroySwapchain",
        at = @At(
            value = "INVOKE",
            target = "Lorg/lwjgl/vulkan/KHRSwapchain;vkDestroySwapchainKHR(Lorg/lwjgl/vulkan/VkDevice;JLorg/lwjgl/vulkan/VkAllocationCallbacks;)V"
        )
    )
    private void destroyProviderProxySwapchain(
        VkDevice device,
        long swapchain,
        VkAllocationCallbacks allocator
    ) {
        if (useProviderProxy()) {
            FrameGenerationCompatibilityLayer.destroyProxySwapchain(
                device.address(),
                swapchain,
                allocator == null ? 0L : allocator.address()
            );
            return;
        }
        KHRSwapchain.vkDestroySwapchainKHR(device, swapchain, allocator);
    }

    @Redirect(
        method = "configure",
        at = @At(
            value = "INVOKE",
            target = "Lorg/lwjgl/vulkan/KHRSwapchain;vkGetSwapchainImagesKHR(Lorg/lwjgl/vulkan/VkDevice;JLjava/nio/IntBuffer;Ljava/nio/LongBuffer;)I"
        )
    )
    private int getProviderProxySwapchainImages(
        VkDevice device,
        long swapchain,
        IntBuffer count,
        LongBuffer images
    ) {
        if (useProviderProxy()) {
            int result = FrameGenerationCompatibilityLayer.getProxySwapchainImages(
                device.address(),
                swapchain,
                MemoryUtil.memAddress(count),
                images == null ? 0L : MemoryUtil.memAddress(images)
            );
            if (result != StreamlineBridge.NATIVE_NOT_HANDLED) return result;
        }
        return KHRSwapchain.vkGetSwapchainImagesKHR(device, swapchain, count, images);
    }

    @Redirect(
        method = "acquireNextTexture",
        at = @At(
            value = "INVOKE",
            target = "Lorg/lwjgl/vulkan/KHRSwapchain;vkAcquireNextImageKHR(Lorg/lwjgl/vulkan/VkDevice;JJJJLjava/nio/IntBuffer;)I"
        )
    )
    private int acquireProviderProxyImage(
        VkDevice device,
        long swapchain,
        long timeout,
        long semaphore,
        long fence,
        IntBuffer imageIndex
    ) {
        if (useProviderProxy()) {
            int result = FrameGenerationCompatibilityLayer.acquireProxySwapchainImage(
                device.address(),
                swapchain,
                timeout,
                semaphore,
                fence,
                MemoryUtil.memAddress(imageIndex)
            );
            if (result != StreamlineBridge.NATIVE_NOT_HANDLED) return result;
        }
        return KHRSwapchain.vkAcquireNextImageKHR(
            device, swapchain, timeout, semaphore, fence, imageIndex
        );
    }

    @Redirect(
        method = "present",
        at = @At(
            value = "INVOKE",
            target = "Lorg/lwjgl/vulkan/KHRSwapchain;vkQueuePresentKHR(Lorg/lwjgl/vulkan/VkQueue;Lorg/lwjgl/vulkan/VkPresentInfoKHR;)I"
        )
    )
    private int presentProviderProxyImage(VkQueue queue, VkPresentInfoKHR presentInfo) {
        if (useProviderProxy()) {
            int result = FrameGenerationCompatibilityLayer.queueProxyPresent(
                queue.address(), presentInfo.address()
            );
            if (result != StreamlineBridge.NATIVE_NOT_HANDLED) return result;
        }
        return KHRSwapchain.vkQueuePresentKHR(queue, presentInfo);
    }

    @Inject(method = "<init>", at = @At("RETURN"))
    private void onInit(VulkanDevice device, long surfaceHandle, CallbackInfo ci) {
        VulkanRegistry.registerSurface((VulkanGpuSurface) (Object) this);
    }

    @Inject(method = "configure", at = @At("HEAD"))
    private void beforeConfigure(
            com.mojang.blaze3d.systems.GpuSurface.Configuration configuration,
            CallbackInfo ci
    ) {
        VulkanRegistry.notifySwapchainReconfigureStarting();
    }

    @Inject(method = "configure", at = @At("RETURN"))
    private void onConfigure(com.mojang.blaze3d.systems.GpuSurface.Configuration configuration, CallbackInfo ci) {
        VulkanRegistry.notifySwapchainRecreated();
    }

    @Inject(method = "present", at = @At("HEAD"))
    private void onPresent(CallbackInfo ci) {
        try {
            StreamlineBridge.nReflexMarker(4); // PRESENT_START
        } catch (UnsatisfiedLinkError e) {
            // Ignore
        }
        VulkanRegistry.onPresent();
    }

    @Inject(method = "present", at = @At("RETURN"))
    private void onPresentEnd(CallbackInfo ci) {
        VulkanRegistry.onPresentCompleted();
        try {
            StreamlineBridge.nReflexMarker(5); // PRESENT_END
        } catch (UnsatisfiedLinkError e) {
            // Ignore
        }
    }

    @Inject(method = "blitFromTexture", at = @At("HEAD"))
    private void onBlit(com.mojang.blaze3d.systems.CommandEncoderBackend encoder, com.mojang.blaze3d.textures.GpuTextureView textureView, CallbackInfo ci) {
        if (textureView instanceof VulkanGpuTextureView vulkanView) {
            if (vulkanView.texture() != null) {
                VulkanRegistry.registerColorImage(vulkanView.texture().vkImage());
            }
        }
    }
}
