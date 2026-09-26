package org.malionaro.dlss_frame_generation.client.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.MainTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.GameRenderState;
import org.joml.Vector4f;
import org.malionaro.dlss_frame_generation.client.render.CompositeFallbackPolicy;
import org.malionaro.dlss_frame_generation.client.render.NvidiaDlssRenderResolution;
import org.malionaro.dlss_frame_generation.client.StreamlineBridge;
import org.malionaro.dlss_frame_generation.client.render.VulkanRegistry;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public class GameRendererMixin {
    @Unique private static final Vector4f DLSS_FG_TRANSPARENT =
            new Vector4f(0.0f);
    @Shadow @Final @Mutable private RenderTarget mainRenderTarget;
    @Shadow @Final private GameRenderState gameRenderState;

    @Unique private RenderTarget dlss_fg$displayTarget;
    @Unique private MainTarget dlss_fg$worldTarget;
    @Unique private MainTarget dlss_fg$uiTarget;
    @Unique private RenderTarget dlss_fg$worldTargetForFrame;
    @Unique private boolean dlss_fg$separateUiForFrame;

    // 26.3 changed GameRenderer#render from (DeltaTracker, boolean) to (), so every
    // handler below takes only the CallbackInfo. The DeltaTracker that used to be
    // passed here is obtained from RenderSystem where a frame delta is needed.
    @Inject(method = "render", at = @At("HEAD"))
    private void onRenderStart(CallbackInfo ci) {
        if (dlss_fg$displayTarget != null) {
            // A previous frame always hands control back to Minecraft with the
            // display target active. Enforce that invariant before vanilla's
            // resize check as recovery from an interrupted frame.
            mainRenderTarget = dlss_fg$displayTarget;
        } else {
            dlss_fg$displayTarget = mainRenderTarget;
        }
        try {
            StreamlineBridge.nReflexMarker(2); // RENDER_SUBMIT_START
        } catch (UnsatisfiedLinkError e) {
            // Ignore
        }
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void onRenderEnd(CallbackInfo ci) {
        if (dlss_fg$displayTarget != null) {
            mainRenderTarget = dlss_fg$displayTarget;
        }
        try {
            StreamlineBridge.nReflexMarker(3); // RENDER_SUBMIT_END
        } catch (UnsatisfiedLinkError e) {
            // Ignore
        }
    }

    @Inject(
        method = "render",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/GameRenderer;renderLevel()V"
        )
    )
    private void prepareReducedResolutionWorld(CallbackInfo ci) {
        dlss_fg$displayTarget = mainRenderTarget;
        NvidiaDlssRenderResolution.Resolution resolution =
                VulkanRegistry.resolveWorldResolution(
                        dlss_fg$displayTarget.width,
                        dlss_fg$displayTarget.height
                );

        if (resolution.reduced()) {
            if (dlss_fg$worldTarget == null) {
                dlss_fg$worldTarget = new MainTarget(
                        resolution.renderWidth(),
                        resolution.renderHeight()
                );
            } else if (dlss_fg$worldTarget.width != resolution.renderWidth()
                    || dlss_fg$worldTarget.height != resolution.renderHeight()) {
                dlss_fg$worldTarget.resize(
                        resolution.renderWidth(),
                        resolution.renderHeight()
                );
            }
            dlss_fg$worldTargetForFrame = dlss_fg$worldTarget;
            RenderSystem.getDevice().createCommandEncoder()
                    .clearColorAndDepthTextures(
                            dlss_fg$worldTarget.getColorTexture(),
                            gameRenderState.guiRenderState.clearColorOverride,
                            dlss_fg$worldTarget.getDepthTexture(),
                            0.0
                    );
        } else {
            dlss_fg$worldTargetForFrame = dlss_fg$displayTarget;
        }

        mainRenderTarget = dlss_fg$worldTargetForFrame;
        VulkanRegistry.beginWorldFrame(gameRenderState);
    }

    @Unique
    private void dlss_fg$copyWorldToDisplay(RenderTarget src, RenderTarget dst) {
        if (src == null || dst == null || src == dst) return;
        if (src.getColorTextureView() == null || dst.getColorTextureView() == null) return;
        if (src.getDepthTextureView() == null || dst.getDepthTextureView() == null) return;
        try {
            // Vanilla outline blit handles both same-size copy and reduced-resolution upscale.
            src.blitAndBlendToTexture(dst.getColorTextureView(), dst.getDepthTextureView());
        } catch (Throwable t) {
            org.slf4j.LoggerFactory.getLogger("DLSS_FG_Mixin").debug("H4 fallback blit failed", t);
        }
    }

    @Inject(
        method = "render",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/renderpearl/api/commands/CommandEncoder;clearDepthTexture(Lcom/mojang/renderpearl/api/textures/GpuTexture;D)V"
        )
    )
    private void captureSceneBeforeHud(CallbackInfo ci) {
        RenderTarget worldTarget = dlss_fg$worldTargetForFrame != null
                ? dlss_fg$worldTargetForFrame
                : mainRenderTarget;
        RenderTarget displayTarget = dlss_fg$displayTarget != null
                ? dlss_fg$displayTarget
                : mainRenderTarget;
        dlss_fg$separateUiForFrame = VulkanRegistry.shouldSeparateUi();
        VulkanRegistry.captureFrameGenerationInputs(
                worldTarget,
                displayTarget,
                dlss_fg$separateUiForFrame
        );

        // H4: guaranteed composite fallback – if capture bailed (no CmdBuffer, slot busy, validation fail)
        // and we are rendering at reduced resolution, blit worldTarget -> displayTarget via Java so HUD never composites over black.
        boolean frameResourcesCaptured = VulkanRegistry.getCurrentFrameResources() != null;
        boolean worldTargetDiffersFromDisplay = worldTarget != displayTarget
                && worldTarget.getColorTexture() != null
                && displayTarget.getColorTexture() != null;
        if (CompositeFallbackPolicy.evaluate(frameResourcesCaptured, worldTargetDiffersFromDisplay)
                == CompositeFallbackPolicy.Decision.FALLBACK_BLIT_REQUIRED) {
            dlss_fg$copyWorldToDisplay(worldTarget, displayTarget);
        }

        if (dlss_fg$separateUiForFrame) {
            if (dlss_fg$uiTarget == null) {
                dlss_fg$uiTarget = new MainTarget(
                        displayTarget.width,
                        displayTarget.height
                );
            } else if (dlss_fg$uiTarget.width != displayTarget.width
                    || dlss_fg$uiTarget.height != displayTarget.height) {
                dlss_fg$uiTarget.resize(displayTarget.width, displayTarget.height);
            }
            RenderSystem.getDevice().createCommandEncoder()
                    .clearColorAndDepthTextures(
                            dlss_fg$uiTarget.getColorTexture(),
                            DLSS_FG_TRANSPARENT,
                            dlss_fg$uiTarget.getDepthTexture(),
                            0.0
                    );
            mainRenderTarget = dlss_fg$uiTarget;
        } else {
            mainRenderTarget = displayTarget;
        }
    }

    @Inject(
        method = "render",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/gui/render/GuiRenderer;endFrame()V",
            shift = At.Shift.AFTER
        )
    )
    private void compositeFullResolutionUi(CallbackInfo ci) {
        if (!dlss_fg$separateUiForFrame
                || dlss_fg$uiTarget == null
                || dlss_fg$displayTarget == null) {
            return;
        }

        VulkanRegistry.captureFrameGenerationUi(dlss_fg$uiTarget);
        mainRenderTarget = dlss_fg$displayTarget;
        dlss_fg$uiTarget.blitAndBlendToTexture(
                dlss_fg$displayTarget.getColorTextureView(),
                dlss_fg$displayTarget.getDepthTextureView()
        );
        dlss_fg$separateUiForFrame = false;
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void restoreDisplayTargetBeforeClose(CallbackInfo ci) {
        if (dlss_fg$displayTarget != null) {
            mainRenderTarget = dlss_fg$displayTarget;
        }
    }

    @Inject(method = "close", at = @At("RETURN"))
    private void closeAuxiliaryTargets(CallbackInfo ci) {
        if (dlss_fg$worldTarget != null) {
            dlss_fg$worldTarget.destroyBuffers();
            dlss_fg$worldTarget = null;
        }
        if (dlss_fg$uiTarget != null) {
            dlss_fg$uiTarget.destroyBuffers();
            dlss_fg$uiTarget = null;
        }
    }
}
