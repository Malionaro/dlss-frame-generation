package org.malionaro.dlss_frame_generation.client.mixin;

import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import com.mojang.blaze3d.vulkan.VulkanRenderPass;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = VulkanCommandEncoder.class, remap = false)
public interface VulkanCommandEncoderAccessor {
    @Accessor("currentCommandBuffer")
    VkCommandBuffer dlss_fg$getCurrentCommandBuffer();

    @Accessor("currentRenderPass")
    VulkanRenderPass dlss_fg$getCurrentRenderPass();
}
