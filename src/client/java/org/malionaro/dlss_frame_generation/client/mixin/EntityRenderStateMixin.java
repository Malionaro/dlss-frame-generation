package org.malionaro.dlss_frame_generation.client.mixin;

import net.minecraft.client.renderer.entity.state.EntityRenderState;
import org.malionaro.dlss_frame_generation.client.motion.EntityIdHolder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(EntityRenderState.class)
public class EntityRenderStateMixin implements EntityIdHolder {
    @Unique
    private int dlss_fg$entityId = -1;

    @Override
    public int dlss_fg$entityId() {
        return dlss_fg$entityId;
    }

    @Override
    public void dlss_fg$setEntityId(int entityId) {
        dlss_fg$entityId = entityId;
    }
}
