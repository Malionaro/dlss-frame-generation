package org.malionaro.dlss_frame_generation.client.mixin;

import net.minecraft.client.particle.SingleQuadParticle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(SingleQuadParticle.class)
public interface SingleQuadParticleAccessor {
    @Accessor("quadSize")
    float dlss_fg$quadSize();

    @Accessor("rCol")
    float dlss_fg$red();

    @Accessor("gCol")
    float dlss_fg$green();

    @Accessor("bCol")
    float dlss_fg$blue();

    @Accessor("alpha")
    float dlss_fg$alpha();
}
