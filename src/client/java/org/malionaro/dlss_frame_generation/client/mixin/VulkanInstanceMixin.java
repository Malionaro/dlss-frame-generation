package org.malionaro.dlss_frame_generation.client.mixin;

import com.mojang.renderpearl.backend.vulkan.VulkanInstance;
import org.malionaro.dlss_frame_generation.client.interop.StreamlineVulkanRequirements;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

import java.util.Set;

/**
 * Adds Streamline's plugin-reported instance extensions before vkCreateInstance.
 */
@Mixin(value = VulkanInstance.class, remap = false)
public class VulkanInstanceMixin {
    @ModifyArgs(
            method = "<init>",
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/lwjgl/vulkan/VkApplicationInfo;apiVersion(I)Lorg/lwjgl/vulkan/VkApplicationInfo;"
            )
    )
    private void dlss_fg$requestStreamlineVulkanVersion(Args args) {
        int minecraftVersion = args.get(0);
        args.set(
                0,
                StreamlineVulkanRequirements.preferredInstanceApiVersion(
                        minecraftVersion
                )
        );
    }

    @ModifyArgs(
            method = "<init>",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/renderpearl/backend/vulkan/VulkanDebug;create(IZLjava/util/Set;Ljava/util/Set;)Lcom/mojang/renderpearl/backend/vulkan/VulkanDebug;"
            )
    )
    private void dlss_fg$enableStreamlineInstanceExtensions(Args args) {
        Set<String> availableExtensions = args.get(2);
        Set<String> enabledExtensions = args.get(3);
        StreamlineVulkanRequirements.addInstanceExtensions(
                availableExtensions,
                enabledExtensions
        );
    }
}
