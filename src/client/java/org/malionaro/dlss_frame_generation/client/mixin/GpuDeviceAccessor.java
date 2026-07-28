package org.malionaro.dlss_frame_generation.client.mixin;

import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.GpuDeviceBackend;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = GpuDevice.class, remap = false)
public interface GpuDeviceAccessor {
    @Accessor("backend")
    GpuDeviceBackend dlss_fg$getBackend();
}
