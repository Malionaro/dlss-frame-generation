package org.malionaro.dlss_frame_generation.client.mixin;

import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import org.malionaro.dlss_frame_generation.client.VulkanRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = VulkanCommandEncoder.class, remap = false)
public class VulkanCommandEncoderMixin {
    @Inject(method = "<init>", at = @At("RETURN"))
    private void onInit(VulkanDevice device, CallbackInfo ci) {
        VulkanRegistry.registerEncoder((VulkanCommandEncoder) (Object) this);
    }
}
