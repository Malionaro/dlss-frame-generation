package org.malionaro.dlss_frame_generation.client.mixin;

import com.mojang.renderpearl.backend.vulkan.VulkanBackend;
import com.mojang.renderpearl.backend.vulkan.VulkanPhysicalDevice;
import com.mojang.renderpearl.backend.vulkan.init.FeatureSet;
import org.malionaro.dlss_frame_generation.client.interop.D3D12InteropRequirements;
import org.malionaro.dlss_frame_generation.client.interop.StreamlineVulkanRequirements;
import org.malionaro.dlss_frame_generation.client.raytracing.RayTracingDeviceRequirements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

import java.util.Set;

/**
 * Adds optional Vulkan device requirements to Minecraft's logical device.
 *
 * <p>26.2 hooked {@code createDevice(Collection<String>, VulkanPhysicalDevice,
 * Set<VulkanFeature>)} and rewrote the extension and feature arguments in place.
 * 26.3 deleted that overload: requirements are now {@link FeatureSet} records, the
 * public {@code VulkanBackend.REQUIRED_DEVICE_EXTENSIONS} and feature-struct
 * constants moved to {@code VulkanFeatureSets}, and the surviving
 * {@code createDevice(FeatureSet, VulkanPhysicalDevice)} is private static.
 *
 * <p>The hook point is therefore the call site of that private method inside
 * {@code createDevice(GpuDebugOptions)}, which still hands over both the resolved
 * {@link FeatureSet} and the chosen {@link VulkanPhysicalDevice}. The selection
 * chain below is unchanged from 26.2 and still re-verifies support on the physical
 * device before requesting anything, so a device without ray query, without the
 * Win32 interop extensions or without Streamline's requirements simply does not get
 * them and the mod keeps rendering normally.
 */
@Mixin(value = VulkanBackend.class, remap = false)
public class VulkanBackendMixin {
    @Unique
    private static final Logger DLSS_FG$LOGGER =
            LoggerFactory.getLogger("DLSS_FG_RayTracing");

    @Unique
    private static final String DLSS_FG$FEATURESET_NAME = "dlss_fg_optional_device_features";

    @ModifyArgs(
            method = "createDevice(Lcom/mojang/renderpearl/api/device/GpuDebugOptions;)Lcom/mojang/renderpearl/api/device/GpuDevice;",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/renderpearl/backend/vulkan/VulkanBackend;createDevice(Lcom/mojang/renderpearl/backend/vulkan/init/FeatureSet;Lcom/mojang/renderpearl/backend/vulkan/VulkanPhysicalDevice;)Lorg/lwjgl/vulkan/VkDevice;"
            )
    )
    private void dlss_fg$enableOptionalDeviceFeatures(Args args) {
        FeatureSet resolved = args.get(0);
        VulkanPhysicalDevice physicalDevice = args.get(1);
        if (resolved == null || physicalDevice == null) {
            return;
        }

        RayTracingDeviceRequirements.Selection rayTracing =
                RayTracingDeviceRequirements.select(
                        resolved.extensions(),
                        physicalDevice,
                        resolved.features()
                );
        StreamlineVulkanRequirements.Selection streamline =
                StreamlineVulkanRequirements.selectDeviceRequirements(
                        rayTracing.extensions(),
                        physicalDevice,
                        rayTracing.features()
                );
        D3D12InteropRequirements.Selection interop =
                D3D12InteropRequirements.select(
                        streamline.extensions(),
                        physicalDevice
                );

        Set<String> extensions = interop.extensions();
        Set<com.mojang.renderpearl.backend.vulkan.init.VulkanFeature> features =
                streamline.features();
        args.set(0, new FeatureSet(
                DLSS_FG$FEATURESET_NAME,
                extensions,
                features
        ));

        if (rayTracing.rayQueryEnabled()) {
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
        if (interop.zeroCopyInteropPossible()) {
            DLSS_FG$LOGGER.info(
                    "Enabled Vulkan Win32 external-memory and external-semaphore interop on {}",
                    physicalDevice.deviceName()
            );
        } else if (!interop.missingExtensions().isEmpty()) {
            DLSS_FG$LOGGER.info(
                    "Hybrid Vulkan/D3D12 presentation unavailable on {}: missing {}",
                    physicalDevice.deviceName(),
                    interop.missingExtensions()
            );
        }
    }
}
