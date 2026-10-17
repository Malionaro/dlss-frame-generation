package org.malionaro.dlss_frame_generation.client.mixin;

import net.minecraft.client.renderer.CloudRenderer;
import org.malionaro.dlss_frame_generation.client.raytracing.HardwareRayTracingManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * While the RT pass draws its own volumetric clouds, vanilla's cloud layer
 * would show a second, flat set of clouds on top.
 */
@Mixin(CloudRenderer.class)
public class CloudRendererMixin {
    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void dlss_fg$skipForRtClouds(CallbackInfo ci) {
        if (HardwareRayTracingManager.drawsOwnClouds()) {
            ci.cancel();
        }
    }

    @Inject(method = "renderOit", at = @At("HEAD"), cancellable = true)
    private void dlss_fg$skipOitForRtClouds(CallbackInfo ci) {
        if (HardwareRayTracingManager.drawsOwnClouds()) {
            ci.cancel();
        }
    }
}
