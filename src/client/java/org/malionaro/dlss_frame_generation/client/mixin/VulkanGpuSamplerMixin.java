package org.malionaro.dlss_frame_generation.client.mixin;

import com.mojang.renderpearl.backend.vulkan.VulkanGpuSampler;
import org.malionaro.dlss_frame_generation.client.render.TextureLodBias;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** Applies {@link TextureLodBias#pending()}; 0 (vanilla) for every sampler but terrain. */
@Mixin(VulkanGpuSampler.class)
public class VulkanGpuSamplerMixin {
    @ModifyArg(
        method = "<init>",
        at = @At(
            value = "INVOKE",
            target = "Lorg/lwjgl/vulkan/VkSamplerCreateInfo;mipLodBias(F)Lorg/lwjgl/vulkan/VkSamplerCreateInfo;"
        )
    )
    private float dlss_fg$terrainMipBias(float bias) {
        return bias + TextureLodBias.pending();
    }
}
