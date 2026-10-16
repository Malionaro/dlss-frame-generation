package org.malionaro.dlss_frame_generation.client.compat;
import org.malionaro.dlss_frame_generation.client.render.adapter.RendererBackend;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RendererDetectorTest {
    @Test
    void sodiumOnActualVulkanDeviceUsesVulkanAdapterState() {
        RendererDetection detection = RendererDetector.classifyForTest(
                true, true, false, false, false, false
        );

        assertEquals(RendererBackend.SODIUM_VULKAN, detection.backend());
        assertTrue(detection.allowsVulkanFeatures());
        assertEquals(
                "Mojang VulkanDevice resources",
                detection.sodiumDrawBackend()
        );
    }

    @Test
    void irisVulkanWithoutShaderPackRemainsUsable() {
        RendererDetection detection = RendererDetector.classifyForTest(
                true, true, true, true, false, false
        );

        assertEquals(
                ShaderPipelineState.IRIS_VULKAN_SHADERPACK_OFF,
                detection.shaderPipelineState()
        );
        assertTrue(detection.allowsVulkanFeatures());
    }

    @Test
    void irisVulkanSnapshotVersionMeetsMinimum() {
        // First Iris build that runs shader packs on Vulkan.
        assertTrue(RendererDetector.isVersionAtLeast("1.12.0-snapshot+mc26.3-local", "1.11.2"));
        assertFalse(RendererDetector.isVersionAtLeast("1.11.1+mc26.3", "1.11.2"));
    }

    @Test
    void irisVulkanShaderPackCooperates() {
        RendererDetection detection = RendererDetector.classifyForTest(
                true, true, true, true, true, false
        );

        assertEquals(
                ShaderPipelineState.IRIS_VULKAN_SHADERPACK_ACTIVE,
                detection.shaderPipelineState()
        );
        assertTrue(detection.allowsVulkanFeatures());
        assertEquals("", detection.blockingReason());
    }

    @Test
    void irisOpenGlShaderPackFailsClosed() {
        RendererDetection detection = RendererDetector.classifyForTest(
                false, true, true, true, true, false
        );

        assertEquals(
                ShaderPipelineState.IRIS_OPENGL_SHADERPACK_ACTIVE,
                detection.shaderPipelineState()
        );
        assertFalse(detection.allowsVulkanFeatures());
    }

    @Test
    void aperturePresenceDoesNotClaimCompatibility() {
        RendererDetection detection = RendererDetector.classifyForTest(
                true, true, true, true, true, true
        );

        assertEquals(
                ShaderPipelineState.APERTURE_VULKAN_UNVERIFIED,
                detection.shaderPipelineState()
        );
        assertFalse(detection.allowsVulkanFeatures());
    }

    @Test
    void missingIrisApiFailsClosed() {
        RendererDetection detection = RendererDetector.classifyForTest(
                true, true, true, false, false, false
        );

        assertEquals(
                ShaderPipelineState.IRIS_API_UNAVAILABLE,
                detection.shaderPipelineState()
        );
        assertFalse(detection.allowsVulkanFeatures());
    }
}
