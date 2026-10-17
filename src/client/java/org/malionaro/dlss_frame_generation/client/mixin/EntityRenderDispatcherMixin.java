package org.malionaro.dlss_frame_generation.client.mixin;

import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import org.malionaro.dlss_frame_generation.client.motion.EntityIdHolder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Tags each extracted render state with its entity for per-part motion. */
@Mixin(EntityRenderDispatcher.class)
public class EntityRenderDispatcherMixin {
    @Inject(method = "extractEntity", at = @At("RETURN"), require = 0)
    private <E extends Entity> void dlss_fg$tagEntity(
            E entity, float partialTick, CallbackInfoReturnable<EntityRenderState> cir) {
        if (cir.getReturnValue() instanceof EntityIdHolder holder) {
            holder.dlss_fg$setEntityId(entity.getId());
        }
    }
}
