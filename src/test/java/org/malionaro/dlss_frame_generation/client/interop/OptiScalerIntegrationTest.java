package org.malionaro.dlss_frame_generation.client.interop;
import org.malionaro.dlss_frame_generation.client.backend.FrameGenerationBackend;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OptiScalerIntegrationTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void createsFailClosedMinecraftIntegrationConfiguration() throws Exception {
        OptiScalerIntegration.ensureIntegrationConfiguration(temporaryDirectory);

        String ini = Files.readString(
                temporaryDirectory.resolve("OptiScaler.ini"));
        assertTrue(ini.contains("EnableFfxInputs=true"));
        assertTrue(ini.contains("UseFfxInputs=true"));
        assertTrue(ini.contains("VulkanUpscaler=auto"));
        assertTrue(ini.contains("StreamlineSpoofing=false"));
        assertTrue(ini.contains("Dxgi=false"));
        assertTrue(ini.contains("OverrideNvapiDll=false"));
        assertTrue(ini.contains(
                "OptiDllPath=" + temporaryDirectory.toAbsolutePath().normalize()));
    }

    @Test
    void selectsNativeVulkanUpscalerForDetectedVendor() throws Exception {
        Path amdDirectory = temporaryDirectory.resolve("amd");
        Path intelDirectory = temporaryDirectory.resolve("intel");
        Files.createDirectories(amdDirectory);
        Files.createDirectories(intelDirectory);
        Files.writeString(
                amdDirectory.resolve("OptiScaler.ini"),
                "[Upscalers]\nVulkanUpscaler=auto\n"
        );
        Files.writeString(
                intelDirectory.resolve("OptiScaler.ini"),
                "[Upscalers]\nVulkanUpscaler=auto\n"
        );

        OptiScalerIntegration.ensureIntegrationConfiguration(
                amdDirectory,
                FrameGenerationBackend.GpuVendor.AMD
        );
        OptiScalerIntegration.ensureIntegrationConfiguration(
                intelDirectory,
                FrameGenerationBackend.GpuVendor.INTEL
        );

        assertTrue(Files.readString(amdDirectory.resolve("OptiScaler.ini"))
                .contains("VulkanUpscaler=fsr31"));
        assertTrue(Files.readString(intelDirectory.resolve("OptiScaler.ini"))
                .contains("VulkanUpscaler=xess"));
    }

    @Test
    void preservesExplicitSettingsAndOnlyResolvesAutoValues() throws Exception {
        Path iniPath = temporaryDirectory.resolve("OptiScaler.ini");
        Files.writeString(iniPath, """
                [Inputs]
                EnableFfxInputs=auto
                UseFfxInputs=false
                [Libraries]
                OptiDllPath=auto
                [Spoofing]
                StreamlineSpoofing=auto
                Dxgi=true
                [NvApi]
                OverrideNvapiDll=auto
                """);

        OptiScalerIntegration.ensureIntegrationConfiguration(temporaryDirectory);

        String ini = Files.readString(iniPath);
        assertTrue(ini.contains("EnableFfxInputs=true"));
        assertTrue(ini.contains("UseFfxInputs=false"));
        assertTrue(ini.contains("StreamlineSpoofing=false"));
        assertTrue(ini.contains("Dxgi=true"));
        assertTrue(ini.contains("OverrideNvapiDll=false"));
        assertEquals(1, ini.lines()
                .filter(line -> line.startsWith("OptiDllPath="))
                .count());
    }

    @Test
    void extractsCompleteVerifiedRuntimeFromJarResources() throws Exception {
        Path extracted = temporaryDirectory.resolve("embedded");

        OptiScalerIntegration.extractEmbeddedBundle(extracted);

        Path dll = extracted.resolve("OptiScaler.dll");
        assertTrue(Files.isRegularFile(dll));
        assertTrue(Files.isRegularFile(
                extracted.resolve("amd_fidelityfx_vk.dll")));
        assertTrue(Files.isRegularFile(extracted.resolve("libxess.dll")));
        assertTrue(Files.isRegularFile(
                extracted.resolve("D3D12_Optiscaler/D3D12Core.dll")));
        assertTrue(Files.isRegularFile(
                extracted.resolve("Licenses/XeSS_LICENSE.txt")));

        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(dll));
        assertEquals(
                "fbfb6676b829dad7e020fb830586a16aa0ec6add78016db48ef12e2ae1803231",
                HexFormat.of().formatHex(digest)
        );
    }
}
