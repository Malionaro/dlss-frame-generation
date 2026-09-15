package org.malionaro.dlss_frame_generation.client.interop;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Optional Vulkan extensions beyond the native Streamline requirements.
 * Everything here is strictly opt-in by availability: an extension is only
 * ever added when the loader or driver reports it, so a missing optional can
 * never fail device creation. Deliberately free of Minecraft/LWJGL types so
 * the selection logic stays unit-testable.
 */
final class OptionalVulkanExtensions {
    private static final Logger LOGGER =
            LoggerFactory.getLogger("dlss_fg.streamline");

    /**
     * Optional instance extensions. {@code VK_EXT_debug_utils} lets
     * Streamline 2.12+ annotate SL/NGX workloads for Nsight Graphics and
     * RenderDoc profiling markers.
     */
    static final Set<String> INSTANCE_EXTENSIONS = Set.of(
            "VK_EXT_debug_utils"
    );

    /**
     * Optional device extensions for the NVIDIA route. Streamline 2.14 drives
     * Vulkan Reflex through {@code VK_NV_low_latency2} whenever the device
     * exposes it and falls back to the NvAPI path otherwise, so exposing it
     * can only upgrade the Reflex transport, never break it.
     */
    static final Set<String> NVIDIA_DEVICE_EXTENSIONS = Set.of(
            "VK_NV_low_latency2"
    );

    private static volatile Set<String> lastInstanceOptionals = Set.of();
    private static volatile Set<String> lastDeviceOptionals = Set.of();

    private OptionalVulkanExtensions() {
    }

    static Set<String> applyInstanceOptionals(
            Set<String> availableExtensions,
            Set<String> enabledExtensions
    ) {
        lastInstanceOptionals = applyOptionalExtensions(
                availableExtensions, enabledExtensions,
                INSTANCE_EXTENSIONS, "instance");
        return lastInstanceOptionals;
    }

    static Set<String> applyNvidiaDeviceOptionals(
            Set<String> supportedExtensions,
            Set<String> enabledExtensions
    ) {
        lastDeviceOptionals = applyOptionalExtensions(
                supportedExtensions, enabledExtensions,
                NVIDIA_DEVICE_EXTENSIONS, "device");
        return lastDeviceOptionals;
    }

    /**
     * Adds every available optional extension to the enabled set and returns
     * the ones actually added. Unavailable optionals are silently skipped and
     * the enabled set is otherwise untouched.
     */
    static Set<String> applyOptionalExtensions(
            Set<String> availableExtensions,
            Set<String> enabledExtensions,
            Set<String> optionalExtensions,
            String scope
    ) {
        LinkedHashSet<String> added = new LinkedHashSet<>();
        for (String extension : optionalExtensions) {
            if (!availableExtensions.contains(extension)) {
                continue;
            }
            if (enabledExtensions.add(extension)) {
                added.add(extension);
                LOGGER.info(
                        "Enabling optional Vulkan {} extension {}",
                        scope, extension
                );
            }
        }
        return Set.copyOf(added);
    }

    /** Optional instance extensions enabled during the last instance setup. */
    static Set<String> lastInstanceOptionals() {
        return lastInstanceOptionals;
    }

    /** Optional device extensions enabled during the last device selection. */
    static Set<String> lastDeviceOptionals() {
        return lastDeviceOptionals;
    }
}
