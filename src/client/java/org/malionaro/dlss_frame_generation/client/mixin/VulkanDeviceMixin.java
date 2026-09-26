package org.malionaro.dlss_frame_generation.client.mixin;

import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanInstance;
import com.mojang.renderpearl.backend.vulkan.VulkanPhysicalDevice;
import com.mojang.renderpearl.backend.vulkan.checkpoints.CheckpointExtension;
import com.mojang.renderpearl.backend.vulkan.init.FeatureSet;
import org.lwjgl.vulkan.VkDevice;
import org.malionaro.dlss_frame_generation.client.interop.NativeLoader;
import org.malionaro.dlss_frame_generation.client.render.VulkanRegistry;
import org.malionaro.dlss_frame_generation.client.access.VulkanDeviceAccess;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.Set;

@Mixin(value = VulkanDevice.class, remap = false)
public class VulkanDeviceMixin implements VulkanDeviceAccess {
    @Unique
    private VulkanPhysicalDevice dlss_fg$physicalDevice;
    @Unique
    private Set<String> dlss_fg$enabledExtensions = Set.of();

    // 26.3 signature:
    //   VulkanDevice(VulkanInstance, VulkanPhysicalDevice, FeatureSet, VkDevice, long, CheckpointExtension)
    // ShaderSource is gone and the extension set moved into the FeatureSet record.
    @Inject(method = "<init>", at = @At("HEAD"))
    private static void onInitHead(VulkanInstance instance, VulkanPhysicalDevice physicalDevice,
                        FeatureSet enabledFeatures, VkDevice vkDevice, long vma, CheckpointExtension checkpointExtension, CallbackInfo ci) {
        // Load the Streamline interposer before VulkanDevice finishes. Streamline
        // needs to see the Vulkan calls, otherwise frame generation misses them.
        // This runs before super(), so there is no `this` yet. That is why Mixin
        // requires the handler to be static here.
        try {
            NativeLoader.earlyLoadInterposer(LoggerFactory.getLogger("DLSS_FG_EarlyInit"));
        } catch (Throwable t) {
            // Never crash Minecraft during early init
        }
    }

    @Inject(method = "<init>", at = @At("RETURN"))
    private void onInit(VulkanInstance instance, VulkanPhysicalDevice physicalDevice,
                        FeatureSet enabledFeatures, VkDevice vkDevice, long vma, CheckpointExtension checkpointExtension, CallbackInfo ci) {
        this.dlss_fg$physicalDevice = physicalDevice;
        this.dlss_fg$enabledExtensions = (enabledFeatures == null || enabledFeatures.extensions() == null)
                ? Set.of()
                : Set.copyOf(enabledFeatures.extensions());
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
