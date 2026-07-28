package org.malionaro.dlss_frame_generation.client.mixin;

import com.mojang.blaze3d.vulkan.VulkanBackend;
import com.mojang.blaze3d.vulkan.VulkanPhysicalDevice;
import com.mojang.blaze3d.vulkan.init.VulkanFeature;
import org.malionaro.dlss_frame_generation.client.StreamlineVulkanRequirements;
import org.malionaro.dlss_frame_generation.client.D3D12InteropRequirements;
import org.malionaro.dlss_frame_generation.client.raytracing.RayTracingDeviceRequirements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

import java.util.Collection;
import java.util.Set;

/**
 * Adds optional hardware ray-query support to Minecraft's logical device.
 */
@Mixin(value = VulkanBackend.class, remap = false)
public class VulkanBackendMixin {
    @Unique
    private static final Logger DLSS_FG$LOGGER =
            LoggerFactory.getLogger("DLSS_FG_RayTracing");

    @ModifyArgs(
            method = "createDevice",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/vulkan/VulkanBackend;createDevice(Ljava/util/Collection;Lcom/mojang/blaze3d/vulkan/VulkanPhysicalDevice;Ljava/util/Set;)Lorg/lwjgl/vulkan/VkDevice;"
            )
    )
    private void dlss_fg$enableOptionalRayQuery(Args args) {
        Collection<String> extensions = args.get(0);
        VulkanPhysicalDevice physicalDevice = args.get(1);
        Set<VulkanFeature> features = args.get(2);

        RayTracingDeviceRequirements.Selection selection =
                RayTracingDeviceRequirements.select(extensions, physicalDevice, features);
        StreamlineVulkanRequirements.Selection streamlineSelection =
                StreamlineVulkanRequirements.selectDeviceRequirements(
                        selection.extensions(),
                        physicalDevice,
                        selection.features()
                );
        D3D12InteropRequirements.Selection d3d12Selection =
                D3D12InteropRequirements.select(
                        streamlineSelection.extensions(),
                        physicalDevice
                );
        args.set(0, d3d12Selection.extensions());
        args.set(2, streamlineSelection.features());
        StreamlineVulkanRequirements.rememberSelectedDeviceExtensions(
                d3d12Selection.extensions()
        );

        if (selection.rayQueryEnabled()) {
            DLSS_FG$LOGGER.info(
                    "Enabled Vulkan acceleration structures and hardware ray queries on {}",
                    physicalDevice.deviceName()
            );
        } else {
            DLSS_FG$LOGGER.info(
                    "Hardware ray queries are unavailable on {}; using normal raster rendering",
                    physicalDevice.deviceName()
            );
        }
        if (d3d12Selection.zeroCopyInteropPossible()) {
            DLSS_FG$LOGGER.info(
                    "Enabled Vulkan Win32 external-memory and external-semaphore interop on {}",
                    physicalDevice.deviceName()
            );
        } else if (!d3d12Selection.missingExtensions().isEmpty()) {
            DLSS_FG$LOGGER.info(
                    "Hybrid Vulkan/D3D12 presentation unavailable on {}: missing {}",
                    physicalDevice.deviceName(),
                    d3d12Selection.missingExtensions()
            );
        }
    }

    @ModifyArgs(
            method = "createDevice",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/vulkan/VulkanDevice;<init>(Lcom/mojang/blaze3d/shaders/ShaderSource;Lcom/mojang/blaze3d/vulkan/VulkanInstance;Lcom/mojang/blaze3d/vulkan/VulkanPhysicalDevice;Ljava/util/Set;Lorg/lwjgl/vulkan/VkDevice;JLcom/mojang/blaze3d/vulkan/checkpoints/CheckpointExtension;)V"
            )
    )
    private void dlss_fg$propagateActualDeviceExtensions(Args args) {
        Set<String> originalExtensions = args.get(3);
        args.set(
                3,
                StreamlineVulkanRequirements.consumeSelectedDeviceExtensions(
                        originalExtensions
                )
        );
    }
}
