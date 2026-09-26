package org.malionaro.dlss_frame_generation.client.mixin;

import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import com.mojang.renderpearl.backend.vulkan.VulkanRenderPass;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = VulkanCommandEncoder.class, remap = false)
public interface VulkanCommandEncoderAccessor {
    // Verified unchanged in 26.3: the encoder still records outside a render pass
    // into `currentCommandBuffer`, exposed through the private commandBuffer()
    // helper. 26.3 only renamed textureInitCommandBuffer() to
    // objectInitCommandBuffer() and added a separate currentInitCommandBuffer.
    @Accessor("currentCommandBuffer")
    VkCommandBuffer dlss_fg$getCurrentCommandBuffer();

    @Accessor("currentRenderPass")
    VulkanRenderPass dlss_fg$getCurrentRenderPass();
}
