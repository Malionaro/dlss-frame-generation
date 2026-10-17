package org.malionaro.dlss_frame_generation.client.mixin;

import net.minecraft.client.particle.Particle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Particle.class)
public interface ParticleAccessor {
    @Accessor("x")
    double dlss_fg$x();

    @Accessor("y")
    double dlss_fg$y();

    @Accessor("z")
    double dlss_fg$z();
}
