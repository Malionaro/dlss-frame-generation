package org.malionaro.dlss_frame_generation.client.interop;
import org.malionaro.dlss_frame_generation.client.backend.FrameGenerationBackend;
import org.malionaro.dlss_frame_generation.client.StreamlineBridge;

import com.mojang.blaze3d.vulkan.VulkanBackend;
import com.mojang.blaze3d.vulkan.VulkanPhysicalDevice;
import com.mojang.blaze3d.vulkan.init.VulkanFeature;
import com.mojang.blaze3d.vulkan.init.VulkanPNextStruct;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures2;
import org.lwjgl.vulkan.VkPhysicalDeviceVulkan12Features;
import org.lwjgl.vulkan.VkPhysicalDeviceVulkan13Features;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

import static org.lwjgl.vulkan.VK11.vkGetPhysicalDeviceFeatures2;
import static org.lwjgl.vulkan.VK10.VK_VERSION_MAJOR;
import static org.lwjgl.vulkan.VK10.VK_VERSION_MINOR;
import static org.lwjgl.vulkan.VK10.VK_VERSION_PATCH;
import static org.lwjgl.vulkan.VK13.VK_API_VERSION_1_3;

/**
 * Applies the Vulkan instance/device requirements reported by the loaded
 * Streamline plugins before Minecraft creates its Vulkan objects.
 */
public final class StreamlineVulkanRequirements {
    private static final Logger LOGGER =
            LoggerFactory.getLogger("dlss_fg.streamline");
    private static final ThreadLocal<Set<String>> SELECTED_DEVICE_EXTENSIONS =
            new ThreadLocal<>();
    private static final VulkanPNextStruct VK13_FEATURES_STRUCT =
            new VulkanPNextStruct(
                    VkPhysicalDeviceVulkan13Features.STYPE,
                    VkPhysicalDeviceVulkan13Features.SIZEOF
            );

    private StreamlineVulkanRequirements() {
    }

    public static void addInstanceExtensions(
            Set<String> availableExtensions,
            Set<String> enabledExtensions
    ) {
        for (String extension : nativeRequirements(
                StreamlineBridge.VULKAN_REQUIREMENT_INSTANCE_EXTENSIONS)) {
            if (availableExtensions.contains(extension)) {
                enabledExtensions.add(extension);
            } else {
                LOGGER.warn(
                        "Streamline requires unavailable Vulkan instance extension {}",
                        extension
                );
            }
        }
        OptionalVulkanExtensions.applyInstanceOptionals(
                availableExtensions, enabledExtensions);
    }

    /** Optional instance extensions enabled during the last instance setup. */
    public static Set<String> lastOptionalInstanceExtensions() {
        return OptionalVulkanExtensions.lastInstanceOptionals();
    }

    /** Optional device extensions enabled during the last device selection. */
    public static Set<String> lastOptionalDeviceExtensions() {
        return OptionalVulkanExtensions.lastDeviceOptionals();
    }

    public static int preferredInstanceApiVersion(int minecraftVersion) {
        try {
            if (VK.getInstanceVersionSupported() >= VK_API_VERSION_1_3) {
                if (minecraftVersion < VK_API_VERSION_1_3) {
                    LOGGER.info(
                            "Requesting Vulkan 1.3 for Streamline instead of Minecraft's Vulkan {} instance API",
                            apiVersionString(minecraftVersion)
                    );
                }
                return VK_API_VERSION_1_3;
            }
            LOGGER.warn(
                    "The Vulkan loader only supports {}; retaining Minecraft's Vulkan {} instance API",
                    apiVersionString(VK.getInstanceVersionSupported()),
                    apiVersionString(minecraftVersion)
            );
            return minecraftVersion;
        } catch (RuntimeException unavailableLoader) {
            LOGGER.warn(
                    "Could not query the Vulkan loader version; retaining Minecraft's Vulkan API version",
                    unavailableLoader
            );
            return minecraftVersion;
        }
    }

    private static String apiVersionString(int version) {
        return VK_VERSION_MAJOR(version) + "."
                + VK_VERSION_MINOR(version) + "."
                + VK_VERSION_PATCH(version);
    }

    public static Selection selectDeviceRequirements(
            Collection<String> currentExtensions,
            VulkanPhysicalDevice physicalDevice,
            Set<VulkanFeature> currentFeatures
    ) {
        LinkedHashSet<String> extensions =
                new LinkedHashSet<>(currentExtensions);
        LinkedHashSet<VulkanFeature> features =
                new LinkedHashSet<>(currentFeatures);

        if (physicalDevice.vkPhysicalDeviceProperties().vendorID()
                != FrameGenerationBackend.GpuVendor.NVIDIA.vendorId()) {
            return new Selection(Set.copyOf(extensions), Set.copyOf(features));
        }

        for (String extension : nativeRequirements(
                StreamlineBridge.VULKAN_REQUIREMENT_DEVICE_EXTENSIONS)) {
            if (physicalDevice.hasDeviceExtension(extension)) {
                extensions.add(extension);
            } else {
                LOGGER.warn(
                        "Streamline requires unavailable Vulkan device extension {} on {}",
                        extension,
                        physicalDevice.deviceName()
                );
            }
        }

        // NVIDIA-only: offer VK_NV_low_latency2 when the driver exposes it so
        // Streamline 2.14 can drive Reflex through the extension instead of
        // NvAPI. Availability-gated; duplicates with native requirements are
        // absorbed by the set.
        LinkedHashSet<String> supportedOptionals = new LinkedHashSet<>();
        for (String extension : OptionalVulkanExtensions.NVIDIA_DEVICE_EXTENSIONS) {
            if (physicalDevice.hasDeviceExtension(extension)) {
                supportedOptionals.add(extension);
            }
        }
        OptionalVulkanExtensions.applyNvidiaDeviceOptionals(
                supportedOptionals, extensions);

        for (String featureName : nativeRequirements(
                StreamlineBridge.VULKAN_REQUIREMENT_FEATURES_12)) {
            VulkanFeature feature = vulkan12Feature(featureName);
            if (feature == null) {
                LOGGER.warn(
                        "Cannot map Streamline Vulkan 1.2 feature requirement {}",
                        featureName
                );
            } else if (isFeatureSupported(physicalDevice, feature)) {
                features.add(feature);
            } else {
                LOGGER.warn(
                        "Streamline requires unsupported Vulkan 1.2 feature {} on {}",
                        featureName,
                        physicalDevice.deviceName()
                );
            }
        }

        for (String featureName : nativeRequirements(
                StreamlineBridge.VULKAN_REQUIREMENT_FEATURES_13)) {
            VulkanFeature feature = vulkan13Feature(featureName);
            if (feature == null) {
                LOGGER.warn(
                        "Cannot map Streamline Vulkan 1.3 feature requirement {}",
                        featureName
                );
            } else if (isFeatureSupported(physicalDevice, feature)) {
                // Minecraft enables promoted features such as synchronization2
                // through their KHR extension structs. Vulkan forbids including
                // both the promoted core struct and the old feature struct for
                // the same field, so replace the old entry by name.
                features.removeIf(existing ->
                        existing.name().equals(featureName));
                features.add(feature);
            } else {
                LOGGER.warn(
                        "Streamline requires unsupported Vulkan 1.3 feature {} on {}",
                        featureName,
                        physicalDevice.deviceName()
                );
            }
        }

        return new Selection(Set.copyOf(extensions), Set.copyOf(features));
    }

    public static void rememberSelectedDeviceExtensions(
            Collection<String> extensions
    ) {
        SELECTED_DEVICE_EXTENSIONS.set(Set.copyOf(extensions));
    }

    public static Set<String> consumeSelectedDeviceExtensions(
            Set<String> fallback
    ) {
        Set<String> selected = SELECTED_DEVICE_EXTENSIONS.get();
        SELECTED_DEVICE_EXTENSIONS.remove();
        return selected != null ? selected : Set.copyOf(fallback);
    }

    private static String[] nativeRequirements(int kind) {
        try {
            String[] requirements =
                    StreamlineBridge.nGetVulkanRequirements(kind);
            if (requirements == null) {
                warnRequirementsUnknownOnce(
                        "native bridge returned null requirements");
                return new String[0];
            }
            return requirements;
        } catch (UnsatisfiedLinkError error) {
            // B10: an empty array here used to silently mean "no requirements".
            // Say loudly (once) that requirements are UNKNOWN so missing
            // extension/feature gates stay visible instead of fail-open quiet.
            warnRequirementsUnknownOnce(
                    "native bridge unavailable: " + error.getMessage());
            return new String[0];
        }
    }

    private static volatile boolean requirementsUnknownWarned;

    private static void warnRequirementsUnknownOnce(String reason) {
        if (!requirementsUnknownWarned) {
            requirementsUnknownWarned = true;
            LOGGER.warn(
                    "Streamline Vulkan requirements unknown ({}); "
                            + "continuing without provider requirements — "
                            + "frame generation stays gated on capabilities",
                    reason);
        }
    }

    private static VulkanFeature vulkan12Feature(String featureName) {
        return feature(
                featureName,
                VkPhysicalDeviceVulkan12Features.class,
                VulkanBackend.VK12_FEATURES_STRUCT
        );
    }

    private static VulkanFeature vulkan13Feature(String featureName) {
        return feature(
                featureName,
                VkPhysicalDeviceVulkan13Features.class,
                VK13_FEATURES_STRUCT
        );
    }

    private static VulkanFeature feature(
            String featureName,
            Class<?> featureClass,
            VulkanPNextStruct featureStruct
    ) {
        String normalized = featureName
                .replace("_", "")
                .toUpperCase(Locale.ROOT);
        for (Field field : featureClass.getFields()) {
            if (!Modifier.isStatic(field.getModifiers())
                    || !field.getName().equals(normalized)) {
                continue;
            }
            try {
                Object value = field.get(null);
                if (value instanceof Number offset) {
                    return new VulkanFeature(
                            featureStruct,
                            featureName,
                            offset.longValue()
                    );
                }
            } catch (IllegalAccessException ignored) {
                return null;
            }
        }
        return null;
    }

    private static boolean isFeatureSupported(
            VulkanPhysicalDevice physicalDevice,
            VulkanFeature feature
    ) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDeviceFeatures2 features =
                    VkPhysicalDeviceFeatures2.calloc(stack).sType$Default();
            feature.set(features, false, stack);
            vkGetPhysicalDeviceFeatures2(
                    physicalDevice.vkPhysicalDevice(),
                    features
            );
            return feature.get(features);
        }
    }

    public record Selection(
            Set<String> extensions,
            Set<VulkanFeature> features
    ) {
    }
}
