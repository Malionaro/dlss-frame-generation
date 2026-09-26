package org.malionaro.dlss_frame_generation.client.mixin;

import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.backend.api.GpuDeviceBackend;
import com.mojang.renderpearl.frontend.FrontendGpuDevice;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

// 26.3 turned GpuDevice into an interface and moved the concrete state into
// FrontendGpuDevice, so the `backend` field now lives there. Mixin must target
// the implementation class, not the interface.
@Mixin(value = FrontendGpuDevice.class, remap = false)
public interface GpuDeviceAccessor {
    @Accessor("backend")
    GpuDeviceBackend dlss_fg$getBackend();
}
