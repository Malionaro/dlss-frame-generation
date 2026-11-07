/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.lod.dh.mixin;

import com.nvidia.rtxremix.minecraft.lod.dh.DistantHorizonsCompat;
import com.seibel.distanthorizons.core.api.internal.ClientApi;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * While capturing, Distant Horizons draws no LODs, yet its vanilla fade still faded the
 * main target's terrain toward their empty textures: black wherever the depth held land
 * once the main target carried the Remix depth for other mods' draws (ModOverlayLayer).
 */
@Mixin(ClientApi.class)
abstract class ClientApiFadeMixin {

    @Inject(method = {"renderFadeOpaque", "renderFadeTransparent"}, at = @At("HEAD"), cancellable = true)
    private void rtxremix$skipFade(CallbackInfo ci) {
        if (DistantHorizonsCompat.capturing()) ci.cancel();
    }
}
