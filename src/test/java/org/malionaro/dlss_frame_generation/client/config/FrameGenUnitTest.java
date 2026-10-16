package org.malionaro.dlss_frame_generation.client.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.malionaro.dlss_frame_generation.client.diagnostics.BackendStatusFormatter;
import org.malionaro.dlss_frame_generation.client.diagnostics.FrameGenTestRunner;
import org.malionaro.dlss_frame_generation.client.config.FrameGenConfig;
import org.malionaro.dlss_frame_generation.client.backend.FrameGenerationBackend;
import org.malionaro.dlss_frame_generation.client.backend.FrameGenerationCompatibilityLayer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class FrameGenUnitTest {

    @Test
    public void testAllFrameGenEngineDiagnostics() {
        List<FrameGenTestRunner.TestResult> results = FrameGenTestRunner.runAllDiagnostics();
        assertFalse(results.isEmpty(), "Diagnostic results should not be empty");
        assertTrue(results.stream().anyMatch(result ->
                result.name().equals("End-to-end frame generation")));
        assertFalse(
                FrameGenTestRunner.hardwarePathValidated(results),
                "An isolated unit-test JVM must never be reported as "
                        + "hardware-validated"
        );
    }

    // dlssSharpness is @Deprecated on purpose: old config files still carry
    // the field, so sanitize() must keep clamping it. Suppress the javac
    // deprecation warning exactly here instead of removing the coverage.
    @SuppressWarnings("deprecation")
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
        assertEquals(0, config.amdFsrMode);
        assertEquals(0, config.intelXessMode);
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
    public void frameGenerationProvidersAreMutuallyExclusive() {
        FrameGenConfig config = new FrameGenConfig();

        config.setFrameGenerationEnabled(
                FrameGenerationBackend.GpuVendor.NVIDIA, true
        );
        assertTrue(config.dlssFgEnabled);
        assertFalse(config.amdFsrFgEnabled);
        assertFalse(config.intelXessFgEnabled);

        config.setFrameGenerationEnabled(
                FrameGenerationBackend.GpuVendor.AMD, true
        );
        assertFalse(config.dlssFgEnabled);
        assertTrue(config.amdFsrFgEnabled);
        assertFalse(config.intelXessFgEnabled);

        config.setFrameGenerationEnabled(
                FrameGenerationBackend.GpuVendor.INTEL, true
        );
        assertFalse(config.dlssFgEnabled);
        assertFalse(config.amdFsrFgEnabled);
        assertTrue(config.intelXessFgEnabled);

        config.setFrameGenerationEnabled(
                FrameGenerationBackend.GpuVendor.INTEL, false
        );
        assertEquals(
                FrameGenerationBackend.GpuVendor.UNKNOWN,
                config.selectedFrameGenerationVendor()
        );
    }

    @Test
    public void hybridPresenterExcludesVulkanFrameGenerationProxies() {
        FrameGenConfig config = new FrameGenConfig();
        config.setHybridD3D12PresentationEnabled(true);

        assertFalse(config.dlssFgEnabled);
        assertFalse(config.amdFsrFgEnabled);
        assertFalse(config.intelXessFgEnabled);

        config.setFrameGenerationEnabled(
                FrameGenerationBackend.GpuVendor.NVIDIA, true);
        assertFalse(config.dlssFgEnabled);
    }

    @Test
    public void detectedGpuRemovesIncompatibleSavedProviders() {
        FrameGenConfig config = new FrameGenConfig();
        config.dlssFgEnabled = true;
        config.amdFsrFgEnabled = true;
        config.intelXessFgEnabled = true;

        assertTrue(config.retainFrameGenerationForVendor(
                FrameGenerationBackend.GpuVendor.AMD
        ));
        assertFalse(config.dlssFgEnabled);
        assertTrue(config.amdFsrFgEnabled);
        assertFalse(config.intelXessFgEnabled);
        assertEquals(
                FrameGenerationBackend.GpuVendor.AMD,
                config.selectedFrameGenerationVendor()
        );
    }

    @Test
    public void temporalUpscalersAreMutuallyExclusive() {
        FrameGenConfig config = new FrameGenConfig();
        config.setUpscalerMode(
                FrameGenerationBackend.GpuVendor.NVIDIA, 2
        );
        assertEquals(2, config.dlssUpscalingMode);

        config.setUpscalerMode(
                FrameGenerationBackend.GpuVendor.AMD, 3
        );
        assertEquals(0, config.dlssUpscalingMode);
        assertEquals(3, config.amdFsrMode);
        assertEquals(0, config.intelXessMode);

        config.setUpscalerMode(
                FrameGenerationBackend.GpuVendor.INTEL, 4
        );
        assertEquals(0, config.dlssUpscalingMode);
        assertEquals(0, config.amdFsrMode);
        assertEquals(4, config.intelXessMode);
        assertEquals(
                FrameGenerationBackend.GpuVendor.INTEL,
                config.selectedUpscalerVendor()
        );
    }

    @Test
    public void ambiguousAutoDetectedFrameGeneratorsAreDisabled() {
        FrameGenConfig config = new FrameGenConfig();
        config.activeVendorId = 0;
        config.dlssFgEnabled = true;
        config.amdFsrFgEnabled = true;
        config.intelXessFgEnabled = true;

        config.sanitize();

        // Auto mode prefers NVIDIA instead of turning everything off.
        assertTrue(config.dlssFgEnabled);
        assertFalse(config.amdFsrFgEnabled);
        assertFalse(config.intelXessFgEnabled);
    }

    @Test
    public void convertsNativeProviderErrorsIntoReadableStatusKinds() {
        assertEquals(
                BackendStatusFormatter.StatusKind.NO_SUPPORTED_ADAPTER,
                BackendStatusFormatter.classify(
                        "DLSS-G=Result::eErrorNoSupportedAdapterFound",
                        false,
                        false
                )
        );
        assertEquals(
                BackendStatusFormatter.StatusKind.D3D12_REQUIRED,
                BackendStatusFormatter.classify(
                        "XeSS-FG needs D3D12; Vulkan can use FSR 3.1",
                        false,
                        false
                )
        );
        assertEquals(
                BackendStatusFormatter.StatusKind.INPUTS_PENDING,
                BackendStatusFormatter.classify(
                        "Dense GPU inputs ready; native provider did not accept this frame",
                        true,
                        false
                )
        );
        assertEquals(
                BackendStatusFormatter.StatusKind.ACTIVE,
                BackendStatusFormatter.classify("anything", true, true)
        );
        assertFalse(BackendStatusFormatter.isUsable(
                "DLSS-G=Result::eErrorNoSupportedAdapterFound",
                false
        ));
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

    @Test
    public void configRoundtripHandlesCorruptJsonWithBackup(
            @TempDir Path tempDir) throws Exception {
        Path configPath = tempDir.resolve("frame_gen.json");
        Path backupPath = tempDir.resolve("frame_gen.json.bak");
        FrameGenConfig.overrideConfigPathForTesting(configPath);
        FrameGenConfig.resetInstanceForTesting();
        try {
            // Write corrupt JSON
            Files.writeString(configPath, "{ invalid json ::: }");
            // Direct call, no reflection (load is package-visible for tests)
            FrameGenConfig loaded = FrameGenConfig.load();
            assertNotNull(loaded, "Corrupt config should fallback to default instance");
            // Defaults must be sanitized (schema v3: explicit provider-choice flag)
            assertEquals(3, loaded.configVersion);
            assertEquals(FrameGenConfig.Preset.CUSTOM, loaded.activePreset);
            // Backup must preserve the corrupt payload next to the rewritten file
            assertTrue(Files.exists(configPath), "Config path should be rewritten to valid JSON after corrupt recovery");
            assertTrue(Files.exists(backupPath), "Corrupt payload must be backed up");
            String backup = Files.readString(backupPath);
            assertTrue(backup.contains("invalid json"), "Backup should preserve corrupt payload");
        } finally {
            FrameGenConfig.clearConfigPathOverrideForTesting();
            FrameGenConfig.resetInstanceForTesting();
        }
    }

    @Test
    public void freshConfigHasAllProvidersOffUntilDetectionChooses() {
        // No ambiguous all-on state on fresh installs.
        FrameGenConfig config = new FrameGenConfig();
        assertFalse(config.dlssFgEnabled);
        assertFalse(config.amdFsrFgEnabled);
        assertFalse(config.intelXessFgEnabled);
        assertFalse(config.userHasChosenProvider);
        assertEquals(
                FrameGenerationBackend.GpuVendor.UNKNOWN,
                config.selectedFrameGenerationVendor()
        );
    }

    @Test
    public void explicitChoiceMarksProviderChosen() {
        FrameGenConfig config = new FrameGenConfig();
        config.setFrameGenerationEnabled(
                FrameGenerationBackend.GpuVendor.AMD, true
        );
        assertTrue(config.userHasChosenProvider);
        assertTrue(config.amdFsrFgEnabled);
    }

    @Test
    public void sanitizeKeepsChoiceMarkerUnset() {
        // Internal conflict resolution must not look like a user choice,
        // otherwise first-run auto-enable would never fire.
        FrameGenConfig config = new FrameGenConfig();
        config.amdFsrMode = 4;
        config.sanitize();
        assertFalse(config.userHasChosenProvider);
    }

    @Test
    public void vulkanUpscalerModesAboveNativeAaFailClosedToOff() {
        // Only 0=Off and 1=Native-AA are real Vulkan modes.
        FrameGenConfig config = new FrameGenConfig();
        config.amdFsrMode = 4;
        config.intelXessMode = 3;
        config.sanitize();
        assertEquals(0, config.amdFsrMode);
        assertEquals(0, config.intelXessMode);

        config.amdFsrMode = 1;
        config.intelXessMode = 0;
        config.sanitize();
        assertEquals(1, config.amdFsrMode);
        assertEquals(0, config.intelXessMode);

        config.amdFsrMode = 0;
        config.intelXessMode = 1;
        config.sanitize();
        assertEquals(0, config.amdFsrMode);
        assertEquals(1, config.intelXessMode);
    }
}
