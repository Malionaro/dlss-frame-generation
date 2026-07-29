package org.malionaro.dlss_frame_generation.client.interop;

import com.mojang.blaze3d.vulkan.VulkanPhysicalDevice;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Vulkan device extensions required for zero-copy Win32 Vulkan/D3D12
 * resource and fence sharing.
 */
public final class D3D12InteropRequirements {
    public static final List<String> REQUIRED_DEVICE_EXTENSIONS = List.of(
            "VK_KHR_external_memory",
            "VK_KHR_external_memory_win32",
            "VK_KHR_external_semaphore",
            "VK_KHR_external_semaphore_win32"
    );

    private D3D12InteropRequirements() {
    }

    public static Selection select(
            Collection<String> currentExtensions,
            VulkanPhysicalDevice physicalDevice
    ) {
        LinkedHashSet<String> selected =
                new LinkedHashSet<>(currentExtensions);
        LinkedHashSet<String> missing = new LinkedHashSet<>();
        if (!isWindows()) {
            return new Selection(
                    Set.copyOf(selected),
                    Set.copyOf(REQUIRED_DEVICE_EXTENSIONS),
                    false
            );
        }
        for (String extension : REQUIRED_DEVICE_EXTENSIONS) {
            if (physicalDevice.hasDeviceExtension(extension)) {
                selected.add(extension);
            } else {
                missing.add(extension);
            }
        }
        return new Selection(
                Set.copyOf(selected),
                Set.copyOf(missing),
                missing.isEmpty()
        );
    }

    static Selection selectForTest(
            Collection<String> currentExtensions,
            Collection<String> supportedExtensions,
            boolean windows
    ) {
        LinkedHashSet<String> selected =
                new LinkedHashSet<>(currentExtensions);
        LinkedHashSet<String> missing = new LinkedHashSet<>();
        if (!windows) {
            missing.addAll(REQUIRED_DEVICE_EXTENSIONS);
        } else {
            for (String extension : REQUIRED_DEVICE_EXTENSIONS) {
                if (supportedExtensions.contains(extension)) {
                    selected.add(extension);
                } else {
                    missing.add(extension);
                }
            }
        }
        return new Selection(
                Set.copyOf(selected),
                Set.copyOf(missing),
                windows && missing.isEmpty()
        );
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "")
                .toLowerCase(java.util.Locale.ROOT)
                .contains("win");
    }

    public record Selection(
            Set<String> extensions,
            Set<String> missingExtensions,
            boolean zeroCopyInteropPossible
    ) {
    }
}
