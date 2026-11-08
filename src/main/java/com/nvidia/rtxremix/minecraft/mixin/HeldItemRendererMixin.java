/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.nvidia.rtxremix.minecraft.entity.EntityRenderContext;
import net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.FirstPersonHandsAndItemsRenderState;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tells the first-person capture which vanilla hand the arm and item being
 * submitted belong to, so a held light can be placed at the right hand. Who holds
 * an item travels on its render state instead ({@code ItemStackRenderStateMixin}).
 */
@Mixin(FirstPersonHandsAndItemsRenderer.class)
public class HeldItemRendererMixin {

    @Inject(method = "submitArmWithItem", at = @At("HEAD"))
    private void rtxremix$beginFirstPersonHand(
            PlayerRenderState playerState,
            FirstPersonHandsAndItemsRenderState state,
            float partialTicks,
            float xRot,
            InteractionHand hand,
            float attack,
            ItemStack itemStack,
            float inverseArmHeight,
            PoseStack poseStack,
            SubmitNodeCollector submitNodeCollector,
            int lightCoords,
            CallbackInfo ci) {
        EntityRenderContext context = EntityRenderContext.get();
        if (context.isRemixPass()) {
            context.setActiveHand(hand);
            if (hand == InteractionHand.MAIN_HAND) {
                // Vanilla lets the hand trail the camera by (view - bob) * 0.1; under the
                // path tracer's temporal upscaling that lag reads as a twitch. Keep a quarter.
                state.xBob = state.viewXRot - (state.viewXRot - state.xBob) * 0.25f;
                state.yBob = state.viewYRot - (state.viewYRot - state.yBob) * 0.25f;
            }
        }
    }

    @Inject(method = "submitArmWithItem", at = @At("RETURN"))
    private void rtxremix$endFirstPersonHand(
            PlayerRenderState playerState,
            FirstPersonHandsAndItemsRenderState state,
            float partialTicks,
            float xRot,
            InteractionHand hand,
            float attack,
            ItemStack itemStack,
            float inverseArmHeight,
            PoseStack poseStack,
            SubmitNodeCollector submitNodeCollector,
            int lightCoords,
            CallbackInfo ci) {
        EntityRenderContext context = EntityRenderContext.get();
        if (context.isRemixPass()) context.clearActiveHand();
    }
}
