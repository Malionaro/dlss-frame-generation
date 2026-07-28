package org.malionaro.dlss_frame_generation.client.raytracing;

import com.mojang.blaze3d.vulkan.VulkanPhysicalDevice;
import com.mojang.blaze3d.vulkan.VulkanBackend;
import com.mojang.blaze3d.vulkan.init.VulkanFeature;
import com.mojang.blaze3d.vulkan.init.VulkanPNextStruct;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkPhysicalDeviceAccelerationStructureFeaturesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures2;
import org.lwjgl.vulkan.VkPhysicalDeviceRayQueryFeaturesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceVulkan12Features;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_KHR_ACCELERATION_STRUCTURE_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRDeferredHostOperations.VK_KHR_DEFERRED_HOST_OPERATIONS_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRRayQuery.VK_KHR_RAY_QUERY_EXTENSION_NAME;
import static org.lwjgl.vulkan.VK11.vkGetPhysicalDeviceFeatures2;

/**
 * Selects the optional Vulkan features needed by the hybrid ray-query renderer.
 *
 * <p>The requirements are deliberately optional: a machine without hardware
 * ray tracing must still be able to create Minecraft's normal Vulkan device.</p>
 */
public final class RayTracingDeviceRequirements {
    private static final VulkanPNextStruct ACCELERATION_STRUCTURE_FEATURES =
            new VulkanPNextStruct(
                    VkPhysicalDeviceAccelerationStructureFeaturesKHR.STYPE,
                    VkPhysicalDeviceAccelerationStructureFeaturesKHR.SIZEOF
            );
    private static final VulkanPNextStruct RAY_QUERY_FEATURES =
            new VulkanPNextStruct(
                    VkPhysicalDeviceRayQueryFeaturesKHR.STYPE,
                    VkPhysicalDeviceRayQueryFeaturesKHR.SIZEOF
            );

    private static final VulkanFeature ACCELERATION_STRUCTURE =
            new VulkanFeature(
                    ACCELERATION_STRUCTURE_FEATURES,
                    "accelerationStructure",
                    VkPhysicalDeviceAccelerationStructureFeaturesKHR.ACCELERATIONSTRUCTURE
            );
    private static final VulkanFeature RAY_QUERY =
            new VulkanFeature(
                    RAY_QUERY_FEATURES,
                    "rayQuery",
                    VkPhysicalDeviceRayQueryFeaturesKHR.RAYQUERY
            );
    private static final VulkanFeature BUFFER_DEVICE_ADDRESS =
            new VulkanFeature(
                    VulkanBackend.VK12_FEATURES_STRUCT,
                    "bufferDeviceAddress",
                    VkPhysicalDeviceVulkan12Features.BUFFERDEVICEADDRESS
            );

    private static final Set<String> EXTENSIONS = Set.of(
            VK_KHR_ACCELERATION_STRUCTURE_EXTENSION_NAME,
            VK_KHR_RAY_QUERY_EXTENSION_NAME,
            VK_KHR_DEFERRED_HOST_OPERATIONS_EXTENSION_NAME
    );
    private static final Set<VulkanFeature> FEATURES = Set.of(
            ACCELERATION_STRUCTURE,
            RAY_QUERY,
            BUFFER_DEVICE_ADDRESS
    );

    private RayTracingDeviceRequirements() {
    }

    public static Selection select(
            Collection<String> currentExtensions,
            VulkanPhysicalDevice physicalDevice,
            Set<VulkanFeature> currentFeatures
    ) {
        LinkedHashSet<String> extensions = new LinkedHashSet<>(currentExtensions);
        LinkedHashSet<VulkanFeature> features = new LinkedHashSet<>(currentFeatures);

        boolean extensionsAvailable = EXTENSIONS.stream()
                .allMatch(physicalDevice::hasDeviceExtension);
        boolean featureBitsAvailable = extensionsAvailable
                && queryFeatureBits(physicalDevice);
        if (featureBitsAvailable) {
            extensions.addAll(EXTENSIONS);
            features.addAll(FEATURES);
        }

        return new Selection(
                Set.copyOf(extensions),
                Set.copyOf(features),
                featureBitsAvailable
        );
    }

    private static boolean queryFeatureBits(VulkanPhysicalDevice physicalDevice) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDeviceRayQueryFeaturesKHR rayQuery =
                    VkPhysicalDeviceRayQueryFeaturesKHR.calloc(stack)
                            .sType$Default();
            VkPhysicalDeviceAccelerationStructureFeaturesKHR accelerationStructure =
                    VkPhysicalDeviceAccelerationStructureFeaturesKHR.calloc(stack)
                            .sType$Default()
                            .pNext(rayQuery.address());
            VkPhysicalDeviceVulkan12Features vulkan12 =
                    VkPhysicalDeviceVulkan12Features.calloc(stack)
                            .sType$Default()
                            .pNext(accelerationStructure.address());
            VkPhysicalDeviceFeatures2 features2 = VkPhysicalDeviceFeatures2.calloc(stack)
                    .sType$Default()
                    .pNext(vulkan12.address());

            vkGetPhysicalDeviceFeatures2(physicalDevice.vkPhysicalDevice(), features2);
            return vulkan12.bufferDeviceAddress()
                    && accelerationStructure.accelerationStructure()
                    && rayQuery.rayQuery();
        }
    }

    public record Selection(
            Set<String> extensions,
            Set<VulkanFeature> features,
            boolean rayQueryEnabled
    ) {
    }
}
