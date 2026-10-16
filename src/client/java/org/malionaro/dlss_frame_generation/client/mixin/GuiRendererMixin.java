package org.malionaro.dlss_frame_generation.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.renderer.GameRenderer;
import org.malionaro.dlss_frame_generation.client.render.UiLayerRedirect;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Draws the HUD into the DLSS-G UI layer by redirecting the single target
 * lookup in {@code GuiRenderer.draw}, instead of swapping the
 * {@code GameRenderer.mainRenderTarget} field. WrapOperation so other mods
 * wrapping the same call still chain.
 */
@Mixin(GuiRenderer.class)
public class GuiRendererMixin {
    @WrapOperation(
        method = "draw",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/GameRenderer;mainRenderTarget()Lcom/mojang/blaze3d/pipeline/RenderTarget;"
        )
    )
    private RenderTarget dlss_fg$drawIntoUiLayer(
            GameRenderer gameRenderer,
            Operation<RenderTarget> original
    ) {
        RenderTarget uiLayer = UiLayerRedirect.target();
        return uiLayer != null ? uiLayer : original.call(gameRenderer);
    }
}
