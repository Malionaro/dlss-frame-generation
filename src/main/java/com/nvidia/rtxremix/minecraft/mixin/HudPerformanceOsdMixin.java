/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 Malionaro
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.osd.PerformanceOsd;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Draws the performance overlay after the rest of the HUD. */
@Mixin(Hud.class)
public class HudPerformanceOsdMixin {
    @Inject(method = "extractRenderState", at = @At("RETURN"))
    private void rtxremix$renderPerformanceOsd(GuiGraphicsExtractor extractor, DeltaTracker deltaTracker,
                                              CallbackInfo ci) {
        PerformanceOsd.render(extractor);
    }
}
