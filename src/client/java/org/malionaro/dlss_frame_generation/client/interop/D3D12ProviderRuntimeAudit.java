package org.malionaro.dlss_frame_generation.client.interop;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Verifies the concrete DLL set required by the D3D12-only frame-generation
 * providers. A generic FidelityFX loader or the XeSS super-resolution DLL is
 * deliberately not treated as proof that frame generation is present.
 */
public final class D3D12ProviderRuntimeAudit {
    public record RuntimeStatus(
            String provider,
            boolean complete,
            List<String> missingFiles,
            String detail
    ) {
        public RuntimeStatus {
            missingFiles = List.copyOf(missingFiles);
        }
    }

    private static final List<String> FSR4_REQUIRED = List.of(
            "amd_fidelityfx_dx12.dll",
            "amd_fidelityfx_framegeneration_dx12.dll"
    );
    private static final List<String> XESS_FG_REQUIRED = List.of(
            "libxess_fg.dll",
            "libxell.dll"
    );

    private D3D12ProviderRuntimeAudit() {
    }

    public static RuntimeStatus fsr4FrameGeneration() {
        return inspect("AMD FSR 4 Frame Generation", FSR4_REQUIRED);
    }

    public static RuntimeStatus xessFrameGeneration() {
        return inspect("Intel XeSS Frame Generation", XESS_FG_REQUIRED);
    }

    static RuntimeStatus inspect(
            String provider,
            List<String> requiredFiles,
            Set<String> availableFiles
    ) {
        List<String> missing = requiredFiles.stream()
                .filter(file -> !availableFiles.contains(file))
                .toList();
        return result(provider, missing);
    }

    private static RuntimeStatus inspect(
            String provider,
            List<String> requiredFiles
    ) {
        List<String> missing = new ArrayList<>();
        for (String file : requiredFiles) {
            if (!available(file)) {
                missing.add(file);
            }
        }
        return result(provider, missing);
    }

    private static RuntimeStatus result(
            String provider,
            List<String> missing
    ) {
        if (missing.isEmpty()) {
            return new RuntimeStatus(
                    provider,
                    true,
                    List.of(),
                    "All required provider DLLs are present"
            );
        }
        return new RuntimeStatus(
                provider,
                false,
                missing,
                "Not available: missing " + String.join(", ", missing)
        );
    }

    private static boolean available(String file) {
        if (D3D12ProviderRuntimeAudit.class.getResource(
                "/optiscaler/windows/" + file) != null) {
            return true;
        }
        Path optiScalerDirectory = OptiScalerIntegration.installDirectory();
        if (Files.isRegularFile(optiScalerDirectory.resolve(file))) {
            return true;
        }
        String extracted = NativeLoader.getExtractedNativesDir();
        return !extracted.isBlank()
                && Files.isRegularFile(
                        Path.of(extracted).resolve("optiscaler").resolve(file));
    }
}
