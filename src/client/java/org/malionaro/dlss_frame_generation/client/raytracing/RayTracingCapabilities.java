package org.malionaro.dlss_frame_generation.client.raytracing;

import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanPhysicalDevice;
import org.malionaro.dlss_frame_generation.client.access.VulkanDeviceAccess;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Immutable hardware/capability report for the future Vulkan ray-tracing path.
 * Hardware support, enabled device extensions and renderer readiness are kept
 * separate so the UI never advertises a feature that cannot render yet.
 */
public record RayTracingCapabilities(
        State state,
        String deviceName,
        int vendorId,
        Set<String> availableExtensions,
        Set<String> enabledExtensions,
        boolean rayQuerySupported,
        boolean rayTracingPipelineSupported,
        String statusMessage
) {
    public static final String ACCELERATION_STRUCTURE = "VK_KHR_acceleration_structure";
    public static final String RAY_QUERY = "VK_KHR_ray_query";
    public static final String DEFERRED_HOST_OPERATIONS = "VK_KHR_deferred_host_operations";
    public static final String RAY_TRACING_PIPELINE = "VK_KHR_ray_tracing_pipeline";
    public static final String PIPELINE_LIBRARY = "VK_KHR_pipeline_library";

    private static final Set<String> REQUIRED_RAY_QUERY_EXTENSIONS = Set.of(
            ACCELERATION_STRUCTURE,
            RAY_QUERY,
            DEFERRED_HOST_OPERATIONS
    );

    public enum State {
        WAITING_FOR_DEVICE,
        UNSUPPORTED,
        EXTENSIONS_NOT_ENABLED,
        BACKEND_PENDING,
        READY
    }

    public RayTracingCapabilities {
        availableExtensions = Set.copyOf(availableExtensions);
        enabledExtensions = Set.copyOf(enabledExtensions);
    }

    public static RayTracingCapabilities waitingForDevice() {
        return new RayTracingCapabilities(
                State.WAITING_FOR_DEVICE,
                "No Vulkan device",
                0,
                Set.of(),
                Set.of(),
                false,
                false,
                "Waiting for the Vulkan device"
        );
    }

    public static RayTracingCapabilities detect(VulkanDevice device, boolean rendererBackendImplemented) {
        if (!(device instanceof VulkanDeviceAccess access)
                || access.dlss_fg$getPhysicalDevice() == null) {
            return waitingForDevice();
        }

        VulkanPhysicalDevice physicalDevice = access.dlss_fg$getPhysicalDevice();
        Set<String> available = new LinkedHashSet<>();
        for (String extension : Set.of(
                ACCELERATION_STRUCTURE,
                RAY_QUERY,
                DEFERRED_HOST_OPERATIONS,
                RAY_TRACING_PIPELINE,
                PIPELINE_LIBRARY
        )) {
            if (physicalDevice.hasDeviceExtension(extension)) {
                available.add(extension);
            }
        }

        return fromExtensionSets(
                physicalDevice.deviceName(),
                physicalDevice.vkPhysicalDeviceProperties().vendorID(),
                available,
                access.dlss_fg$getEnabledExtensions(),
                rendererBackendImplemented
        );
    }

    static RayTracingCapabilities fromExtensionSets(
            String deviceName,
            int vendorId,
            Set<String> availableExtensions,
            Set<String> enabledExtensions,
            boolean rendererBackendImplemented
    ) {
        boolean rayQueryAvailable = availableExtensions.containsAll(REQUIRED_RAY_QUERY_EXTENSIONS);
        boolean pipelineAvailable = rayQueryAvailable
                && availableExtensions.contains(RAY_TRACING_PIPELINE);

        if (!rayQueryAvailable) {
            Set<String> missing = new LinkedHashSet<>(REQUIRED_RAY_QUERY_EXTENSIONS);
            missing.removeAll(availableExtensions);
            return new RayTracingCapabilities(
                    State.UNSUPPORTED,
                    deviceName,
                    vendorId,
                    availableExtensions,
                    enabledExtensions,
                    false,
                    pipelineAvailable,
                    "Hardware RT unavailable; missing " + String.join(", ", missing)
            );
        }

        if (!enabledExtensions.containsAll(REQUIRED_RAY_QUERY_EXTENSIONS)) {
            return new RayTracingCapabilities(
                    State.EXTENSIONS_NOT_ENABLED,
                    deviceName,
                    vendorId,
                    availableExtensions,
                    enabledExtensions,
                    true,
                    pipelineAvailable,
                    "RT hardware found; Vulkan RT extensions are not enabled"
            );
        }

        if (!rendererBackendImplemented) {
            return new RayTracingCapabilities(
                    State.BACKEND_PENDING,
                    deviceName,
                    vendorId,
                    availableExtensions,
                    enabledExtensions,
                    true,
                    pipelineAvailable,
                    "RT hardware found; scene acceleration backend is pending"
            );
        }

        return new RayTracingCapabilities(
                State.READY,
                deviceName,
                vendorId,
                availableExtensions,
                enabledExtensions,
                true,
                pipelineAvailable,
                "Hardware ray tracing ready"
        );
    }

    public boolean hardwareSupported() {
        return state != State.WAITING_FOR_DEVICE && state != State.UNSUPPORTED;
    }

    public boolean extensionsEnabled() {
        return state == State.BACKEND_PENDING || state == State.READY;
    }

    public boolean backendReady() {
        return state == State.READY;
    }
}
