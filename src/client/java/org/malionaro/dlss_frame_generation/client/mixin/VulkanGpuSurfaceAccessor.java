package org.malionaro.dlss_frame_generation.client.mixin;

import com.mojang.blaze3d.vulkan.VulkanGpuSurface;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import it.unimi.dsi.fastutil.longs.LongList;

@Mixin(value = VulkanGpuSurface.class, remap = false)
public interface VulkanGpuSurfaceAccessor {
    @Accessor("swapchain")
    long dlss_fg$getSwapchain();

    @Accessor("currentImageIndex")
    int dlss_fg$getCurrentImageIndex();

    @Accessor("swapchainImages")
    LongList dlss_fg$getSwapchainImages();

    @Accessor("acquireSemaphores")
    long[] dlss_fg$getAcquireSemaphores();

    @Accessor("presentSemaphores")
    long[] dlss_fg$getPresentSemaphores();

    @Accessor("currentAcquireSemaphore")
    int dlss_fg$getCurrentAcquireSemaphore();

    @Accessor("swapchainImageFormat")
    int dlss_fg$getSwapchainImageFormat();
}
