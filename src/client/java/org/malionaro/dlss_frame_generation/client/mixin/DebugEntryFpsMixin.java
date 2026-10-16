package org.malionaro.dlss_frame_generation.client.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.debug.DebugEntryFps;
import net.minecraft.client.gui.components.debug.DebugScreenDisplayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import org.malionaro.dlss_frame_generation.client.backend.FrameGenerationBackend;
import org.malionaro.dlss_frame_generation.client.render.NvidiaDlssRenderResolution;
import org.malionaro.dlss_frame_generation.client.render.TextureLodBias;
import org.malionaro.dlss_frame_generation.client.render.VulkanRegistry;
import org.malionaro.dlss_frame_generation.client.ui.FrameGenFpsStats;
import java.util.Locale;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * F3 FPS line: shows the frame-generation output rate instead of the bare
 * render-loop rate while FG is active, and adds a line with the technology,
 * multiplier and base rate (or why FG is off), the frame times, and the
 * upscaler's render and output resolution.
 */
@Mixin(DebugEntryFps.class)
public class DebugEntryFpsMixin {
    @Unique private static final int DLSS_FG$MAX_REASON_LENGTH = 90;

    @Unique private FrameGenFpsStats dlss_fg$stats;

    @Inject(method = "display", at = @At("HEAD"))
    private void dlss_fg$captureStats(
            DebugScreenDisplayer displayer,
            Level serverOrClientLevel,
            LevelChunk clientChunk,
            LevelChunk serverChunk,
            CallbackInfo ci
    ) {
        dlss_fg$stats = FrameGenFpsStats.capture();
    }

    @Redirect(
        method = "display",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/Minecraft;getFps()I"
        )
    )
    private int dlss_fg$frameGenerationFps(Minecraft minecraft) {
        FrameGenFpsStats stats = dlss_fg$stats;
        return stats != null ? stats.outputFps() : minecraft.getFps();
    }

    @Inject(method = "display", at = @At("TAIL"))
    private void dlss_fg$addFrameGenerationLine(
            DebugScreenDisplayer displayer,
            Level serverOrClientLevel,
            LevelChunk clientChunk,
            LevelChunk serverChunk,
            CallbackInfo ci
    ) {
        FrameGenFpsStats stats = dlss_fg$stats;
        dlss_fg$stats = null;
        if (stats == null) {
            return;
        }
        Component line;
        if (stats.active()) {
            line = Component.translatable(
                    "dlss_fg.osd.debug_active",
                    stats.technologyName(),
                    stats.multiplier(),
                    stats.baseFps(),
                    stats.outputFps()
            );
        } else if (stats.inactiveReason().isBlank()) {
            line = Component.translatable("dlss_fg.osd.debug_off");
        } else {
            String reason = stats.inactiveReason();
            if (reason.length() > DLSS_FG$MAX_REASON_LENGTH) {
                reason = reason.substring(0, DLSS_FG$MAX_REASON_LENGTH - 3) + "...";
            }
            line = Component.translatable("dlss_fg.osd.debug_inactive", reason);
        }
        displayer.addPriorityLine(line.getString());
        displayer.addPriorityLine(dlss_fg$frameTimeLine(stats).getString());
        displayer.addPriorityLine(dlss_fg$upscalingLine(stats).getString());
    }

    @Unique
    private static Component dlss_fg$frameTimeLine(FrameGenFpsStats stats) {
        double baseMs = stats.baseFps() > 0 ? 1000.0 / stats.baseFps() : 0.0;
        double outputMs = stats.outputFps() > 0 ? 1000.0 / stats.outputFps() : baseMs;
        return Component.translatable(
                "dlss_fg.osd.debug_frametime",
                String.format(Locale.ROOT, "%.2f", baseMs),
                String.format(Locale.ROOT, "%.2f", outputMs)
        );
    }

    @Unique
    private static Component dlss_fg$upscalingLine(FrameGenFpsStats stats) {
        NvidiaDlssRenderResolution.Resolution resolution = VulkanRegistry.activeResolution();
        if (resolution.mode() == 0 || resolution.outputWidth() <= 1) {
            return Component.translatable("dlss_fg.osd.debug_upscaling_off",
                    resolution.outputWidth(), resolution.outputHeight());
        }
        String technology = switch (stats.vendor()) {
            case NVIDIA -> "DLSS";
            case AMD, INTEL -> "FSR 3.1";
            case UNKNOWN -> "-";
        };
        String mode = Component.translatable(switch (resolution.mode()) {
            case 1 -> stats.vendor() == FrameGenerationBackend.GpuVendor.NVIDIA
                    ? "dlss_fg.value.dlaa" : "dlss_fg.value.native_aa";
            case 2 -> "dlss_fg.value.quality";
            case 3 -> "dlss_fg.value.balanced";
            case 4 -> "dlss_fg.value.performance";
            case 5 -> "dlss_fg.value.ultra_performance";
            case 6 -> "dlss_fg.value.ultra_quality";
            default -> "dlss_fg.value.unknown";
        }).getString();
        double percent = 100.0 * resolution.renderWidth() / resolution.outputWidth();
        return Component.translatable(
                VulkanRegistry.lastFrameUpscaled()
                        ? "dlss_fg.osd.debug_upscaling"
                        : "dlss_fg.osd.debug_upscaling_waiting",
                technology,
                mode,
                resolution.renderWidth(),
                resolution.renderHeight(),
                resolution.outputWidth(),
                resolution.outputHeight(),
                String.format(Locale.ROOT, "%.0f", percent),
                String.format(Locale.ROOT, "%.0f", VulkanRegistry.dynamicResolutionScale() * 100.0),
                String.format(Locale.ROOT, "%.2f", TextureLodBias.applied())
        );
    }
}
