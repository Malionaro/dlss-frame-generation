package org.malionaro.dlss_frame_generation.client.ui;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import org.malionaro.dlss_frame_generation.client.CompatibilityManager;
import org.malionaro.dlss_frame_generation.client.FrameGenConfig;
import org.malionaro.dlss_frame_generation.client.FrameGenMod;
import org.malionaro.dlss_frame_generation.client.FrameGenerationBackend;
import org.malionaro.dlss_frame_generation.client.ReflexPacer;
import org.malionaro.dlss_frame_generation.client.StreamlineBridge;
import org.malionaro.dlss_frame_generation.client.VulkanRegistry;

public class FrameGenOsdRenderer {

    public static void renderOsd(GuiGraphicsExtractor extractor, int screenWidth, int screenHeight) {
        FrameGenConfig config = FrameGenConfig.getInstance();
        if (!config.osdEnabled) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.gui == null) return;

        // OSD Position Top-Left with dark background pill
        int osdX = 10;
        int osdY = 10;
        int width = 210;
        int height = 65;

        // Extract metrics
        int baseFps = mc.getFps();
        double frameTimeMs = ReflexPacer.getAverageFrameTimeMs();

        CompatibilityManager comp = FrameGenMod.compatibilityManager();
        String vendorName = "UNKNOWN";
        FrameGenerationBackend backend = null;
        if (comp != null && comp.activeVendorBackend() != null) {
            backend = comp.activeVendorBackend();
            vendorName = backend.vendor().displayName();
        }
        boolean fgActive = backend != null && backend.isFrameGenerationActive();
        int fgMultiplier = fgActive ? configuredMultiplier(config, backend.vendor()) : 1;
        int estimatedFgFps = baseFps * fgMultiplier;

        // Draw translucent dark background card (ARGB 0xCC000000)
        extractor.fill(osdX, osdY, osdX + width, osdY + height, 0xCC111111);

        // Header Title
        extractor.text(mc.font, Component.literal("⚡ FRAME GENERATION OSD"), osdX + 8, osdY + 6, 0x00FF88);

        // Metric Lines
        String fpsLine = String.format(
                "FPS: %d (Base: %d | FG %s)",
                estimatedFgFps,
                baseFps,
                fgActive ? fgMultiplier + "x" : "INACTIVE"
        );
        extractor.text(mc.font, Component.literal(fpsLine), osdX + 8, osdY + 20, 0xFFFFFF);

        String latencyLine = String.format("Pacing Latency: %.2f ms", frameTimeMs);
        extractor.text(mc.font, Component.literal(latencyLine), osdX + 8, osdY + 33, 0xAAAAAA);

        String backendLine = String.format(
                "Backend: %s | Accepted: %d/%d",
                vendorName,
                VulkanRegistry.getProviderAcceptedFrameCount(),
                VulkanRegistry.getCapturedFrameCount()
        );
        extractor.text(mc.font, Component.literal(backendLine), osdX + 8, osdY + 46, 0x00AAFF);
    }

    private static int configuredMultiplier(
            FrameGenConfig config,
            FrameGenerationBackend.GpuVendor vendor
    ) {
        return switch (vendor) {
            case NVIDIA -> Math.min(
                    config.frameMultiplier,
                    maximumNativeMultiplier(FrameGenerationBackend.GpuVendor.NVIDIA)
            );
            case AMD -> config.amdFsrMultiplier;
            case INTEL -> 2;
            case UNKNOWN -> 1;
        };
    }

    private static int maximumNativeMultiplier(
            FrameGenerationBackend.GpuVendor vendor
    ) {
        try {
            return Math.max(1, StreamlineBridge.nGetMaxFrameMultiplier(vendor.vendorId()));
        } catch (UnsatisfiedLinkError ignored) {
            return 1;
        }
    }
}
