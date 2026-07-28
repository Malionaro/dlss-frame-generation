package org.malionaro.dlss_frame_generation.client.mixin;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import org.malionaro.dlss_frame_generation.client.FrameGenMod;
import org.malionaro.dlss_frame_generation.client.CompatibilityManager;
import org.malionaro.dlss_frame_generation.client.FrameGenerationBackend;
import org.malionaro.dlss_frame_generation.client.VulkanRegistry;
import org.malionaro.dlss_frame_generation.client.motion.MotionVectorResource;
import org.malionaro.dlss_frame_generation.client.motion.MotionVectorSnapshot;
import org.malionaro.dlss_frame_generation.client.motion.MotionVectorValidator;
import org.malionaro.dlss_frame_generation.client.motion.MotionVectorDebugRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin into Hud.extractRenderState() to draw the Motion Vector Debug Overlay.
 * In MC 26.2, HUD rendering uses GuiGraphicsExtractor, not GuiGraphics.
 * This is the critical hook that ensures the debug renderer is called every frame.
 */
@Mixin(Hud.class)
public class GuiHudMixin {

    @Inject(method = "extractRenderState", at = @At("RETURN"))
    private void onExtractRenderState(GuiGraphicsExtractor extractor, DeltaTracker deltaTracker, CallbackInfo ci) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.getWindow() == null) return;

            int screenWidth = mc.getWindow().getGuiScaledWidth();
            int screenHeight = mc.getWindow().getGuiScaledHeight();

            // The performance OSD is an independent HUD feature. It must remain
            // visible when motion-vector debugging is disabled.
            org.malionaro.dlss_frame_generation.client.ui.FrameGenOsdRenderer
                    .renderOsd(extractor, screenWidth, screenHeight);

            MotionVectorDebugRenderer debugRenderer = FrameGenMod.motionVectorDebugRenderer();
            if (debugRenderer == null || debugRenderer.getDebugMode() == MotionVectorDebugRenderer.DebugMode.OFF) {
                return;
            }

            MotionVectorResource gpuResource = VulkanRegistry.getActiveMotionVectors();
            MotionVectorValidator.ValidationResult gpuValidation =
                    VulkanRegistry.getMotionVectorValidation();
            MotionVectorSnapshot gpuSnapshot =
                    VulkanRegistry.getActiveMotionVectorSnapshot();
            // The native provider consumes the dense GPU image, while the HUD
            // needs a cheap CPU camera sample to draw readable arrows. Keep
            // that preview updated even after the real VkImage is available.
            debugRenderer.updateCameraMotion(mc, screenWidth, screenHeight);

            CompatibilityManager compatibilityManager = FrameGenMod.compatibilityManager();
            FrameGenerationBackend activeBackend = compatibilityManager != null
                    ? compatibilityManager.activeVendorBackend()
                    : null;

            debugRenderer.renderHudOverlay(
                    extractor,
                    screenWidth,
                    screenHeight,
                    activeBackend,
                    gpuResource != null ? gpuResource : debugRenderer.currentResource(),
                    gpuValidation != null ? gpuValidation : debugRenderer.currentValidation(),
                    gpuSnapshot
            );
        } catch (Throwable t) {
            FrameGenMod.LOGGER.debug("Frame Generation HUD rendering failed", t);
        }
    }
}
