package org.malionaro.dlss_frame_generation;

import org.junit.jupiter.api.Test;
import org.malionaro.dlss_frame_generation.client.testing.FrameGenTestRunner;
import org.malionaro.dlss_frame_generation.client.FrameGenConfig;
import org.malionaro.dlss_frame_generation.client.FrameGenerationBackend;
import org.malionaro.dlss_frame_generation.client.FrameGenerationCompatibilityLayer;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class FrameGenUnitTest {

    @Test
    public void testAllFrameGenEngineDiagnostics() {
        List<FrameGenTestRunner.TestResult> results = FrameGenTestRunner.runAllDiagnostics();
        assertFalse(results.isEmpty(), "Diagnostic results should not be empty");
        
        for (FrameGenTestRunner.TestResult result : results) {
            // The JNI bridge is intentionally loaded by the Fabric client
            // lifecycle, not by the isolated Gradle unit-test JVM.
            if (result.name().equals("Native capability query")) {
                continue;
            }
            assertTrue(result.passed(), "Failed diagnostic test: " + result.name() + " - " + result.message());
        }
    }

    @Test
    public void sanitizesUnsafeNativeConfigurationValues() {
        FrameGenConfig config = new FrameGenConfig();
        config.activeVendorId = 12345;
        config.frameMultiplier = -4;
        config.amdFsrMultiplier = 99;
        config.intelXessMfgMultiplier = 0;
        config.reflexMode = 8;
        config.amdFsrMode = 4;
        config.intelXessMode = 3;
        config.dlssSharpness = Float.NaN;
        config.targetFps = 0;
        config.rayTracingMode = null;
        config.rayTracingQuality = null;
        config.rayTracingMaxDistance = 5000;
        config.rayTracingRaysPerPixel = 0;

        config.sanitize();

        assertEquals(0, config.activeVendorId);
        assertEquals(2, config.frameMultiplier);
        assertEquals(2, config.amdFsrMultiplier);
        assertEquals(2, config.intelXessMfgMultiplier);
        assertEquals(2, config.reflexMode);
        assertEquals(1, config.amdFsrMode);
        assertEquals(1, config.intelXessMode);
        assertEquals(0.0f, config.dlssSharpness);
        assertEquals(30, config.targetFps);
        assertEquals(FrameGenConfig.RayTracingMode.OFF, config.rayTracingMode);
        assertEquals(FrameGenConfig.RayTracingQuality.MEDIUM, config.rayTracingQuality);
        assertEquals(48, config.rayTracingMaxDistance);
        assertEquals(1, config.rayTracingRaysPerPixel);
    }

    @Test
    public void mapsVulkanVendorIdsWithoutRendererStringHeuristics() {
        assertEquals(FrameGenerationBackend.GpuVendor.NVIDIA,
                FrameGenerationBackend.GpuVendor.fromVendorId(0x10DE));
        assertEquals(FrameGenerationBackend.GpuVendor.AMD,
                FrameGenerationBackend.GpuVendor.fromVendorId(0x1002));
        assertEquals(FrameGenerationBackend.GpuVendor.INTEL,
                FrameGenerationBackend.GpuVendor.fromVendorId(0x8086));
        assertEquals(FrameGenerationBackend.GpuVendor.UNKNOWN,
                FrameGenerationBackend.GpuVendor.fromVendorId(0xFFFF));
    }

    @Test
    public void selectsExpandableVendorProvidersWithoutMislabelingIntelFallback() {
        var amdVulkan = FrameGenerationCompatibilityLayer.selectPreferredProvider(
                FrameGenerationBackend.GpuVendor.AMD,
                FrameGenerationCompatibilityLayer.RenderApi.VULKAN,
                true, false, false
        );
        var intelVulkan = FrameGenerationCompatibilityLayer.selectPreferredProvider(
                FrameGenerationBackend.GpuVendor.INTEL,
                FrameGenerationCompatibilityLayer.RenderApi.VULKAN,
                true, false, false
        );
        var amdD3d12 = FrameGenerationCompatibilityLayer.selectPreferredProvider(
                FrameGenerationBackend.GpuVendor.AMD,
                FrameGenerationCompatibilityLayer.RenderApi.D3D12,
                false, true, false
        );
        var intelD3d12 = FrameGenerationCompatibilityLayer.selectPreferredProvider(
                FrameGenerationBackend.GpuVendor.INTEL,
                FrameGenerationCompatibilityLayer.RenderApi.D3D12,
                false, false, true
        );

        assertEquals(FrameGenerationCompatibilityLayer.ProviderId.AMD_FSR31_VULKAN, amdVulkan);
        assertEquals(FrameGenerationCompatibilityLayer.ProviderId.CROSS_VENDOR_FSR31_VULKAN, intelVulkan);
        assertNotEquals(FrameGenerationCompatibilityLayer.ProviderId.INTEL_XESS_D3D12, intelVulkan);
        assertEquals(FrameGenerationCompatibilityLayer.ProviderId.AMD_FSR4_D3D12, amdD3d12);
        assertEquals(FrameGenerationCompatibilityLayer.ProviderId.INTEL_XESS_D3D12, intelD3d12);
    }
}
