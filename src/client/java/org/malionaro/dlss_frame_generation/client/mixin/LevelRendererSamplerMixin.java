package org.malionaro.dlss_frame_generation.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import java.util.OptionalDouble;
import net.minecraft.client.renderer.LevelRenderer;
import org.malionaro.dlss_frame_generation.client.render.TextureLodBias;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Gives the terrain sampler the upscaler's texture mip bias (see
 * {@link TextureLodBias}). Vanilla already rebuilds this sampler when it is
 * null, so a bias change only drops it and lets the main pass recreate it.
 */
@Mixin(LevelRenderer.class)
public class LevelRendererSamplerMixin {
    @Shadow private GpuSampler chunkLayerSampler;

    @Inject(method = "lambda$addMainPass$0", at = @At("HEAD"))
    private void dlss_fg$rebuildSamplerOnBiasChange(CallbackInfo ci) {
        if (chunkLayerSampler != null && TextureLodBias.needsRebuild()) {
            // close() queues the destroy behind in-flight frames.
            chunkLayerSampler.close();
            chunkLayerSampler = null;
        }
    }

    @WrapOperation(
        method = "lambda$addMainPass$0",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/renderpearl/api/device/GpuDevice;createSampler(Lcom/mojang/renderpearl/api/textures/AddressMode;Lcom/mojang/renderpearl/api/textures/AddressMode;Lcom/mojang/renderpearl/api/textures/FilterMode;Lcom/mojang/renderpearl/api/textures/FilterMode;ILjava/util/OptionalDouble;)Lcom/mojang/renderpearl/api/textures/GpuSampler;"
        )
    )
    private GpuSampler dlss_fg$biasedTerrainSampler(
            GpuDevice device,
            AddressMode addressModeU,
            AddressMode addressModeV,
            FilterMode minFilter,
            FilterMode magFilter,
            int maxAnisotropy,
            OptionalDouble maxLod,
            Operation<GpuSampler> original
    ) {
        TextureLodBias.beginTerrainSampler();
        try {
            return original.call(device, addressModeU, addressModeV,
                    minFilter, magFilter, maxAnisotropy, maxLod);
        } finally {
            TextureLodBias.endTerrainSampler();
        }
    }
}
