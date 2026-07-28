package org.malionaro.dlss_frame_generation.client.mixin;

import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanInstance;
import com.mojang.blaze3d.vulkan.VulkanPhysicalDevice;
import com.mojang.blaze3d.vulkan.checkpoints.CheckpointExtension;
import org.lwjgl.vulkan.VkDevice;
import org.malionaro.dlss_frame_generation.client.NativeLoader;
import org.malionaro.dlss_frame_generation.client.VulkanRegistry;
import org.malionaro.dlss_frame_generation.client.access.VulkanDeviceAccess;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.mojang.blaze3d.shaders.ShaderSource;
import java.util.Set;

@Mixin(value = VulkanDevice.class, remap = false)
public class VulkanDeviceMixin implements VulkanDeviceAccess {
    @Unique
    private VulkanPhysicalDevice dlss_fg$physicalDevice;
    @Unique
    private Set<String> dlss_fg$enabledExtensions = Set.of();

    @Inject(method = "<init>", at = @At("HEAD"))
    private static void onInitHead(ShaderSource shaderSource, VulkanInstance instance, VulkanPhysicalDevice physicalDevice,
                        Set<String> enabledExtensions, VkDevice vkDevice, long vma, CheckpointExtension checkpointExtension, CallbackInfo ci) {
        // CRITICAL: Load Streamline interposer BEFORE VulkanDevice is fully constructed.
        // This allows Streamline to intercept Vulkan API calls for frame generation.
        try {
            NativeLoader.earlyLoadInterposer(LoggerFactory.getLogger("DLSS_FG_EarlyInit"));
        } catch (Throwable t) {
            // Never crash Minecraft during early init
        }
    }

    @Inject(method = "<init>", at = @At("RETURN"))
    private void onInit(ShaderSource shaderSource, VulkanInstance instance, VulkanPhysicalDevice physicalDevice,
                        Set<String> enabledExtensions, VkDevice vkDevice, long vma, CheckpointExtension checkpointExtension, CallbackInfo ci) {
        this.dlss_fg$physicalDevice = physicalDevice;
        this.dlss_fg$enabledExtensions = Set.copyOf(enabledExtensions);
        VulkanRegistry.registerDevice((VulkanDevice) (Object) this);
    }

    @Override
    public VulkanPhysicalDevice dlss_fg$getPhysicalDevice() {
        return this.dlss_fg$physicalDevice;
    }

    @Override
    public Set<String> dlss_fg$getEnabledExtensions() {
        return this.dlss_fg$enabledExtensions;
    }
}
