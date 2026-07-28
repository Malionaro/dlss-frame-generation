package org.malionaro.dlss_frame_generation.client.mixin;

import net.minecraft.client.Camera;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Camera.class)
public interface CameraAccessor {
    @Accessor("position")
    Vec3 dlss_fg$getPosition();

    @Accessor("forwards")
    Vector3f dlss_fg$getForwards();

    @Accessor("up")
    Vector3f dlss_fg$getUp();

    @Accessor("left")
    Vector3f dlss_fg$getLeft();
}
