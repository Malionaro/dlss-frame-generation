package org.malionaro.dlss_frame_generation.client.interop;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class D3D12ProviderRuntimeAuditTest {
    @Test
    void genericUpscalerDllsDoNotImplyFrameGeneration() {
        var fsr = D3D12ProviderRuntimeAudit.inspect(
                "FSR 4",
                List.of(
                        "amd_fidelityfx_dx12.dll",
                        "amd_fidelityfx_framegeneration_dx12.dll"
                ),
                Set.of(
                        "amd_fidelityfx_dx12.dll",
                        "amd_fidelityfx_upscaler_dx12.dll"
                )
        );
        var xess = D3D12ProviderRuntimeAudit.inspect(
                "XeSS-FG",
                List.of("libxess_fg.dll", "libxell.dll"),
                Set.of("libxess.dll")
        );

        assertFalse(fsr.complete());
        assertTrue(fsr.missingFiles().contains(
                "amd_fidelityfx_framegeneration_dx12.dll"));
        assertFalse(xess.complete());
        assertTrue(xess.missingFiles().contains("libxess_fg.dll"));
        assertTrue(xess.missingFiles().contains("libxell.dll"));
    }
}
