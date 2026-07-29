package org.malionaro.dlss_frame_generation.client.mixin;
import org.malionaro.dlss_frame_generation.client.render.VulkanRegistry;

import net.minecraft.client.Minecraft;
import org.malionaro.dlss_frame_generation.client.FrameGenMod;
import org.malionaro.dlss_frame_generation.client.StreamlineBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public class MinecraftMixin {
    @Inject(method = "runTick", at = @At("HEAD"))
    private void onTickStart(CallbackInfo ci) {
        try {
            StreamlineBridge.nReflexMarker(0); // SIMULATION_START
        } catch (UnsatisfiedLinkError e) {
            // Ignore
        }
    }

    @Inject(method = "runTick", at = @At("RETURN"))
    private void onTickEnd(CallbackInfo ci) {
        try {
            StreamlineBridge.nReflexMarker(1); // SIMULATION_END
        } catch (UnsatisfiedLinkError e) {
            // Ignore
        }
    }

    @Inject(method = "setLevel", at = @At("HEAD"))
    private void onSetLevel(CallbackInfo ci) {
        try {
            org.malionaro.dlss_frame_generation.client.render.VulkanRegistry.setWorldReset();
        } catch (Throwable t) {
            // Ignore
        }
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void onClientClose(CallbackInfo ci) {
        try {
            org.malionaro.dlss_frame_generation.client.render.VulkanRegistry.shutdown();
        } catch (Throwable error) {
            FrameGenMod.LOGGER.debug("Vulkan frame-generation shutdown failed", error);
        }
        try {
            StreamlineBridge.nShutdown();
        } catch (UnsatisfiedLinkError error) {
            FrameGenMod.LOGGER.debug("Native frame-generation shutdown unavailable: {}", error.getMessage());
        }
    }
}
