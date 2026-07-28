package org.malionaro.dlss_frame_generation.client.mixin;

import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Options.class)
public interface OptionsAccessor {
    @Accessor("fov")
    OptionInstance<Integer> dlss_fg$getFov();

    @Accessor("renderDistance")
    OptionInstance<Integer> dlss_fg$getRenderDistance();
}
