package org.malionaro.dlss_frame_generation.client.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.debug.DebugEntryFps;
import net.minecraft.client.gui.components.debug.DebugScreenDisplayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import org.malionaro.dlss_frame_generation.client.ui.FrameGenFpsStats;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * F3 FPS line: shows the frame-generation output rate instead of the bare
 * render-loop rate while FG is active, and adds a line with the technology,
 * multiplier and base rate (or why FG is off).
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
    }
}
