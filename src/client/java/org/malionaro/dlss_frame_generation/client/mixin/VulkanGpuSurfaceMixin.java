package org.malionaro.dlss_frame_generation.client.mixin;
import org.malionaro.dlss_frame_generation.client.compat.CompatibilityManager;

import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuSurface;
import org.malionaro.dlss_frame_generation.client.backend.FrameGenerationCompatibilityLayer;
import org.malionaro.dlss_frame_generation.client.FrameGenMod;
import org.malionaro.dlss_frame_generation.client.interop.D3D12PresentationBridge;
import org.malionaro.dlss_frame_generation.client.StreamlineBridge;
import org.malionaro.dlss_frame_generation.client.render.VulkanRegistry;
import org.lwjgl.sdl.SDLProperties;
import org.lwjgl.sdl.SDLVideo;
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

    @Unique
    private long dlss_fg$drainStartNanos = 0L;

    @Unique
    private static final long dlss_fg$DRAIN_TIMEOUT_NANOS = 2_000_000_000L;

    // Completion gating lives in VulkanRegistry's rotating
    // present-gate (SlotFenceGate) + the pipeline's confirmed-present serial.
    // The counter below is diagnostics only, not a sync primitive (the
    // never-written acquire-semaphore stub was removed).
    @Unique
    private long dlss_fg$presentSerialTimeline = 0L;
    @Unique
    private long dlss_fg$proxySwapchain = 0L;

    private static boolean useProviderProxy() {
        return FrameGenerationCompatibilityLayer.proxySwapchainAvailable();
    }

    /**
     * Resolves the native Win32 HWND for the given SDL window.
     *
     * <p>Minecraft 26.3 replaced GLFW with SDL, so {@code Window#handle()} is now an
     * {@code SDL_Window*} and {@code GLFWNativeWin32.glfwGetWin32Window} is gone. SDL3
     * exposes the platform handle as a window property instead. Returns 0 on any
     * non-Windows host or if SDL has not been initialised, which keeps the caller on
     * the normal Vulkan present path.
     */
    @Unique
    private static long resolveWin32Window(long sdlWindow) {
        if (Platform.get() != Platform.WINDOWS || sdlWindow == 0L) {
            return 0L;
        }
        try {
            int properties = SDLVideo.SDL_GetWindowProperties(sdlWindow);
            if (properties == 0) {
                return 0L;
            }
            return SDLProperties.SDL_GetPointerProperty(
                    properties,
                    SDLVideo.SDL_PROP_WINDOW_WIN32_HWND_POINTER,
                    0L
            );
        } catch (Throwable ignored) {
            // SDL not initialised or property unsupported: stay on the normal path.
            return 0L;
        }
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
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            long sdlWindow = (mc != null && mc.getWindow() != null) ? mc.getWindow().handle() : 0L;
            long nativeWindow = resolveWin32Window(sdlWindow);
            int result = D3D12PresentationBridge.createSwapchain(
                    nativeWindow,
                    createInfo.address(),
                    MemoryUtil.memAddress(swapchain)
            );
            if (result != StreamlineBridge.NATIVE_NOT_HANDLED) {
                // Track D3D12 proxy creation separately; dlss_fg$proxySwapchain stays 0 for DXGI-owned handles
                if (result == 0) {
                    // Successful DXGI proxy swapchain – not a FidelityFX proxy, keep fidelity flag cleared
                    dlss_fg$proxySwapchain = 0L;
                }
                return result;
            }
        }
        if (useProviderProxy()) {
            int result = FrameGenerationCompatibilityLayer.createProxySwapchain(
                createInfo.address(),
                allocator == null ? 0L : allocator.address(),
                MemoryUtil.memAddress(swapchain)
            );
            if (result != StreamlineBridge.NATIVE_NOT_HANDLED) {
                // Track ownership for destroy – remember handle only when proxy actually created it
                if (result == 0) {
                    try {
                        dlss_fg$proxySwapchain = swapchain.get(0);
                    } catch (Throwable ignored) {
                        dlss_fg$proxySwapchain = 0L;
                    }
                } else {
                    dlss_fg$proxySwapchain = 0L;
                }
                return result;
            }
        }
        dlss_fg$proxySwapchain = 0L;
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
        // Destroy ownership-check – only route through proxy if this handle was created via createProxySwapchain
        // Use per-handle tracking (dlss_fg$proxySwapchain) and fallback to swapchainGeneration/proxy flag heuristic
        boolean isProxyOwned = false;
        if (useProviderProxy()) {
            if (dlss_fg$proxySwapchain != 0L && dlss_fg$proxySwapchain == swapchain) {
                isProxyOwned = true;
            } else if (dlss_fg$proxySwapchain == 0L && VulkanRegistry.getSwapchainGeneration() != 0
                    && FrameGenerationCompatibilityLayer.proxySwapchainAvailable()) {
                // Fallback heuristic when handle tracking was reset (e.g. after reload) but generation indicates proxy lifetime
                isProxyOwned = true;
            }
        }
        if (isProxyOwned) {
            try {
                FrameGenerationCompatibilityLayer.destroyProxySwapchain(
                    device.address(),
                    swapchain,
                    allocator == null ? 0L : allocator.address()
                );
                dlss_fg$proxySwapchain = 0L;
                return;
            } catch (Throwable t) {
                org.slf4j.LoggerFactory.getLogger("DLSS_FG_Mixin").debug("M4 proxy destroy failed, falling back to vanilla", t);
            }
        }
        // Not a proxy-owned swapchain – call vanilla destroy directly to avoid mismatched free
        dlss_fg$proxySwapchain = 0L;
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
            com.mojang.renderpearl.api.device.GpuSurface.Configuration configuration,
            CallbackInfo ci
    ) {
        dlss_fg$drainStartNanos = System.nanoTime();
        try {
            VulkanRegistry.notifySwapchainReconfigureStarting();
        } catch (Throwable t) {
            org.slf4j.LoggerFactory.getLogger("DLSS_FG_Mixin").debug("notifySwapchainReconfigureStarting failed", t);
        }
    }

    @Inject(method = "configure", at = @At("RETURN"))
    private void onConfigure(com.mojang.renderpearl.api.device.GpuSurface.Configuration configuration, CallbackInfo ci) {
        try {
            VulkanRegistry.notifySwapchainRecreated();
        } finally {
            try {
                var comp = FrameGenMod.compatibilityManager();
                if (comp != null && comp.providerSnapshot().state() == org.malionaro.dlss_frame_generation.client.backend.ProviderLifecycle.State.DRAINING) {
                    long elapsed = dlss_fg$drainStartNanos == 0L ? 0L : System.nanoTime() - dlss_fg$drainStartNanos;
                    if (elapsed > dlss_fg$DRAIN_TIMEOUT_NANOS) {
                        comp.failProviderTransient("Swapchain drain timeout after " + (elapsed / 1_000_000L) + "ms -> FAILED");
                        org.slf4j.LoggerFactory.getLogger("DLSS_FG_Mixin").warn("Drain timeout ({} ms) -> FAILED", elapsed / 1_000_000L);
                    } else {
                        long gen = VulkanRegistry.getSwapchainGeneration();
                        if (gen != 0L) {
                            comp.completeProviderSwapchainRebuild(gen);
                        } else {
                            long snapGen = comp.providerSnapshot().swapchainGeneration();
                            long target = snapGen == 0L ? 1L : snapGen + 1;
                            comp.completeProviderSwapchainRebuild(target);
                        }
                    }
                }
            } catch (Throwable t) {
                try {
                    var c2 = FrameGenMod.compatibilityManager();
                    if (c2 != null) c2.failProviderTransient("Drain finally resolution failed: " + t.getMessage());
                } catch (Throwable ignored) {}
                org.slf4j.LoggerFactory.getLogger("DLSS_FG_Mixin").debug("Drain finally handling failed", t);
            } finally {
                dlss_fg$drainStartNanos = 0L;
            }
        }
    }

    @Unique
    private void dlss_fg$handleConfigureFailure(Throwable cause) {
        try {
            var comp = FrameGenMod.compatibilityManager();
            if (comp != null && comp.providerSnapshot().state() == org.malionaro.dlss_frame_generation.client.backend.ProviderLifecycle.State.DRAINING) {
                long elapsed = dlss_fg$drainStartNanos == 0L ? 0L : System.nanoTime() - dlss_fg$drainStartNanos;
                if (elapsed > dlss_fg$DRAIN_TIMEOUT_NANOS) {
                    comp.failProviderTransient("Swapchain configure failed and drain timed out: " + (cause != null ? cause.getMessage() : "unknown"));
                } else {
                    long gen = VulkanRegistry.getSwapchainGeneration();
                    if (gen == 0L) gen = comp.providerSnapshot().swapchainGeneration() + 1;
                    if (gen == 0L) gen = 1L;
                    comp.completeProviderSwapchainRebuild(gen);
                }
            }
        } catch (Throwable t) {
            try {
                var c2 = FrameGenMod.compatibilityManager();
                if (c2 != null) c2.failProviderTransient("Configure failure drain resolution failed: " + t.getMessage());
            } catch (Throwable ignored) {}
        } finally {
            dlss_fg$drainStartNanos = 0L;
        }
    }

    @Inject(method = "present", at = @At("HEAD"))
    private void onPresent(CallbackInfo ci) {
        // Timeout->FAILED for stuck DRAINING (covers configure exception path where RETURN never runs)
        try {
            var comp = FrameGenMod.compatibilityManager();
            if (comp != null && comp.providerSnapshot().state() == org.malionaro.dlss_frame_generation.client.backend.ProviderLifecycle.State.DRAINING) {
                long elapsed = dlss_fg$drainStartNanos == 0L ? 0L : System.nanoTime() - dlss_fg$drainStartNanos;
                if (elapsed > dlss_fg$DRAIN_TIMEOUT_NANOS) {
                    comp.failProviderTransient("Swapchain drain timeout in present after " + (elapsed / 1_000_000L) + "ms -> FAILED");
                    org.slf4j.LoggerFactory.getLogger("DLSS_FG_Mixin").warn("Drain timeout in present ({} ms) -> FAILED", elapsed / 1_000_000L);
                    dlss_fg$drainStartNanos = 0L;
                }
            }
        } catch (Throwable ignored) {}
        // Diagnostic present counter. Completion gating is owned
        // by VulkanRegistry's present-gate + the pipeline serial (see above).
        try {
            dlss_fg$presentSerialTimeline++;
        } catch (Throwable ignored) {}
        try {
            StreamlineBridge.nReflexMarker(4); // PRESENT_START
        } catch (UnsatisfiedLinkError e) {
            // Ignore
        }
        VulkanRegistry.onPresent();
    }

    @Inject(method = "present", at = @At("RETURN"))
    private void onPresentEnd(CallbackInfo ci) {
        // Present completion gating. The registry's rotating
        // present-gate (drain-marker fence, 2 presents deep) is polled with a
        // bounded wait; under -Ddlss_fg.strictSync=true this is a full
        // vkDeviceWaitIdle instead. The serial itself advances in
        // VulkanFrameGenerationPipeline.onPresentCompleted only on confirmation.
        if (VulkanRegistry.isStrictSyncEnabled()) {
            try {
                VulkanRegistry.waitUntilGpuIdleBrief(2L);
            } catch (Throwable ignored) {
            }
        }
        VulkanRegistry.onPresentCompleted();
        try {
            StreamlineBridge.nReflexMarker(5); // PRESENT_END
        } catch (UnsatisfiedLinkError e) {
            // Ignore
        }
        // Ensure DRAINING is eventually resolved even if configure's RETURN was missed: final safety net
        try {
            var comp = FrameGenMod.compatibilityManager();
            if (comp != null && comp.providerSnapshot().state() == org.malionaro.dlss_frame_generation.client.backend.ProviderLifecycle.State.DRAINING) {
                long elapsed = dlss_fg$drainStartNanos == 0L ? 0L : System.nanoTime() - dlss_fg$drainStartNanos;
                if (elapsed > dlss_fg$DRAIN_TIMEOUT_NANOS) {
                    comp.failProviderTransient("Swapchain drain timeout in presentEnd after " + (elapsed / 1_000_000L) + "ms -> FAILED");
                    dlss_fg$drainStartNanos = 0L;
                }
            }
        } catch (Throwable ignored) {}
    }

}
