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

        // Extract metrics
        FrameGenFpsStats stats = FrameGenFpsStats.capture();
        int baseFps = stats.baseFps();
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
        int estimatedFgFps = stats.outputFps();

        // When frame generation is off, "INACTIVE" alone is useless during
        // debugging: the reason lives in the native status string. Show it so
        // the overlay answers "why is this off" without opening the log.
        Component fgState;
        if (stats.active()) {
            fgState = Component.translatable(
                    "dlss_fg.value.multiplier", stats.multiplier()
            );
        } else {
            fgState = stats.inactiveReason().isBlank()
                    ? Component.translatable("dlss_fg.osd.inactive")
                    : Component.literal(stats.inactiveReason());
        }

        boolean showReflex = backend != null && backend.vendor() == FrameGenerationBackend.GpuVendor.NVIDIA;
        boolean showRt = config.rayTracingMode != FrameGenConfig.RayTracingMode.OFF;

        // Build lines first so the card width follows the longest line
        // (German strings overflowed the fixed 210px box). Colors raised to
        // WCAG-friendlier values on the translucent card.
        record OsdLine(Component text, int color) {}
        java.util.List<OsdLine> lines = new java.util.ArrayList<>();
        lines.add(new OsdLine(
                Component.translatable("dlss_fg.osd.title"), 0xFF00FF88));
        lines.add(new OsdLine(
                Component.translatable(
                        "dlss_fg.osd.fps",
                        estimatedFgFps,
                        baseFps,
                        fgState),
                0xFFFFFFFF));
        lines.add(new OsdLine(
                Component.translatable(
                        "dlss_fg.osd.latency",
                        String.format(java.util.Locale.ROOT, "%.2f", frameTimeMs)),
                0xFFE8E8E8));
        lines.add(new OsdLine(
                Component.translatable(
                        "dlss_fg.osd.backend",
                        vendorName,
                        VulkanRegistry.getProviderAcceptedFrameCount(),
                        VulkanRegistry.getCapturedFrameCount()),
                0xFF66CCFF));
        if (showReflex) {
            // No hardcoded English — reuse the value keys.
            Component reflexValue = Component.translatable(
                    config.reflexMode == 2 ? "dlss_fg.value.on_boost"
                            : config.reflexMode == 1 ? "dlss_fg.value.on"
                            : "dlss_fg.value.off");
            lines.add(new OsdLine(
                    Component.translatable("dlss_fg.osd.reflex", reflexValue),
                    0xFFFFCC55));
        }
        if (showRt) {
            lines.add(new OsdLine(
                    Component.translatable(
                            "dlss_fg.osd.rt",
                            config.rayTracingMode.displayName()),
                    0xFFFF88FF));
        }

        int width = 16;
        for (OsdLine line : lines) {
            width = Math.max(width, mc.font.width(line.text()) + 16);
        }
        int height = 12 + lines.size() * 13;

        // Draw translucent dark background card (ARGB 0xCC111111)
        extractor.fill(osdX, osdY, osdX + width, osdY + height, 0xCC111111);

        int currentY = osdY + 6;
        for (OsdLine line : lines) {
            extractor.text(mc.font, line.text(), osdX + 8, currentY, line.color());
            currentY += 13;
        }
    }
}
