package org.malionaro.dlss_frame_generation.client.mixin;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.malionaro.dlss_frame_generation.client.FrameGenMod;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Invalidates the nearby ray-query scene when the client receives a block
 * change, avoiding periodic full scene rebuilds during otherwise static play.
 */
@Mixin(ClientLevel.class)
public final class ClientLevelMixin {
    @Inject(method = "sendBlockUpdated", at = @At("HEAD"))
    private void dlss_fg$invalidateRayTracingScene(
            BlockPos position,
            BlockState oldState,
            BlockState newState,
            int flags,
            CallbackInfo callback
    ) {
        if (oldState == newState || oldState.equals(newState)) {
            return;
        }
        if (FrameGenMod.rayTracingManager() != null) {
            FrameGenMod.rayTracingManager().onBlockChanged(position);
        }
    }
}
