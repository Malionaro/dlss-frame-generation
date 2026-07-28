package org.malionaro.dlss_frame_generation.client.mixin;

import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import com.mojang.blaze3d.vulkan.VulkanGpuSurface;
import org.malionaro.dlss_frame_generation.client.FrameGenerationCompatibilityLayer;
import org.malionaro.dlss_frame_generation.client.FrameGenMod;
import org.malionaro.dlss_frame_generation.client.D3D12PresentationBridge;
import org.malionaro.dlss_frame_generation.client.StreamlineBridge;
import org.malionaro.dlss_frame_generation.client.VulkanRegistry;
import org.lwjgl.glfw.GLFWNativeWin32;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.Platform;
import org.lwjgl.vulkan.KHRSwapchain;
import org.lwjgl.vulkan.KHRSynchronization2;
import org.lwjgl.vulkan.VkAllocationCallbacks;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkPresentInfoKHR;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkSwapchainCreateInfoKHR;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.IntBuffer;
import java.nio.LongBuffer;

@Mixin(value = VulkanGpuSurface.class, remap = false)
public class VulkanGpuSurfaceMixin {
    @Unique
    private long dlss_fg$windowHandle;

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
        if (D3D12PresentationBridge.canCreateProxySwapchain()) {
            long nativeWindow = Platform.get() == Platform.WINDOWS
                    ? GLFWNativeWin32.glfwGetWin32Window(
                            dlss_fg$windowHandle)
                    : 0L;
            int result = D3D12PresentationBridge.createSwapchain(
                    nativeWindow,
                    createInfo.address(),
                    MemoryUtil.memAddress(swapchain)
            );
            if (result != StreamlineBridge.NATIVE_NOT_HANDLED) {
                return result;
            }
        }
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
        if (D3D12PresentationBridge.ownsSwapchain(swapchain)) {
            D3D12PresentationBridge.destroySwapchain(swapchain);
            return;
        }
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
        if (D3D12PresentationBridge.ownsSwapchain(swapchain)) {
            return D3D12PresentationBridge.getSwapchainImages(
                    swapchain,
                    MemoryUtil.memAddress(count),
                    images == null ? 0L : MemoryUtil.memAddress(images)
            );
        }
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
        if (D3D12PresentationBridge.ownsSwapchain(swapchain)) {
            if (fence != 0L) {
                return org.lwjgl.vulkan.VK10.VK_ERROR_FEATURE_NOT_PRESENT;
            }
            return D3D12PresentationBridge.acquireNextImage(
                    swapchain,
                    semaphore,
                    MemoryUtil.memAddress(imageIndex)
            );
        }
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
        if (presentInfo.swapchainCount() == 1
                && D3D12PresentationBridge.ownsSwapchain(
                        presentInfo.pSwapchains().get(0))) {
            return D3D12PresentationBridge.queuePresent(
                    queue.address(), presentInfo.address());
        }
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
        this.dlss_fg$windowHandle = surfaceHandle;
        VulkanRegistry.registerSurface((VulkanGpuSurface) (Object) this);
    }

    @Redirect(
        method = "blitFromTexture",
        at = @At(
            value = "INVOKE",
            target = "Lorg/lwjgl/vulkan/KHRSynchronization2;vkCmdPipelineBarrier2KHR(Lorg/lwjgl/vulkan/VkCommandBuffer;Lorg/lwjgl/vulkan/VkDependencyInfo;)V"
        )
    )
    private void adjustHybridD3D12ImageBarrier(
            VkCommandBuffer commandBuffer,
            VkDependencyInfo dependencyInfo
    ) {
        var compatibility = FrameGenMod.compatibilityManager();
        if (compatibility != null
                && compatibility.adapter() != null) {
            D3D12PresentationBridge.adjustBlitBarrier(
                    dependencyInfo,
                    compatibility.adapter()
                            .getGraphicsQueueFamilyIndex()
            );
        }
        KHRSynchronization2.vkCmdPipelineBarrier2KHR(
                commandBuffer, dependencyInfo);
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

}
