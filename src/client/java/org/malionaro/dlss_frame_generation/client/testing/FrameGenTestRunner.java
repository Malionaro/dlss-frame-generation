package org.malionaro.dlss_frame_generation.client.testing;

import org.joml.Matrix4f;
import org.malionaro.dlss_frame_generation.client.BackendStatusFormatter;
import org.malionaro.dlss_frame_generation.client.FrameGenerationBackend;
import org.malionaro.dlss_frame_generation.client.FrameGenerationCompatibilityLayer;
import org.malionaro.dlss_frame_generation.client.ReflexPacer;
import org.malionaro.dlss_frame_generation.client.StreamlineBridge;
import org.malionaro.dlss_frame_generation.client.VulkanRegistry;
import org.malionaro.dlss_frame_generation.client.motion.OpticalFlowArtifactCorrector;
import org.malionaro.dlss_frame_generation.client.vulkan.UltrawideLensCorrector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FrameGenTestRunner {
    private static final Logger LOGGER = LoggerFactory.getLogger("FrameGen_SelfTest");

    public record BackendCheck(
            FrameGenerationBackend.GpuVendor vendor,
            int capabilities,
            String nativeStatus,
            boolean selected
    ) {}

    public record TestResult(
            String name,
            boolean passed,
            String message,
            java.util.List<BackendCheck> backendChecks
    ) {
        public TestResult {
            backendChecks = java.util.List.copyOf(backendChecks);
        }

        public TestResult(String name, boolean passed, String message) {
            this(name, passed, message, java.util.List.of());
        }
    }

    public static java.util.List<TestResult> runAllDiagnostics() {
        java.util.List<TestResult> results = new java.util.ArrayList<>();

        // Test 1: Read-only native capability query. Diagnostics must not apply
        // presets or mutate the user's saved configuration.
        try {
            FrameGenerationCompatibilityLayer.ProviderId activeProvider =
                    FrameGenerationCompatibilityLayer.ProviderId.fromNativeId(
                            StreamlineBridge.nGetActiveProviderId()
                    );
            FrameGenerationBackend.GpuVendor activeVendor =
                    vendorForProvider(activeProvider);
            StringBuilder details = new StringBuilder(
                    "active=").append(StreamlineBridge.nGetActiveProviderName());
            java.util.List<BackendCheck> backendChecks =
                    new java.util.ArrayList<>();
            for (FrameGenerationBackend.GpuVendor vendor :
                    new FrameGenerationBackend.GpuVendor[] {
                            FrameGenerationBackend.GpuVendor.NVIDIA,
                            FrameGenerationBackend.GpuVendor.AMD,
                            FrameGenerationBackend.GpuVendor.INTEL
                    }) {
                int capabilities = vendor == FrameGenerationBackend.GpuVendor.NVIDIA
                        ? StreamlineBridge.nGetBackendSupportedCapabilities(
                                vendor.vendorId()
                        )
                        : StreamlineBridge.nGetBackendCapabilities(
                                vendor.vendorId()
                        );
                String status = StreamlineBridge.nGetBackendStatus(
                        vendor.vendorId());
                backendChecks.add(new BackendCheck(
                        vendor,
                        capabilities,
                        status,
                        vendor == activeVendor
                ));
                details.append(" | ")
                        .append(vendor.name())
                        .append("=0x")
                        .append(Integer.toHexString(capabilities))
                        .append(" (")
                        .append(status)
                        .append(')');
            }
            boolean valid = backendChecks.stream()
                    .filter(BackendCheck::selected)
                    .anyMatch(check -> BackendStatusFormatter.isUsable(
                            check.nativeStatus(),
                            check.capabilities() != 0
                    ));
            results.add(new TestResult(
                    "Native capability query",
                    valid,
                    details.toString(),
                    backendChecks
            ));
        } catch (Throwable t) {
            results.add(new TestResult("Native capability query", false, t.getMessage()));
        }

        // Test 2: Runtime renderer-input pipeline. An uninitialized pipeline is
        // informational outside a loaded world; a disabled pipeline is a real
        // diagnostic failure.
        try {
            String status = VulkanRegistry.getFrameGenerationPipelineStatus();
            boolean passed = status != null
                    && !status.toLowerCase(java.util.Locale.ROOT).contains("disabled");
            results.add(new TestResult(
                    "Renderer input pipeline",
                    passed,
                    status
                            + " | captured=" + VulkanRegistry.getCapturedFrameCount()
                            + ", accepted=" + VulkanRegistry.getProviderAcceptedFrameCount()
                            + ", presented=" + VulkanRegistry.getCompletedPresentCount()
            ));
        } catch (Throwable t) {
            results.add(new TestResult("Renderer input pipeline", false, t.getMessage()));
        }

        // Test 3: Reflex Pacer Moving Average Math
        try {
            double initialMs = ReflexPacer.getAverageFrameTimeMs();
            ReflexPacer.paceFrame();
            double postMs = ReflexPacer.getAverageFrameTimeMs();
            boolean passed = (postMs > 0.0);
            results.add(new TestResult("Reflex Pacer Latency Tracker", passed, String.format("Calculated pace latency: %.2f ms", postMs)));
        } catch (Throwable t) {
            results.add(new TestResult("Reflex Pacer Latency Tracker", false, t.getMessage()));
        }

        // Test 4: AI Optical Flow Artifact Corrector Bounds
        try {
            OpticalFlowArtifactCorrector.updateCameraRotation(0, 0, 1);
            float f1 = OpticalFlowArtifactCorrector.getDampeningFactor();
            OpticalFlowArtifactCorrector.updateCameraRotation(1, 0, 0); // Fast 90 degree turn
            float f2 = OpticalFlowArtifactCorrector.getDampeningFactor();
            boolean passed = (f1 >= f2 && f2 >= 0.2f && f2 <= 1.0f);
            results.add(new TestResult("AI Optical Flow Artifact Corrector", passed, String.format("Dampening factor on fast turn: %.2f", f2)));
        } catch (Throwable t) {
            results.add(new TestResult("AI Optical Flow Artifact Corrector", false, t.getMessage()));
        }

        // Test 5: Ultrawide Lens Corrector Matrix Transform
        try {
            Matrix4f mat = new Matrix4f().identity();
            float initialM00 = mat.m00();
            UltrawideLensCorrector.applyLensCorrection(mat, 3.555f); // 32:9 Super Ultrawide
            boolean passed = (mat.m00() < initialM00);
            results.add(new TestResult("Ultrawide Lens Distortion Corrector", passed, String.format("32:9 X-compression: %.4f -> %.4f", initialM00, mat.m00())));
        } catch (Throwable t) {
            results.add(new TestResult("Ultrawide Lens Distortion Corrector", false, t.getMessage()));
        }

        for (TestResult res : results) {
            LOGGER.info("[DIAGNOSTIC] [{}] {} - {}", res.passed ? "PASS" : "FAIL", res.name, res.message);
        }

        return results;
    }

    private static FrameGenerationBackend.GpuVendor vendorForProvider(
            FrameGenerationCompatibilityLayer.ProviderId provider
    ) {
        return switch (provider) {
            case NVIDIA_STREAMLINE_VULKAN ->
                    FrameGenerationBackend.GpuVendor.NVIDIA;
            case AMD_FSR31_VULKAN, AMD_FSR4_D3D12 ->
                    FrameGenerationBackend.GpuVendor.AMD;
            case INTEL_XESS_D3D12, CROSS_VENDOR_FSR31_VULKAN ->
                    FrameGenerationBackend.GpuVendor.INTEL;
            case NONE -> FrameGenerationBackend.GpuVendor.UNKNOWN;
        };
    }
}
