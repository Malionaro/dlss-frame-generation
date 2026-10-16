package org.malionaro.dlss_frame_generation.client.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.MainTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.GameRenderState;
import org.joml.Vector4f;
import org.malionaro.dlss_frame_generation.client.compat.ForeignShaderPipeline;
import org.malionaro.dlss_frame_generation.client.compat.VitrailBridge;
import org.malionaro.dlss_frame_generation.client.render.CompositeFallbackPolicy;
import org.malionaro.dlss_frame_generation.client.render.UiLayerRedirect;
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

    // Shader-pack engine (Vitrail) cooperation, see ForeignShaderPipeline.
    @Unique private MainTarget dlss_fg$shaderPackOutput;
    @Unique private int dlss_fg$shaderPackFullWidth;
    @Unique private int dlss_fg$shaderPackFullHeight;
    @Unique private boolean dlss_fg$shaderPackCopyPending;

    // 26.3 changed GameRenderer#render from (DeltaTracker, boolean) to (), so every
    // handler below takes only the CallbackInfo. The DeltaTracker that used to be
    // passed here is obtained from RenderSystem where a frame delta is needed.
    @Inject(method = "render", at = @At("HEAD"))
    private void onRenderStart(CallbackInfo ci) {
        // The HUD redirect lives only between the pre-HUD clear and
        // GuiRenderer.endFrame; drop it if an interrupted frame left it set.
        UiLayerRedirect.end();
        dlss_fg$separateUiForFrame = false;
        if (ForeignShaderPipeline.active()) {
            // The shader-pack engine owns the world target and recovers it
            // itself; this mode never assigns mainRenderTarget.
            dlss_fg$displayTarget = null;
            dlss_fg$shaderPackCopyPending = false;
        } else if (dlss_fg$displayTarget != null) {
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
        if (ForeignShaderPipeline.active()) {
            // Never swap mainRenderTarget here: Vitrail has already put its
            // scaled world textures into this very object (beginWorld at the
            // first clear) and restores them through the field at endWorld.
            // DLSS/FSR instead drives Vitrail's own render scale.
            dlss_fg$worldTargetForFrame = null;
            dlss_fg$prepareShaderPackScale();
            VulkanRegistry.beginWorldFrame(gameRenderState);
            return;
        }
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

    /**
     * Window-size reference for the shader-pack path. While Vitrail scales,
     * mainRenderTarget reports the scaled size, so the size recorded after
     * its last restore is used (or the window on the very first frames).
     */
    @Unique
    private void dlss_fg$prepareShaderPackScale() {
        boolean scaled = VitrailBridge.worldScaled();
        if (!scaled) {
            dlss_fg$shaderPackFullWidth = mainRenderTarget.width;
            dlss_fg$shaderPackFullHeight = mainRenderTarget.height;
        } else if (dlss_fg$shaderPackFullWidth <= 0
                || dlss_fg$shaderPackFullHeight <= 0) {
            net.minecraft.client.Minecraft minecraft =
                    net.minecraft.client.Minecraft.getInstance();
            dlss_fg$shaderPackFullWidth = minecraft.getWindow().getWidth();
            dlss_fg$shaderPackFullHeight = minecraft.getWindow().getHeight();
        }
        if (!VitrailBridge.available()
                || dlss_fg$shaderPackFullWidth <= 0
                || dlss_fg$shaderPackFullHeight <= 0) {
            return;
        }
        NvidiaDlssRenderResolution.Resolution resolution =
                VulkanRegistry.resolveWorldResolution(
                        dlss_fg$shaderPackFullWidth,
                        dlss_fg$shaderPackFullHeight
                );
        // The ratio applies from Vitrail's next beginWorld, i.e. next frame.
        if (resolution.reduced()) {
            int whole = VitrailBridge.wholePercent();
            int percent = Math.round(whole * resolution.renderWidth()
                    / (float) dlss_fg$shaderPackFullWidth);
            VitrailBridge.requestScale(Math.max(1, Math.min(whole - 1, percent)));
        } else {
            VitrailBridge.requestScale(-1);
        }
    }

    @Unique
    private void dlss_fg$captureShaderPackFrame() {
        RenderTarget world = mainRenderTarget;
        int fullWidth = dlss_fg$shaderPackFullWidth > 0
                ? dlss_fg$shaderPackFullWidth : world.width;
        int fullHeight = dlss_fg$shaderPackFullHeight > 0
                ? dlss_fg$shaderPackFullHeight : world.height;
        boolean scaled = world.width < fullWidth || world.height < fullHeight;
        RenderTarget output = world;
        if (scaled) {
            // DLSS/FSR writes the window-size result here; it replaces
            // Vitrail's FSR-1 upscale after endWorld restored the target.
            boolean fresh = false;
            if (dlss_fg$shaderPackOutput == null) {
                dlss_fg$shaderPackOutput = new MainTarget(fullWidth, fullHeight);
                fresh = true;
            } else if (dlss_fg$shaderPackOutput.width != fullWidth
                    || dlss_fg$shaderPackOutput.height != fullHeight) {
                dlss_fg$shaderPackOutput.resize(fullWidth, fullHeight);
                fresh = true;
            }
            if (fresh) {
                // Give the new images a defined layout before the pipeline
                // records its copy into them.
                RenderSystem.getDevice().createCommandEncoder()
                        .clearColorAndDepthTextures(
                                dlss_fg$shaderPackOutput.getColorTexture(),
                                DLSS_FG_TRANSPARENT,
                                dlss_fg$shaderPackOutput.getDepthTexture(),
                                0.0
                        );
            }
            output = dlss_fg$shaderPackOutput;
        }
        dlss_fg$separateUiForFrame = VulkanRegistry.shouldSeparateUi();
        VulkanRegistry.captureFrameGenerationInputs(
                world, output, dlss_fg$separateUiForFrame);
        dlss_fg$shaderPackCopyPending = scaled
                && VulkanRegistry.getCurrentFrameResources() != null
                && VulkanRegistry.lastFrameUpscaled();
    }

    /**
     * Runs right after the depth-only clear, i.e. after Vitrail's endWorld
     * restored the window-size textures and drew its own upscale.
     */
    @Inject(
        method = "render",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/renderpearl/api/commands/CommandEncoder;clearDepthTexture(Lcom/mojang/renderpearl/api/textures/GpuTexture;D)V",
            shift = At.Shift.AFTER
        )
    )
    private void dlss_fg$afterShaderPackWorld(CallbackInfo ci) {
        if (!ForeignShaderPipeline.active()) {
            return;
        }
        RenderTarget display = mainRenderTarget;
        if (!VitrailBridge.worldScaled()) {
            dlss_fg$shaderPackFullWidth = display.width;
            dlss_fg$shaderPackFullHeight = display.height;
        }
        if (dlss_fg$shaderPackCopyPending
                && dlss_fg$shaderPackOutput != null
                && dlss_fg$shaderPackOutput.width == display.width
                && dlss_fg$shaderPackOutput.height == display.height) {
            try {
                display.copyColorFrom(dlss_fg$shaderPackOutput);
            } catch (Throwable t) {
                org.slf4j.LoggerFactory.getLogger("DLSS_FG_Mixin")
                        .debug("Shader-pack upscale copy failed", t);
            }
        }
        dlss_fg$shaderPackCopyPending = false;

        if (dlss_fg$separateUiForFrame) {
            // Vitrail restored its textures in this very object; it stays
            // mainRenderTarget, only GuiRenderer.draw is pointed at the layer.
            dlss_fg$displayTarget = display;
            dlss_fg$beginUiLayer(display);
        }
    }

    /**
     * Prepares the transparent full-resolution UI layer and routes the HUD
     * into it via {@link UiLayerRedirect} (see GuiRendererMixin). The
     * {@code mainRenderTarget} field is left alone.
     */
    @Unique
    private void dlss_fg$beginUiLayer(RenderTarget display) {
        if (dlss_fg$uiTarget == null) {
            dlss_fg$uiTarget = new MainTarget(display.width, display.height);
        } else if (dlss_fg$uiTarget.width != display.width
                || dlss_fg$uiTarget.height != display.height) {
            dlss_fg$uiTarget.resize(display.width, display.height);
        }
        RenderSystem.getDevice().createCommandEncoder()
                .clearColorAndDepthTextures(
                        dlss_fg$uiTarget.getColorTexture(),
                        DLSS_FG_TRANSPARENT,
                        dlss_fg$uiTarget.getDepthTexture(),
                        0.0
                );
        UiLayerRedirect.begin(dlss_fg$uiTarget);
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
        if (ForeignShaderPipeline.active()) {
            // Still before Vitrail's endWorld: mainRenderTarget holds the
            // pack's finished (possibly scaled) world. The HUD redirect waits
            // for dlss_fg$afterShaderPackWorld, after the engine restored it.
            dlss_fg$captureShaderPackFrame();
            return;
        }
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

        // The reduced world target hands back to the display target here; the
        // HUD itself is routed into the UI layer by GuiRendererMixin.
        mainRenderTarget = displayTarget;
        if (dlss_fg$separateUiForFrame) {
            dlss_fg$beginUiLayer(displayTarget);
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
        // The HUD is finished; nothing after this point may draw into the layer.
        UiLayerRedirect.end();
        if (!dlss_fg$separateUiForFrame
                || dlss_fg$uiTarget == null
                || dlss_fg$displayTarget == null) {
            dlss_fg$separateUiForFrame = false;
            return;
        }

        VulkanRegistry.captureFrameGenerationUi(dlss_fg$uiTarget);
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
        if (dlss_fg$shaderPackOutput != null) {
            dlss_fg$shaderPackOutput.destroyBuffers();
            dlss_fg$shaderPackOutput = null;
        }
    }
}
