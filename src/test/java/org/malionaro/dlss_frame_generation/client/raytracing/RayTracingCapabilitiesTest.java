package org.malionaro.dlss_frame_generation.client.raytracing;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RayTracingCapabilitiesTest {
    private static final Set<String> RAY_QUERY_EXTENSIONS = Set.of(
            RayTracingCapabilities.ACCELERATION_STRUCTURE,
            RayTracingCapabilities.RAY_QUERY,
            RayTracingCapabilities.DEFERRED_HOST_OPERATIONS,
            RayTracingCapabilities.RAY_TRACING_PIPELINE
    );

    @Test
    void distinguishesUnsupportedHardwareFromDisabledDeviceExtensions() {
        RayTracingCapabilities unsupported = RayTracingCapabilities.fromExtensionSets(
                "Test GPU", 0x10DE, Set.of(), Set.of(), false);
        assertEquals(RayTracingCapabilities.State.UNSUPPORTED, unsupported.state());
        assertFalse(unsupported.hardwareSupported());

        RayTracingCapabilities notEnabled = RayTracingCapabilities.fromExtensionSets(
                "Test GPU", 0x10DE, RAY_QUERY_EXTENSIONS, Set.of(), false);
        assertEquals(RayTracingCapabilities.State.EXTENSIONS_NOT_ENABLED, notEnabled.state());
        assertTrue(notEnabled.hardwareSupported());
        assertFalse(notEnabled.extensionsEnabled());
    }

    @Test
    void keepsControlsGatedUntilTheRendererBackendExists() {
        RayTracingCapabilities pending = RayTracingCapabilities.fromExtensionSets(
                "Test GPU", 0x10DE, RAY_QUERY_EXTENSIONS, RAY_QUERY_EXTENSIONS, false);
        assertEquals(RayTracingCapabilities.State.BACKEND_PENDING, pending.state());
        assertTrue(pending.rayQuerySupported());
        assertTrue(pending.rayTracingPipelineSupported());
        assertTrue(pending.extensionsEnabled());
        assertFalse(pending.backendReady());

        RayTracingCapabilities ready = RayTracingCapabilities.fromExtensionSets(
                "Test GPU", 0x10DE, RAY_QUERY_EXTENSIONS, RAY_QUERY_EXTENSIONS, true);
        assertEquals(RayTracingCapabilities.State.READY, ready.state());
        assertTrue(ready.backendReady());
    }
}
