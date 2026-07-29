package org.malionaro.dlss_frame_generation.client.ui;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import org.malionaro.dlss_frame_generation.client.compat.CompatibilityManager;
import org.malionaro.dlss_frame_generation.client.config.FrameGenConfig;
import org.malionaro.dlss_frame_generation.client.FrameGenMod;
import org.malionaro.dlss_frame_generation.client.backend.FrameGenerationBackend;
import org.malionaro.dlss_frame_generation.client.render.ReflexPacer;
import org.malionaro.dlss_frame_generation.client.StreamlineBridge;
import org.malionaro.dlss_frame_generation.client.render.VulkanRegistry;

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
        String vendorName = Component.translatable(
                "dlss_fg.value.unknown"
        ).getString();
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
        extractor.text(
                mc.font,
                Component.translatable("dlss_fg.osd.title"),
                osdX + 8,
                osdY + 6,
                0xFF00FF88
        );

        // Metric Lines
        Component fpsLine = Component.translatable(
                "dlss_fg.osd.fps",
                estimatedFgFps,
                baseFps,
                fgActive
                        ? Component.translatable(
                                "dlss_fg.value.multiplier", fgMultiplier
                        )
                        : Component.translatable("dlss_fg.osd.inactive")
        );
        extractor.text(mc.font, fpsLine, osdX + 8, osdY + 20, 0xFFFFFFFF);

        Component latencyLine = Component.translatable(
                "dlss_fg.osd.latency",
                String.format(java.util.Locale.ROOT, "%.2f", frameTimeMs)
        );
        extractor.text(mc.font, latencyLine, osdX + 8, osdY + 33, 0xFFAAAAAA);

        Component backendLine = Component.translatable(
                "dlss_fg.osd.backend",
                vendorName,
                VulkanRegistry.getProviderAcceptedFrameCount(),
                VulkanRegistry.getCapturedFrameCount()
        );
        extractor.text(mc.font, backendLine, osdX + 8, osdY + 46, 0xFF00AAFF);
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
