package org.malionaro.dlss_frame_generation.client.mixin;

import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import org.malionaro.dlss_frame_generation.client.motion.EntityPartCapture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Records model-part poses for per-pixel entity motion. Runs after
 * {@code setupAnim}, so the parts hold this frame's animation. Optional: when
 * another mod reshapes this method the mod keeps the box-translation motion.
 */
@Mixin(ModelFeatureRenderer.class)
public class ModelFeatureRendererMixin {
    @Inject(
            method = "prepareModel",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/model/Model;renderToBuffer(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;III)V"
            ),
            require = 0
    )
    private void dlss_fg$capturePartPoses(ModelFeatureRenderer.Submit<?> submit, CallbackInfo ci) {
        EntityPartCapture.capture(submit.state(), submit.model(), submit.pose());
    }
}
